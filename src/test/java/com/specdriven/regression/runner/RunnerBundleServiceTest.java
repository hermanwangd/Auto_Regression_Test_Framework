package com.specdriven.regression.runner;
import com.specdriven.regression.testsupport.FixtureGit;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class RunnerBundleServiceTest {
 static final ObjectMapper JSON=new ObjectMapper(); @TempDir Path temp;
 String mainCommit,serviceCommit;Path main,service,lock,roots;ObjectNode document,binding;
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 @BeforeEach void fixture()throws Exception{
  main=Files.createDirectory(temp.resolve("main"));service=Files.createDirectory(temp.resolve("service"));
  for(Path p:List.of(main,service)){FixtureGit.init(p);Files.createDirectories(p.resolve("tests/sit/cases"));Files.createDirectories(p.resolve("tests/sit/data"));Files.writeString(p.resolve("tests/sit/cases/same.yaml"),"case: public-fixture\n");Files.writeString(p.resolve("tests/sit/data/public.json"),"{\"public\":true}\n");}
  Files.writeString(main.resolve("tests/sit/suite.yaml"),"suite: finite-public-sources\n");
  for(Path p:List.of(main,service)){FixtureGit.add(p, ".");FixtureGit.commit(p, "public source fixture");}
  mainCommit=FixtureGit.head(main);serviceCommit=FixtureGit.head(service);
  document=JSON.createObjectNode();document.put("schema_version","runner-dependencies.lock.v1");document.put("lock_id","public-two-repo");document.put("lock_version","1");
  var mf=document.putObject("main_flow");mf.put("owner_ref","public-main-owner");mf.put("source_repo",repo(main));mf.put("source_testrepo_version",mainCommit);mf.put("sit_root","tests/sit");
  var repos=document.putArray("repositories");for(Path p:List.of(main,service)){var r=repos.addObject();r.put("source_repo",repo(p));r.put("source_testrepo_version",commit(p));r.put("commit_algorithm","git-sha1");r.put("source_owner_ref","public-owner");}
  var artifacts=document.putArray("artifacts");ObjectNode suite=artifact(main,"suite","tests/sit/suite.yaml","suite");artifacts.add(suite);mf.put("suite_artifact_id",suite.path("artifact_id").asText());artifacts.add(artifact(main,"case","tests/sit/cases/same.yaml","same"));artifacts.add(artifact(service,"case","tests/sit/cases/same.yaml","same"));artifacts.add(artifact(service,"sanitized_data","tests/sit/data/public.json","data"));
  var sut=document.putArray("sut_expectations").addObject();sut.put("sut_id","public-service");sut.put("owner_ref","public-owner");sut.put("source_repo",repo(service));sut.put("expected_deployed_revision","public-sut-v1");sut.put("readiness_binding_ref","readiness-public");sut.put("cleanup_owner_ref","public-cleaner");
  var rel=document.putArray("relations").addObject();rel.put("from_artifact_id",artifacts.get(2).path("artifact_id").asText());rel.put("relation","data_for");rel.put("to_ref",artifacts.get(3).path("artifact_id").asText());
  lock=main.resolve("tests/sit/dependencies.lock");binding=JSON.createObjectNode();binding.put("schema_version","runner-trusted-roots.v1");binding.put("binding_id","public-roots");var rs=binding.putArray("roots");for(Path p:List.of(main,service)){var r=rs.addObject();r.put("source_repo",repo(p));r.put("expected_commit",commit(p));r.put("commit_algorithm","git-sha1");r.put("absolute_checkout_root",p.toString());r.put("source_owner_ref","public-owner");r.put("binding_evidence_sha256","1".repeat(64));}
  roots=temp.resolve("roots.json");persist();
 }
 String repo(Path p){return "https://example.invalid/"+p.getFileName();} String commit(Path p){return p.equals(main)?mainCommit:serviceCommit;}
 ObjectNode artifact(Path p,String role,String path,String id)throws Exception{
  var a=JSON.createObjectNode();String c=commit(p);a.put("artifact_id",repo(p)+"@"+c+":"+path+"#"+id);a.put("role",role);a.put("source_repo",repo(p));a.put("source_commit",c);a.put("source_path",path);a.put("source_id",id);a.put("source_testrepo_version",c);a.put("sha256",sha(Files.readAllBytes(p.resolve(path))));a.put("bytes",Files.size(p.resolve(path)));a.put("source_owner_ref","public-owner");a.put("cleanup_owner_ref","public-cleaner");a.put("required",true);return a;
 }
 void persist()throws Exception{
  Files.write(lock,JSON.writeValueAsBytes(document));FixtureGit.add(main, "tests/sit/dependencies.lock");
  if(FixtureGit.hasStagedChanges(main))FixtureGit.commit(main, "versioned main product SIT lock");
  ((ObjectNode)binding.path("roots").get(0)).put("expected_commit",FixtureGit.head(main));
  binding.put("approved_lock_sha256",sha(Files.readAllBytes(lock)));Files.write(roots,JSON.writeValueAsBytes(binding));
 }
 record Result(int code,String out,String err,Path target){}
 Result execute(String suffix)throws Exception{Path out=temp.resolve("out-"+suffix);var stdout=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();int code=new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(new String[]{"runner-package","--dependencies-lock",lock.toString(),"--expected-lock-sha256",sha(Files.readAllBytes(lock)),"--roots-binding",roots.toString(),"--expected-roots-sha256",sha(Files.readAllBytes(roots)),"--out",out.toString()},new PrintStream(stdout),new PrintStream(stderr));return new Result(code,stdout.toString(),stderr.toString(),out);}
 void blocked(String suffix)throws Exception{Result r=execute(suffix);assertThat(r.code()).as(r.out()+r.err()).isEqualTo(1);assertThat(r.out()).contains("BLOCKED");assertThat(Files.exists(r.target().resolve("source-bindings.json"))).isFalse();}
 @Test void twoRepositoriesPreserveQualifiedIdsAndDerivedBytes()throws Exception{var r=execute("normal");assertThat(r.code()).as(r.out()+r.err()).isZero();JsonNode b=JSON.readTree(r.target().resolve("source-bindings.json").toFile());assertThat(b.path("source_binding_state").asText()).isEqualTo("ADMITTED");assertThat(b.path("artifacts")).hasSize(4);assertThat(b.path("artifacts").get(1).path("artifact_id").asText()).isNotEqualTo(b.path("artifacts").get(2).path("artifact_id").asText());assertThat(b.path("execution_binding_state").asText()).isNotEqualTo("READY");assertThat(Files.readAllBytes(lock)).isEqualTo(JSON.writeValueAsBytes(document));}
 @Test void missingRepositoryBlockedWithoutAmbientSearch()throws Exception{((ObjectNode)binding.path("roots").get(1)).put("absolute_checkout_root",temp.resolve("missing").toString());persist();blocked("repo");}
 @Test void missingPathBlocks()throws Exception{Files.delete(service.resolve("tests/sit/data/public.json"));blocked("path");}
 @Test void wrongProtectedCheckoutCommitBlocks()throws Exception{((ObjectNode)binding.path("roots").get(1)).put("expected_commit","0".repeat(40));persist();blocked("commit");}
 @Test void modifiedBytesCannotBorrowCommittedVersion()throws Exception{Files.writeString(service.resolve("tests/sit/data/public.json"),"changed\n");blocked("hash");}
 @Test void duplicateQualifiedIdentityRejected()throws Exception{((ArrayNode)document.path("artifacts")).add(document.path("artifacts").get(1).deepCopy());persist();blocked("duplicate");}
 @Test void traversalRejectedEvenWithRecomputedLockPin()throws Exception{((ObjectNode)document.path("artifacts").get(3)).put("source_path","../outside");persist();blocked("traversal");}
 @Test void gitReadsOnlyTheExactLiteralTreeEntry()throws Exception{
  String name="tests/sit/data/-fixture[1].json";
  Files.writeString(service.resolve(name),"literal fixture\n");
  Files.writeString(service.resolve("tests/sit/data/-fixture1.json"),"different fixture\n");
  FixtureGit.add(service, "tests/sit/data");FixtureGit.commit(service, "literal fixture paths");
  serviceVersion(FixtureGit.head(service));
  ((ArrayNode)document.get("artifacts")).add(artifact(service,"sanitized_data",name,"literal"));persist();
  var result=execute("literal-path");
  assertThat(result.code()).as(result.out()+result.err()).isZero();
  try(var files=Files.walk(result.target().resolve("inputs"))){
   Path bundled=files.filter(file->file.endsWith(name)).findFirst().orElseThrow();
   assertThat(Files.readString(bundled)).isEqualTo("literal fixture\n");
  }
 }
 @Test void symlinkAndHardlinkInputsBlocked()throws Exception{Path p=service.resolve("tests/sit/data/public.json");Path outside=temp.resolve("outside");Files.move(p,outside);Files.createSymbolicLink(p,outside);blocked("symlink");Files.delete(p);Files.createLink(p,outside);blocked("hardlink");}
 @Test void requiredDataOrCleanupOwnerCannotDisappear()throws Exception{((ObjectNode)document.path("artifacts").get(3)).put("cleanup_owner_ref","");persist();blocked("owner");}
 @Test void sourceRevisionIsNotDeployedSutRevision()throws Exception{((ObjectNode)document.path("sut_expectations").get(0)).put("expected_deployed_revision","");persist();blocked("sut");}
 @Test void traceabilityCannotReferenceAnotherQualifiedCaseAsRequiredData()throws Exception{((ObjectNode)document.path("relations").get(0)).put("to_ref","missing@qualified:id");persist();blocked("relation");}
 @Test void committedXChangedButWorktreeRevertedToAIsBlocked()throws Exception{
  Path source=main.resolve("tests/sit/cases/same.yaml");byte[] a=Files.readAllBytes(source);Files.writeString(source,"case: changed-in-X\n");FixtureGit.add(main, "tests/sit/cases/same.yaml");FixtureGit.commit(main, "changed committed case");
  ((ObjectNode)binding.path("roots").get(0)).put("expected_commit",FixtureGit.head(main));Files.write(roots,JSON.writeValueAsBytes(binding));Files.write(source,a);blocked("X-blob");
 }
 @Test void dirtyVersionedLockIsBlockedEvenWithNewIndependentRawPin()throws Exception{document.put("lock_version","2");Files.write(lock,JSON.writeValueAsBytes(document));binding.put("approved_lock_sha256",sha(Files.readAllBytes(lock)));Files.write(roots,JSON.writeValueAsBytes(binding));blocked("dirty-lock");}
 @Test void wrongProtectedMainCheckoutNeverUsesAncestorFallback()throws Exception{((ObjectNode)binding.path("roots").get(0)).put("expected_commit",mainCommit);Files.write(roots,JSON.writeValueAsBytes(binding));blocked("wrong-X");}
 @Test void nonAncestorMainArtifactCommitIsBlocked()throws Exception{
  String orphan=FixtureGit.orphanCommit(main);
  ((ObjectNode)document.path("main_flow")).put("source_testrepo_version",orphan);((ObjectNode)document.path("repositories").get(0)).put("source_testrepo_version",orphan);
  String oldSuite=document.path("main_flow").path("suite_artifact_id").asText();String newSuite="";
  for(JsonNode a:document.path("artifacts")){if(a.path("source_repo").asText().equals(repo(main))){ObjectNode o=(ObjectNode)a;o.put("source_commit",orphan);o.put("source_testrepo_version",orphan);o.put("artifact_id",repo(main)+"@"+orphan+":"+o.path("source_path").asText()+"#"+o.path("source_id").asText());if(o.path("role").asText().equals("suite"))newSuite=o.path("artifact_id").asText();}}
  ((ObjectNode)document.path("main_flow")).put("suite_artifact_id",newSuite);persist();blocked("non-ancestor");
 }

 void serviceVersion(String version)throws Exception{
  serviceCommit=version;((ObjectNode)document.path("repositories").get(1)).put("source_testrepo_version",version);var replaced=new HashMap<String,String>();
  for(JsonNode node:document.path("artifacts"))if(node.path("source_repo").asText().equals(repo(service))){ObjectNode a=(ObjectNode)node;String old=a.path("artifact_id").asText();a.put("source_commit",version);a.put("source_testrepo_version",version);a.put("artifact_id",repo(service)+"@"+version+":"+a.path("source_path").asText()+"#"+a.path("source_id").asText());replaced.put(old,a.path("artifact_id").asText());}
  for(JsonNode node:document.path("relations")){ObjectNode rel=(ObjectNode)node;for(String key:List.of("from_artifact_id","to_ref"))if(replaced.containsKey(rel.path(key).asText()))rel.put(key,replaced.get(rel.path(key).asText()));}
  ((ObjectNode)binding.path("roots").get(1)).put("expected_commit",version);persist();
 }
 @Test void onePhysicalSourceWithTwoQualifiedIdsExportsBothBindings()throws Exception{
  ObjectNode second=document.path("artifacts").get(1).deepCopy();second.put("source_id","second-qualified-id");second.put("artifact_id",repo(main)+"@"+mainCommit+":tests/sit/cases/same.yaml#second-qualified-id");((ArrayNode)document.path("artifacts")).add(second);persist();var result=execute("shared-physical");assertThat(result.code()).as(result.out()+result.err()).isZero();JsonNode manifest=JSON.readTree(result.target().resolve("bundle-manifest.json").toFile());assertThat(manifest.path("files")).hasSize(5);Set<String> identities=new HashSet<>();for(JsonNode entry:manifest.path("files"))identities.add(entry.path("artifact_id").asText());assertThat(identities).hasSize(5);try(var paths=Files.walk(result.target().resolve("inputs"))){assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(4);}
 }
 @Test void committedServiceSymlinkCannotMasqueradeAsRegularWorktreeFile()throws Exception{
  Path path=service.resolve("tests/sit/cases/same.yaml");FixtureGit.indexAsSymlink(service, "tests/sit/cases/same.yaml");FixtureGit.commit(service, "public committed symlink mode with regular current bytes");serviceVersion(FixtureGit.head(service));blocked("service-symlink-mode");assertThat(Files.isSymbolicLink(path)).isFalse();
 }
 @Test void committedMainXModeMustBeRegularEvenWhenAllBlobBytesAgree()throws Exception{
  FixtureGit.indexAsSymlink(main, "tests/sit/cases/same.yaml");FixtureGit.commit(main, "main X symlink mode");((ObjectNode)binding.path("roots").get(0)).put("expected_commit",FixtureGit.head(main));Files.write(roots,JSON.writeValueAsBytes(binding));blocked("main-X-mode");
 }
 @Test void committedLockModeMustBeRegular()throws Exception{
  FixtureGit.indexAsSymlink(main, "tests/sit/dependencies.lock");FixtureGit.commit(main, "lock X symlink mode");((ObjectNode)binding.path("roots").get(0)).put("expected_commit",FixtureGit.head(main));Files.write(roots,JSON.writeValueAsBytes(binding));blocked("lock-mode");
 }
 @Test void executableRegularCommittedModeRemainsSupported()throws Exception{FixtureGit.indexAsExecutable(service, "tests/sit/cases/same.yaml");FixtureGit.commit(service, "public regular executable file mode");serviceVersion(FixtureGit.head(service));var result=execute("regular-100755");assertThat(result.code()).as(result.out()+result.err()).isZero();}
 @Test void rejectionDiagnosticsNeverEchoUnknownKeysOrExceedBound()throws Exception{
  for(int i=0;i<30;i++)document.put("PUBLIC_CANARY_FIELD_"+i+"_"+"x".repeat(40000),false);persist();var result=execute("bounded-diagnostics");assertThat(result.code()).isEqualTo(1);assertThat(result.err().length()).isLessThanOrEqualTo(4096);assertThat(result.err()).doesNotContain("PUBLIC_CANARY_FIELD_");
 }
 @Test void changedOldPinsInvalidated()throws Exception{String original=sha(Files.readAllBytes(lock));Files.writeString(lock,"changed: true\n");var out=new ByteArrayOutputStream();int c=new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(new String[]{"runner-package","--dependencies-lock",lock.toString(),"--expected-lock-sha256",original,"--roots-binding",roots.toString(),"--expected-roots-sha256",sha(Files.readAllBytes(roots)),"--out",temp.resolve("pin-out").toString()},new PrintStream(out),new PrintStream(new ByteArrayOutputStream()));assertThat(c).isEqualTo(1);assertThat(out.toString()).contains("BLOCKED");}
}
