package com.specdriven.regression.evidence;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.provider.jdbc.ReviewedJdbcProof;
import com.specdriven.regression.provider.jdbc.ReviewedOwnedMariaDbPolicy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
/** Historical synthetic semantic joins. No current clock/process/driver/credentials/backend. */
class ReviewedJdbcProofIdentityTest {
 @TempDir Path root;ReviewedGrpcProofIdentityTest h;ObjectNode proof;static final String PATH="provider-evidence/jdbc/reviewed-readonly.json";String data;
 @BeforeEach void fixture()throws Exception {
  h=new ReviewedGrpcProofIdentityTest();h.root=root;h.validHistoricalFixture();
  for(var node:List.of(h.result,h.provenance,h.index))replace(node);
  var lease=ReviewedGrpcProofIdentityTest.obj("version","reviewed-mariadb-lease.v1","profile",ReviewedOwnedMariaDbPolicy.PROFILE,"owner_run_id","a".repeat(32),"container_id","b".repeat(64),"image_digest",ReviewedOwnedMariaDbPolicy.IMAGE_SHA,"container_started_at","2000-01-01T00:00:00Z","owner_pid",2,"owner_start_instant","2000-01-01T00:00:00Z","db_pid",3,"db_start_instant","2000-01-01T00:00:00Z","endpointIPv4","172.17.0.2","port",3306,"schema","swarm_probe","query_sha256",ReviewedJdbcProof.querySha(),"driver_sha256",ReviewedOwnedMariaDbPolicy.DRIVER_SHA,"issued_at","2000-01-01T00:00:00Z","expires_at","2000-01-01T00:05:00Z");h.provenance.set("fixture_lease",lease);
  bindRawLease();
  data="{\"row_count\":0,\"columns\":[{\"name\":\"probe_id\",\"jdbc_type\":4},{\"name\":\"probe_value\",\"jdbc_type\":12},{\"name\":\"total_rows\",\"jdbc_type\":-5}],\"rows\":[]}";
  ((ObjectNode)h.result.path("provider_results").get(0).path("resolved_operation_results").get(0)).putObject("outputs").put("result",data);
  proof=ReviewedGrpcProofIdentityTest.obj("evidence_type","jdbc_query","provider_type","jdbc","provider_id","grpc","fixture_lease_sha256",h.provenance.path("fixture_lease_sha256").asText(),"owner_run_id","a".repeat(32),"endpoint","172.17.0.2:3306","schema","swarm_probe","profile",ReviewedOwnedMariaDbPolicy.PROFILE,"query_sha256",ReviewedJdbcProof.querySha(),"driver_sha256",ReviewedOwnedMariaDbPolicy.DRIVER_SHA,"driver_connect_attempted",true,"connected",true,"sql_attempted",true,"cleanup_confirmed",true,"status","OK","stage","COMPLETE","sql_state","","vendor_code",0,"result",data,"invocation_id","69e168da-0c39-4c55-9dc3-e6380885c768","process_pid",100,"process_start_instant","2000-01-01T00:00:00Z");
  proof.put("fixture_admitted_at","2000-01-01T00:00:01.105Z").put("operation","db_query").put("operation_ms",100).put("cleanup_ms",10).put("total_ms",110).put("operation_started_at","2000-01-01T00:00:01.100Z").put("operation_ended_at","2000-01-01T00:00:01.210Z");
  ((ObjectNode)h.index.path("entries").get(0)).put("evidence_type","jdbc_query");save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }
 void replace(ObjectNode node)throws Exception {String text=ReviewedGrpcProofIdentityTest.JSON.writeValueAsString(node).replace("grpc_client.v0.3","jdbc.v0.3").replace("grpc_client","jdbc").replace("unary_call","db_query").replace(ReviewedGrpcProofIdentityTest.PROOF,PATH);ObjectNode updated=(ObjectNode)ReviewedGrpcProofIdentityTest.JSON.readTree(text);node.removeAll();node.setAll(updated);}
 void save()throws Exception {
  h.write(ReviewedGrpcProofIdentityTest.HANDOFF,h.provenance);h.write(ReviewedGrpcProofIdentityTest.POLICY,h.policy);h.write(PATH,proof);
  ObjectNode b=h.result.putObject("product_test");for(String f:List.of("contract_version","handoff_sha256","knowledge_mode","test_level","test_type","environment_stage","workspace_ref","project_ref","repository_revisions","provider_modes","dependency_modes"))b.set(f,h.provenance.path(f).deepCopy());b.put("provenance_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(ReviewedGrpcProofIdentityTest.HANDOFF)))));h.write("result.json",h.result);Files.writeString(root.resolve("evidence_index.yaml"),new org.yaml.snakeyaml.Yaml().dump(ReviewedGrpcProofIdentityTest.JSON.convertValue(h.index,Map.class)));
 }
 @ParameterizedTest @ValueSource(strings={"owner","endpoint","schema","profile","query","driver","lease","attempt_type","connected_type","sql_type","cleanup_type","attempt_false","connected_false","sql_false","cleanup_false","status","result","invocation","pid","start","closure","unindexed","evidence_type","provider_type","provider_id","operation","missing_time","string_time","negative_time","inconsistent_time","over_budget_ok","ok_stage","sql_failure_ok","missing_started","outside_lease","column_name","column_type","column_extra","row_type","row_missing","row_extra","null_flag","duplicate_json","trailing_json"})
 void editedProofIsInvalidEvenWhenItsFileAndIndexAreReadable(String change)throws Exception {
  switch(change){case "owner"->proof.put("owner_run_id","0".repeat(32));case "endpoint"->proof.put("endpoint","172.17.0.3:3306");case "schema"->proof.put("schema","other");case "profile"->proof.put("profile","generic");case "query"->proof.put("query_sha256","e".repeat(64));case "driver"->proof.put("driver_sha256","e".repeat(64));case "lease"->proof.put("fixture_lease_sha256","e".repeat(64));case "attempt_type"->proof.put("driver_connect_attempted","true");case "connected_type"->proof.put("connected",1);case "sql_type"->proof.put("sql_attempted","true");case "cleanup_type"->proof.put("cleanup_confirmed",1);case "attempt_false"->proof.put("driver_connect_attempted",false);case "connected_false"->proof.put("connected",false);case "sql_false"->proof.put("sql_attempted",false);case "cleanup_false"->proof.put("cleanup_confirmed",false);case "status"->proof.put("status","CONNECT_FAILED");case "result"->proof.put("result","{}");case "invocation"->proof.put("invocation_id","other");case "pid"->proof.put("process_pid",0);case "start"->proof.put("process_start_instant","invalid");case "closure"->h.provenance.putArray("reviewed_files");case "unindexed"->h.index.withArray("entries").remove(0);default->mutateClosedProof(change);}save();assertThat(h.validate().valid()).as("must reject JDBC %s: %s",change,h.validate().findings()).isFalse();
 }

 @Test void failedTypedFalseAndHistoricalPreflightStayValid()throws Exception {
  h.result.put("status","failed");((ObjectNode)h.result.path("test_results").get(0)).put("status","failed");proof.put("result","").put("stage","CLEANUP").put("status","CLEANUP_UNCONFIRMED").put("driver_connect_attempted",false).put("connected",false).put("sql_attempted",false).put("cleanup_confirmed",false);save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }
 void bindRawLease()throws Exception {byte[] raw=ReviewedGrpcProofIdentityTest.JSON.writeValueAsBytes(h.provenance.path("fixture_lease"));String pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));h.provenance.put("fixture_lease_raw_base64",Base64.getEncoder().encodeToString(raw)).put("fixture_lease_sha256",pin);((ObjectNode)h.provenance.path("reviewed_files").get(0)).put("sha256",pin);if(proof!=null)proof.put("fixture_lease_sha256",pin);}
 @ParameterizedTest @ValueSource(strings={"joint_edit","missing_raw","raw_mismatch","raw_invalid"}) void rawLeaseIsIndependentlyBoundAtTheCli(String kind)throws Exception {
  switch(kind){case "joint_edit"->{((ObjectNode)h.provenance.path("fixture_lease")).put("owner_run_id","0".repeat(32));proof.put("owner_run_id","0".repeat(32));}case "missing_raw"->h.provenance.remove("fixture_lease_raw_base64");case "raw_mismatch"->h.provenance.put("fixture_lease_raw_base64",Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));case "raw_invalid"->h.provenance.put("fixture_lease_raw_base64","not_base64!");default->throw new AssertionError();}save();
  var output=new java.io.ByteArrayOutputStream();var cli=new com.specdriven.regression.cli.RegressionCommand(new com.specdriven.regression.productrepo.ProductRepoService(),new com.specdriven.regression.discovery.ReleasePackageService());var stream=new java.io.PrintStream(output,true,java.nio.charset.StandardCharsets.UTF_8);assertThat(cli.execute(new String[]{"validate-evidence","--result",root.resolve("result.json").toString()},stream,stream)).as("CLI must reject %s: %s",kind,output).isNotZero();
 }
 void mutateClosedProof(String change)throws Exception {
  switch(change){case "evidence_type"->proof.put("evidence_type","other");case "provider_type"->proof.put("provider_type","other");case "provider_id"->proof.put("provider_id","other");case "operation"->proof.put("operation","seed");case "missing_time"->proof.remove("operation_ms");case "string_time"->proof.put("cleanup_ms","10");case "negative_time"->proof.put("operation_ms",-1);case "inconsistent_time"->proof.put("total_ms",0);case "over_budget_ok"->proof.put("operation_ms",5001);case "ok_stage"->proof.put("stage","SOURCE");case "sql_failure_ok"->proof.put("sql_state","08000");case "missing_started"->proof.remove("operation_started_at");case "outside_lease"->{((ObjectNode)h.provenance.path("fixture_lease")).put("issued_at","1999-12-31T23:55:00Z").put("expires_at","2000-01-01T00:00:00Z");bindRawLease();}default->mutatePayload(change);}
 }
 void mutatePayload(String change)throws Exception {
  var payload=(ObjectNode)ReviewedGrpcProofIdentityTest.JSON.readTree(data);var row=payload.putArray("rows").addObject().put("probe_id",7).put("probe_value","different-business-data").put("total_rows",99);row.putArray("null_columns");payload.put("row_count",1);
  switch(change){case "column_name"->((ObjectNode)payload.path("columns").get(0)).put("name","other");case "column_type"->((ObjectNode)payload.path("columns").get(0)).put("jdbc_type",12);case "column_extra"->((ObjectNode)payload.path("columns").get(0)).put("extra",true);case "row_type"->row.put("probe_id","7");case "row_missing"->row.remove("total_rows");case "row_extra"->row.put("extra",true);case "null_flag"->row.putArray("null_columns").add("probe_value");default->{}}String value=ReviewedGrpcProofIdentityTest.JSON.writeValueAsString(payload);if(change.equals("duplicate_json"))value=value.substring(0,value.length()-1)+",\"row_count\":1}";if(change.equals("trailing_json"))value+=" {}";proof.put("result",value);((ObjectNode)h.result.path("provider_results").get(0).path("resolved_operation_results").get(0).path("outputs")).put("result",value);
 }
 @Test void typedBusinessDifferenceAndFailedTimeoutRemainHistoricalEvidence()throws Exception {
  h.result.put("status","failed");((ObjectNode)h.result.path("test_results").get(0)).put("status","failed");mutatePayload("valid");save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
  proof.put("status","DEADLINE_EXCEEDED").put("stage","CONNECT").put("connected",false).put("sql_attempted",false).put("result","").put("operation_ms",5001).put("cleanup_ms",10).put("total_ms",5011).put("operation_ended_at","2000-01-01T00:00:06.111Z");h.result.put("end_time","2000-01-01T00:00:07Z");save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }

 @ParameterizedTest @ValueSource(strings={"failed","blocked"}) void boundRawLeaseAllowsZeroProviderPreflight(String status)throws Exception {
  h.result.put("status",status);((ObjectNode)h.result.path("test_results").get(0)).put("status",status);h.result.putArray("provider_results");h.result.putArray("provider_summary");h.result.putArray("step_results");h.result.putArray("steps");h.result.withArray("evidence_refs").remove(0);h.result.putArray("provider_evidence_refs");h.index.withArray("entries").remove(0);h.provenance.putObject("observed_provider_modes");h.policy.put("status","blocked");((ObjectNode)h.index.path("entries").get(1)).put("status","blocked");save();Files.delete(root.resolve(PATH));assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }
 @ParameterizedTest @ValueSource(strings={"duplicate","trailing","malformed"}) void malformedOriginalLeaseBytesStayRejectedEvenWithMatchingDigest(String kind)throws Exception {
  String raw=ReviewedGrpcProofIdentityTest.JSON.writeValueAsString(h.provenance.path("fixture_lease"));raw=switch(kind){case "duplicate"->raw.substring(0,raw.length()-1)+",\"port\":3306}";case "trailing"->raw+" {}";default->"{broken";};byte[] bytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);String pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));h.provenance.put("fixture_lease_raw_base64",Base64.getEncoder().encodeToString(bytes)).put("fixture_lease_sha256",pin);((ObjectNode)h.provenance.path("reviewed_files").get(0)).put("sha256",pin);proof.put("fixture_lease_sha256",pin);save();assertThat(h.validate().valid()).isFalse();
 }
 @Test void truthfulCleanupOverrunIsFailedEvidenceRatherThanInvalidEvidence()throws Exception {
  h.result.put("status","failed").put("end_time","2000-01-01T00:00:04Z");((ObjectNode)h.result.path("test_results").get(0)).put("status","failed");proof.put("status","CLEANUP_UNCONFIRMED").put("stage","CLEANUP").put("cleanup_confirmed",false).put("result","").put("operation_ms",100).put("cleanup_ms",2001).put("total_ms",2101).put("operation_ended_at","2000-01-01T00:00:03.201Z");save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }

 @Test void aFailedProofStillRequiresItsSingleCanonicalResolvedOperation()throws Exception {
  h.result.put("status","failed");((ObjectNode)h.result.path("test_results").get(0)).put("status","failed");((ObjectNode)h.result.path("provider_results").get(0)).putArray("resolved_operation_results");proof.put("status","CONNECT_FAILED").put("stage","CONNECT").put("connected",false).put("sql_attempted",false).put("result","");save();assertThat(h.validate().valid()).isFalse();
 }

 @Test void truthfulLongCleanupObservationMustStayFailedRatherThanInvalid()throws Exception {
  h.result.put("status","failed").put("end_time","2000-01-01T00:00:33Z");((ObjectNode)h.result.path("test_results").get(0)).put("status","failed");proof.put("status","CLEANUP_UNCONFIRMED").put("stage","CLEANUP").put("cleanup_confirmed",false).put("result","").put("operation_ms",100).put("cleanup_ms",30001).put("total_ms",30101).put("operation_ended_at","2000-01-01T00:00:31.201Z");save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }

 @Test void privacyRejectedTypedResultRemainsValidFailedHistoricalEvidence()throws Exception {
  h.result.put("status","failed");((ObjectNode)h.result.path("test_results").get(0)).put("status","failed");proof.put("status","SECRET_OUTPUT_BLOCKED").put("stage","RESULT").put("result","");save();assertThat(h.validate().valid()).as(h.validate().findings().toString()).isTrue();
 }

}
