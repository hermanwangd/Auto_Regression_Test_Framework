package com.specdriven.regression.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Trusted already-checked-out roots only; source admission/materialization never executes tests. */
public final class RunnerBundleService {
    private record Root(String repo,String sourceCommit,String checkoutCommit,Path path,String owner,boolean main){}
    private record Blob(Root root,String path,String commit,String digest,String artifactId,String role,String sourceId,byte[] bytes){}
    public static final class SourceBindings {
        private final ObjectNode metadata; private final List<Blob> blobs; private final byte[] lock;
        private SourceBindings(ObjectNode metadata,List<Blob> blobs,byte[] lock){this.metadata=metadata;this.blobs=List.copyOf(blobs);this.lock=lock.clone();}
        public JsonNode metadata(){return metadata.deepCopy();}
    }
    public record Exported(Path sourceBindings,Path bundleManifest,String bindingsSha256,String manifestSha256){}
    private static void require(boolean ok,String reason){RunnerFiles.require(ok,reason);}
    private static String text(JsonNode n,String k){return RunnerFiles.text(n,k);}
    private static void commit(String value){require(value.matches("[a-f0-9]{40}"),"exact_git_sha1_required");}
    private static void owner(String value){require(!value.equalsIgnoreCase("UNBOUND")&&!value.startsWith("MISSING"),"SOURCE_OWNER_UNBOUND");}
    private static String repo(JsonNode n){String r=text(n,"source_repo");java.net.URI uri=java.net.URI.create(r);require(uri.isAbsolute()&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null&&!r.contains("\n"),"governed_nonsecret_repository_identity_required");return r;}
    private static byte[] git(Path root,int limit,String...arguments)throws IOException{
        require(Files.isDirectory(root.resolve(".git"),LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(root.resolve(".git")),"trusted_checkout_dotgit_directory_required");
        byte[] config=RunnerFiles.read(root,".git/config",65536);String c=new String(config,java.nio.charset.StandardCharsets.UTF_8);
        require(!c.matches("(?is).*\\[\\s*include(?:if)?[^]]*].*"),"git_config_includes_forbidden");
        require(!Files.exists(root.resolve(".git/objects/info/alternates"),LinkOption.NOFOLLOW_LINKS),"external_git_object_alternates_forbidden");
        List<String> command=new ArrayList<>(List.of("/usr/bin/git","--no-optional-locks","--no-replace-objects","-c","core.hooksPath=/dev/null","-c","protocol.allow=never","-C",root.toString()));command.addAll(List.of(arguments));
        ProcessBuilder builder=new ProcessBuilder(command);builder.environment().clear();builder.environment().putAll(Map.of("PATH","/usr/bin:/bin","LC_ALL","C","GIT_CONFIG_NOSYSTEM","1","GIT_CONFIG_GLOBAL","/dev/null","GIT_TERMINAL_PROMPT","0","GIT_LITERAL_PATHSPECS","1"));builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=builder.start();ExecutorService io=Executors.newSingleThreadExecutor();Future<byte[]> read=io.submit(()->{try(var in=p.getInputStream();var out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){require(out.size()+n<=limit,"committed_blob_byte_limit");out.write(buf,0,n);}return out.toByteArray();}});
        try{byte[] data=read.get(10,TimeUnit.SECONDS);require(p.waitFor(1,TimeUnit.SECONDS)&&p.exitValue()==0,"git_commit_or_blob_unavailable");return data;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("git_metadata_interrupted",e);}catch(ExecutionException|TimeoutException e){throw new IOException("git_metadata_failed_or_timeout",e);}finally{p.destroyForcibly();io.shutdownNow();try{p.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private static byte[] regularBlob(Path root,String revision,String path,int limit)throws IOException{
        byte[] tree=git(root,4096,"ls-tree","-z",revision,"--",path);
        String entry=new String(tree,java.nio.charset.StandardCharsets.UTF_8);int separator=entry.indexOf('\t');
        require(separator>0&&entry.endsWith("\0")&&entry.indexOf('\0')==entry.length()-1,"literal_committed_file_entry_required");
        String[] header=entry.substring(0,separator).split(" ");
        require(header.length==3&&Set.of("100644","100755").contains(header[0])&&header[1].equals("blob")&&entry.substring(separator+1,entry.length()-1).equals(path),"committed_source_mode_not_regular");
        return git(root,limit,"cat-file","blob",revision+":"+path);
    }
    private static String head(Path root)throws IOException{return new String(git(root,128,"rev-parse","--verify","HEAD^{commit}"),java.nio.charset.StandardCharsets.US_ASCII).strip();}
    private static void ancestor(Root r)throws IOException{git(r.path(),1,"merge-base","--is-ancestor",r.sourceCommit(),r.checkoutCommit());}
    public SourceBindings inspectBindings(Path lockFile,String independentlyExpectedLockSha256,Path protectedTrustedRoots,String independentlyExpectedRootsSha256)throws IOException{
        byte[] lockRaw=RunnerFiles.read(lockFile,RunnerFiles.TEXT_LIMIT);require(RunnerFiles.sha(lockRaw).equals(independentlyExpectedLockSha256)&&independentlyExpectedLockSha256.matches("[a-f0-9]{64}"),"independent_lock_pin_mismatch");
        JsonNode lock=RunnerFiles.document(lockRaw);JsonNode trusted=RunnerFiles.pinned(protectedTrustedRoots,independentlyExpectedRootsSha256);
        RunnerFiles.fields(lock,"schema_version","lock_id","lock_version","main_flow","repositories","artifacts","sut_expectations","relations");require(text(lock,"schema_version").equals("runner-dependencies.lock.v1"),"unsupported_lock_version");text(lock,"lock_id");text(lock,"lock_version");
        RunnerFiles.fields(trusted,"schema_version","binding_id","approved_lock_sha256","roots");require(text(trusted,"schema_version").equals("runner-trusted-roots.v1")&&RunnerFiles.digest(trusted,"approved_lock_sha256").equals(independentlyExpectedLockSha256),"protected_lock_binding_mismatch");text(trusted,"binding_id");
        JsonNode flow=lock.get("main_flow");RunnerFiles.fields(flow,"owner_ref","source_repo","source_testrepo_version","sit_root","suite_artifact_id");owner(text(flow,"owner_ref"));String mainRepo=repo(flow);require(text(flow,"sit_root").equals("tests/sit"),"one_existing_product_tests_sit_root_required");String mainSource=text(flow,"source_testrepo_version");commit(mainSource);
        var declared=new LinkedHashMap<String,JsonNode>();for(JsonNode r:RunnerFiles.array(lock,"repositories",1,16)){RunnerFiles.fields(r,"source_repo","source_testrepo_version","commit_algorithm","source_owner_ref");String id=repo(r);commit(text(r,"source_testrepo_version"));require(text(r,"commit_algorithm").equals("git-sha1"),"unsupported_commit_algorithm");owner(text(r,"source_owner_ref"));require(declared.putIfAbsent(id,r)==null,"duplicate_repository");}
        require(declared.containsKey(mainRepo)&&text(declared.get(mainRepo),"source_testrepo_version").equals(mainSource),"main_source_version_mismatch");
        var roots=new LinkedHashMap<String,Root>();
        for(JsonNode r:RunnerFiles.array(trusted,"roots",1,16)){
            RunnerFiles.fields(r,"source_repo","expected_commit","commit_algorithm","absolute_checkout_root","source_owner_ref","binding_evidence_sha256");String id=repo(r);require(declared.containsKey(id),"unconsumed_trusted_root");String expected=text(r,"expected_commit");commit(expected);require(text(r,"commit_algorithm").equals("git-sha1"),"unsupported_commit_algorithm");RunnerFiles.digest(r,"binding_evidence_sha256");owner(text(r,"source_owner_ref"));require(text(r,"source_owner_ref").equals(text(declared.get(id),"source_owner_ref")),"source_owner_binding_mismatch");
            Path path=Path.of(text(r,"absolute_checkout_root"));require(path.isAbsolute()&&path.normalize().equals(path),"explicit_trusted_absolute_root_required");
            // A contained file read anchors/checks every parent before Git metadata is consulted.
            RunnerFiles.read(path,".git/HEAD",4096);require(head(path).equals(expected),"protected_checkout_commit_mismatch");String source=text(declared.get(id),"source_testrepo_version");
            Root root=new Root(id,source,expected,path,text(r,"source_owner_ref"),id.equals(mainRepo));require(root.main()||source.equals(expected),"service_checkout_must_equal_locked_commit");if(root.main()&&!source.equals(expected))ancestor(root);
            require(roots.putIfAbsent(id,root)==null,"duplicate_trusted_root");
        }
        require(roots.keySet().equals(declared.keySet()),"missing_trusted_repository_root");Root main=roots.get(mainRepo);require(lockFile.toAbsolutePath().equals(main.path().resolve("tests/sit/dependencies.lock")),"lock_outside_main_product_sit_root");
        require(Arrays.equals(lockRaw,regularBlob(main.path(),main.checkoutCommit(),"tests/sit/dependencies.lock",RunnerFiles.TEXT_LIMIT)),"lock_worktree_differs_from_committed_checkout_X");
        List<Blob> blobs=new ArrayList<>();var ids=new LinkedHashMap<String,JsonNode>();long total=0;Set<String> allowedRoles=Set.of("suite","case","config_template","sanitized_data","oracle","cleanup_definition","provider_contract");
        for(JsonNode a:RunnerFiles.array(lock,"artifacts",1,128)){
            RunnerFiles.fields(a,"artifact_id","role","source_repo","source_commit","source_path","source_id","source_testrepo_version","sha256","bytes","source_owner_ref","cleanup_owner_ref","required");String repository=repo(a);Root r=roots.get(repository);require(r!=null,"artifact_repository_unbound");String source=text(a,"source_commit");commit(source);require(source.equals(r.sourceCommit())&&source.equals(text(a,"source_testrepo_version")),"artifact_source_testrepo_version_mismatch");
            String path=text(a,"source_path");RunnerFiles.relative(path);for(Path part:Path.of(path))require(!Set.of(".git",".ssh",".aws",".env","secrets","credentials").contains(part.toString().toLowerCase(java.util.Locale.ROOT)),"private_source_path_excluded");String localId=text(a,"source_id");require(localId.matches("[A-Za-z0-9_.-]{1,128}"),"source_id_delimiter_or_size_invalid");String id=repository+"@"+source+":"+path+"#"+localId;require(text(a,"artifact_id").equals(id)&&ids.putIfAbsent(id,a)==null,"qualified_identity_mismatch_or_duplicate");require(allowedRoles.contains(text(a,"role")),"sut_or_unknown_payload_role_forbidden");owner(text(a,"source_owner_ref"));owner(text(a,"cleanup_owner_ref"));require(text(a,"source_owner_ref").equals(r.owner()),"artifact_source_owner_mismatch");require(a.path("required").isBoolean(),"required_flag_missing");
            require(a.path("bytes").isIntegralNumber()&&a.path("bytes").asLong()>0&&a.path("bytes").asLong()<=RunnerFiles.TEXT_LIMIT,"input_file_byte_limit");String expected=RunnerFiles.digest(a,"sha256");byte[] raw=RunnerFiles.read(r.path(),path,RunnerFiles.TEXT_LIMIT);total+=raw.length;require(total<=16L*1024*1024,"input_closure_byte_limit");require(raw.length==a.path("bytes").asLong()&&RunnerFiles.sha(raw).equals(expected),"input_bytes_or_sha_mismatch");
            require(Arrays.equals(raw,regularBlob(r.path(),source,path,RunnerFiles.TEXT_LIMIT)),"input_differs_from_committed_source_A");
            // Main-only ancestor exception is safe ONLY with all three byte identities equal.
            if(r.main())require(Arrays.equals(raw,regularBlob(r.path(),r.checkoutCommit(),path,RunnerFiles.TEXT_LIMIT)),"main_input_committed_X_differs_from_A_or_worktree");
            require(!(raw.length>1&&((raw[0]==0x7f&&raw[1]=='E')||(raw[0]=='M'&&raw[1]=='Z')||(raw[0]=='P'&&raw[1]=='K'))),"sut_binary_or_archive_forbidden");RunnerFiles.noSecret(raw);blobs.add(new Blob(r,path,source,expected,id,text(a,"role"),localId,raw));
        }
        JsonNode suite=ids.get(text(flow,"suite_artifact_id"));require(suite!=null&&text(suite,"role").equals("suite")&&text(suite,"source_repo").equals(mainRepo)&&text(suite,"source_path").equals("tests/sit/suite.yaml"),"root_suite_binding_invalid");
        var edges=new HashMap<String,List<String>>();Set<String> relations=Set.of("requires","oracle_for","config_for","data_for","cleanup_for","traceability");
        for(JsonNode relation:RunnerFiles.array(lock,"relations",0,512)){RunnerFiles.fields(relation,"from_artifact_id","relation","to_ref");String from=text(relation,"from_artifact_id"),kind=text(relation,"relation"),to=text(relation,"to_ref");require(ids.containsKey(from)&&relations.contains(kind),"relation_source_or_kind_invalid");if(!kind.equals("traceability")){require(ids.containsKey(to),"required_relation_target_unbound");edges.computeIfAbsent(from,k->new ArrayList<>()).add(to);}}
        for(String id:ids.keySet())acyclic(id,edges,new HashSet<>(),new HashSet<>());
        Set<String> suts=new HashSet<>();for(JsonNode sut:RunnerFiles.array(lock,"sut_expectations",1,16)){RunnerFiles.fields(sut,"sut_id","owner_ref","source_repo","expected_deployed_revision","readiness_binding_ref","cleanup_owner_ref");require(suts.add(text(sut,"sut_id")),"duplicate_sut");owner(text(sut,"owner_ref"));owner(text(sut,"cleanup_owner_ref"));require(roots.containsKey(repo(sut)),"sut_source_repo_unbound");text(sut,"expected_deployed_revision");text(sut,"readiness_binding_ref");}
        for(Root r:roots.values())require(head(r.path()).equals(r.checkoutCommit()),"checkout_changed_during_admission");require(Arrays.equals(lockRaw,RunnerFiles.read(lockFile,RunnerFiles.TEXT_LIMIT)),"lock_changed_during_admission");
        ObjectNode metadata=RunnerFiles.object();metadata.put("schema_version","runner-source-bindings.v1");metadata.put("binding_id",UUID.randomUUID().toString());metadata.put("source_binding_state","ADMITTED");metadata.put("execution_binding_state","NOT_EVALUATED");metadata.put("trusted_root_binding_sha256",independentlyExpectedRootsSha256);
        var l=metadata.putObject("lock");l.put("lock_id",text(lock,"lock_id"));l.put("lock_version",text(lock,"lock_version"));l.put("sha256",independentlyExpectedLockSha256);l.put("source_repo",mainRepo);l.put("source_testrepo_version",main.checkoutCommit());l.put("composition_root_version",main.checkoutCommit());l.put("artifact_source_testrepo_version",main.sourceCommit());l.put("source_path","tests/sit/dependencies.lock");
        metadata.set("repositories",lock.get("repositories").deepCopy());metadata.set("artifacts",lock.get("artifacts").deepCopy());metadata.set("relations",lock.get("relations").deepCopy());metadata.set("sut_expectations",lock.get("sut_expectations").deepCopy());metadata.putArray("gaps").addObject().put("code","ACTUAL_DEPLOYED_SUT_NOT_EVALUATED").put("owner_action","Bind and observe each SUT separately before execution acceptance");
        metadata.set("validator",RunnerFiles.JSON.valueToTree(RunnerProducerObserver.observe()));
        return new SourceBindings(metadata,blobs,lockRaw);
    }
    private static void acyclic(String id,Map<String,List<String>> edges,Set<String> active,Set<String> done){if(done.contains(id))return;require(active.add(id),"cyclic_source_dependency");for(String to:edges.getOrDefault(id,List.of()))acyclic(to,edges,active,done);active.remove(id);done.add(id);}
    public Exported exportBundle(SourceBindings admitted,Path freshDestination)throws IOException{
        require(admitted!=null,"admitted_binding_required");
        Root root=admitted.blobs.stream().map(Blob::root).filter(Root::main).findFirst().orElseThrow();require(Arrays.equals(admitted.lock,RunnerFiles.read(root.path(),"tests/sit/dependencies.lock",RunnerFiles.TEXT_LIMIT)),"admitted_lock_changed_before_export");
        for(Blob b:admitted.blobs){require(head(b.root().path()).equals(b.root().checkoutCommit())&&Arrays.equals(b.bytes(),RunnerFiles.read(b.root().path(),b.path(),RunnerFiles.TEXT_LIMIT)),"admitted_source_changed_before_export");}
        RunnerFiles.fresh(freshDestination);ObjectNode bundle=RunnerFiles.object();bundle.put("schema_version","runner-bundle.v1");bundle.put("classification","derived_execution_snapshot");bundle.put("lock_sha256",admitted.metadata.path("lock").path("sha256").asText());bundle.put("lock_version",admitted.metadata.path("lock").path("lock_version").asText());var files=bundle.putArray("files");long total=0;Map<String,String> physical=new LinkedHashMap<>();
        for(Blob b:admitted.blobs){String ref="inputs/"+RunnerFiles.sha(b.root().repo().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16)+"/"+b.path();String previous=physical.putIfAbsent(ref,b.digest());require(previous==null||previous.equals(b.digest()),"conflicting_physical_snapshot_identity");if(previous==null){RunnerFiles.save(freshDestination,ref,b.bytes());total+=b.bytes().length;}var entry=files.addObject();entry.put("path",ref);entry.put("bytes",b.bytes().length);entry.put("sha256",b.digest());entry.put("source_repo",b.root().repo());entry.put("source_commit",b.commit());entry.put("source_path",b.path());entry.put("artifact_id",b.artifactId());entry.put("role",b.role());entry.put("source_id",b.sourceId());entry.put("source_testrepo_version",b.commit());}
        RunnerFiles.save(freshDestination,"control/dependencies.lock",admitted.lock);byte[] manifest=RunnerFiles.bytes(bundle);RunnerFiles.save(freshDestination,"bundle-manifest.json",manifest);
        ObjectNode metadata=admitted.metadata.deepCopy();var snapshot=metadata.putObject("snapshot");snapshot.put("manifest_sha256",RunnerFiles.sha(manifest));snapshot.put("closure_sha256",RunnerFiles.sha(manifest));snapshot.put("artifact_count",admitted.blobs.size());snapshot.put("total_bytes",total);snapshot.put("classification","derived_execution_snapshot");byte[] bytes=RunnerFiles.bytes(metadata);RunnerFiles.save(freshDestination,"source-bindings.json",bytes);RunnerFiles.sync(freshDestination);
        return new Exported(freshDestination.resolve("source-bindings.json"),freshDestination.resolve("bundle-manifest.json"),RunnerFiles.sha(bytes),RunnerFiles.sha(manifest));
    }
}
