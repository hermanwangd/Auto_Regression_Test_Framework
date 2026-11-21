package com.featuredeliveryintelligence.fdi.producttest;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** Actual controlled child + synthetic sentinels/owner double, not a database or native module PASS. */
class ReviewedJdbcTransportTest {
 @TempDir Path root;ProductTestRequest request;Path jar,driver;String leasePin;AtomicInteger guards=new AtomicInteger(),sources=new AtomicInteger();
 static final String USER="public_probe",PASSWORD="PUBLIC_SYNTHETIC_PIPE_SENTINEL_123456";
 @BeforeEach void fixture()throws Exception {
  String asset=System.getProperty("reviewed.mariadb.driver.test");Assumptions.assumeTrue(asset!=null,"cached driver binding needed for file-pin admission only");driver=Path.of(asset);
  root=root.toRealPath();Path artifacts=Files.createDirectories(root.resolve("artifacts"));Files.createDirectories(root.resolve("outputs"));
  String handoff=ProductTestingModuleTest.withReviewedProfile(artifacts,ProductTestingModuleTest.validHandoff());var document=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(handoff);
  Path profile=artifacts.resolve(document.path("reviewed_files").get(0).path("path").asText());
  // Find the actual profile entry rather than assuming its position.
  for(var entry:document.path("reviewed_files"))if(entry.path("role").asText().equals("profile")){profile=artifacts.resolve(entry.path("path").asText());Files.writeString(profile,Files.readString(profile).replace("test_level: component","test_level: integration"));((com.fasterxml.jackson.databind.node.ObjectNode)entry).put("sha256",LocalFiles.sha256(profile));}
  document.putArray("reviewed_contracts").addObject().put("provider_contract","jdbc.v0.3").put("path","contracts/jdbc.yaml").put("sha256","d".repeat(64));
  Path lease=artifacts.resolve("fixtures/lease.json");Files.createDirectories(lease.getParent());Instant now=Instant.now();var l=Json.MAPPER.createObjectNode().put("version","reviewed-mariadb-lease.v1").put("profile","owned-mariadb-readonly.v1").put("owner_run_id","a".repeat(32)).put("container_id","b".repeat(64)).put("image_digest",JdbcCanonicalEvidenceTest.IMAGE).put("container_started_at",now.toString()).put("owner_pid",2).put("owner_start_instant",now.toString()).put("db_pid",3).put("db_start_instant",now.toString()).put("endpointIPv4","172.17.0.2").put("port",3306).put("schema","swarm_probe").put("query_sha256",LocalFiles.sha256(JdbcCanonicalEvidenceTest.SQL.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("driver_sha256",JdbcCanonicalEvidenceTest.DRIVER).put("issued_at",now.toString()).put("expires_at",now.plusSeconds(120).toString());Json.MAPPER.writeValue(lease.toFile(),l);leasePin=LocalFiles.sha256(lease);document.withArray("reviewed_files").addObject().put("path","fixtures/lease.json").put("role","fixture").put("sha256",leasePin);
  Path query=artifacts.resolve("queries/owned-readonly.sql");Files.createDirectories(query.getParent());Files.writeString(query,JdbcCanonicalEvidenceTest.SQL);document.withArray("reviewed_files").addObject().put("path","queries/owned-readonly.sql").put("role","fixture").put("sha256",LocalFiles.sha256(query));
  Files.writeString(artifacts.resolve("suite_manifest.yaml"),"suite_id: SUITE\n");Json.MAPPER.writeValue(artifacts.resolve("handoff.json").toFile(),document);
  request=new ProductTestRequest("product-test-request.v1","product-test","1","mission:1","request:1","rev-17","workspace:local","project:local",Map.of("example","a".repeat(40)),Map.of("REQ-1",List.of("TC-1")),"integration","regression","local","not_used",artifacts.toString(),artifacts.resolve("suite_manifest.yaml").toString(),"SUITE","local",artifacts.resolve("handoff.json").toString(),LocalFiles.sha256(artifacts.resolve("handoff.json")));
  Path source=root.resolve("FrameChild.java");String body="byte[] b=System.in.readNBytes(1025);String frame=new String(b,java.nio.charset.StandardCharsets.US_ASCII);boolean ok=frame.startsWith(\"SWJDBC1\\n\")&&frame.contains(System.getProperty(\"swarm.jdbc.invocation_id\",\"MISSING\"))&&frame.contains(System.getenv(\"REGRESS_REVIEWED_HANDOFF_SHA256\"))&&frame.contains(\""+PASSWORD+"\");for(String v:System.getenv().values())if(v.contains(\""+PASSWORD+"\"))ok=false;for(String v:java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())if(v.contains(\""+PASSWORD+"\"))ok=false;java.util.Arrays.fill(b,(byte)0);System.out.println(ok?\"FRAME_CHECKED\":\"FRAME_INVALID\");";
  Files.writeString(source,"public class FrameChild{public static void main(String[] args)throws Exception{"+body+"}}");assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",root.toString(),source.toString())).isZero();var manifest=new Manifest();manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,"FrameChild");jar=root.resolve("frame-child.jar");try(var out=new JarOutputStream(Files.newOutputStream(jar),manifest)){out.putNextEntry(new JarEntry("FrameChild.class"));out.write(Files.readAllBytes(root.resolve("FrameChild.class")));out.closeEntry();}
 }
 @AfterEach void preserveObservedControls()throws Exception {
  Path output=root.resolve("outputs");if(!Files.isDirectory(output))return;Path retained=Path.of("target/b2b-module-transport-controls").resolve(UUID.randomUUID().toString());try(var files=Files.walk(output)){for(Path file:files.toList()){Path to=retained.resolve(output.relativize(file));if(Files.isDirectory(file))Files.createDirectories(to);else Files.copy(file,to);}}
 }
 FrameworkCliTransport transport(ReviewedJdbcBinding b)throws Exception{return new FrameworkCliTransport(new FrameworkBinding(Path.of(System.getProperty("java.home"),"bin/java"),jar,LocalFiles.sha256(jar),request.expectedHandoffSha256(),root.resolve("artifacts"),root.resolve("outputs"),Duration.ofSeconds(4),4096,leasePin,null,b));}
 ReviewedJdbcBinding binding(){return new ReviewedJdbcBinding(driver,JdbcCanonicalEvidenceTest.DRIVER,leasePin,(lease,r)->{guards.incrementAndGet();assertThat(r.handoffSha256()).isEqualTo(request.expectedHandoffSha256());assertThat(r.leaseSha256()).isEqualTo(leasePin);},r->{sources.incrementAndGet();return new JdbcCredentialLease(USER.toCharArray(),PASSWORD.toCharArray());});}
 @Test void trustedOwnerGuardPrecedesOneAnonymousPipeSourceAndOwnedChildIsReaped()throws Exception {
  var receipt=transport(binding()).execute(request);assertThat(guards).hasValue(1);assertThat(sources).hasValue(1);assertThat(receipt.outcome()).as(receipt.diagnostic()).isEqualTo(ProductTestReceipt.Outcome.INVALID_EVIDENCE);
  assertThat(Files.readString(Path.of(receipt.outputDirectory()).resolve("run.stdout.log"))).isEqualTo("FRAME_CHECKED\n");assertThat(Files.readString(Path.of(receipt.outputDirectory()).resolve("jdbc-child-observation.json"))).contains("\"terminated\":true","\"pipes_closed\":true").doesNotContain(PASSWORD,USER);
 }
 @Test void missingGuardAndWrongDriverPinCannotCreateTrustedBinding() {
  assertThatThrownBy(()->new ReviewedJdbcBinding(driver,JdbcCanonicalEvidenceTest.DRIVER,leasePin,null,r->new JdbcCredentialLease(USER.toCharArray(),PASSWORD.toCharArray()))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new ReviewedJdbcBinding(driver,"c".repeat(64),leasePin,(lease,r)->{},r->new JdbcCredentialLease(USER.toCharArray(),PASSWORD.toCharArray()))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void absentOwnerSourceBindingRejectsBeforeLaunchingOrResolving()throws Exception {var receipt=transport(null).execute(request);assertThat(receipt.outcome()).isEqualTo(ProductTestReceipt.Outcome.REJECTED);assertThat(guards).hasValue(0);assertThat(sources).hasValue(0);}
 @Test void rejectingOwnerNeverResolvesTheCredentialSource()throws Exception {
  var b=binding();var refused=new ReviewedJdbcBinding(driver,b.driverSha256(),leasePin,(lease,r)->{guards.incrementAndGet();throw new IllegalStateException(PASSWORD);},b.credentials());var receipt=transport(refused).execute(request);assertThat(guards).hasValue(1);assertThat(sources).hasValue(0);assertThat(receipt.outcome()).isNotEqualTo(ProductTestReceipt.Outcome.COMPLETED);assertThat(receipt.diagnostic()).doesNotContain(PASSWORD);
 }
}
