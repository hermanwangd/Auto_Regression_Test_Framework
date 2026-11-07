package com.specdriven.regression.fixture;

import static org.assertj.core.api.Assertions.*;
import static com.specdriven.regression.fixture.SharedSitDataDecision.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.runner.RunnerBundleService;
import com.specdriven.regression.sit.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedSitDataContractValidatorTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-11-07T20:00:00Z");
    static final String REPO = "https://example.invalid/c17-public";
    RunnerBundleService.SourceBindings source;
    Map<String, JsonNode> artifacts = new HashMap<>();
    ObjectNode contract, controller;
    SitReadinessReport readiness;
    byte[] canonical;
    final SharedSitDataContractValidator validator = new SharedSitDataContractValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static byte[] bytes(JsonNode node) throws Exception { return JSON.writeValueAsBytes(node); }
    static JsonNode ordered(JsonNode value) {
        if (value.isObject()) { ObjectNode result = JSON.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add); for (String key : keys) result.set(key, ordered(value.get(key))); return result; }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); value.forEach(v -> result.add(ordered(v))); return result; }
        return value.deepCopy();
    }
    static String digest(JsonNode value) throws Exception { return sha(bytes(ordered(value))); }
    static String git(Path root, String... args) throws Exception {
        var argv = new ArrayList<>(List.of("/usr/bin/git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", "-C", root.toString())); argv.addAll(List.of(args));
        var builder = new ProcessBuilder(argv); builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_OPTIONAL_LOCKS", "0", "GIT_TERMINAL_PROMPT", "0", "GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z", "GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z"));
        builder.redirectErrorStream(true); var process = builder.start(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); byte[] output = process.getInputStream().readNBytes(8193); assertThat(output.length).isLessThanOrEqualTo(8192); assertThat(process.exitValue()).as(new String(output)).isZero(); return new String(output).strip();
    }
    @BeforeAll void actualAdmittedPublicSource(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("main-product")); git(root, "init", "-q");
        Map<String,String> payloads = Map.of("suite", "suite: public-c17\n", "data", "{\"public\":true}\n", "schema", "{\"type\":\"object\"}\n", "config", "{\"public_config\":true}\n", "oracle", "{\"public\":true}\n", "cleanup", "{\"owned\":\"public-contract-only\"}\n");
        Map<String,String> paths = Map.of("suite", "tests/sit/suite.yaml", "data", "tests/sit/data/public.json", "schema", "tests/sit/config/schema.json", "config", "tests/sit/config/nonsecret.json", "oracle", "tests/sit/data/oracle.json", "cleanup", "tests/sit/config/cleanup.json");
        for (String name : paths.keySet()) { Path file = root.resolve(paths.get(name)); Files.createDirectories(file.getParent()); Files.writeString(file, payloads.get(name)); }
        git(root, "add", "."); git(root, "-c", "user.name=Public Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "public C17 immutable sources A"); String a = git(root, "rev-parse", "HEAD");
        ObjectNode lock = JSON.createObjectNode(); lock.put("schema_version", "runner-dependencies.lock.v1"); lock.put("lock_id", "c17-public"); lock.put("lock_version", "1");
        var flow = lock.putObject("main_flow"); flow.put("owner_ref", "public-owner"); flow.put("source_repo", REPO); flow.put("source_testrepo_version", a); flow.put("sit_root", "tests/sit");
        var repo = lock.putArray("repositories").addObject(); repo.put("source_repo", REPO); repo.put("source_testrepo_version", a); repo.put("commit_algorithm", "git-sha1"); repo.put("source_owner_ref", "public-owner");
        var list = lock.putArray("artifacts");
        for (String name : new TreeSet<>(paths.keySet())) {
            String role = switch(name) { case "suite" -> "suite"; case "data" -> "sanitized_data"; case "oracle" -> "oracle"; case "cleanup" -> "cleanup_definition"; default -> "config_template"; };
            var item = list.addObject(); item.put("artifact_id", REPO+"@"+a+":"+paths.get(name)+"#"+name); item.put("role", role); item.put("source_repo", REPO); item.put("source_commit", a); item.put("source_path", paths.get(name)); item.put("source_id", name); item.put("source_testrepo_version", a); item.put("sha256", sha(Files.readAllBytes(root.resolve(paths.get(name))))); item.put("bytes", Files.size(root.resolve(paths.get(name)))); item.put("source_owner_ref", "public-owner"); item.put("cleanup_owner_ref", "public-cleaner"); item.put("required", true); artifacts.put(name, item.deepCopy());
        }
        flow.put("suite_artifact_id", artifacts.get("suite").path("artifact_id").asText());
        var sut = lock.putArray("sut_expectations").addObject(); sut.put("sut_id", "sut-main"); sut.put("owner_ref", "public-owner"); sut.put("source_repo", REPO); sut.put("expected_deployed_revision", "sut-v1"); sut.put("readiness_binding_ref", "sut"); sut.put("cleanup_owner_ref", "public-cleaner"); lock.putArray("relations");
        Path lockPath = root.resolve("tests/sit/dependencies.lock"); Files.write(lockPath, bytes(lock)); git(root, "add", "."); git(root, "-c", "user.name=Public Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "public composition X lock"); String x = git(root, "rev-parse", "HEAD");
        var binding = JSON.createObjectNode(); binding.put("schema_version", "runner-trusted-roots.v1"); binding.put("binding_id", "c17-public"); binding.put("approved_lock_sha256", sha(bytes(lock))); var bound = binding.putArray("roots").addObject(); bound.put("source_repo", REPO); bound.put("expected_commit", x); bound.put("commit_algorithm", "git-sha1"); bound.put("absolute_checkout_root", root.toString()); bound.put("source_owner_ref", "public-owner"); bound.put("binding_evidence_sha256", "1".repeat(64)); Path roots = temp.resolve("roots.json"); Files.write(roots, bytes(binding));
        source = new RunnerBundleService().inspectBindings(lockPath, sha(bytes(lock)), roots, sha(bytes(binding)));
    }
    @BeforeEach void inputs() throws Exception {
        contract = JSON.createObjectNode(); contract.put("contract_version", "shared-sit-data.v1"); contract.put("contract_id", "c17-public"); contract.put("dataset_id", "public-data"); contract.put("data_version", "data-v1"); contract.put("schema_version", "schema-v1");
        var refs = contract.putObject("artifacts"); for (String name : List.of("data", "schema", "oracle", "cleanup", "config")) { var ref = refs.putObject(name); ref.put("artifact_id", artifacts.get(name).path("artifact_id").asText()); ref.put("sha256", artifacts.get(name).path("sha256").asText()); }
        var resource = contract.putArray("resources").addObject(); resource.put("resource_id", "public-record"); resource.put("run_namespace", "c17-public-run"); resource.put("owner_token_ref", "owner-ref://public-owner"); resource.put("effect", "OWNED_WRITE");
        var checks = new ArrayList<SitReadinessReport.Check>(); var pre = contract.putArray("preconditions");
        for (String id : List.of("data", "schema", "auth", "dependency", "sut")) {
            String kind = switch(id) { case "data", "schema" -> "test_data"; case "dependency" -> "dependency"; default -> "external_system"; };
            String version = id+"-v1", purpose = switch(id) { case "data" -> "DATA_VERSION"; case "schema" -> "SCHEMA_VERSION"; case "auth" -> "AUTH"; case "dependency" -> "DEPENDENCY"; default -> "SUT"; };
            var item = pre.addObject(); item.put("check_id", id); item.put("kind", kind); item.put("purpose", purpose); item.put("subject_id", id.equals("sut") ? "sut-main" : "public-data"); item.put("expected_version", version);
            checks.add(new SitReadinessReport.Check(id, kind, "public-contract-fixture", "1", false, SitReadinessState.READY, version, version, NOW.minusMillis(500), Map.of("public", "contract-only"), "", ""));
        }
        var sut = contract.putArray("sut_expectations").addObject(); sut.put("sut_id", "sut-main"); sut.put("expected_deployed_revision", "sut-v1"); readiness = new SitReadinessReport(SitReadinessState.READY, NOW.minusSeconds(1), NOW, checks);
        canonical = bytes(JSON.createObjectNode().put("dsl_version", "v0.3").put("result_contract_version", "v0.3").put("run_id", "RUN-c17-public").put("suite_id", "C17-PUBLIC").put("profile", "local_c17").put("batch_id", "C17-BATCH").put("status", "passed"));
        controller = JSON.createObjectNode(); controller.put("context_version", "shared-sit-data-controller.v1"); controller.put("scope", "ISOLATED_ONLY"); controller.put("run_id", "RUN-c17-public"); controller.put("run_namespace", "c17-public-run"); controller.put("source_binding_sha256", digest(source.metadata())); controller.put("approved_contract_sha256", sha(bytes(contract))); controller.put("readiness_sha256", digest(JSON.valueToTree(readiness.toMap()))); controller.put("valid_until", NOW.plusSeconds(60).toString()); controller.put("ownership", "LOCAL_FAKE"); controller.put("cleanup", "CONFIRMED"); controller.putArray("owner_token_refs").add("owner-ref://public-owner"); var actual = controller.putArray("actual_sut_revisions").addObject(); actual.put("sut_id", "sut-main"); actual.put("observed_revision", "sut-v1"); actual.put("observation_ref", "sut"); var ci = controller.putObject("canonical_identity"); ci.put("sha256", sha(canonical)); ci.put("run_id", "RUN-c17-public"); ci.put("suite_id", "C17-PUBLIC"); ci.put("profile", "local_c17"); ci.put("batch_id", "C17-BATCH");
    }
    SharedSitDataDecision assess() throws Exception { return assess(true); }
    SharedSitDataDecision assess(boolean approve) throws Exception {
        if (approve) { controller.put("approved_contract_sha256", sha(bytes(contract))); controller.put("readiness_sha256", digest(JSON.valueToTree(readiness.toMap()))); if (canonical == null) ((ObjectNode)controller.path("canonical_identity")).putNull("sha256"); else ((ObjectNode)controller.path("canonical_identity")).put("sha256", sha(canonical)); }
        return validator.assess(bytes(contract), sha(bytes(contract)), source, bytes(controller), sha(bytes(controller)), readiness, canonical);
    }
    void blocked(SharedSitDataDecision decision) { assertThat(decision.acceptance()).isNotEqualTo(Acceptance.CONTRACT_ONLY); assertThat(decision.persistentSharedOwnershipBound()).isFalse(); assertThat(decision.preCleanupSaveBound()).isFalse(); }
    @Test void actualAdmittedSourcesAndReadyIsolatedFactsGiveContractOnly() throws Exception { byte[] original=canonical.clone(); var d=assess(); assertThat(d.contractState()).isEqualTo(ContractState.VALID); assertThat(d.acceptance()).isEqualTo(Acceptance.CONTRACT_ONLY); assertThat(d.canonicalIdentityState()).isEqualTo(CanonicalIdentityState.MATCHED); assertThat(d.canonicalSha256()).isEqualTo(sha(original)); assertThat(canonical).isEqualTo(original); assertThat(d.contractOriginState()).isEqualTo(OriginState.UNBOUND); }
    @Test void actualAdmittedSourceObjectIsRequired() throws Exception { blocked(validator.assess(bytes(contract),sha(bytes(contract)),null,bytes(controller),sha(bytes(controller)),readiness,canonical)); }
    @ParameterizedTest @ValueSource(strings={"data","schema","oracle","cleanup","config"}) void qualifiedSourceHashMismatchCannotBorrowAdmission(String name)throws Exception { ((ObjectNode)contract.path("artifacts").path(name)).put("sha256","0".repeat(64)); assertThat(assess().contractState()).isEqualTo(ContractState.INVALID); }
    @Test void legalHashWithWrongSourceRoleCannotBorrowAdmission()throws Exception { ((ObjectNode)contract.path("artifacts")).set("data",contract.path("artifacts").path("schema").deepCopy()); blocked(assess()); }
    @Test void controllerCannotRelabelActualSourceIdentity()throws Exception { controller.put("source_binding_sha256","0".repeat(64)); blocked(assess()); }
    @Test void externalRawPinsAreMandatory()throws Exception { blocked(validator.assess(bytes(contract),"0".repeat(64),source,bytes(controller),sha(bytes(controller)),readiness,canonical)); blocked(validator.assess(bytes(contract),sha(bytes(contract)),source,bytes(controller),"0".repeat(64),readiness,canonical)); }
    @ParameterizedTest @ValueSource(strings={"sql","endpoint","program","password","idempotency_key"}) void executableCredentialOrSelfKeyFieldsRefused(String key)throws Exception { contract.put(key,"PUBLIC_UNTRUSTED_CANARY"); var d=assess(); blocked(d); assertThat(d.toString()).doesNotContain("PUBLIC_UNTRUSTED_CANARY"); }
    @Test void duplicateJsonFieldsRefusedEvenWhenRawPinMatches()throws Exception { byte[] raw=new String(bytes(contract)).replace("\"contract_id\":", "\"contract_id\":\"duplicate\",\"contract_id\":").getBytes(); controller.put("approved_contract_sha256",sha(raw)); blocked(validator.assess(raw,sha(raw),source,bytes(controller),sha(bytes(controller)),readiness,canonical)); }
    @ParameterizedTest @ValueSource(strings={"namespace","owner","effect","duplicate","wildcard"}) void resourceScopeAndOwnershipRefsAreClosed(String variant)throws Exception { var first=(ObjectNode)contract.path("resources").get(0); switch(variant){case "namespace" -> first.put("run_namespace","another-run"); case "owner" -> first.put("owner_token_ref","raw-token-value"); case "effect" -> first.put("effect","UNKNOWN"); case "duplicate" -> ((ArrayNode)contract.path("resources")).add(first.deepCopy()); default -> first.put("resource_id","record.*");} blocked(assess()); }
    @Test void contractAndCollectionBudgetsAreFinite()throws Exception { blocked(validator.assess(new byte[65537],"0".repeat(64),source,bytes(controller),sha(bytes(controller)),readiness,canonical)); var a=(ArrayNode)contract.path("resources"); for(int i=1;i<33;i++)a.add(a.get(0).deepCopy()); blocked(assess()); }
    @Test void versionMismatchEvenWithReadyLabelBlocks()throws Exception { var checks=new ArrayList<>(readiness.checks()); var c=checks.get(0); checks.set(0,new SitReadinessReport.Check(c.id(),c.kind(),c.adapter(),c.adapterVersion(),false,SitReadinessState.READY,c.expectedVersion(),"wrong-version",c.observedAt(),c.evidence(),"","")); readiness=new SitReadinessReport(SitReadinessState.READY,readiness.startedAt(),readiness.finishedAt(),checks); blocked(assess()); }
    @Test void missingAuthCheckCannotBeSkipped()throws Exception { var list=(ArrayNode)contract.path("preconditions"); list.remove(2); blocked(assess()); }
    @Test void actualSutRevisionCannotBeSourceRepoVersion()throws Exception { ((ObjectNode)controller.path("actual_sut_revisions").get(0)).put("observed_revision",source.metadata().path("lock").path("composition_root_version").asText()); blocked(assess()); }
    @Test void readinessHashAndFreshnessAreBound()throws Exception { controller.put("readiness_sha256","0".repeat(64)); blocked(assess(false)); controller.put("readiness_sha256",digest(JSON.valueToTree(readiness.toMap()))); readiness=new SitReadinessReport(SitReadinessState.READY,NOW.minusSeconds(1000),NOW.minusSeconds(999),readiness.checks()); blocked(assess()); }
    @Test void expiredControllerContextCannotAuthorizeAssessment()throws Exception { controller.put("valid_until",NOW.minusSeconds(1).toString()); blocked(assess()); }
    @Test void callerReadyCannotCreateSharedAuthorityOrCheckpoint()throws Exception { controller.put("scope","SHARED"); controller.put("ownership","READY"); var d=assess(); assertThat(d.scope()).isEqualTo(Scope.SHARED_BLOCKED); assertThat(d.acceptance()).isEqualTo(Acceptance.BLOCKED_NO_SHARED_OWNERSHIP_OR_PRECLEANUP_SAVE_BINDING); assertThat(d.ownershipState()).isEqualTo(OwnershipState.UNKNOWN); blocked(d); }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","NOT_RUN"}) void uncertainCleanupBlocksWithoutChangingCanonicalPass(String cleanup)throws Exception { controller.put("cleanup",cleanup); byte[] before=canonical.clone(); var d=assess(); blocked(d); assertThat(d.canonicalVerdict()).isEqualTo("passed"); assertThat(d.canonicalSha256()).isEqualTo(sha(before)); assertThat(canonical).isEqualTo(before); }
    @Test void unknownOwnerBlocksWithoutSerializingTokenReferences()throws Exception { controller.put("ownership","UNKNOWN"); var d=assess(); blocked(d); assertThat(d.toString()).doesNotContain("owner-ref://"); }
    @Test void canonicalFailureRemainsFailureWithLifecycleBlocker()throws Exception { var c=(ObjectNode)JSON.readTree(canonical); c.put("status","failed"); canonical=bytes(c); controller.put("cleanup","UNKNOWN"); byte[] before=canonical.clone(); var d=assess(); blocked(d); assertThat(d.canonicalVerdict()).isEqualTo("failed"); assertThat(canonical).isEqualTo(before); assertThat(d.canonicalSha256()).isEqualTo(sha(before)); }
    @Test void missingCanonicalNeverFabricatesPass()throws Exception { canonical=null; var d=assess(); blocked(d); assertThat(d.canonicalIdentityState()).isEqualTo(CanonicalIdentityState.MISSING); assertThat(d.canonicalVerdict()).isEqualTo("UNKNOWN"); }
    @Test void mismatchedCanonicalHashCannotCreditPass()throws Exception { ((ObjectNode)controller.path("canonical_identity")).put("sha256","0".repeat(64)); var d=assess(false); blocked(d); assertThat(d.canonicalVerdict()).isEqualTo("UNKNOWN"); }
    @Test void repeatedAssessmentIsStableAndChangedIdentityNeedsNewApproval()throws Exception { var one=assess(); assertThat(assess()).isEqualTo(one); contract.put("data_version","changed-v2"); blocked(assess(false)); }
    @Test void idempotencyIgnoresDeclarativeArrayOrder()throws Exception { var first=assess(); var a=(ArrayNode)contract.path("preconditions"); var list=new ArrayList<JsonNode>(); a.forEach(list::add); Collections.reverse(list); a.removeAll();list.forEach(a::add); assertThat(assess().idempotencyKey()).isEqualTo(first.idempotencyKey()); }
    @Test void semanticKeyMatchesIndependentGoldenVector()throws Exception { var c=JSON.createObjectNode().put("contract_version","shared-sit-data.v1");var s=JSON.createObjectNode().put("lock_sha256","0".repeat(64)).put("composition_root_version","1".repeat(40));s.putArray("artifacts");var run=JSON.createObjectNode().put("run_id","r").put("run_namespace","n").put("scope","CONTRACT");assertThat(SharedSitDataContractValidator.semanticKey(c,s,run)).isEqualTo("52691196831130916461027f1d58f624d31721513408d9b57fab0e3b628213d3"); }
    @Test void nonUtf8MetadataCannotBeAdmitted() throws Exception {
        byte[] body = JSON.writeValueAsString(contract).getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] raw = new byte[body.length + 2]; raw[0] = (byte)0xff; raw[1] = (byte)0xfe; System.arraycopy(body, 0, raw, 2, body.length);
        controller.put("approved_contract_sha256", sha(raw));
        blocked(validator.assess(raw, sha(raw), source, bytes(controller), sha(bytes(controller)), readiness, canonical));
    }
    @Test void futureReadinessCannotCreditReady() throws Exception {
        var checks = new ArrayList<SitReadinessReport.Check>();
        for (var c : readiness.checks()) checks.add(new SitReadinessReport.Check(c.id(), c.kind(), c.adapter(), c.adapterVersion(), c.simulated(), c.state(), c.expectedVersion(), c.observedVersion(), NOW.plusMillis(200), c.evidence(), c.code(), c.ownerAction()));
        readiness = new SitReadinessReport(SitReadinessState.READY, NOW.plusMillis(100), NOW.plusMillis(300), checks);
        blocked(assess());
    }
}
