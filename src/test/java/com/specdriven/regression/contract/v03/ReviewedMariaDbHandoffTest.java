package com.specdriven.regression.contract.v03;
import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.provider.jdbc.ReviewedOwnedMariaDbPolicy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ReviewedMariaDbHandoffTest {
 @TempDir Path temporary;ReviewedLoopbackRestTest h=new ReviewedLoopbackRestTest();
 @Test void exactQueryAndLeaseAreIncludedInReviewedClosureWithoutCredentialResolution()throws Exception {
  Path suite=fixture();Files.writeString(suite.resolve("fixtures/lease.json")," \n"+Files.readString(suite.resolve("fixtures/lease.json")));byte[] raw=Files.readAllBytes(suite.resolve("fixtures/lease.json"));assertThatCode(()->h.prepare(suite)).doesNotThrowAnyException();
  var projection=new LinkedHashMap<String,Object>();var writer=ProductTestHandoffService.class.getDeclaredMethod("recordJdbcLease",Map.class,Map.class,String.class,byte[].class);writer.setAccessible(true);writer.invoke(null,projection,h.json.readValue(raw,Map.class),ReviewedOwnedMariaDbPolicy.digest(raw),raw);assertThat(Base64.getDecoder().decode((String)projection.get("fixture_lease_raw_base64"))).containsExactly(raw);
  var handoff=h.json.readTree(suite.resolve("handoff/reviewed.json").toFile());assertThat(handoff.path("reviewed_files").toString()).contains("queries/owned-readonly.sql","fixtures/lease.json");
 }
 @Test void changedSqlOrUnboundedBindingsCannotBePrepared()throws Exception {
  Path suite=fixture();assertThatCode(()->h.prepare(suite)).doesNotThrowAnyException();Files.writeString(suite.resolve("queries/owned-readonly.sql"),"SELECT 1");assertThatThrownBy(()->h.prepare(suite)).isInstanceOf(IllegalArgumentException.class);
 }
 Path fixture()throws Exception {
  h.temporary=temporary;Path suite=h.fixture("http://127.0.0.1:1","GET",true);
  var manifest=h.read(suite.resolve("suite_manifest.yaml"));h.map(h.map(manifest.get("targets")).get("todo_api")).put("provider_contract","jdbc.v0.3");h.map(manifest.get("artifact_roots")).put("queries","queries/");h.map(manifest.get("artifact_roots")).put("fixtures","fixtures/");h.write(suite.resolve("suite_manifest.yaml"),manifest);
  var env=h.read(suite.resolve("env_profiles/local_native.yaml"));h.map(h.map(env.get("targets")).get("todo_api")).put("bindings",Map.of("dialect","mariadb","profile",ReviewedOwnedMariaDbPolicy.PROFILE,"connection.secret_ref",ReviewedOwnedMariaDbPolicy.SECRET_REF,"endpoint","172.17.0.2:3306","owner_run_id","a".repeat(32)));h.write(suite.resolve("env_profiles/local_native.yaml"),env);
  Files.delete(suite.resolve("contracts/rest_client_v0_3.yaml"));h.json.writeValue(suite.resolve("contracts/jdbc_v0_3.yaml").toFile(),h.read(Path.of("docs/02-architecture/contracts/provider-contracts/jdbc_v0_3.yaml")));
  Files.createDirectories(suite.resolve("queries"));Files.writeString(suite.resolve("queries/owned-readonly.sql"),ReviewedOwnedMariaDbPolicy.SQL);Files.createDirectories(suite.resolve("fixtures"));Instant now=Instant.now();Map<String,Object> lease=new LinkedHashMap<>();lease.put("version","reviewed-mariadb-lease.v1");lease.put("profile",ReviewedOwnedMariaDbPolicy.PROFILE);lease.put("owner_run_id","a".repeat(32));lease.put("container_id","b".repeat(64));lease.put("image_digest",ReviewedOwnedMariaDbPolicy.IMAGE_SHA);lease.put("container_started_at",now.toString());lease.put("owner_pid",2);lease.put("owner_start_instant",now.toString());lease.put("db_pid",3);lease.put("db_start_instant",now.toString());lease.put("endpointIPv4","172.17.0.2");lease.put("port",3306);lease.put("schema","swarm_probe");lease.put("query_sha256",ReviewedOwnedMariaDbPolicy.digest(ReviewedOwnedMariaDbPolicy.SQL.getBytes(java.nio.charset.StandardCharsets.UTF_8)));lease.put("driver_sha256",ReviewedOwnedMariaDbPolicy.DRIVER_SHA);lease.put("issued_at",now.toString());lease.put("expires_at",now.plusSeconds(120).toString());h.json.writeValue(suite.resolve("fixtures/lease.json").toFile(),lease);
  h.write(suite.resolve("test_cases/items.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-HTTP","title","Synthetic reviewed JDBC metadata","execute",List.of(Map.of("id","read","target","todo_api","op","db_query","with",Map.of("query_ref","artifact://queries/owned-readonly.sql"))),"verify",List.of(Map.of("id","oracle","type","assertion","assert",Map.of("actual","step://read/result","operator","json_match","expected_ref","artifact://expected/items.json")))));
  var review=h.json.readValue(suite.resolve("review-input.json").toFile(),Map.class);review.put("reviewed_contracts",List.of(Map.of("path","contracts/jdbc_v0_3.yaml","provider_contract","jdbc.v0.3")));var files=(List<Object>)review.get("reviewed_files");files.add(Map.of("path","queries/owned-readonly.sql","role","fixture"));files.add(Map.of("path","fixtures/lease.json","role","fixture"));h.map(((List<?>)review.get("requirement_mapping")).get(0)).put("oracle_refs",List.of("expected/items.json"));h.json.writeValue(suite.resolve("review-input.json").toFile(),review);return suite;
 }
}
