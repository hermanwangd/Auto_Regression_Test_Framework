package com.specdriven.regression.producttest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Offline integrity/closure checking. Swarm remains the source of knowledge governance. */
public final class ProductTestHandoffService {
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    private static final int MAX_FILES = 128;
    private static final String CONTRACT = "sample_fake_provider.v0.3";
    private static final String COGNEE_COMMIT = "ba3631f2ed363a6ea50d649c34c56885af6b36fe";
    private static final Set<String> HANDOFF_FIELDS = Set.of("contract_version", "suite_id", "profile", "knowledge_mode",
            "review_ref", "reviewed_by", "reviewed_at", "valid_until", "framework_baseline", "repository_revisions", "workspace_ref", "project_ref",
            "swarm_source", "cognee_source", "reviewed_files", "reviewed_contracts", "requirement_mapping",
            "selection_receipt", "knowledge_sources");
    private static final Set<String> V2_FIELDS;
    static { var fields=new java.util.LinkedHashSet<>(HANDOFF_FIELDS);fields.remove("selection_receipt");fields.remove("knowledge_sources");fields.addAll(Set.of("knowledge_selection_proof","preparation_provenance","external_artifact_bindings","proposal_trace","expected_sut_revisions","sut_revision_evidence"));V2_FIELDS=Set.copyOf(fields); }
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Clock clock;

    public ProductTestHandoffService() { this(Clock.systemUTC()); }
    public ProductTestHandoffService(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    /** Presence survives a malformed value, so the caller cannot silently downgrade its route. */
    public record InvocationRequirement(String expectedSha256, String fixtureLeaseSha256,String sutBindingSha256) {
        public InvocationRequirement(String expectedSha256,String fixtureLeaseSha256){this(expectedSha256,fixtureLeaseSha256,null);}
        public InvocationRequirement(String expectedSha256) {this(expectedSha256,null);}
        public static InvocationRequirement fromEnvironment(Map<String,String> environment) {
            return new InvocationRequirement(environment.get("REGRESS_REVIEWED_HANDOFF_SHA256"), environment.get("REGRESS_REVIEWED_FIXTURE_LEASE_SHA256"),environment.get("REGRESS_REVIEWED_SUT_BINDING_SHA256"));
        }
        public boolean required() { return expectedSha256 != null || fixtureLeaseSha256 != null || sutBindingSha256 != null; }
    }

    /** Reads no executable/provider input and treats a present null policy as configured. */
    public static boolean configured(Path manifest, String requestedProfile) {
        // Discovery is read-only and must preserve ordinary suites reached through OS aliases.
        // The original caller path still goes through strict Inputs in snapshot(), never this canonical path.
        try {
            Path canonicalManifest = manifest.toAbsolutePath().toRealPath();
            Map<String,Object> suite = yaml(readStandalone(canonicalManifest), "suite_manifest");
            if (!suite.containsKey("env_profiles")) return false;
            String profile = requestedProfile == null || requestedProfile.isBlank()
                    ? text(suite.get("default_profile"), "default_profile") : text(requestedProfile, "profile");
            Map<String,Object> profiles = object(suite.get("env_profiles"), "env_profiles");
            if (!profiles.containsKey(profile)) return false;
            String ref = relativePath(text(object(profiles.get(profile), "env_profile_ref").get("ref"), "env_profile_ref"));
            Path canonicalProfile = canonicalManifest.getParent().resolve(ref).toRealPath();
            return yaml(readStandalone(canonicalProfile), "env_profile").containsKey("product_test");
        } catch (IOException e) { throw fail("input_unavailable", "cannot inspect suite product-test policy", e); }
    }

    public Snapshot snapshot(Path manifest, String profile, InvocationRequirement requirement) {
        if (requirement == null || !requirement.required()) throw fail("handoff_digest_missing", "independent invocation pin is required");
        String expected = sha(requirement.expectedSha256(), "handoff_digest_invalid");
        Inputs inputs = new Inputs(manifest);
        Selection selection = select(inputs, profile);
        byte[] handoffBytes = inputs.read(selection.policy.reviewedHandoffRef());
        if (!expected.equals(digest(handoffBytes))) throw fail("handoff_digest_mismatch", "reviewed handoff bytes changed");
        Map<String,Object> document=json(handoffBytes,"handoff");
        if(!"product-test-reviewed-handoff.v2".equals(document.get("contract_version"))&&requirement.sutBindingSha256()!=null)throw fail("sut_binding_route","SUT binding pin requires reviewed v2");
        Snapshot snapshot=validateAndSnapshot(inputs, selection, document, expected, clock.instant(),requirement.sutBindingSha256());
        try {
            if(selection.policy.nativeExecution() && nativeCapability(selection)==ReviewedNativeCapability.GRPC) {
                String leasePin=sha(requirement.fixtureLeaseSha256(),"fixture_lease_digest_missing_or_invalid");
                var fixture=com.specdriven.regression.provider.grpc.ReviewedLoopbackGrpcPolicy.fixture(snapshot.root,grpcBindings(selection),true);
                equal(leasePin,fixture.leaseSha256(),"fixture_lease_digest_mismatch");
            }
            return snapshot;
        } catch(RuntimeException error) {snapshot.close();throw error;}
    }

    /** Packages supplied review facts; its result is explicitly prepared, never approved. */
    public Map<String,Object> prepare(Path manifest, String profile, Path reviewInput, Path selectionReceipt, Path out) {
        Inputs inputs = new Inputs(manifest);
        Selection selection = select(inputs, profile);
        String outRef = inputs.relative(out);
        equal(selection.policy.reviewedHandoffRef(), outRef, "handoff_output_path");
        Map<String,Object> handoff = new LinkedHashMap<>(json(readStandalone(reviewInput), "review_input"));
        String proposedVersion=handoff.containsKey("contract_version")?text(handoff.get("contract_version"),"contract_version"):"product-test-reviewed-handoff.v1";
        only(handoff,"product-test-reviewed-handoff.v2".equals(proposedVersion)?V2_FIELDS:HANDOFF_FIELDS,"review_input");
        suppliedOrPut(handoff, "contract_version", proposedVersion);
        suppliedOrPut(handoff, "suite_id", text(selection.suite.get("suite_id"), "suite_id"));
        suppliedOrPut(handoff, "profile", selection.profile);
        suppliedOrPut(handoff, "knowledge_mode", selection.policy.knowledgeMode());
        int preparedFiles = 0; long preparedBytes = 0;
        for (String field : List.of("reviewed_files", "reviewed_contracts")) {
            List<Object> hashed = new ArrayList<>();
            for (Object raw : list(handoff.get(field), field, true)) {
                Map<String,Object> item = new LinkedHashMap<>(object(raw, field));
                checkBudget(++preparedFiles, preparedBytes);
                byte[] input = inputs.read(text(item.get("path"), field + ".path"));
                preparedBytes += input.length; checkBudget(preparedFiles, preparedBytes);
                item.put("sha256", digest(input));
                hashed.add(item);
            }
            handoff.put(field, hashed);
        }
        boolean proposedV2="product-test-reviewed-handoff.v2".equals(proposedVersion);
        if(proposedV2){
            if(selectionReceipt!=null)throw fail("private_receipt_forbidden","v2 accepts metadata proof only");
            object(handoff.get("knowledge_selection_proof"),"knowledge_selection_proof");
        } else if ("required".equals(selection.policy.knowledgeMode())) {
            if (selectionReceipt == null) throw fail("selection_receipt_missing", "required knowledge needs the supplied receipt");
            String ref = inputs.relative(selectionReceipt);
            handoff.put("selection_receipt", Map.of("path", ref, "sha256", digest(inputs.read(ref))));
        } else if (selectionReceipt != null || handoff.containsKey("selection_receipt") || handoff.containsKey("knowledge_sources")) {
            throw fail("not_used_knowledge_fields", "not_used must not carry a receipt or knowledge sources");
        }
        byte[] bytes;
        try { bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(handoff); }
        catch (IOException e) { throw fail("invalid_json", "review input cannot be serialized"); }
        if (bytes.length > MAX_FILE_BYTES) throw fail("file_size_limit", "prepared handoff exceeds 2 MiB");
        String pin = digest(bytes);
        // Reuse the same cached file bytes and closure checks; no source copy or provider call occurs.
        try (Snapshot ignored = validateAndSnapshot(inputs, selection, handoff, pin, clock.instant(),proposedV2?text(object(handoff.get("sut_revision_evidence"),"sut_revision_evidence").get("sha256"),"sut_revision_evidence.sha256"):null)) {
            Path output = inputs.root.resolve(outRef);
            safeOutputParent(inputs.root, output.getParent());
            writeNewOrRegular(output, bytes);
            Path digestFile = output.resolveSibling(output.getFileName() + ".sha256");
            writeNewOrRegular(digestFile, (pin + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Map.of("status", "prepared_not_approved", "handoff_path", output.toString(),
                    "sha256_path", digestFile.toString(), "sha256", pin);
        }
    }

    private Snapshot validateAndSnapshot(Inputs inputs, Selection s, Map<String,Object> h, String pin, Instant now,String sutBindingSha256) {
        String version=text(h.get("contract_version"),"contract_version");boolean v2="product-test-reviewed-handoff.v2".equals(version);
        if(!v2)equal("product-test-reviewed-handoff.v1",version,"contract_version");
        only(h,v2?V2_FIELDS:HANDOFF_FIELDS,"handoff");
        if(v2&&(!s.policy.nativeExecution()||!"required".equals(s.policy.knowledgeMode())))throw fail("v2_capability","v2 requires native local required knowledge");
        if(!v2&&s.policy.nativeExecution()&&"required".equals(s.policy.knowledgeMode()))throw fail("native_required_v2_missing","native required knowledge needs v2");
        equal(s.policy.knowledgeMode(), text(h.get("knowledge_mode"), "knowledge_mode"), "knowledge_mode");
        String review = text(h.get("review_ref"), "review_ref"); text(h.get("reviewed_by"), "reviewed_by");
        String workspace = text(h.get("workspace_ref"), "workspace_ref");
        String project = text(h.get("project_ref"), "project_ref");
        Instant reviewedAt = instant(h.get("reviewed_at"), "reviewed_at");
        Instant validUntil = instant(h.get("valid_until"), "valid_until");
        if (reviewedAt.isAfter(now)) throw fail("reviewed_at_future", "review time is after invocation time");
        if (!validUntil.isAfter(now) || !validUntil.isAfter(reviewedAt)) throw fail("expired_review", "review validity must extend beyond invocation time");
        equal("0.3.2", text(h.get("framework_baseline"), "framework_baseline"), "framework_baseline");
        Map<String,String> repositories = stringMap(h.get("repository_revisions"), "repository_revisions", false);
        sourcePins(h);

        Map<String,FileEntry> approved = new LinkedHashMap<>();
        Set<String> allPaths = new LinkedHashSet<>();
        long total = 0;
        for (Object raw : list(h.get("reviewed_files"), "reviewed_files", false)) {
            Map<String,Object> f = object(raw, "reviewed_file"); only(f, Set.of("path", "role", "sha256"), "reviewed_file");
            String path = relativePath(text(f.get("path"), "reviewed_file.path"));
            String role = text(f.get("role"), "reviewed_file.role");
            if (!Set.of("manifest", "profile", "test", "fixture", "oracle").contains(role)) throw fail("reviewed_file_role", "unsupported role");
            if (!allPaths.add(path)) throw fail("duplicate_reviewed_file", "reviewed paths must be unique");
            checkBudget(allPaths.size(), total);
            if (path.equals(s.policy.reviewedHandoffRef())) throw fail("reviewed_file_self_reference", "handoff is external provenance");
            byte[] bytes = inputs.read(path);
            equal(sha(f.get("sha256"), "file_sha256"), digest(bytes), "file_digest_mismatch");
            approved.put(path, new FileEntry(path, role, bytes)); total += bytes.length;
            checkBudget(allPaths.size(), total);
        }
        Map<String,FileEntry> contracts = new LinkedHashMap<>();
        Set<String> filenames = new LinkedHashSet<>();
        for (Object raw : list(h.get("reviewed_contracts"), "reviewed_contracts", false)) {
            Map<String,Object> f = object(raw, "reviewed_contract");
            only(f, Set.of("provider_contract", "path", "sha256"), "reviewed_contract");
            String id = text(f.get("provider_contract"), "provider_contract");
            String path = relativePath(text(f.get("path"), "reviewed_contract.path"));
            if (!allPaths.add(path) || contracts.containsKey(id) || !filenames.add(Path.of(path).getFileName().toString())) {
                throw fail("duplicate_reviewed_contract", "contract identity, path and filename must be unique");
            }
            checkBudget(allPaths.size(), total);
            if (!path.endsWith(".yaml")) throw fail("reviewed_contract_path", "explicit catalog requires .yaml files");
            byte[] bytes = inputs.read(path);
            equal(sha(f.get("sha256"), "contract_sha256"), digest(bytes), "file_digest_mismatch");
            Map<String,Object> contract = yaml(bytes, "reviewed_contract");
            equal(id, text(contract.get("provider_contract"), "provider_contract"), "reviewed_contract_identity");
            equal("v0.3", text(contract.get("contract_version"), "contract_version"), "reviewed_contract_version");
            equal(s.policy.nativeExecution() ? nativeCapability(s).contract() : CONTRACT, id, "reviewed_contract_identity");
            equal(s.policy.nativeExecution() ? nativeCapability(s).providerType() : "sample_fake_provider", text(contract.get("provider_type"), "provider_type"), "reviewed_contract_provider");
            contracts.put(id, new FileEntry(path, "contract", bytes)); total += bytes.length;
            checkBudget(allPaths.size(), total);
        }
        checkBudget(allPaths.size(), total);
        equal(text(s.suite.get("suite_id"), "suite_id"), text(h.get("suite_id"), "suite_id"), "suite_id_mismatch");
        equal(s.profile, text(h.get("profile"), "profile"), "profile_mismatch");
        Closure closure = validateClosure(inputs, s, approved, contracts.keySet());
        validateMappings(h, closure, review);
        Map<String,Object> provenance = new LinkedHashMap<>();
        provenance.put("contract_version", version);
        provenance.put("handoff_sha256", pin); provenance.put("suite_id", h.get("suite_id"));
        provenance.put("profile", s.profile); provenance.put("knowledge_mode", s.policy.knowledgeMode());
        provenance.put("test_level", s.policy.testLevel()); provenance.put("test_type", s.policy.testType());
        provenance.put("environment_stage", s.policy.environmentStage());
        provenance.put("workspace_ref", workspace); provenance.put("project_ref", project);
        provenance.put("review_ref", review); provenance.put("reviewed_by", h.get("reviewed_by"));
        provenance.put("reviewed_at", reviewedAt.toString()); provenance.put("valid_until", validUntil.toString());
        provenance.put("framework_baseline", h.get("framework_baseline")); provenance.put("repository_revisions", repositories);
        provenance.put("swarm_source", freeze(h.get("swarm_source")));
        if (h.containsKey("cognee_source")) provenance.put("cognee_source", freeze(h.get("cognee_source")));
        provenance.put("requirement_mapping", freeze(h.get("requirement_mapping")));
        provenance.put("reviewed_files", freeze(h.get("reviewed_files")));
        provenance.put("reviewed_contracts", freeze(h.get("reviewed_contracts")));
        provenance.put("evidence_classification", "framework_verification_only"); provenance.put("downstream_release_evidence", false);
        if(v2){
            var reviewed=new LinkedHashMap<String,KnowledgeSelectionProof.ReviewedInput>();approved.forEach((path,file)->reviewed.put(path,new KnowledgeSelectionProof.ReviewedInput(file.role,digest(file.bytes))));contracts.forEach((id,file)->reviewed.put(file.path,new KnowledgeSelectionProof.ReviewedInput("contract",digest(file.bytes))));
            provenance.putAll(KnowledgeSelectionProof.validate(h,repositories,closure.tests,reviewed,inputs::read,now,sutBindingSha256));
            checkProvenanceBudget(provenance);
        } else if ("required".equals(s.policy.knowledgeMode())) validateKnowledge(inputs, h, repositories, closure, now, allPaths, provenance);
        else if (h.containsKey("selection_receipt") || h.containsKey("knowledge_sources")) {
            throw fail("not_used_knowledge_fields", "not_used forbids knowledge receipt and mappings");
        }
        Snapshot snapshot=materialize(inputs.manifestRef, s.policy, approved, contracts, provenance);
        if(s.policy.nativeExecution() && nativeCapability(s)==ReviewedNativeCapability.GRPC) {
            try {
                var fixture=com.specdriven.regression.provider.grpc.ReviewedLoopbackGrpcPolicy.fixture(snapshot.root,grpcBindings(s),false);
                provenance.put("fixture_lease",fixture.lease()); provenance.put("fixture_lease_sha256",fixture.leaseSha256());
                return new Snapshot(snapshot.root,snapshot.suiteManifest,snapshot.contractDirectory,snapshot.policy,immutableObject(provenance));
            } catch(RuntimeException error) {snapshot.close();throw error;}
        }
        return snapshot;
    }

    /** Bound the actual encoded projection before any runner snapshot or TEST dispatch. */
    static void checkProvenanceBudget(Map<String,Object> provenance){
        // Leave room for runtime identities and the finite gRPC lease added after admission.
        final int limit=MAX_FILE_BYTES-65536;
        try{JSON.writerWithDefaultPrettyPrinter().writeValue(new java.io.OutputStream(){
            private int written;
            @Override public void write(int value)throws IOException{if(written>=limit)throw new IOException("provenance_output_limit");written++;}
            @Override public void write(byte[]bytes,int offset,int length)throws IOException{java.util.Objects.checkFromIndexSize(offset,length,bytes.length);if(length>limit-written)throw new IOException("provenance_output_limit");written+=length;}
        },provenance);}catch(IOException invalid){throw fail("provenance_output_limit","reviewed v2 projection exceeds runtime metadata budget");}
    }

    private static Selection select(Inputs inputs, String requested) {
        Map<String,Object> suite = yaml(inputs.read(inputs.manifestRef), "suite_manifest");
        equal("v0.3", text(suite.get("manifest_version"), "manifest_version"), "unsupported_manifest");
        if (suite.containsKey("provider_contract_resolution")) throw fail("provider_contract_resolution", "reviewed mode uses only explicit pinned contracts");
        only(suite, Set.of("manifest_version", "suite_id", "default_profile", "artifact_roots", "targets", "env_profiles", "tests"), "suite_manifest");
        String profile = requested == null || requested.isBlank() ? text(suite.get("default_profile"), "default_profile") : text(requested, "profile");
        Map<String,Object> profiles = object(suite.get("env_profiles"), "env_profiles");
        Map<String,Object> profileRef = object(profiles.get(profile), "selected_profile");
        only(profileRef, Set.of("ref"), "selected_profile");
        String ref = relativePath(text(profileRef.get("ref"), "selected_profile.ref"));
        Map<String,Object> env = yaml(inputs.read(ref), "env_profile");
        ProductTestPolicy policy = ProductTestPolicy.parse(env.get("product_test"));
        relativePath(policy.reviewedHandoffRef());
        return new Selection(profile, ref, suite, env, policy);
    }

    private static Closure validateClosure(Inputs inputs, Selection s, Map<String,FileEntry> files, Set<String> contractIds) {
        Map<String,String> needed = new LinkedHashMap<>();
        need(needed, inputs.manifestRef, "manifest"); need(needed, s.profileRef, "profile");
        Map<String,Object> targets = object(s.suite.get("targets"), "targets");
        if (targets.isEmpty() || targets.size() > 32) throw fail("targets", "requires 1..32 targets");
        Set<String> usedContracts = new LinkedHashSet<>();
        targets.forEach((id, raw) -> {
            Map<String,Object> t = object(raw, "target"); only(t, Set.of("provider_contract"), "target");
            String expected = s.policy.nativeExecution() ? nativeCapability(s).contract() : CONTRACT;
            equal(expected, text(t.get("provider_contract"), "provider_contract"), "unsupported_provider");
            usedContracts.add(expected);
        });
        if (!usedContracts.equals(contractIds)) throw fail("reviewed_contract_coverage", "exact used contracts are required");
        if (!targets.keySet().equals(s.policy.providerModes().keySet())) throw fail("provider_mode_coverage", "each target needs exactly one mode");
        for (String mode : s.policy.providerModes().values()) equal(s.policy.nativeExecution() ? "real" : "simulated", mode, "provider_mode_mismatch");
        validateProfile(s, targets.keySet());
        Map<String,Object> roots = object(s.suite.get("artifact_roots"), "artifact_roots");
        roots.forEach((key, value) -> relativePath(text(value, "artifact_root")));
        Set<String> tests = new LinkedHashSet<>();
        Map<String,Set<String>> oracleTests = new LinkedHashMap<>();
        Set<String> testPaths = new LinkedHashSet<>();
        int nativeRequests = 0;
        for (Object raw : list(s.suite.get("tests"), "tests", false)) {
            Map<String,Object> ref = object(raw, "test_ref"); only(ref, Set.of("ref"), "test_ref");
            String path = relativePath(text(ref.get("ref"), "test_ref"));
            if (!testPaths.add(path)) throw fail("duplicate_test_reference", "test references must be unique");
            need(needed, path, "test");
            FileEntry entry = files.get(path);
            if (entry == null) throw fail("reviewed_file_coverage", "test definition is not reviewed");
            Map<String,Object> test = yaml(entry.bytes, "test");
            only(test, Set.of("dsl_version", "test_case_id", "title", "source_refs", "setup", "execute", "verify", "cleanup"), "test");
            equal("v0.3", text(test.get("dsl_version"), "dsl_version"), "dsl_version");
            String id = text(test.get("test_case_id"), "test_case_id");
            if (!tests.add(id)) throw fail("duplicate_test_case_id", "test identities must be unique");
            Set<String> stepIds = new LinkedHashSet<>(); Map<String,String> stepOps = new LinkedHashMap<>();
            for (String phase : List.of("setup", "execute", "verify", "cleanup")) {
                List<Object> steps = test.containsKey(phase) ? list(test.get(phase), phase, true) : List.of();
                if (("execute".equals(phase) || "verify".equals(phase)) && steps.isEmpty()) throw fail("empty_test_phase", "execute and verify must be nonempty");
                for (Object stepRaw : steps) {
                    Map<String,Object> step = object(stepRaw, "step");
                    String sid = text(step.get("id"), "step.id");
                    if (!stepIds.add(sid)) throw fail("duplicate_step_id", "steps must have unique IDs");
                    if ("verify".equals(phase)) {
                        only(step, Set.of("id", "type", "assert"), "verify_step");
                        equal("assertion", text(step.get("type"), "verify.type"), "unsupported_verify");
                        Map<String,Object> assertion = object(step.get("assert"), "assert");
                        only(assertion, s.policy.nativeExecution() ? Set.of("actual", "operator", "expected", "expected_ref")
                                : Set.of("actual", "operator", "expected"), "assert");
                        String operator = text(assertion.get("operator"), "assert.operator");
                        if (!Set.of("equals", "not_equals", "gt", "gte", "lt", "lte", "exists", "not_exists", "json_match").contains(operator)) {
                            throw fail("unsupported_assertion", "only bounded value assertions are supported");
                        }
                        actualRef(assertion.get("actual"), stepOps);
                        if (assertion.containsKey("expected_ref") && assertion.containsKey("expected"))
                            throw fail("ambiguous_oracle", "use one expected operand");
                        if (s.policy.nativeExecution() && "json_match".equals(operator) && !assertion.containsKey("expected_ref"))
                            throw fail("missing_oracle", "native JSON matching requires a pinned artifact expected_ref");
                        if (assertion.containsKey("expected") || assertion.containsKey("expected_ref")) {
                            Object expected = assertion.get(assertion.containsKey("expected_ref") ? "expected_ref" : "expected");
                            if (assertion.containsKey("expected_ref") && (!(expected instanceof String value) || !value.startsWith("artifact://")))
                                throw fail("unsupported_reference", "expected_ref must be a static artifact");
                            if (expected instanceof String str && str.startsWith("artifact://")) {
                                String oracle = artifact(str, roots); need(needed, oracle, "oracle"); addOracle(oracleTests, oracle, id);
                            } else {
                                literal(expected); addOracle(oracleTests, path + "#" + sid, id);
                            }
                        } else if (!Set.of("exists", "not_exists").contains(operator)) throw fail("missing_oracle", "assertion expected operand is required");
                        else addOracle(oracleTests, path + "#" + sid, id);
                    } else {
                        only(step, Set.of("id", "target", "op", "with"), "provider_step");
                        String target = text(step.get("target"), "step.target");
                        if (!targets.containsKey(target)) throw fail("unknown_target", "step target is not declared");
                        String op = text(step.get("op"), "step.op");
                        if (s.policy.nativeExecution()) {
                            if (!"execute".equals(phase) || !nativeCapability(s).operation().equals(op) || ++nativeRequests > 1)
                                throw fail("unsupported_operation", "native reviewed execution permits one selected operation across the entire suite");
                            nativeCapability(s).validateInputs(object(step.get("with"), "step.with"));
                            if (nativeCapability(s) == ReviewedNativeCapability.NATS) {
                                String fixture = artifact(text(object(step.get("with"), "step.with").get("payload_ref"), "payload_ref"), roots);
                                need(needed, fixture, "fixture");
                            }
                            if (nativeCapability(s) == ReviewedNativeCapability.GRPC) {
                                var input=object(step.get("with"),"step.with");
                                String descriptor=artifact(text(input.get("grpc.descriptor_ref"),"grpc.descriptor_ref"),roots);
                                String request=artifact(text(input.get("grpc.message"),"grpc.message"),roots);
                                equal(com.specdriven.regression.provider.grpc.ReviewedLoopbackGrpcPolicy.profile(input).descriptorPath(),descriptor,"grpc_descriptor_path");
                                equal("fixtures/request.json",request,"grpc_request_path");
                                need(needed,descriptor,"fixture"); need(needed,request,"fixture"); need(needed,"fixtures/lease.json","fixture");
                            }
                            stepOps.put(sid, op);
                            continue;
                        }
                        String expectedOp = switch (phase) { case "setup" -> "setup_fixture"; case "execute" -> "execute_sample"; default -> "cleanup_fixture"; };
                        equal(expectedOp, op, "unsupported_operation");
                        Map<String,Object> with = object(step.get("with"), "step.with");
                        Set<String> allowed = switch (op) {
                            case "setup_fixture" -> Set.of("fixture.setup_ref", "fixture.input_ref");
                            case "execute_sample" -> Set.of("sample.input_ref", "sample.expected_ref");
                            default -> Set.of("fixture.cleanup_ref", "fixture.workspace_ref");
                        };
                        only(with, allowed, "step.with");
                        Set<String> required = switch (op) {
                            case "setup_fixture" -> allowed;
                            case "execute_sample" -> Set.of("sample.input_ref");
                            default -> allowed;
                        };
                        if (!with.keySet().containsAll(required)) throw fail("missing_static_input", "known operation is missing a required static input");
                        for (var input : with.entrySet()) {
                            if ("fixture.workspace_ref".equals(input.getKey())) cleanupRef(input.getValue(), stepOps);
                            else {
                                String artifact = artifact(text(input.getValue(), "operation_input"), roots);
                                String role = "sample.expected_ref".equals(input.getKey()) ? "oracle" : "fixture";
                                need(needed, artifact, role);
                                if ("oracle".equals(role)) addOracle(oracleTests, artifact, id);
                            }
                        }
                        stepOps.put(sid, op);
                    }
                }
            }
        }
        if (s.policy.nativeExecution() && nativeRequests != 1) throw fail("unsupported_operation", "exactly one native operation is required");
        if (!needed.keySet().equals(files.keySet())) throw fail("reviewed_file_coverage", "reviewed files must exactly cover the complete supported input graph");
        for (var entry : needed.entrySet()) {
            FileEntry file = files.get(entry.getKey());
            equal(entry.getValue(), file.role, "reviewed_file_role_mismatch");
            if (Set.of("fixture", "oracle").contains(file.role)) {
                Object value = yamlValue(file.bytes, "static_input");
                staticValue(value);
            }
        }
        return new Closure(tests, oracleTests);
    }

    private static Map<String,Object> grpcBindings(Selection s) {
        var targets=object(s.env.get("targets"),"profile.targets");
        return object(object(targets.values().iterator().next(),"profile.target").get("bindings"),"bindings");
    }

    private static ReviewedNativeCapability nativeCapability(Selection s) {
        Map<String,Object> targets = object(s.suite.get("targets"), "targets");
        if (targets.size() != 1) throw fail("targets", "native execution requires one target");
        var target = object(targets.values().iterator().next(), "target");
        return ReviewedNativeCapability.fromContract(text(target.get("provider_contract"), "provider_contract"));
    }

    private static void validateProfile(Selection s, Set<String> targets) {
        Map<String,Object> env = s.env;
        only(env, Set.of("profile_id", "execution_mode", "isolation", "evidence_classification", "downstream_release_evidence", "targets", "product_test", "sit_readiness"), "env_profile");
        equal(s.profile, text(env.get("profile_id"), "profile_id"), "profile_id");
        equal("local", text(env.get("execution_mode"), "execution_mode"), "execution_mode");
        if (env.containsKey("evidence_classification")) equal("framework_verification_only", text(env.get("evidence_classification"), "evidence_classification"), "evidence_classification");
        if (env.containsKey("downstream_release_evidence") && !Boolean.FALSE.equals(env.get("downstream_release_evidence"))) throw fail("release_evidence_prohibited", "fake product tests cannot qualify release evidence");
        Map<String,Object> bindings = object(env.get("targets"), "profile.targets");
        if (!bindings.keySet().equals(targets)) throw fail("target_coverage", "profile and suite targets must match");
        for (Object raw : bindings.values()) {
            Map<String,Object> target = object(raw, "profile.target"); only(target, Set.of("runtime_mode", "bindings"), "profile.target");
            if (s.policy.nativeExecution()) {
                equal("native", text(target.get("runtime_mode"), "runtime_mode"), "runtime_mode");
                Map<String,Object> values = object(target.get("bindings"), "bindings");
                nativeCapability(s).validateBindings(values);
                continue;
            }
            equal("stub", text(target.get("runtime_mode"), "runtime_mode"), "runtime_mode");
            Map<String,Object> values = object(target.get("bindings"), "bindings"); only(values, Set.of("workspace_ref", "clock_ref"), "bindings");
            String workspace = text(values.get("workspace_ref"), "workspace_ref");
            String clock = text(values.get("clock_ref"), "clock_ref");
            if (!workspace.matches("local://[A-Za-z0-9._-]{1,128}") || !clock.startsWith("fixed://")) throw fail("unsupported_reference", "fake bindings require a local workspace and fixed clock");
            instant(clock.substring("fixed://".length()), "clock_ref");
        }
        Set<String> checks = new LinkedHashSet<>();
        if (s.policy.nativeExecution() && env.containsKey("sit_readiness")) throw fail("unsupported_readiness", "native local execution does not use SIT readiness");
        if (env.containsKey("sit_readiness")) {
            Map<String,Object> readiness = object(env.get("sit_readiness"), "sit_readiness");
            for (Object raw : list(readiness.get("checks"), "readiness.checks", false)) {
                String id = text(object(raw, "readiness.check").get("id"), "readiness.check.id");
                if (!checks.add(id)) throw fail("duplicate_dependency", "readiness IDs must be unique");
            }
        } else if (s.policy.sharedState() != null) throw fail("shared_state_readiness_required", "shared state requires readiness checks");
        if (!checks.equals(s.policy.dependencyModes().keySet())) throw fail("dependency_mode_coverage", "each readiness check needs exactly one dependency mode");
    }

    private static void validateMappings(Map<String,Object> h, Closure closure, String review) {
        Set<String> requirements = new LinkedHashSet<>(); Set<String> mappedTests = new LinkedHashSet<>();
        Map<String,Set<String>> mappedOracles = new LinkedHashMap<>();
        for (Object raw : list(h.get("requirement_mapping"), "requirement_mapping", false)) {
            Map<String,Object> mapping = object(raw, "requirement_mapping");
            only(mapping, Set.of("requirement_ref", "test_case_ids", "oracle_refs", "review_ref"), "requirement_mapping");
            String requirement = text(mapping.get("requirement_ref"), "requirement_ref");
            if (!requirements.add(requirement)) throw fail("duplicate_requirement", "requirements must be unique");
            equal(review, text(mapping.get("review_ref"), "oracle.review_ref"), "oracle_review_ref");
            Set<String> tests = strings(mapping.get("test_case_ids"), "test_case_ids", false);
            if (!closure.tests.containsAll(tests)) throw fail("unknown_test_case_id", "requirement mapping names an unexecuted test");
            mappedTests.addAll(tests);
            Set<String> oracles = strings(mapping.get("oracle_refs"), "oracle_refs", false);
            for (String oracle : oracles) {
                Set<String> actual = closure.oracleTests.get(oracle);
                if (actual == null || Collections.disjoint(actual, tests)) throw fail("oracle_coverage", "oracle mapping must name an operand used by its reviewed tests");
                Set<String> intersection = new LinkedHashSet<>(actual); intersection.retainAll(tests);
                mappedOracles.computeIfAbsent(oracle, unused -> new LinkedHashSet<>()).addAll(intersection);
            }
        }
        if (!mappedTests.equals(closure.tests)) throw fail("test_case_coverage", "every executed test needs a requirement mapping");
        if (!mappedOracles.equals(closure.oracleTests)) throw fail("oracle_coverage", "every expected operand must be bound to a reviewed requirement for its test");
    }

    private static void sourcePins(Map<String,Object> h) {
        Map<String,Object> swarm = object(h.get("swarm_source"), "swarm_source");
        only(swarm, Set.of("base_revision", "files"), "swarm_source");
        gitSha(swarm.get("base_revision"), "swarm_source.base_revision");
        Map<String,String> pins = stringMap(swarm.get("files"), "swarm_source.files", false);
        pins.forEach((path, value) -> { relativePath(path); sha(value, "swarm_source.file_sha256"); });
        if (!h.containsKey("cognee_source") && "not_used".equals(h.get("knowledge_mode"))) return;
        Map<String,Object> cognee = object(h.get("cognee_source"), "cognee_source");
        only(cognee, Set.of("version", "commit", "runtime_version_verified"), "cognee_source");
        equal("1.6.2", text(cognee.get("version"), "cognee_source.version"), "cognee_source_version");
        equal(COGNEE_COMMIT, text(cognee.get("commit"), "cognee_source.commit"), "cognee_source_commit");
        if (!Boolean.FALSE.equals(cognee.get("runtime_version_verified"))) throw fail("cognee_runtime_version_unverified", "source pins do not verify a deployed runtime");
    }

    private static void validateKnowledge(Inputs inputs, Map<String,Object> h, Map<String,String> repositories,
            Closure closure, Instant now, Set<String> reviewedPaths, Map<String,Object> provenance) {
        Map<String,Object> locator = object(h.get("selection_receipt"), "selection_receipt");
        only(locator, Set.of("path", "sha256"), "selection_receipt");
        String path = relativePath(text(locator.get("path"), "selection_receipt.path"));
        if (reviewedPaths.contains(path)) throw fail("receipt_in_reviewed_files", "receipt is external provenance, not an executable input");
        byte[] bytes = inputs.read(path);
        String rawSha = sha(locator.get("sha256"), "selection_receipt.sha256");
        equal(rawSha, digest(bytes), "receipt_digest_mismatch");
        Map<String,Object> receipt = json(bytes, "selection_receipt");
        equal("selection", text(receipt.get("phase"), "receipt.phase"), "receipt_phase");
        equal("ELIGIBLE_CONTEXT", text(receipt.get("status"), "receipt.status"), "receipt_status");
        String selectionSha = sha(receipt.get("selectionReceiptSha256"), "selectionReceiptSha256");
        String snapshotSha = sha(receipt.get("sourceSnapshotSha256"), "sourceSnapshotSha256");
        Map<String,Object> context = object(receipt.get("context"), "context");
        Map<String,Object> request = object(context.get("request"), "context.request");
        String workspace = text(request.get("workspaceRef"), "request.workspaceRef");
        String project = text(request.get("knowledgeProjectRef"), "request.knowledgeProjectRef");
        String consumer = text(request.get("consumerProjectRef"), "request.consumerProjectRef");
        equal(text(h.get("workspace_ref"), "workspace_ref"), workspace, "reviewed_workspace_ref");
        equal(text(h.get("project_ref"), "project_ref"), consumer, "reviewed_project_ref");
        equal(workspace, text(context.get("readWorkspaceRef"), "readWorkspaceRef"), "receipt_workspace_scope");
        equal(project, text(context.get("readProjectRef"), "readProjectRef"), "receipt_project_scope");
        equal(repositories, stringMap(request.get("currentRepositoryRevisions"), "request.currentRepositoryRevisions", false), "receipt_repository_revisions");
        Instant fetchedAt = instant(context.get("fetchedAt"), "context.fetchedAt");
        Instant evaluatedAt = instant(context.get("evaluatedAt"), "context.evaluatedAt");
        if (fetchedAt.isAfter(evaluatedAt) || evaluatedAt.isAfter(now)) throw fail("receipt_freshness", "receipt timestamps must precede invocation in order");
        List<Object> selected = list(context.get("selected"), "context.selected", false);
        List<Object> excluded = list(context.get("excluded"), "context.excluded", true);
        Map<String,Map<String,Object>> selectedById = new LinkedHashMap<>(); Set<String> selectedRefs = new LinkedHashSet<>();
        for (Object raw : selected) {
            Map<String,Object> record = object(raw, "selected");
            String id = text(record.get("recordKey"), "selected.recordKey");
            String ref = text(record.get("knowledgeRef"), "selected.knowledgeRef");
            if (selectedById.putIfAbsent(id, record) != null || !selectedRefs.add(ref)) throw fail("ambiguous_selected", "selected identities must be unique");
        }
        Set<String> excludedIds = new LinkedHashSet<>(); Set<String> excludedRefs = new LinkedHashSet<>();
        for (Object raw : excluded) {
            Map<String,Object> record = object(raw, "excluded");
            if (record.containsKey("recordKey") && record.get("recordKey") instanceof String id && !id.isBlank()) excludedIds.add(id);
            if (record.containsKey("knowledgeRef") && record.get("knowledgeRef") instanceof String ref && !ref.isBlank()) excludedRefs.add(ref);
        }
        Map<String,Object> trace = object(receipt.get("cogneeSelection"), "cogneeSelection");
        equal("CHUNKS", text(trace.get("searchType"), "searchType"), "receipt_search_type");
        equal("SEARCHED", text(trace.get("searchStatus"), "searchStatus"), "receipt_search_status");
        String dataset = text(trace.get("datasetId"), "datasetId");
        try { if (!java.util.UUID.fromString(dataset).toString().equals(dataset)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw fail("receipt_dataset", "datasetId must be a canonical UUID"); }
        String candidateSha = sha(trace.get("candidateSearchSha256"), "candidateSearchSha256");
        String querySha = sha(trace.get("querySha256"), "querySha256");
        String providerVersion = text(trace.get("providerVersion"), "providerVersion");
        equal("UNVERIFIED_BY_HTTP_CLIENT", providerVersion, "receipt_provider_version");
        List<Object> candidates = list(object(trace.get("candidateSearch"), "candidateSearch").get("candidates"), "candidateSearch.candidates", false);
        Map<String,List<Map<String,Object>>> candidatesById = new LinkedHashMap<>();
        for (Object raw : candidates) {
            Map<String,Object> candidate = object(raw, "candidate");
            String id = text(candidate.get("sourceId"), "candidate.sourceId");
            candidatesById.computeIfAbsent(id, unused -> new ArrayList<>()).add(candidate);
        }
        Map<String,Set<String>> requiredMappings = new LinkedHashMap<>();
        for (Object raw : list(h.get("requirement_mapping"), "requirement_mapping", false)) {
            Map<String,Object> mapping = object(raw, "requirement_mapping");
            requiredMappings.put(text(mapping.get("requirement_ref"), "requirement_ref"), strings(mapping.get("test_case_ids"), "test_case_ids", false));
        }
        Map<String,Set<String>> knowledgeMappings = new LinkedHashMap<>();
        Set<String> sourceIds = new LinkedHashSet<>(); List<Object> sourceProvenance = new ArrayList<>();
        for (Object raw : list(h.get("knowledge_sources"), "knowledge_sources", false)) {
            Map<String,Object> source = object(raw, "knowledge_source");
            only(source, Set.of("source_id", "source_revision", "provider_revision", "source_ref", "knowledge_ref", "content_sha256",
                    "repository_revisions", "applicable_project_refs", "consumer_project_ref", "test_case_ids", "requirement_refs"), "knowledge_source");
            String id = text(source.get("source_id"), "source_id"); String knowledge = text(source.get("knowledge_ref"), "knowledge_ref");
            if (!sourceIds.add(id)) throw fail("duplicate_knowledge_source", "source IDs must be unique");
            if (excludedIds.contains(id) || excludedRefs.contains(knowledge)) throw fail("excluded_knowledge_source", "excluded records cannot be reviewed sources");
            Map<String,Object> record = selectedById.get(id);
            if (record == null) throw fail("missing_selected_source", "candidate-only sources are not eligible context");
            equal(knowledge, text(record.get("knowledgeRef"), "selected.knowledgeRef"), "knowledge_ref_mismatch");
            List<Map<String,Object>> matches = candidatesById.getOrDefault(id, List.of());
            if (matches.size() != 1) throw fail("ambiguous_candidate", "exactly one retrieved candidate must match the selected source");
            Map<String,Object> candidate = matches.get(0);
            int revision = positive(source.get("source_revision"), "source_revision");
            equal(revision, positive(record.get("recordVersion"), "recordVersion"), "source_revision_mismatch");
            equal(revision, positive(candidate.get("sourceRevision"), "sourceRevision"), "candidate_revision_mismatch");
            String providerRevision = text(source.get("provider_revision"), "provider_revision");
            equal(providerRevision, text(record.get("providerRevision"), "selected.providerRevision"), "provider_revision_mismatch");
            equal(providerRevision, text(candidate.get("providerRevision"), "candidate.providerRevision"), "candidate_provider_revision_mismatch");
            String sourceRef = text(source.get("source_ref"), "source_ref");
            equal(sourceRef, text(candidate.get("sourceRef"), "candidate.sourceRef"), "source_ref_mismatch");
            Map<String,Object> proposal = object(record.get("proposal"), "selected.proposal");
            if (!strings(proposal.get("sourceRefs"), "proposal.sourceRefs", false).contains(sourceRef)) throw fail("source_ref_mismatch", "candidate source ref is not in selected proposal");
            equal(workspace, text(candidate.get("workspaceRef"), "candidate.workspaceRef"), "candidate_workspace_scope");
            equal(project, text(candidate.get("projectRef"), "candidate.projectRef"), "candidate_project_scope");
            equal(dataset, text(candidate.get("datasetId"), "candidate.datasetId"), "candidate_dataset_mismatch");
            String content = sha(source.get("content_sha256"), "content_sha256");
            equal(content, sha(candidate.get("contentSha256"), "candidate.contentSha256"), "content_digest_mismatch");
            String documentId = text(candidate.get("documentId"), "candidate.documentId");
            int chunks = positive(candidate.get("chunkCount"), "candidate.chunkCount");
            equal(repositories, stringMap(source.get("repository_revisions"), "source.repository_revisions", false), "source_repository_revisions");
            equal(repositories, stringMap(record.get("repositoryRevisions"), "selected.repositoryRevisions", false), "selected_repository_revisions");
            Set<String> applicable = strings(record.get("applicableProjectRefs"), "applicableProjectRefs", false);
            equal(applicable, strings(source.get("applicable_project_refs"), "applicable_project_refs", false), "applicable_projects_mismatch");
            equal(consumer, text(source.get("consumer_project_ref"), "consumer_project_ref"), "consumer_project_mismatch");
            if (!applicable.contains(consumer)) throw fail("consumer_not_applicable", "reviewed source must apply to this consumer");
            Instant observed = instant(record.get("observedAt"), "selected.observedAt");
            Instant valid = instant(record.get("validUntil"), "selected.validUntil");
            if (observed.isAfter(fetchedAt) || !valid.isAfter(observed) || !valid.isAfter(now)) throw fail("expired_selected_source", "selected source freshness must extend beyond invocation time");
            String proposalDigest = sha(record.get("proposalDigest"), "proposalDigest");
            String decisionKey = text(record.get("decisionRecordKey"), "decisionRecordKey");
            int decisionVersion = positive(record.get("decisionRecordVersion"), "decisionRecordVersion");
            String decisionDigest = sha(record.get("decisionProposalDigest"), "decisionProposalDigest");
            equal(id, decisionKey, "decision_record_mismatch"); equal(revision, decisionVersion, "decision_version_mismatch");
            equal(proposalDigest, decisionDigest, "decision_digest_mismatch");
            Map<String,Object> governance = object(record.get("governance"), "governance");
            String decisionRef = text(governance.get("decisionRef"), "governance.decisionRef");
            Set<String> tests = strings(source.get("test_case_ids"), "knowledge.test_case_ids", false);
            if (!closure.tests.containsAll(tests)) throw fail("knowledge_test_case_mismatch", "knowledge maps to an unexecuted test");
            Set<String> requirementRefs = strings(source.get("requirement_refs"), "knowledge.requirement_refs", false);
            Set<String> sourceTests = new LinkedHashSet<>();
            for (String requirement : requirementRefs) {
                Set<String> required = requiredMappings.get(requirement);
                if (required == null || Collections.disjoint(required, tests)) throw fail("knowledge_requirement_mismatch", "knowledge must map to a reviewed requirement and its tests");
                Set<String> matching = new LinkedHashSet<>(required); matching.retainAll(tests); sourceTests.addAll(matching);
                knowledgeMappings.computeIfAbsent(requirement, unused -> new LinkedHashSet<>()).addAll(matching);
            }
            if (!sourceTests.equals(tests)) throw fail("knowledge_test_case_mismatch", "every source test must have an exact reviewed requirement mapping");
            Map<String,Object> facts = new LinkedHashMap<>(source);
            facts.put("dataset_id", dataset); facts.put("document_id", documentId); facts.put("chunk_count", chunks);
            facts.put("proposal_digest", proposalDigest); facts.put("decision_record_key", decisionKey);
            facts.put("decision_record_version", decisionVersion); facts.put("decision_proposal_digest", decisionDigest);
            facts.put("decision_ref", decisionRef); facts.put("observed_at", observed.toString()); facts.put("valid_until", valid.toString());
            sourceProvenance.add(freeze(facts));
        }
        if (!knowledgeMappings.equals(requiredMappings)) throw fail("knowledge_requirement_coverage", "required knowledge must cover every reviewed requirement/test pair");
        provenance.put("selection_receipt_raw_sha256", rawSha); provenance.put("selection_receipt_sha256", selectionSha);
        provenance.put("source_snapshot_sha256", snapshotSha); provenance.put("candidate_search_sha256", candidateSha);
        provenance.put("query_sha256", querySha); provenance.put("dataset_id", dataset); provenance.put("provider_version", providerVersion);
        provenance.put("knowledge_sources", List.copyOf(sourceProvenance));
        provenance.put("knowledge_project_ref", project);
    }

    private static Snapshot materialize(String manifest, ProductTestPolicy policy, Map<String,FileEntry> files,
            Map<String,FileEntry> contracts, Map<String,Object> provenance) {
        Path root = null;
        try {
            root = Files.createTempDirectory("regress-reviewed-");
            Path catalog = Files.createDirectory(root.resolve(".reviewed-contracts"));
            for (FileEntry file : files.values()) {
                Path target = root.resolve(file.path);
                Files.createDirectories(target.getParent());
                Files.write(target, file.bytes, StandardOpenOption.CREATE_NEW);
            }
            for (FileEntry file : contracts.values()) {
                Files.write(catalog.resolve(Path.of(file.path).getFileName()), file.bytes, StandardOpenOption.CREATE_NEW);
            }
            return new Snapshot(root, root.resolve(manifest), catalog, policy, immutableObject(provenance));
        } catch (IOException | RuntimeException error) {
            if (root != null) try { deleteTree(root); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw fail("snapshot_creation_failed", "could not materialize the verified private input snapshot", error);
        }
    }

    public static final class Snapshot implements AutoCloseable {
        private final Path root;
        private final Path suiteManifest;
        private final Path contractDirectory;
        private final ProductTestPolicy policy;
        private final Map<String,Object> provenance;
        private boolean closed;
        private final Object rootIdentity;
        private Snapshot(Path root, Path suiteManifest, Path contractDirectory, ProductTestPolicy policy, Map<String,Object> provenance) {
            this.root = root; this.suiteManifest = suiteManifest; this.contractDirectory = contractDirectory;
            this.policy = policy; this.provenance = provenance;
            this.rootIdentity = isVerifiedV2NativeRequired() ? currentRootIdentity() : null;
        }
        public Path suiteManifest() { return suiteManifest; }
        public Path contractDirectory() { return contractDirectory; }
        public ProductTestPolicy policy() { return policy; }
        public Map<String,Object> provenance() { return provenance; }
        private boolean isVerifiedV2NativeRequired() {
            return "product-test-reviewed-handoff.v2".equals(provenance.get("contract_version"))
                    && policy.nativeExecution() && "required".equals(policy.knowledgeMode());
        }
        private Object currentRootIdentity() {
            noSymlinks(root);
            try {
                BasicFileAttributes attributes=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attributes.isDirectory()||attributes.fileKey()==null)throw fail("snapshot_root_identity","private snapshot directory identity unavailable");
                return attributes.fileKey();
            }catch(IOException unavailable){throw fail("snapshot_root_unavailable","private snapshot directory unavailable",unavailable);}
        }
        /** The actual verified capability, not caller labels or alternate filesystem discovery. */
        public synchronized Map<Path,Map<String,Object>> verifiedSelectedEnvProfiles(Path requestedManifest) {
            if(closed||!isVerifiedV2NativeRequired())throw fail("snapshot_profile_capability","requires an open verified v2 native required snapshot");
            equal(suiteManifest.toAbsolutePath().normalize(),requestedManifest.toAbsolutePath().normalize(),"snapshot_manifest_path");
            equal(rootIdentity,currentRootIdentity(),"snapshot_root_replaced");
            String manifestRef=null,profileRef=null;byte[]manifestBytes=null,profileBytes=null;int count=0;long total=0;
            for(Object raw:list(provenance.get("reviewed_files"),"snapshot.reviewed_files",false)){
                Map<String,Object> file=object(raw,"snapshot.reviewed_file");String ref=relativePath(text(file.get("path"),"snapshot.path"));
                byte[]bytes=readStandalone(root.resolve(ref));equal(sha(file.get("sha256"),"snapshot.sha256"),digest(bytes),"snapshot_input_digest_mismatch");total+=bytes.length;checkBudget(++count,total);
                String role=text(file.get("role"),"snapshot.role");
                if("manifest".equals(role)){if(manifestRef!=null)throw fail("snapshot_manifest_ambiguous","one verified manifest required");manifestRef=ref;manifestBytes=bytes;}
                if("profile".equals(role)){if(profileRef!=null)throw fail("snapshot_profile_ambiguous","one verified profile required");profileRef=ref;profileBytes=bytes;}
            }
            for(Object raw:list(provenance.get("reviewed_contracts"),"snapshot.reviewed_contracts",false)){
                Map<String,Object> file=object(raw,"snapshot.reviewed_contract");String ref=relativePath(text(file.get("path"),"snapshot.contract.path"));byte[]bytes=readStandalone(contractDirectory.resolve(Path.of(ref).getFileName()));
                equal(sha(file.get("sha256"),"snapshot.contract.sha256"),digest(bytes),"snapshot_contract_digest_mismatch");total+=bytes.length;checkBudget(++count,total);
            }
            if(manifestRef==null||profileRef==null)throw fail("snapshot_profile_missing","verified manifest/profile entry missing");
            equal(suiteManifest,root.resolve(manifestRef),"snapshot_manifest_identity");String profile=text(provenance.get("profile"),"snapshot.profile");
            Map<String,Object> manifest=yaml(manifestBytes,"snapshot.manifest");Map<String,Object> selected=object(object(manifest.get("env_profiles"),"snapshot.env_profiles").get(profile),"snapshot.selected_profile");
            equal(profileRef,relativePath(text(selected.get("ref"),"snapshot.selected_profile.ref")),"snapshot_selected_profile_ref");
            Map<String,Object> document=yaml(profileBytes,"snapshot.profile");equal(profile,text(document.get("profile_id"),"snapshot.profile_id"),"snapshot_profile_id");
            equal(rootIdentity,currentRootIdentity(),"snapshot_root_replaced");
            return Map.of(root.resolve(profileRef),immutableObject(document));
        }
        @Override public synchronized void close() { if (!closed) { deleteTree(root); closed = true; } }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) { throw fail("snapshot_cleanup_failed", "could not delete the framework-created snapshot", e); }
    }

    /** All discovery, hashing and copying share these exact byte arrays. */
    private static final class Inputs {
        private final Path root;
        private final String manifestRef;
        private final Map<String,byte[]> bytes = new LinkedHashMap<>();
        private Inputs(Path manifest) {
            if (manifest == null) throw fail("suite_manifest", "manifest path is required");
            Path absolute = manifest.toAbsolutePath().normalize();
            root = absolute.getParent();
            if (root == null) throw fail("unsafe_path", "manifest must have a suite directory");
            noSymlinks(root);
            manifestRef = relativePath(absolute.getFileName().toString());
        }
        private byte[] read(String ref) {
            String path = relativePath(ref);
            if (path.startsWith(".reviewed-contracts/") || path.equals(".reviewed-contracts")) throw fail("unsafe_path", "reserved snapshot catalog path");
            return bytes.computeIfAbsent(path, ignored -> readStandalone(root.resolve(path)));
        }
        private String relative(Path path) {
            if (path == null) throw fail("unsafe_path", "path is required");
            Path absolute = path.toAbsolutePath().normalize();
            if (!absolute.startsWith(root) || absolute.equals(root)) throw fail("unsafe_path", "path must remain inside the suite");
            return relativePath(root.relativize(absolute).toString());
        }
    }

    private static byte[] readStandalone(Path path) {
        Path absolute = path.toAbsolutePath().normalize(); noSymlinks(absolute);
        try {
            BasicFileAttributes before = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw fail("non_regular_file", "reviewed inputs must be regular files");
            if (before.size() > MAX_FILE_BYTES) throw fail("file_size_limit", "input exceeds 2 MiB");
            rejectHardlink(absolute);
            byte[] bytes;
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(absolute, options);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.min(before.size(), 8192))) {
                ByteBuffer chunk = ByteBuffer.allocate(8192);
                while (channel.read(chunk) != -1) {
                    chunk.flip();
                    if ((long) buffer.size() + chunk.remaining() > MAX_FILE_BYTES) throw fail("file_size_limit", "input exceeds 2 MiB");
                    buffer.write(chunk.array(), 0, chunk.remaining()); chunk.clear();
                }
                bytes = buffer.toByteArray();
            }
            noSymlinks(absolute); rejectHardlink(absolute);
            BasicFileAttributes after = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size() != after.size()
                    || after.size() != bytes.length) throw fail("file_changed_during_read", "input changed while reading");
            return bytes;
        } catch (IOException e) { throw fail("input_unavailable", "cannot read a reviewed local file", e); }
    }
    private static void rejectHardlink(Path path) throws IOException {
        try {
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(links instanceof Number count) || count.longValue() != 1) throw fail("hardlink_input", "hardlinked inputs are not accepted");
        } catch (UnsupportedOperationException e) { throw fail("hardlink_check_unavailable", "this filesystem cannot establish single-link input identity", e); }
    }
    private static void noSymlinks(Path path) {
        Path absolute = path.toAbsolutePath().normalize(); Path cursor = absolute.getRoot();
        for (Path segment : absolute) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw fail("symlink_input", "symbolic links and linked parents are prohibited");
        }
    }
    private static void safeOutputParent(Path root, Path parent) {
        if (!parent.startsWith(root)) throw fail("unsafe_path", "output must remain inside the suite");
        noSymlinks(parent);
        try { Files.createDirectories(parent); } catch (IOException e) { throw fail("output_unavailable", "cannot create handoff output directory", e); }
        noSymlinks(parent);
    }
    private static void writeNewOrRegular(Path file, byte[] bytes) {
        noSymlinks(file);
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw fail("unsafe_output", "handoff output must be a regular file");
                rejectHardlink(file);
            }
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
            }
        } catch (IOException e) { throw fail("output_unavailable", "cannot write prepared handoff", e); }
    }

    private static String relativePath(String value) {
        String text = text(value, "path");
        if (text.startsWith("/") || text.startsWith("~") || text.contains("\\") || text.contains("%") || text.contains(":") || text.contains("#")) throw fail("unsafe_path", "only unencoded suite-relative paths are accepted");
        Path path;
        try { path = Path.of(text); } catch (RuntimeException e) { throw fail("unsafe_path", "invalid local path"); }
        if (path.isAbsolute() || path.getNameCount() == 0) throw fail("unsafe_path", "absolute or empty path");
        for (Path part : path) if (Set.of(".", "..", "").contains(part.toString())) throw fail("unsafe_path", "traversal segments are prohibited");
        return path.toString();
    }
    private static String artifact(String ref, Map<String,Object> roots) {
        if (!ref.startsWith("artifact://") || ref.contains("${")) throw fail("unsupported_reference", "operation inputs must be static artifact references");
        String tail = ref.substring("artifact://".length()); int slash = tail.indexOf('/');
        if (slash <= 0 || slash == tail.length() - 1) throw fail("unsupported_reference", "artifact requires a root and a relative file");
        String root = tail.substring(0, slash);
        if (!roots.containsKey(root)) throw fail("unsupported_reference", "artifact root is undeclared");
        String base = relativePath(text(roots.get(root), "artifact_root"));
        return relativePath(base + "/" + relativePath(tail.substring(slash + 1)));
    }
    private static void actualRef(Object value, Map<String,String> ops) {
        String ref = text(value, "assert.actual");
        if (!ref.startsWith("step://")) throw fail("unsupported_reference", "assertion actual must be a known typed step output");
        String tail = ref.substring(7); int slash = tail.indexOf('/');
        if (slash <= 0) throw fail("unsupported_reference", "actual step output is malformed");
        String op = ops.get(tail.substring(0, slash)); String output = tail.substring(slash + 1);
        boolean supported = "execute_sample".equals(op) && output.matches("actual_json(?:\\.[A-Za-z0-9_]+)*|actual_text")
                || "http_request".equals(op) && Set.of("response.status", "response.body", "response.headers", "response.duration_ms").contains(output)
                || "run_command".equals(op) && Set.of("exit_code", "stdout", "stderr", "duration_ms").contains(output)
                || "nats_publish".equals(op) && Set.of("published", "subject", "duration_ms").contains(output)
                || "unary_call".equals(op) && Set.of("response.message", "response.status", "response.duration_ms").contains(output);
        if (!supported) throw fail("unsupported_reference", "actual must refer to an earlier supported execution output");
    }
    private static void cleanupRef(Object value, Map<String,String> ops) {
        String ref = text(value, "fixture.workspace_ref");
        if (!ref.startsWith("step://") || !ref.endsWith("/workspace_ref")) throw fail("unsupported_reference", "cleanup requires the known setup workspace output");
        String id = ref.substring(7, ref.length() - "/workspace_ref".length());
        if (!"setup_fixture".equals(ops.get(id))) throw fail("unsupported_reference", "cleanup workspace must come from earlier setup");
    }
    private static void literal(Object value) {
        if (value instanceof String str && (str.contains("://") || str.contains("${"))) throw fail("unsupported_reference", "expected operands must be literal or static artifacts");
        if (value instanceof Map<?,?> map) map.values().forEach(ProductTestHandoffService::literal);
        else if (value instanceof List<?> list) list.forEach(ProductTestHandoffService::literal);
        else if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) throw fail("unsupported_literal", "unsupported expected operand type");
    }
    private static void staticValue(Object value) {
        if (value instanceof String text && (text.contains("${") || text.startsWith("env://") || text.startsWith("generated://"))) throw fail("unsupported_reference", "static inputs cannot contain environment or generated substitutions");
        if (value instanceof Map<?,?> map) map.values().forEach(ProductTestHandoffService::staticValue);
        else if (value instanceof List<?> list) list.forEach(ProductTestHandoffService::staticValue);
    }
    private static void checkBudget(int files, long bytes) {
        if (files > MAX_FILES) throw fail("file_count_limit", "at most 128 reviewed files and contracts");
        if (bytes > MAX_TOTAL_BYTES) throw fail("total_size_limit", "reviewed inputs exceed 16 MiB");
    }
    private static void need(Map<String,String> needed, String path, String role) {
        String previous = needed.putIfAbsent(path, role);
        if (previous != null && !previous.equals(role)) throw fail("conflicting_file_role", "one input cannot serve incompatible reviewed roles");
    }
    private static void addOracle(Map<String,Set<String>> mapping, String oracle, String test) {
        mapping.computeIfAbsent(oracle, unused -> new LinkedHashSet<>()).add(test);
    }
    private static Object yamlValue(byte[] bytes, String field) {
        try {
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0); options.setCodePointLimit(MAX_FILE_BYTES); options.setNestingDepthLimit(50);
            return new Yaml(new SafeConstructor(options)).load(new java.io.ByteArrayInputStream(bytes));
        } catch (RuntimeException e) { throw fail("invalid_yaml", field + " must be a bounded unambiguous safe document", e); }
    }
    private static Map<String,Object> yaml(byte[] bytes, String field) { return object(yamlValue(bytes, field), field); }
    private static Map<String,Object> json(byte[] bytes, String field) {
        try { return object(JSON.readValue(bytes, Object.class), field); }
        catch (IOException | RuntimeException e) { throw fail("invalid_json", field + " must be one strict JSON object without duplicate keys", e); }
    }
    private static Map<String,Object> object(Object value, String field) {
        if (!(value instanceof Map<?,?> map)) throw fail(field, "must be an object");
        Map<String,Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw fail(field, "object keys must be strings");
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static List<Object> list(Object value, String field, boolean emptyAllowed) {
        if (!(value instanceof List<?> list) || (!emptyAllowed && list.isEmpty()) || list.size() > MAX_FILES) {
            throw fail(field, "must be a bounded " + (emptyAllowed ? "" : "nonempty ") + "list");
        }
        return new ArrayList<>(list);
    }
    private static Set<String> strings(Object value, String field, boolean emptyAllowed) {
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list(value, field, emptyAllowed)) if (!result.add(text(item, field))) throw fail("duplicate_" + field, "references must be unique");
        return Set.copyOf(result);
    }
    private static Map<String,String> stringMap(Object value, String field, boolean emptyAllowed) {
        Map<String,Object> map = object(value, field);
        if ((!emptyAllowed && map.isEmpty()) || map.size() > MAX_FILES) throw fail(field, "requires a bounded nonempty map");
        Map<String,String> result = new LinkedHashMap<>();
        map.forEach((key, raw) -> result.put(text(key, field), text(raw, field)));
        return Map.copyOf(result);
    }
    private static String text(Object value, String field) {
        if (!(value instanceof String str) || str.isBlank() || str.length() > 1024 || !str.equals(str.trim()) || str.codePoints().anyMatch(Character::isISOControl)) {
            throw fail(field, "must be a bounded nonempty string");
        }
        return str;
    }
    private static String sha(Object value, String field) {
        if (!(value instanceof String str) || !str.matches("[0-9a-f]{64}")) throw fail(field, "requires a lowercase SHA-256 hex digest");
        return str;
    }
    private static void gitSha(Object value, String field) {
        if (!(value instanceof String str) || !str.matches("[0-9a-f]{40}")) throw fail(field, "requires a lowercase Git commit SHA");
    }
    private static int positive(Object value, String field) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0 || ((Number) value).longValue() > Integer.MAX_VALUE) {
            throw fail(field, "must be a positive integer");
        }
        return ((Number) value).intValue();
    }
    private static Instant instant(Object value, String field) {
        String text = text(value, field);
        if (!text.endsWith("Z")) throw fail(field, "must be a UTC ISO-8601 instant");
        try { return Instant.parse(text); } catch (RuntimeException e) { throw fail(field, "must be a UTC ISO-8601 instant"); }
    }
    private static void only(Map<String,Object> map, Set<String> fields, String field) {
        for (var e : map.entrySet()) if (!fields.contains(e.getKey()) || e.getValue() == null) throw fail(field + "." + e.getKey(), "unknown field or explicit null");
    }
    private static void equal(Object expected, Object actual, String field) {
        if (!java.util.Objects.equals(expected, actual)) throw fail(field, "reviewed and supplied identities/bytes do not match");
    }
    private static void suppliedOrPut(Map<String,Object> map, String field, String expected) {
        if (map.containsKey(field)) equal(expected, text(map.get(field), field), field);
        else map.put(field, expected);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> copy = new LinkedHashMap<>(); map.forEach((key, item) -> copy.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(ProductTestHandoffService::freeze).toList();
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> immutableObject(Map<String,Object> value) { return (Map<String,Object>) freeze(value); }
    private static IllegalArgumentException fail(String code, String reason) { return new IllegalArgumentException("product_test_" + code + ": " + reason); }
    private static IllegalArgumentException fail(String code, String reason, Throwable cause) { return new IllegalArgumentException("product_test_" + code + ": " + reason, cause); }
    private record Selection(String profile, String profileRef, Map<String,Object> suite, Map<String,Object> env, ProductTestPolicy policy) { }
    private record FileEntry(String path, String role, byte[] bytes) { }
    private record Closure(Set<String> tests, Map<String,Set<String>> oracleTests) { }
}
