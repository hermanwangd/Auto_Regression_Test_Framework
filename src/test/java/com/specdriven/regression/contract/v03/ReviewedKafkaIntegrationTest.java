package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PUBLIC_SYNTHETIC source/contract fixtures, never broker or network authority. */
class ReviewedKafkaIntegrationTest {
    @TempDir Path temporary;
    final ReviewedLoopbackRestTest helper=new ReviewedLoopbackRestTest();
    static final String OWNER="b".repeat(32);
    @Test void exactKafkaPublishSourceClosureCanBePreparedWithoutExecutingProvider() throws Exception {
        Path suite=fixture();assertThatCode(()->helper.prepare(suite)).doesNotThrowAnyException();
        var handoff=helper.json.readTree(suite.resolve("handoff/reviewed.json").toFile());
        assertThat(handoff.path("reviewed_contracts").get(0).path("provider_contract").asText()).isEqualTo("kafka.v0.3");
        assertThat(handoff.path("reviewed_files").toString()).contains("fixtures/lease.json","fixtures/payload.json");
    }
    @Test void finitePublishAdapterOptsIntoOwnedWriteWithoutSharedState() {
        Object candidate=new com.specdriven.regression.contract.v03.adapter.KafkaV03Adapter();
        assertThat(candidate).isInstanceOf(com.specdriven.regression.sit.SitPolicyAwareProvider.class);
        var adapter=(com.specdriven.regression.sit.SitPolicyAwareProvider)candidate;
        var step=new V03ExecutionStep("TC-KAFKA","execute","publish","todo_api","kafka.v0.3","kafka","local_native","native","kafka_publish",Map.of("payload_ref","artifact://fixtures/payload.json","key","run."+OWNER),"");
        assertThat(adapter.sitEffect(step)).isEqualTo(com.specdriven.regression.sit.SitOperationEffect.OWNED_WRITE);
        assertThatThrownBy(()->adapter.executeWithSitPolicy(step,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void syntheticAckTraversesExistingCanonicalEngineAndKafkaEvidenceJoin()throws Exception {
        Path suite=fixture();liveDates(suite);helper.prepare(suite);var adapter=new ContractAdapter();var result=contractRun(suite,adapter);
        assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");assertThat(adapter.calls).hasValue(1);helper.assertValid(result);
        var proofFile=result.evidenceDir().resolve("provider-evidence/kafka/reviewed-publish.json");var proof=(com.fasterxml.jackson.databind.node.ObjectNode)helper.json.readTree(proofFile.toFile());proof.put("owner_run_id","a".repeat(32));helper.json.writeValue(proofFile.toFile(),proof);
        var checked=new com.specdriven.regression.evidence.EvidenceHardeningService().validateResult(result.resultJson());assertThat(checked.valid()).isFalse();assertThat(checked.findings().toString()).contains("reviewed_evidence_invalid");
    }
    @Test void syntheticAckAndWrongOraclePreserveFrameworkFailWithValidEvidence()throws Exception {
        Path suite=fixture();liveDates(suite);var test=helper.read(suite.resolve("test_cases/items.yaml"));helper.map(helper.map(((List<?>)test.get("verify")).get(0)).get("assert")).put("expected",1);helper.write(suite.resolve("test_cases/items.yaml"),test);helper.prepare(suite);var adapter=new ContractAdapter();var result=contractRun(suite,adapter);assertThat(result.status()).as(result.findings().toString()).isEqualTo("failed");assertThat(adapter.calls).hasValue(1);helper.assertValid(result);
    }
    @Test void pinnedScalarArtifactOracleUsesExistingTypedAssertionPath()throws Exception {
        Path suite=fixture();liveDates(suite);Files.writeString(suite.resolve("expected/offset.json"),"0\n");
        var test=helper.read(suite.resolve("test_cases/items.yaml"));var assertion=helper.map(helper.map(((List<?>)test.get("verify")).get(0)).get("assert"));assertion.remove("expected");assertion.put("expected_ref","artifact://expected/offset.json");helper.write(suite.resolve("test_cases/items.yaml"),test);
        var review=helper.json.readValue(suite.resolve("review-input.json").toFile(),Map.class);((List<Object>)review.get("reviewed_files")).add(Map.of("path","expected/offset.json","role","oracle"));helper.map(((List<?>)review.get("requirement_mapping")).get(0)).put("oracle_refs",List.of("expected/offset.json"));helper.json.writeValue(suite.resolve("review-input.json").toFile(),review);
        helper.prepare(suite);var adapter=new ContractAdapter();var result=contractRun(suite,adapter);assertThat(result.status()).as(result.findings().toString()).isEqualTo("passed");assertThat(adapter.calls).hasValue(1);helper.assertValid(result);
    }
    @Test void changedReviewedPayloadRejectsBeforeInjectedAdapterAcquisition()throws Exception {
        Path suite=fixture();liveDates(suite);helper.prepare(suite);Files.writeString(suite.resolve("fixtures/payload.json"),"{}");var adapter=new ContractAdapter();var result=contractRun(suite,adapter);assertThat(result.status()).isEqualTo("blocked");assertThat(result.providerRuntimeExecuted()).isFalse();assertThat(adapter.calls).hasValue(0);
    }
    void liveDates(Path suite)throws Exception{Path path=suite.resolve("fixtures/lease.json");var lease=(com.fasterxml.jackson.databind.node.ObjectNode)helper.json.readTree(path.toFile());lease.put("issued_at",java.time.Instant.now().minusSeconds(1).toString()).put("expires_at",java.time.Instant.now().plusSeconds(300).toString());helper.json.writeValue(path.toFile(),lease);}
    V03RuntimeExecutionService.V03RuntimeRunResult contractRun(Path suite,V03ProviderRuntimeAdapter adapter)throws Exception{
        var pin=new ProductTestHandoffService.InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(suite.resolve("fixtures/lease.json")))));
        var service=new V03RuntimeExecutionService(new com.specdriven.regression.contract.ContractBaselineService(),new V03ProviderRuntimeRegistry(List.of(adapter)));
        return service.runReviewed(suite.resolve("suite_manifest.yaml"),"local_native",com.specdriven.regression.summary.SuiteExecutionContext.standalone("local_native",temporary.toRealPath().resolve("runs"),"V03"),pin);
    }
    /** Explicit test double: ACK and ownership declarations are synthetic, never native acceptance. */
    static class ContractAdapter implements com.specdriven.regression.sit.SitPolicyAwareProvider {
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        final String outcome;final boolean testSimulated;ContractAdapter(){this("ACK",false);}ContractAdapter(String outcome,boolean simulated){this.outcome=outcome;this.testSimulated=simulated;}
        public String providerType(){return "kafka";}public boolean supports(String c,String o){return c.equals("kafka.v0.3")&&o.equals("kafka_publish");}public boolean simulated(){return testSimulated;}public com.specdriven.regression.sit.SitOperationEffect sitEffect(String c,String o){return com.specdriven.regression.sit.SitOperationEffect.OWNED_WRITE;}
        public V03StepResult executeWithSitPolicy(V03ExecutionStep s,V03ExecutionContext c,com.specdriven.regression.sit.SitExecutionScope scope){throw new AssertionError("no shared scope");}
        public V03StepResult execute(V03ExecutionStep step,V03ExecutionContext context){
            calls.incrementAndGet();try{boolean ack=outcome.equals("ACK");var json=new com.fasterxml.jackson.databind.ObjectMapper();byte[] raw=Files.readAllBytes(context.suiteRoot().resolve("fixtures/lease.json"));var lease=json.readTree(raw);var proof=json.createObjectNode();for(String f:List.of("owner_run_id","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode"))proof.set(f,lease.get(f));proof.put("version","reviewed-kafka-publish.v1").put("evidence_type","kafka_event").put("provider_type","kafka").put("run_id",context.runDir().getFileName().toString()).put("batch_id",context.runDir().getParent().getFileName().toString()).put("step_id",step.id()).put("provider_id",step.target()).put("operation","kafka_publish").put("fixture_lease_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw))).put("client_version","3.9.2").put("attempted",true).put("acknowledged",ack).put("cleanup_confirmed",true).put("status",outcome).put("partition",ack?0:-1).put("offset",ack?0:-1).put("client_started_at",java.time.Instant.now().toString()).put("client_finished_at",java.time.Instant.now().toString());String ref="provider-evidence/kafka/reviewed-publish.json";Path path=context.runDir().resolve(ref);Files.createDirectories(path.getParent());json.writeValue(path.toFile(),proof);return new V03StepResult(step.id(),ack?"passed":"failed",Map.of("partition",ack?0:-1,"offset",ack?0L:-1L,"key",lease.path("key").asText(),"topic",lease.path("topic").asText()),List.of(ref),ack?"":"KAFKA_CONNECTION_FAILED",ack?"":"PUBLIC_SYNTHETIC failed transport");}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"lease_expired","lease_future","client_before_run","client_after_run","client_after_lease"})
    void resealedHistoricalTimeCannotPassRealValidateEvidenceOrReport(String variant)throws Exception {
        Path suite=fixture();liveDates(suite);helper.prepare(suite);var result=contractRun(suite,new ContractAdapter());helper.assertValid(result);
        String original=sha(Files.readAllBytes(result.resultJson()));Path copy=copyGraph(result);resealTime(copy,variant);
        var validation=cli("validate-evidence","--result",copy.resolve("result.json").toString());var report=cli("report","--result",copy.resolve("result.json").toString());
        assertThat(sha(Files.readAllBytes(result.resultJson()))).isEqualTo(original);
        org.assertj.core.api.SoftAssertions.assertSoftly(soft->{soft.assertThat(validation.exit()).as(variant+validation.stdout()+validation.stderr()).isNotZero();soft.assertThat(report.exit()).as(variant+report.stdout()+report.stderr()).isNotZero();soft.assertThat(report.stdout()).contains("report_status: invalid").doesNotContain("report_status: review_ready");});
    }
    @Test void aHistoricallyValidLeaseExpiredNowStillPassesActualReportAdmission()throws Exception {
        Path suite=fixture();liveDates(suite);helper.prepare(suite);var result=contractRun(suite,new ContractAdapter());Path copy=copyGraph(result);
        try(var paths=Files.walk(copy)){for(Path file:paths.filter(p->p.toString().endsWith(".json")).toList()){var tree=helper.json.readTree(file.toFile());shiftRunInstants(tree);helper.json.writeValue(file.toFile(),tree);}}
        resealTime(copy,"historic");var provenance=helper.json.readTree(copy.resolve("product-test/reviewed-handoff.json").toFile());assertThat(java.time.Instant.parse(provenance.at("/fixture_lease/expires_at").asText()).isBefore(java.time.Instant.now())).isTrue();
        var validation=cli("validate-evidence","--result",copy.resolve("result.json").toString());var report=cli("report","--result",copy.resolve("result.json").toString());assertThat(validation.exit()).as(validation.stdout()+validation.stderr()).isZero();assertThat(report.exit()).as(report.stdout()+report.stderr()).isZero();assertThat(report.stdout()).contains("report_status: review_ready");
    }
    @Test void legitimateFailedAndNoProviderPreflightKeepValidHistoricalEvidence()throws Exception {
        for(boolean noProvider:List.of(false,true)){
            Path suite=fixture();liveDates(suite);helper.prepare(suite);var adapter=new ContractAdapter(noProvider?"ACK":"CLIENT_FAILED",noProvider);var result=contractRun(suite,adapter);
            assertThat(result.status()).as(result.findings().toString()).isEqualTo(noProvider?"blocked":"failed");assertThat(adapter.calls).hasValue(noProvider?0:1);helper.assertValid(result);
            var validation=cli("validate-evidence","--result",result.resultJson().toString());assertThat(validation.exit()).as(validation.stdout()+validation.stderr()).isZero();
        }
    }
    Path copyGraph(V03RuntimeExecutionService.V03RuntimeRunResult result)throws Exception{
        Path from=result.resultJson().getParent(),copy=Files.createTempDirectory(temporary,"kafka-tampered-copy-");int count=0;long bytes=0;
        try(var paths=Files.walk(from)){for(Path p:paths.toList()){assertThat(Files.isSymbolicLink(p)).isFalse();Path dest=copy.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(dest);else{assertThat(++count).isLessThanOrEqualTo(128);bytes+=Files.size(p);assertThat(bytes).isLessThanOrEqualTo(8*1024*1024);Files.copy(p,dest);}}}return copy;
    }
    static String sha(byte[] raw)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}
    void resealTime(Path root,String variant)throws Exception{
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)helper.json.readTree(root.resolve("result.json").toFile());var provenance=(com.fasterxml.jackson.databind.node.ObjectNode)helper.json.readTree(root.resolve("product-test/reviewed-handoff.json").toFile());var proof=(com.fasterxml.jackson.databind.node.ObjectNode)helper.json.readTree(root.resolve("provider-evidence/kafka/reviewed-publish.json").toFile());var lease=(com.fasterxml.jackson.databind.node.ObjectNode)provenance.path("fixture_lease");var start=java.time.Instant.parse(result.path("start_time").asText());var end=java.time.Instant.parse(result.path("end_time").asText());
        switch(variant){
            case "lease_expired"->lease.put("issued_at",start.minusSeconds(300).toString()).put("expires_at",start.minusNanos(1).toString());
            case "lease_future"->lease.put("issued_at",start.plusNanos(1).toString()).put("expires_at",start.plusSeconds(300).toString());
            case "client_before_run"->proof.put("client_started_at",start.minusNanos(1).toString());
            case "client_after_run"->proof.put("client_finished_at",end.plusNanos(1).toString());
            case "client_after_lease"->lease.put("expires_at",java.time.Instant.parse(proof.path("client_finished_at").asText()).minusNanos(1).toString());
            case "historic"->lease.put("issued_at",start.minusSeconds(1).toString()).put("expires_at",end.plusSeconds(1).toString());
            default->throw new AssertionError(variant);
        }
        byte[] raw=helper.json.writeValueAsBytes(lease);String pin=sha(raw);provenance.put("fixture_lease_sha256",pin).put("fixture_lease_base64",java.util.Base64.getEncoder().encodeToString(raw));for(var f:provenance.path("reviewed_files"))if("fixtures/lease.json".equals(f.path("path").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)f).put("sha256",pin);proof.put("fixture_lease_sha256",pin);helper.json.writeValue(root.resolve("provider-evidence/kafka/reviewed-publish.json").toFile(),proof);byte[] encoded=helper.json.writeValueAsBytes(provenance);Files.write(root.resolve("product-test/reviewed-handoff.json"),encoded);((com.fasterxml.jackson.databind.node.ObjectNode)result.path("product_test")).put("provenance_sha256",sha(encoded));helper.json.writeValue(root.resolve("result.json").toFile(),result);
    }
    void shiftRunInstants(com.fasterxml.jackson.databind.JsonNode node){
        if(node.isObject()){var o=(com.fasterxml.jackson.databind.node.ObjectNode)node;var fields=new ArrayList<String>();o.fieldNames().forEachRemaining(fields::add);for(String f:fields){var n=o.get(f);if(n.isTextual()&&!Set.of("reviewed_at","valid_until").contains(f)){try{o.put(f,java.time.Instant.parse(n.asText()).minusSeconds(60).toString());}catch(java.time.DateTimeException ignored){}}else shiftRunInstants(n);}}
        else if(node.isArray())for(var n:node)shiftRunInstants(n);
    }
    record Cli(int exit,String stdout,String stderr){}
    Cli cli(String... args)throws Exception{var stdout=new java.io.ByteArrayOutputStream();var stderr=new java.io.ByteArrayOutputStream();int rc=new com.specdriven.regression.cli.RegressionCommand(new com.specdriven.regression.productrepo.ProductRepoService(),new com.specdriven.regression.discovery.ReleasePackageService()).execute(args,new java.io.PrintStream(stdout,true,java.nio.charset.StandardCharsets.UTF_8),new java.io.PrintStream(stderr,true,java.nio.charset.StandardCharsets.UTF_8));return new Cli(rc,stdout.toString(java.nio.charset.StandardCharsets.UTF_8),stderr.toString(java.nio.charset.StandardCharsets.UTF_8));}

    @Test void actualDefaultFenceFailureUsesDeclaredContractCodeWithoutAnyTransport()throws Exception {
        Path suite=fixture();liveDates(suite);helper.prepare(suite);String pin=sha(Files.readAllBytes(suite.resolve("fixtures/lease.json")));var calls=new java.util.concurrent.atomic.AtomicInteger();
        var bridge=com.specdriven.regression.provider.kafka.ReviewedKafkaRuntimeTest.unboundContractBridge(pin,calls);var result=contractRun(suite,bridge);
        assertThat(result.findings().toString()).doesNotContain("undeclared_provider_failure_code");assertThat(result.status()).as(result.findings().toString()).isEqualTo("blocked");assertThat(calls).hasValue(0);helper.assertValid(result);
        var document=helper.json.readTree(result.resultJson().toFile());assertThat(document.path("failure").path("code").asText()).isEqualTo("KAFKA_PUBLISH_FAILED");var proof=helper.json.readTree(result.evidenceDir().resolve("provider-evidence/kafka/reviewed-publish.json").toFile());assertThat(proof.path("status").asText()).isEqualTo("PREFLIGHT_BLOCKED");assertThat(proof.path("attempted").asBoolean()).isFalse();
    }
    Path fixture()throws Exception {
        helper.temporary=temporary;Path suite=helper.fixture("http://127.0.0.1:43199","GET",false);
        var manifest=helper.read(suite.resolve("suite_manifest.yaml"));helper.map(helper.map(manifest.get("targets")).get("todo_api")).put("provider_contract","kafka.v0.3");helper.map(manifest.get("artifact_roots")).put("fixtures","fixtures/");helper.write(suite.resolve("suite_manifest.yaml"),manifest);
        var profile=helper.read(suite.resolve("env_profiles/local_native.yaml"));helper.map(helper.map(profile.get("targets")).get("todo_api")).put("bindings",Map.of("bootstrap_servers","127.0.0.1:19092","topic","sit."+OWNER+".publish","consumer_group","sit."+OWNER+".group"));helper.write(suite.resolve("env_profiles/local_native.yaml"),profile);
        Files.createDirectory(suite.resolve("fixtures"));byte[] payload="{\"id\":\"reviewed\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(suite.resolve("fixtures/payload.json"),payload);
        var lease=new LinkedHashMap<String,Object>();lease.put("version","reviewed-kafka-lease.v1");lease.put("owner_run_id",OWNER);lease.put("nonce",OWNER);lease.put("endpoint","127.0.0.1:19092");lease.put("topic","sit."+OWNER+".publish");lease.put("consumer_group","sit."+OWNER+".group");lease.put("key","run."+OWNER);lease.put("payload_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload)));lease.put("runtime_version","3.9.2");for(String field:List.of("image_child_sha256","image_config_sha256","advertised_listener_sha256"))lease.put(field,"c".repeat(64));lease.put("broker_container_id","d".repeat(64));lease.put("network_mode","none");lease.put("client_network_container_id","d".repeat(64));lease.put("advertised_listener","127.0.0.1:19092");lease.put("controller_listener","127.0.0.1:19093");lease.put("partitions",1);lease.put("initial_offset",0);lease.put("issued_at","2000-01-01T00:00:00Z");lease.put("expires_at","2000-01-01T00:05:00Z");helper.json.writeValue(suite.resolve("fixtures/lease.json").toFile(),lease);
        helper.write(suite.resolve("test_cases/items.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-HTTP","title","PUBLIC_SYNTHETIC Kafka publish contract","execute",List.of(Map.of("id","publish","target","todo_api","op","kafka_publish","with",Map.of("payload_ref","artifact://fixtures/payload.json","key","run."+OWNER,"timeout","PT0.2S"))),"verify",List.of(Map.of("id","published","type","assertion","assert",Map.of("actual","step://publish/offset","operator","equals","expected",0)))));
        helper.json.writeValue(suite.resolve("contracts/kafka.yaml").toFile(),helper.read(Path.of("docs/02-architecture/contracts/provider-contracts/kafka_v0_3.yaml")));
        var review=helper.json.readValue(suite.resolve("review-input.json").toFile(),Map.class);review.put("reviewed_contracts",List.of(Map.of("path","contracts/kafka.yaml","provider_contract","kafka.v0.3")));((List<Object>)review.get("reviewed_files")).add(Map.of("path","fixtures/payload.json","role","fixture"));((List<Object>)review.get("reviewed_files")).add(Map.of("path","fixtures/lease.json","role","fixture"));helper.map(((List<?>)review.get("requirement_mapping")).get(0)).put("oracle_refs",List.of("test_cases/items.yaml#published"));helper.json.writeValue(suite.resolve("review-input.json").toFile(),review);return suite;
    }
}
