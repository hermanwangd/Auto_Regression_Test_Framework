package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import static com.featuredeliveryintelligence.fdi.producttest.preparation.ContextAcquisition.Reason.*;

/** Direct, protected process consumer of the accepted D2 wire. Never resolves Git/source roots or assembles. */
public final class D2SourceBindingsClient {
    public static final String SCHEMA_RESOURCE="schemas/source_bindings.v1.schema.yaml";
    private static final int METADATA_LIMIT=2*1024*1024,PROCESS_OUTPUT_LIMIT=65536;
    private final D2ProducerBinding binding;
    public D2SourceBindingsClient(D2ProducerBinding independentlyProvisionedBinding){binding=Objects.requireNonNull(independentlyProvisionedBinding);}
    public record ExpectedPins(String jarSha256,String schemaSha256,String lockSha256,String rootsSha256) {
        public ExpectedPins {for(String pin:List.of(jarSha256,schemaSha256,lockSha256,rootsSha256))PreparationContracts.sha(pin);}
    }
    /** Only this client can mint the capability after the independently pinned process and output agree. */
    public static final class Admitted {
        private final byte[] metadata,manifest;private final JsonNode document;private final Path bundleRoot;private final ExpectedPins pins;
        private Admitted(byte[] metadata,byte[] manifest,Path bundleRoot,JsonNode document,ExpectedPins pins){this.metadata=metadata.clone();this.manifest=manifest.clone();this.bundleRoot=bundleRoot;this.document=document.deepCopy();this.pins=pins;}
        public byte[] metadataBytes(){return metadata.clone();}
        public String metadataSha256(){return PreparationContracts.hash(metadata);}
        public ExpectedPins producerPins(){return pins;}
        public Map<String,String> currentRepositoryRevisions(){var result=new LinkedHashMap<String,String>();for(JsonNode repo:document.path("repositories"))result.put(text(repo,"source_repo"),text(repo,"source_testrepo_version"));result.put(text(document.path("lock"),"source_repo"),text(document.path("lock"),"composition_root_version"));return Map.copyOf(result);}
        public JsonNode artifact(String qualifiedRef){for(JsonNode artifact:document.path("artifacts"))if(qualifiedRef.equals(artifact.path("artifact_id").asText()))return artifact.deepCopy();throw PreparationContracts.fail(SOURCE_BINDING_MISMATCH);}
        public JsonNode metadata(){return document.deepCopy();}
        /** Reads the D2-created manifest mapping only; never searches/copies a product checkout. */
        public Path materializedArtifact(String qualifiedRef){JsonNode map=PreparationContracts.read(manifest,METADATA_LIMIT);for(JsonNode file:map.path("files"))if(qualifiedRef.equals(file.path("artifact_id").asText()))return bundleRoot.resolve(relative(text(file,"path"))).normalize();throw PreparationContracts.fail(SOURCE_BINDING_MISMATCH);}
        public String executionBindingState(){return "NOT_EVALUATED";}
        @Override public String toString(){return "AdmittedSourceBindings[metadata only; execution NOT_EVALUATED]";}
    }
    public Admitted acquire(){
        long deadline=System.nanoTime()+binding.totalTimeout().toNanos();
        ExpectedPins pins=new ExpectedPins(binding.expectedProducerJarSha256(),binding.expectedRuntimeSchemaSha256(),binding.expectedLockSha256(),binding.expectedTrustedRootsSha256());
        try {
            Path jar=regular(binding.producerJar());
            equal(pins.jarSha256(),hashFile(jar,96L*1024*1024,deadline),PRODUCER_PIN_MISMATCH);
            byte[] schema;
            try(ZipFile zip=new ZipFile(jar.toFile())){
                String name="BOOT-INF/classes/"+SCHEMA_RESOURCE;var entries=zip.stream().filter(e->name.equals(e.getName())).toList();
                PreparationContracts.require(entries.size()==1&&!entries.get(0).isDirectory(),PRODUCER_PIN_MISMATCH);
                try(var in=zip.getInputStream(entries.get(0))){schema=bounded(in,METADATA_LIMIT);}
            }
            equal(pins.schemaSha256(),PreparationContracts.hash(schema),PRODUCER_PIN_MISMATCH);
            PreparationContracts.read(schema,METADATA_LIMIT); // Local pinned schema bytes only; no schema/network resolver.
            equal(pins.lockSha256(),hashFile(regular(binding.dependenciesLock()),METADATA_LIMIT,deadline),SOURCE_BINDING_MISMATCH);
            equal(pins.rootsSha256(),hashFile(regular(binding.trustedRootsBinding()),METADATA_LIMIT,deadline),SOURCE_BINDING_MISMATCH);
            Path parent=binding.outputParent().toRealPath();PreparationContracts.require(Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS),INVALID_INPUT);
            Path output=Files.createTempDirectory(parent,"pinned-d2-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            JsonNode observer=PreparationContracts.read(invoke(List.of("runner-observe-producer"),output,deadline),PROCESS_OUTPUT_LIMIT);
            validateObserver(observer,pins);
            Path assembly=output.resolve("assembled");
            byte[] stdout=invoke(List.of("runner-package","--dependencies-lock",binding.dependenciesLock().toString(),"--expected-lock-sha256",pins.lockSha256(),"--roots-binding",binding.trustedRootsBinding().toString(),"--expected-roots-sha256",pins.rootsSha256(),"--out",assembly.toString()),output,deadline);
            Map<String,String> envelope=envelope(stdout);
            equal("ADMITTED",envelope.get("runner_status"),SOURCE_BINDING_MISMATCH);
            Path metadataPath=outputFile(assembly,envelope.get("source_bindings_json"),"source-bindings.json"),manifestPath=outputFile(assembly,envelope.get("bundle_manifest"),"bundle-manifest.json");
            byte[] raw=read(metadataPath,METADATA_LIMIT),manifest=read(manifestPath,METADATA_LIMIT);
            equal(PreparationContracts.hash(raw),envelope.get("source_bindings_sha256"),SOURCE_BINDING_MISMATCH);
            equal(PreparationContracts.hash(manifest),envelope.get("bundle_manifest_sha256"),SOURCE_BINDING_MISMATCH);
            JsonNode metadata=validateMetadata(PreparationContracts.read(raw,METADATA_LIMIT),observer,pins,binding.approvedPublicReferences());
            validateManifest(metadata,PreparationContracts.read(manifest,METADATA_LIMIT),PreparationContracts.hash(manifest));
            equal(pins.jarSha256(),hashFile(jar,96L*1024*1024,deadline),PRODUCER_PIN_MISMATCH);
            return new Admitted(raw,manifest,assembly,metadata,pins);
        }catch(PreparationContracts.Blocked blocked){throw blocked;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw PreparationContracts.fail(PROCESS_TIMEOUT);}
        catch(Exception invalid){throw PreparationContracts.fail(SOURCE_BINDING_MISMATCH);}
    }
    static JsonNode validateMetadata(JsonNode wire,JsonNode observer,ExpectedPins pins,Set<String> publicReferences){
        fields(wire,"schema_version","binding_id","source_binding_state","execution_binding_state","trusted_root_binding_sha256","lock","validator","repositories","artifacts","relations","sut_expectations","gaps","snapshot");
        equal("runner-source-bindings.v1",text(wire,"schema_version"),SOURCE_BINDING_MISMATCH);equal("ADMITTED",text(wire,"source_binding_state"),SOURCE_BINDING_MISMATCH);equal("NOT_EVALUATED",text(wire,"execution_binding_state"),SUT_BINDING_UNBOUND);
        try{UUID.fromString(text(wire,"binding_id"));}catch(Exception bad){throw PreparationContracts.fail(SOURCE_BINDING_MISMATCH);}
        equal(pins.rootsSha256(),digest(wire,"trusted_root_binding_sha256"),SOURCE_BINDING_MISMATCH);
        validateObserver(observer,pins);equal(observer,wire.path("validator"),PRODUCER_PIN_MISMATCH);
        JsonNode lock=wire.path("lock");fields(lock,"lock_id","lock_version","sha256","source_repo","source_testrepo_version","composition_root_version","artifact_source_testrepo_version","source_path");
        equal(pins.lockSha256(),digest(lock,"sha256"),SOURCE_BINDING_MISMATCH);equal("tests/sit/dependencies.lock",text(lock,"source_path"),SOURCE_BINDING_MISMATCH);
        equal(commit(lock,"composition_root_version"),commit(lock,"source_testrepo_version"),SOURCE_BINDING_MISMATCH);commit(lock,"artifact_source_testrepo_version");
        for(String key:List.of("lock_id","lock_version","source_repo"))allowed(text(lock,key),publicReferences);
        var repos=new LinkedHashMap<String,JsonNode>();
        for(JsonNode repo:array(wire,"repositories",1,16)){
            fields(repo,"source_repo","source_testrepo_version","commit_algorithm","source_owner_ref");String id=text(repo,"source_repo");allowed(id,publicReferences);allowed(owner(repo,"source_owner_ref"),publicReferences);commit(repo,"source_testrepo_version");equal("git-sha1",text(repo,"commit_algorithm"),SOURCE_BINDING_MISMATCH);PreparationContracts.require(repos.putIfAbsent(id,repo)==null,SOURCE_BINDING_MISMATCH);
        }
        JsonNode main=repos.get(text(lock,"source_repo"));PreparationContracts.require(main!=null,SOURCE_BINDING_MISMATCH);equal(text(main,"source_testrepo_version"),text(lock,"artifact_source_testrepo_version"),SOURCE_BINDING_MISMATCH);
        var artifacts=new LinkedHashMap<String,JsonNode>();var physicalSizes=new HashMap<List<String>,Long>();var physicalDigests=new HashMap<List<String>,String>();long total=0;
        for(JsonNode a:array(wire,"artifacts",1,128)){
            fields(a,"artifact_id","role","source_repo","source_commit","source_path","source_id","source_testrepo_version","sha256","bytes","source_owner_ref","cleanup_owner_ref","required");
            String id=text(a,"artifact_id"),repo=text(a,"source_repo"),revision=commit(a,"source_commit"),path=relative(text(a,"source_path")),source=text(a,"source_id");
            PreparationContracts.require(source.matches("[A-Za-z0-9_.-]{1,128}")&&Set.of("suite","case","config_template","sanitized_data","oracle","cleanup_definition","provider_contract").contains(text(a,"role")),SOURCE_BINDING_MISMATCH);
            equal(repo+"@"+revision+":"+path+"#"+source,id,SOURCE_BINDING_MISMATCH);equal(revision,commit(a,"source_testrepo_version"),SOURCE_BINDING_MISMATCH);
            PreparationContracts.require(repos.containsKey(repo),SOURCE_BINDING_MISMATCH);equal(revision,text(repos.get(repo),"source_testrepo_version"),SOURCE_BINDING_MISMATCH);equal(text(repos.get(repo),"source_owner_ref"),owner(a,"source_owner_ref"),SOURCE_BINDING_MISMATCH);
            allowed(id,publicReferences);allowed(repo,publicReferences);allowed(owner(a,"source_owner_ref"),publicReferences);allowed(owner(a,"cleanup_owner_ref"),publicReferences);String contentSha=digest(a,"sha256");long count=integer(a,"bytes",1,2097152);var physical=List.of(repo,path);
            Long prior=physicalSizes.putIfAbsent(physical,count);String oldDigest=physicalDigests.putIfAbsent(physical,contentSha);
            if(prior==null)total+=count;else PreparationContracts.require(prior==count&&Objects.equals(oldDigest,contentSha),SOURCE_BINDING_MISMATCH);
            PreparationContracts.require(a.path("required").isBoolean()&&artifacts.putIfAbsent(id,a)==null&&total<=16777216,SOURCE_BINDING_MISMATCH);
        }
        for(JsonNode relation:array(wire,"relations",0,512)){
            fields(relation,"from_artifact_id","relation","to_ref");PreparationContracts.require(artifacts.containsKey(text(relation,"from_artifact_id")),SOURCE_BINDING_MISMATCH);
            String kind=text(relation,"relation"),to=text(relation,"to_ref");PreparationContracts.require(Set.of("requires","oracle_for","config_for","data_for","cleanup_for","traceability").contains(kind),SOURCE_BINDING_MISMATCH);
            if(kind.equals("traceability"))allowed(to,publicReferences);else PreparationContracts.require(artifacts.containsKey(to),SOURCE_BINDING_MISMATCH);
        }
        Set<String>suts=new HashSet<>();for(JsonNode sut:array(wire,"sut_expectations",1,16)){
            fields(sut,"sut_id","owner_ref","source_repo","expected_deployed_revision","readiness_binding_ref","cleanup_owner_ref");PreparationContracts.require(suts.add(text(sut,"sut_id"))&&repos.containsKey(text(sut,"source_repo")),SOURCE_BINDING_MISMATCH);
            for(String key:List.of("sut_id","owner_ref","source_repo","expected_deployed_revision","readiness_binding_ref","cleanup_owner_ref"))allowed(text(sut,key),publicReferences);owner(sut,"owner_ref");owner(sut,"cleanup_owner_ref");
        }
        for(JsonNode gap:array(wire,"gaps",0,512)){fields(gap,"code","owner_action");equal("ACTUAL_DEPLOYED_SUT_NOT_EVALUATED",text(gap,"code"),SOURCE_BINDING_MISMATCH);equal("Bind and observe each SUT separately before execution acceptance",text(gap,"owner_action"),SOURCE_BINDING_MISMATCH);}
        JsonNode snapshot=wire.path("snapshot");fields(snapshot,"manifest_sha256","closure_sha256","artifact_count","total_bytes","classification");digest(snapshot,"manifest_sha256");digest(snapshot,"closure_sha256");equal("derived_execution_snapshot",text(snapshot,"classification"),SOURCE_BINDING_MISMATCH);equal((long)artifacts.size(),integer(snapshot,"artifact_count",1,128),SOURCE_BINDING_MISMATCH);equal(total,integer(snapshot,"total_bytes",1,16777216),SOURCE_BINDING_MISMATCH);
        return wire.deepCopy();
    }
    private static void validateObserver(JsonNode observer,ExpectedPins pins){
        fields(observer,"version","identity_state","artifact_sha256","source_commit","source_tree","schema_sha256");equal("JAR_OBSERVED",text(observer,"identity_state"),PRODUCER_PIN_MISMATCH);equal(pins.jarSha256(),digest(observer,"artifact_sha256"),PRODUCER_PIN_MISMATCH);equal(pins.schemaSha256(),digest(observer,"schema_sha256"),PRODUCER_PIN_MISMATCH);
        PreparationContracts.require(observer.path("source_commit").isNull()&&observer.path("source_tree").isNull(),PRODUCER_PIN_MISMATCH);if(!observer.path("version").isNull())PreparationContracts.require(text(observer,"version").length()<=128,PRODUCER_PIN_MISMATCH);
    }
    private static void validateManifest(JsonNode metadata,JsonNode manifest,String rawSha){
        fields(manifest,"schema_version","classification","lock_sha256","lock_version","files");equal("runner-bundle.v1",text(manifest,"schema_version"),SOURCE_BINDING_MISMATCH);equal("derived_execution_snapshot",text(manifest,"classification"),SOURCE_BINDING_MISMATCH);equal(text(metadata.path("lock"),"sha256"),digest(manifest,"lock_sha256"),SOURCE_BINDING_MISMATCH);equal(text(metadata.path("lock"),"lock_version"),text(manifest,"lock_version"),SOURCE_BINDING_MISMATCH);equal(rawSha,digest(metadata.path("snapshot"),"manifest_sha256"),SOURCE_BINDING_MISMATCH);equal(rawSha,digest(metadata.path("snapshot"),"closure_sha256"),SOURCE_BINDING_MISMATCH);
        Map<String,JsonNode>expected=new HashMap<>();for(JsonNode a:metadata.path("artifacts"))expected.put(text(a,"artifact_id"),a);
        for(JsonNode file:array(manifest,"files",1,128)){
            fields(file,"path","bytes","sha256","source_repo","source_commit","source_path","artifact_id","role","source_id","source_testrepo_version");relative(text(file,"path"));JsonNode artifact=expected.remove(text(file,"artifact_id"));PreparationContracts.require(artifact!=null,SOURCE_BINDING_MISMATCH);
            for(String key:List.of("bytes","sha256","source_repo","source_commit","source_path","artifact_id","role","source_id","source_testrepo_version"))equal(artifact.path(key),file.path(key),SOURCE_BINDING_MISMATCH);
        }PreparationContracts.require(expected.isEmpty(),SOURCE_BINDING_MISMATCH);
    }
    private byte[] invoke(List<String> arguments,Path output,long deadline)throws Exception{
        check(deadline);var command=new ArrayList<String>(List.of(binding.javaExecutable().toRealPath().toString(),"-Xmx512m","-XX:MaxMetaspaceSize=256m","-Djava.io.tmpdir="+output,"-jar",binding.producerJar().toRealPath().toString()));command.addAll(arguments);
        var builder=new ProcessBuilder(command).directory(output.toFile());builder.environment().clear();builder.environment().putAll(Map.of("PATH","/usr/bin:/bin","LC_ALL","C"));Process process=builder.start();
        var io=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"d2-wire-capture");t.setDaemon(true);return t;});AtomicInteger received=new AtomicInteger();Future<byte[]>stdout=io.submit(()->capture(process.getInputStream(),received)),stderr=io.submit(()->capture(process.getErrorStream(),received));boolean clean=false;
        try{
            while(!process.waitFor(20,TimeUnit.MILLISECONDS)){check(deadline);PreparationContracts.require(received.get()<=PROCESS_OUTPUT_LIMIT,OUTPUT_LIMIT);}
            byte[] out=stdout.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);stderr.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);check(deadline);PreparationContracts.require(process.exitValue()==0,PROCESS_FAILED);return out;
        }catch(TimeoutException timeout){throw PreparationContracts.fail(PROCESS_TIMEOUT);}
        catch(ExecutionException captureFailure){if(captureFailure.getCause() instanceof PreparationContracts.Blocked blocked)throw blocked;throw PreparationContracts.fail(PROCESS_FAILED);}
        finally{
            var descendants=process.descendants().limit(64).toList();descendants.forEach(ProcessHandle::destroyForcibly);
            if(process.isAlive())process.destroyForcibly();boolean stopped=process.waitFor(2,TimeUnit.SECONDS);process.getInputStream().close();process.getErrorStream().close();io.shutdownNow();clean=stopped&&io.awaitTermination(2,TimeUnit.SECONDS)&&descendants.stream().noneMatch(ProcessHandle::isAlive);PreparationContracts.require(clean,CLEANUP_UNCONFIRMED);
        }
    }
    private static byte[] capture(InputStream in,AtomicInteger received)throws Exception{try(in){var out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(received.addAndGet(n)>PROCESS_OUTPUT_LIMIT)throw PreparationContracts.fail(OUTPUT_LIMIT);out.write(b,0,n);}return out.toByteArray();}}
    private static Map<String,String>envelope(byte[] stdout){var out=new LinkedHashMap<String,String>();for(String line:new String(stdout,StandardCharsets.UTF_8).strip().split("\n")){int colon=line.indexOf(": ");PreparationContracts.require(colon>0&&out.putIfAbsent(line.substring(0,colon),line.substring(colon+2))==null,SOURCE_BINDING_MISMATCH);}PreparationContracts.require(out.keySet().equals(Set.of("runner_status","source_bindings_json","source_bindings_sha256","bundle_manifest","bundle_manifest_sha256")),SOURCE_BINDING_MISMATCH);return out;}
    private static Path outputFile(Path root,String value,String name)throws IOException{PreparationContracts.require(value!=null,SOURCE_BINDING_MISMATCH);Path path=Path.of(value);PreparationContracts.require(path.isAbsolute()&&path.normalize().equals(path)&&path.equals(root.resolve(name)),SOURCE_BINDING_MISMATCH);return regular(path);}
    private static Path regular(Path path)throws IOException{PreparationContracts.require(path.isAbsolute()&&path.normalize().equals(path)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),SOURCE_BINDING_MISMATCH);for(Path p=path;p!=null;p=p.getParent())PreparationContracts.require(!Files.isSymbolicLink(p),SOURCE_BINDING_MISMATCH);PreparationContracts.require(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,SOURCE_BINDING_MISMATCH);return path;}
    private static String hashFile(Path path,long max,long deadline)throws Exception{PreparationContracts.require(Files.size(path)<=max,OUTPUT_LIMIT);var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);var digest=MessageDigest.getInstance("SHA-256");long total=0;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check(deadline);total+=n;PreparationContracts.require(total<=max,OUTPUT_LIMIT);digest.update(b,0,n);}}var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);PreparationContracts.require(before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())&&Objects.equals(before.fileKey(),after.fileKey()),SOURCE_BINDING_MISMATCH);return HexFormat.of().formatHex(digest.digest());}
    private static byte[]read(Path path,int limit)throws IOException{try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){return bounded(in,limit);}}
    private static byte[]bounded(InputStream in,int limit)throws IOException{byte[] b=in.readNBytes(limit+1);PreparationContracts.require(b.length<=limit,OUTPUT_LIMIT);return b;}
    private static void check(long deadline){PreparationContracts.require(System.nanoTime()<deadline,PROCESS_TIMEOUT);}
    private static void fields(JsonNode node,String...keys){PreparationContracts.require(node.isObject(),SOURCE_BINDING_MISMATCH);Set<String>actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);PreparationContracts.require(actual.equals(Set.of(keys)),SOURCE_BINDING_MISMATCH);}
    private static List<JsonNode>array(JsonNode node,String field,int min,int max){JsonNode value=node.path(field);PreparationContracts.require(value.isArray()&&value.size()>=min&&value.size()<=max,SOURCE_BINDING_MISMATCH);var result=new ArrayList<JsonNode>();value.forEach(result::add);return result;}
    private static String text(JsonNode node,String field){JsonNode value=node.path(field);PreparationContracts.require(value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=2048&&value.asText().codePoints().noneMatch(Character::isISOControl),SOURCE_BINDING_MISMATCH);return value.asText();}
    private static String owner(JsonNode node,String field){String value=text(node,field);PreparationContracts.require(!value.equalsIgnoreCase("UNBOUND"),SOURCE_OWNER_UNBOUND);return value;}
    private static String digest(JsonNode node,String field){String value=text(node,field);PreparationContracts.require(value.matches("[a-f0-9]{64}"),SOURCE_BINDING_MISMATCH);return value;}
    private static String commit(JsonNode node,String field){String value=text(node,field);PreparationContracts.require(value.matches("[a-f0-9]{40}"),SOURCE_BINDING_MISMATCH);return value;}
    private static long integer(JsonNode node,String field,long min,long max){JsonNode value=node.path(field);PreparationContracts.require(value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()>=min&&value.asLong()<=max,SOURCE_BINDING_MISMATCH);return value.asLong();}
    private static String relative(String value){PreparationContracts.require(value.length()<=1024&&!value.contains("\\")&&!value.startsWith("/")&&!value.contains(":")&&Arrays.stream(value.split("/",-1)).noneMatch(v->v.isEmpty()||v.equals(".")||v.equals("..")),SOURCE_BINDING_MISMATCH);return value;}
    private static void allowed(String ref,Set<String>publicReferences){PreparationContracts.require(publicReferences.contains(ref),METADATA_NOT_APPROVED);}
    private static void equal(Object expected,Object actual,ContextAcquisition.Reason reason){PreparationContracts.require(Objects.equals(expected,actual),reason);}
}
