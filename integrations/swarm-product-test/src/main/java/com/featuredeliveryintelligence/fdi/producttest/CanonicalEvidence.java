package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class CanonicalEvidence {
    private final Path result;
    private final JsonNode document;
    private final Map<String, ProductTestReceipt.Artifact> artifacts;
    private final Map<String, String> treeDigests;
    private final boolean grpcPayload;

    private CanonicalEvidence(Path result, JsonNode document, Map<String, ProductTestReceipt.Artifact> artifacts, Map<String, String> treeDigests, boolean grpcPayload) {
        this.result = result; this.document = document; this.artifacts = Map.copyOf(artifacts); this.treeDigests = Map.copyOf(treeDigests); this.grpcPayload=grpcPayload;
    }

    static CanonicalEvidence inspect(ProductTestRequest request, Path output, String stdout, Instant started) throws Exception {
        return inspect(request,output,stdout,started,null);
    }
    static CanonicalEvidence inspect(ProductTestRequest request, Path output, String stdout, Instant started, String fixtureLeaseSha256) throws Exception { return inspect(request,output,stdout,started,fixtureLeaseSha256,null); }
    static CanonicalEvidence inspect(ProductTestRequest request, Path output, String stdout, Instant started, String fixtureLeaseSha256,String independentSutPin) throws Exception {
        Map<String, String> lines = outputFields(stdout);
        require(lines.containsKey("result_json"), "Missing canonical result location");
        Path result = LocalFiles.contained(output, Path.of(lines.get("result_json")));
        require(result.getFileName().toString().equals("result.json"), "Unexpected canonical result filename");
        boolean grpcPayload=Json.reviewedGrpc(request);
        var document = Json.readCanonical(result,request);
        for (String field : List.of("suite_id", "profile", "batch_id", "run_id"))
            require(Json.required(document, field).equals(lines.get(field)), "CLI/result identity mismatch: " + field);
        require(request.suiteId().equals(Json.required(document, "suite_id"))
                && request.profile().equals(Json.required(document, "profile")), "Request/result identity mismatch");
        String runId = Json.required(document, "run_id");
        require(runId.matches("RUN-(?:[A-Za-z0-9]+-)?[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"), "Non-randomized run identity");
        String batch = Json.required(document, "batch_id");
        require(batch.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"), "Invalid batch identity");
        Path run = output.resolve("target/regression").resolve(request.suiteId()).resolve(batch).resolve(runId);
        require(result.equals(run.resolve("result.json")), "Canonical result escaped its expected fresh run directory");
        require("v0.3".equals(Json.required(document, "dsl_version"))
                && "v0.3".equals(Json.required(document, "result_contract_version")), "Unsupported canonical result version");
        require(Set.of("passed", "failed", "blocked", "skipped").contains(Json.required(document, "status")), "Unknown framework verdict");
        Instant start = Instant.parse(Json.required(document, "start_time"));
        Instant end = Instant.parse(Json.required(document, "end_time"));
        require(!start.isBefore(started.minusMillis(1000)) && !end.isBefore(start) && !end.isAfter(Instant.now().plusSeconds(1)),
                "Canonical result does not belong to this fresh invocation");
        require("framework_verification_only".equals(Json.required(document.path("labels"), "evidence_classification"))
                && explicitFalse(document.path("labels").path("downstream_release_evidence")), "Local result cannot become release evidence");
        rejectReleaseClaims(document);
        Map<String, String> tree = tree(run,grpcPayload);
        validateReferences(document, run);
        Path index = relative(run, Json.required(document, "evidence_index_ref"));
        Path summary = relative(run, Json.required(document, "suite_summary_ref"));
        var summaryDocument = Json.read(summary);
        matchIdentity(document, summaryDocument, true);
        require(!summaryDocument.path("children").isArray() || summaryDocument.path("children").isEmpty(), "Only leaf suites are supported");
        validateReferences(summaryDocument, run);
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(2 * 1024 * 1024); options.setNestingDepthLimit(50);
        Object indexObject = new Yaml(new SafeConstructor(options)).load(new String(LocalFiles.read(index, 2 * 1024 * 1024), StandardCharsets.UTF_8));
        var indexDocument = Json.MAPPER.valueToTree(indexObject);
        matchIdentity(document, indexDocument, false);
        require(indexDocument.path("entries").isArray() && !indexDocument.path("entries").isEmpty(), "Missing evidence entries");
        for (JsonNode entry : indexDocument.path("entries")) {
            require(runId.equals(Json.required(entry, "run_id")) && batch.equals(Json.required(entry, "batch_id")), "Evidence entry identity mismatch");
            relative(run, Json.required(entry, "file_path"));
        }
        Path handoffEvidence = relative(run, "product-test/reviewed-handoff.json");
        require(contains(document.path("evidence_refs"), "product-test/reviewed-handoff.json")
                && containsFile(indexDocument.path("entries"), "product-test/reviewed-handoff.json"), "Reviewed handoff evidence is not indexed");
        var provenance = Json.read(handoffEvidence);
        matchIdentity(document, provenance, false);
        require(Json.required(document, "plan_digest").equals(Json.required(provenance, "plan_digest")), "Reviewed plan identity mismatch");
        require(request.expectedHandoffSha256().equals(Json.required(provenance, "handoff_sha256")), "Reviewed handoff digest mismatch");
        require(request.knowledgeMode().equals(Json.required(provenance, "knowledge_mode")), "Reviewed knowledge mode mismatch");
        require(request.testLevel().equals(Json.required(provenance, "test_level"))
                && request.testType().equals(Json.required(provenance, "test_type"))
                && request.environmentStage().equals(Json.required(provenance, "environment_stage")), "Reviewed capability tuple mismatch");
        require(request.workspaceRef().equals(Json.required(provenance, "workspace_ref"))
                && request.projectRef().equals(Json.required(provenance, "project_ref")), "Reviewed evidence scope mismatch");
        require(Json.MAPPER.valueToTree(request.repositoryRevisions()).equals(provenance.path("repository_revisions")), "Repository revision identity mismatch");
        require("framework_verification_only".equals(Json.required(provenance, "evidence_classification"))
                && explicitFalse(provenance.path("downstream_release_evidence")), "Reviewed evidence cannot become release evidence");
        boolean passed = "passed".equals(Json.required(document, "status"));
        boolean nativeExecution = "integration".equals(request.testLevel());
        validateModes(provenance, "provider_modes", "observed_provider_modes", true, passed, nativeExecution ? "real" : "simulated");
        validateModes(provenance, "dependency_modes", "observed_dependency_modes", false, passed, "simulated");
        if (nativeExecution) {
            byte[] reviewedBytes = LocalFiles.read(Path.of(request.reviewedHandoff()), 2 * 1024 * 1024);
            require(request.expectedHandoffSha256().equals(LocalFiles.sha256(reviewedBytes)), "Reviewed handoff digest mismatch");
            var contracts = Json.MAPPER.readTree(reviewedBytes).path("reviewed_contracts");
            require(contracts.isArray() && contracts.size() == 1 && contracts.equals(provenance.path("reviewed_contracts")),
                    "Native reviewed contract differs from pinned handoff");
            String contract = Json.required(contracts.get(0), "provider_contract");
            require(Set.of("rest_client.v0.3", "cli_process.v0.3", "nats.v0.3", "grpc_client.v0.3", "grpc_descriptor_admission.v0.3", "kafka.v0.3", "jdbc.v0.3").contains(contract), "Unsupported native provider contract");
            String type = switch (contract) { case "cli_process.v0.3" -> "cli_process"; case "kafka.v0.3" -> "kafka"; case "jdbc.v0.3" -> "jdbc"; case "nats.v0.3" -> "nats"; case "grpc_descriptor_admission.v0.3" -> "grpc_descriptor_admission"; case "grpc_client.v0.3" -> "grpc_client"; default -> "rest_client"; };
            String operation = switch (type) { case "cli_process" -> "run_command"; case "kafka" -> "kafka_publish"; case "jdbc" -> "db_query"; case "nats" -> "nats_publish"; case "grpc_client", "grpc_descriptor_admission" -> "unary_call"; default -> "http_request"; };
            boolean requiredV2="required".equals(request.knowledgeMode())&&ReviewedKnowledgeAdmission.v2(Json.MAPPER.readTree(reviewedBytes));
            if(requiredV2)ReviewedKnowledgeAdmission.after(request,Json.MAPPER.readTree(reviewedBytes),Path.of(request.suiteManifest()).getParent(),provenance,independentSutPin,start);
            require(("not_used".equals(request.knowledgeMode())||requiredV2) && provenance.path("provider_modes").size() == 1
                    && provenance.path("dependency_modes").isEmpty(), "Unsupported native capability metadata");
            require(document.path("provider_results").isArray() && document.path("step_results").isArray()
                    && document.path("provider_results").size() <= 1 && document.path("step_results").size() <= 1
                    && (!passed || document.path("provider_results").size() == 1 && document.path("step_results").size() == 1),
                    "Native evidence must describe one selected operation");
            for (var provider : document.path("provider_results")) require(type.equals(provider.path("provider_type").asText())
                    && contract.equals(provider.path("provider_contract").asText())
                    && "native".equals(provider.path("runtime_mode").asText()), "Native provider metadata mismatch");
            for (var step : document.path("step_results")) require("execute".equals(step.path("phase").asText())
                    && operation.equals(step.path("operation").asText()), "Native operation metadata mismatch");
            if(type.equals("kafka")) {
                require("not_used".equals(request.knowledgeMode()),"Kafka requires not_used knowledge");
                require(fixtureLeaseSha256!=null&&fixtureLeaseSha256.matches("[a-f0-9]{64}"),"Independent Kafka fixture lease pin missing");
                verifyKafka(request,run,document,provenance,indexDocument,fixtureLeaseSha256);
            }
            if(type.equals("jdbc")) {
                require(fixtureLeaseSha256!=null&&fixtureLeaseSha256.matches("[a-f0-9]{64}"),"JDBC independent lease pin missing");
                require(fixtureLeaseSha256.equals(Json.required(provenance,"fixture_lease_sha256")),"JDBC independent lease pin mismatch");
                var lease=ReviewedJdbcProof.boundLease(provenance.path("fixture_lease"),provenance.path("fixture_lease_raw_base64").isTextual()?provenance.path("fixture_lease_raw_base64").textValue():null,fixtureLeaseSha256);
                Path file=LocalFiles.contained(Path.of(request.suiteManifest()).getParent(),Path.of(request.suiteManifest()).getParent().resolve("fixtures/lease.json"));
                byte[] bytes=LocalFiles.read(file,16384);require(fixtureLeaseSha256.equals(LocalFiles.sha256(bytes))&&lease.equals(Json.MAPPER.readTree(bytes)),"JDBC independent lease bytes mismatch");
                long matches=0;for(var reviewed:Json.MAPPER.readTree(reviewedBytes).path("reviewed_files"))if("fixtures/lease.json".equals(reviewed.path("path").asText())){require("fixture".equals(reviewed.path("role").asText())&&fixtureLeaseSha256.equals(reviewed.path("sha256").asText()),"JDBC reviewed lease digest mismatch");matches++;}
                require(matches==1,"JDBC lease missing or ambiguous in reviewed closure");
                if(!document.path("provider_results").isEmpty()) {
                    require(contains(document.path("evidence_refs"),ReviewedJdbcProof.PATH)&&containsFile(indexDocument.path("entries"),ReviewedJdbcProof.PATH),"JDBC proof must be indexed");
                    var proof=ReviewedJdbcProof.parseProof(LocalFiles.read(relative(run,ReviewedJdbcProof.PATH),65536));
                    var jdbcPayload=document.path("provider_results").get(0).path("resolved_operation_results").path(0).path("outputs").path("result");
                    var provider=document.path("provider_results").get(0);
                    require(provider.path("provider_id").isTextual()&&!provider.path("provider_id").textValue().isBlank()&&"real".equals(provenance.path("observed_provider_modes").path(provider.path("target").textValue()).textValue())&&provider.path("operations").isArray()&&provider.path("operations").size()==1&&"db_query".equals(provider.path("operations").get(0).textValue())&&provider.path("resolved_operation_results").isArray()&&provider.path("resolved_operation_results").size()==1&&"db_query".equals(provider.path("resolved_operation_results").get(0).path("operation").textValue()),"JDBC canonical provider/operation identity mismatch");
                    ReviewedJdbcProof.validate(lease,fixtureLeaseSha256,proof,passed,jdbcPayload.isTextual()?jdbcPayload.textValue():null,provider.path("provider_id").textValue(),provider.path("operation").textValue(),start,end);
                }
            }
            if(type.equals("grpc_descriptor_admission")) {
                require(fixtureLeaseSha256==null&&independentSutPin==null,"Descriptor-only evidence cannot consume an RPC lease/SUT binding");
                String proofRef=ReviewedGrpcDescriptorProof.PATH;
                require(contains(document.path("evidence_refs"),proofRef)&&containsFile(indexDocument.path("entries"),proofRef),"Descriptor proof must be indexed");
                byte[] proofBytes=LocalFiles.read(relative(run,proofRef),16384);
                ReviewedGrpcDescriptorProof.validate(Json.MAPPER.readTree(proofBytes),document,provenance);
            }
            if(type.equals("grpc_client")) {
                require(fixtureLeaseSha256!=null && fixtureLeaseSha256.matches("[a-f0-9]{64}"),"Independent fixture lease pin missing");
                require(fixtureLeaseSha256.equals(Json.required(provenance,"fixture_lease_sha256")),"Independent fixture lease pin mismatch");
                var lease=provenance.path("fixture_lease");
                Path leaseFile=LocalFiles.contained(Path.of(request.suiteManifest()).getParent(),Path.of(request.suiteManifest()).getParent().resolve("fixtures/lease.json"));
                byte[] leaseBytes=LocalFiles.read(leaseFile,16384);
                require(fixtureLeaseSha256.equals(LocalFiles.sha256(leaseBytes)) && lease.equals(Json.MAPPER.readTree(leaseBytes)),"Independent lease bytes differ from canonical provenance");
                require("reviewed-grpc-lease.v1".equals(Json.required(lease,"version")) && Set.of("echo.v1","greeter.v1","routeguide.getfeature.v1").contains(Json.required(lease,"profile")),"Unsupported fixture lease profile");
                boolean bound=false;
                for(var reviewed:Json.MAPPER.readTree(reviewedBytes).path("reviewed_files")) {
                    if("fixtures/lease.json".equals(reviewed.path("path").asText())) {
                        require("fixture".equals(reviewed.path("role").asText()) && fixtureLeaseSha256.equals(reviewed.path("sha256").asText()),"Reviewed lease digest mismatch");bound=true;
                    }
                }
                require(bound,"Lease missing from reviewed closure");
                if(!document.path("provider_results").isEmpty()) {
                    String proofRef="provider-evidence/grpc-client/reviewed-unary.json";
                    require(contains(document.path("evidence_refs"),proofRef) && containsFile(indexDocument.path("entries"),proofRef),"Native gRPC proof must be indexed");
                    var proof=Json.readGrpcProof(relative(run,proofRef));
                    String grpcProfile=Json.required(lease,"profile");
                    String expectedService=java.util.Map.of("echo.v1","swarm.producttest.EchoService","greeter.v1","helloworld.Greeter",
                            "routeguide.getfeature.v1","routeguide.RouteGuide").get(grpcProfile);
                    String expectedMethod=java.util.Map.of("echo.v1","Echo","greeter.v1","SayHello",
                            "routeguide.getfeature.v1","GetFeature").get(grpcProfile);
                    require(expectedService.equals(Json.required(proof,"service"))
                            && expectedMethod.equals(Json.required(proof,"method")),"Native gRPC finite service/method mismatch");
                    require(fixtureLeaseSha256.equals(Json.required(proof,"fixture_lease_sha256"))
                            && Json.required(lease,"owner_run_id").equals(Json.required(proof,"owner_run_id"))
                            && Json.required(lease,"endpoint").equals(Json.required(proof,"endpoint")),"Native gRPC fixture identity mismatch");
                    require(proof.path("rpc_attempted").isBoolean() && proof.path("cleanup_confirmed").isBoolean(),"Missing gRPC attempt/cleanup proof");
                    if(passed) require(proof.path("rpc_attempted").asBoolean() && proof.path("cleanup_confirmed").asBoolean()
                            && "OK".equals(Json.required(proof,"status")),"Passed unary requires actual RPC and confirmed cleanup");
                }
            }
            for (var provider : document.path("provider_results")) {
                require(operation.equals(provider.path("operation").asText())
                        && Json.MAPPER.valueToTree(List.of(operation)).equals(provider.path("operations")),
                        "Native provider operation metadata mismatch");
                var resolved = provider.path("resolved_operation_results");
                require(resolved.isArray() && resolved.size() <= 1 && (!passed || resolved.size() == 1),
                        "Native resolved operation count mismatch");
                for (var observed : resolved) require(operation.equals(observed.path("operation").asText()),
                        "Native resolved operation metadata mismatch");
            }
        }
        var artifacts = new LinkedHashMap<String, ProductTestReceipt.Artifact>();
        for (var entry : tree.entrySet()) artifacts.put("evidence:" + entry.getKey(), new ProductTestReceipt.Artifact(run.resolve(entry.getKey()).toString(), entry.getValue()));
        for (var entry : Map.of("result", result, "suite_summary", summary, "evidence_index", index, "reviewed_handoff", handoffEvidence).entrySet())
            artifacts.put(entry.getKey(), new ProductTestReceipt.Artifact(entry.getValue().toString(), LocalFiles.sha256(entry.getValue())));
        return new CanonicalEvidence(result, document, artifacts, tree, grpcPayload);
    }
    /** Kafka-specific historical joins. No live-owner lookup or provider/network calls. */
    private static void verifyKafka(ProductTestRequest request,Path run,com.fasterxml.jackson.databind.JsonNode result,
            com.fasterxml.jackson.databind.JsonNode provenance,com.fasterxml.jackson.databind.JsonNode index,String pin)throws Exception {
        Path root=Path.of(request.suiteManifest()).getParent();
        byte[] raw=LocalFiles.read(LocalFiles.contained(root,root.resolve("fixtures/lease.json")),16384);
        require(pin.equals(LocalFiles.sha256(raw))&&pin.equals(Json.required(provenance,"fixture_lease_sha256")),"Independent Kafka lease bytes/pin mismatch");
        var lease=Json.readMetadataUtf8(raw);require(lease.equals(provenance.path("fixture_lease"))&&java.util.Base64.getEncoder().encodeToString(raw).equals(Json.required(provenance,"fixture_lease_base64")),"Kafka frozen lease differs from independent bytes");
        kafkaExact(lease,Set.of("version","owner_run_id","nonce","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode","client_network_container_id","advertised_listener","controller_listener","partitions","initial_offset","issued_at","expires_at"));
        require("reviewed-kafka-lease.v1".equals(Json.required(lease,"version")),"Kafka lease version");String owner=Json.required(lease,"owner_run_id"),endpoint=Json.required(lease,"endpoint");
        require(owner.matches("[a-f0-9]{32}")&&owner.equals(Json.required(lease,"nonce"))&&("sit."+owner+".publish").equals(Json.required(lease,"topic"))&&("sit."+owner+".group").equals(Json.required(lease,"consumer_group"))&&("run."+owner).equals(Json.required(lease,"key")),"Kafka lease owner/topic/group/key mismatch");
        kafkaEndpoint(endpoint);String controller=Json.required(lease,"controller_listener");kafkaEndpoint(controller);
        require(endpoint.equals(Json.required(lease,"advertised_listener"))&&!endpoint.equals(controller)&&"none".equals(Json.required(lease,"network_mode"))&&Json.required(lease,"broker_container_id").equals(Json.required(lease,"client_network_container_id")),"Kafka declared fence mismatch");
        for(String field:List.of("payload_sha256","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id"))require(Json.required(lease,field).matches("[a-f0-9]{64}"),"Kafka lease digest");
        require(Json.required(lease,"runtime_version").matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")&&lease.path("partitions").isIntegralNumber()&&lease.path("partitions").canConvertToInt()&&lease.path("partitions").asInt()==1&&lease.path("initial_offset").isIntegralNumber()&&lease.path("initial_offset").canConvertToLong()&&lease.path("initial_offset").asLong()==0,"Kafka runtime/fresh-partition profile");
        Instant issue=Instant.parse(Json.required(lease,"issued_at")),expiry=Instant.parse(Json.required(lease,"expires_at")),start=Instant.parse(Json.required(result,"start_time"));
        require(expiry.isAfter(issue)&&java.time.Duration.between(issue,expiry).compareTo(java.time.Duration.ofMinutes(10))<=0&&!start.isBefore(issue)&&start.isBefore(expiry),"Kafka historical lease time mismatch");
        byte[] payload=LocalFiles.read(LocalFiles.contained(root,root.resolve("fixtures/payload.json")),65536);require(payload.length>0&&LocalFiles.sha256(payload).equals(Json.required(lease,"payload_sha256")),"Kafka payload digest mismatch");
        for(String path:List.of("fixtures/lease.json","fixtures/payload.json")){
            String expected=path.endsWith("lease.json")?pin:LocalFiles.sha256(payload);int count=0;
            for(var f:provenance.path("reviewed_files"))if(path.equals(f.path("path").asText())){require("fixture".equals(f.path("role").asText())&&expected.equals(f.path("sha256").asText()),"Kafka reviewed input closure mismatch");count++;}
            require(count==1,"Kafka reviewed closure missing/ambiguous");
        }
        if(result.path("provider_results").isEmpty())return;
        String path="provider-evidence/kafka/reviewed-publish.json";
        require(contains(result.path("evidence_refs"),path)&&containsFile(index.path("entries"),path),"Kafka proof is not indexed");
        var proof=Json.readMetadataUtf8(LocalFiles.read(relative(run,path),16384));
        var identities=Set.of("owner_run_id","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode");var fields=new java.util.HashSet<>(identities);fields.addAll(Set.of("version","evidence_type","provider_type","run_id","batch_id","step_id","provider_id","operation","fixture_lease_sha256","client_version","attempted","acknowledged","cleanup_confirmed","status","partition","offset","client_started_at","client_finished_at"));kafkaExact(proof,fields);
        require("reviewed-kafka-publish.v1".equals(Json.required(proof,"version"))&&"kafka_event".equals(Json.required(proof,"evidence_type"))&&"kafka".equals(Json.required(proof,"provider_type"))&&"kafka_publish".equals(Json.required(proof,"operation"))&&"3.9.2".equals(Json.required(proof,"client_version"))&&pin.equals(Json.required(proof,"fixture_lease_sha256")),"Kafka proof profile/client/pin mismatch");
        for(String f:identities)require(lease.path(f).equals(proof.path(f)),"Kafka proof lease identity mismatch");
        for(String f:List.of("run_id","batch_id"))require(Json.required(result,f).equals(Json.required(proof,f)),"Kafka proof run identity mismatch");
        var step=result.path("step_results").get(0);var provider=result.path("provider_results").get(0);
        require(Json.required(step,"id").equals(Json.required(proof,"step_id"))&&Json.required(step,"target").equals(Json.required(proof,"provider_id"))&&Json.required(provider,"target").equals(Json.required(proof,"provider_id")),"Kafka proof step/provider mismatch");
        for(String f:List.of("attempted","acknowledged","cleanup_confirmed"))require(proof.path(f).isBoolean(),"Kafka proof boolean");
        boolean attempt=proof.path("attempted").booleanValue(),ack=proof.path("acknowledged").booleanValue(),clean=proof.path("cleanup_confirmed").booleanValue();String status=Json.required(proof,"status");
        require(Set.of("ACK","CLIENT_FAILED","DEADLINE_EXCEEDED","INTERRUPTED","CLEANUP_UNCERTAIN","PREFLIGHT_BLOCKED").contains(status),"Kafka proof status");
        require(proof.path("partition").isIntegralNumber()&&proof.path("partition").canConvertToInt()&&proof.path("offset").isIntegralNumber()&&proof.path("offset").canConvertToLong(),"Kafka ACK metadata type");
        int partition=proof.path("partition").asInt();long offset=proof.path("offset").asLong();require(!ack||attempt&&partition==0&&offset==0,"Kafka ACK partition/offset mismatch");require(ack||partition==-1&&offset==-1,"Kafka unacked metadata mismatch");
        require(!status.equals("ACK")||attempt&&ack&&clean,"Kafka ACK state mismatch");require(!status.equals("PREFLIGHT_BLOCKED")||!attempt&&!ack&&clean,"Kafka preflight state mismatch");require(!"passed".equals(Json.required(result,"status"))||status.equals("ACK"),"Kafka passed without confirmed ACK");
        if(ack){var outputs=step.path("outputs");for(String f:List.of("partition","offset"))require(outputs.path(f).isIntegralNumber()&&outputs.path(f).canConvertToLong()&&outputs.path(f).longValue()==proof.path(f).longValue(),"Kafka proof output mismatch");for(String f:List.of("key","topic"))require(outputs.path(f).equals(proof.path(f)),"Kafka proof output mismatch");}
        try {
            Instant clientStart=Instant.parse(Json.required(proof,"client_started_at")),clientEnd=Instant.parse(Json.required(proof,"client_finished_at"));
            require(!clientEnd.isBefore(clientStart)&&!clientStart.isBefore(start)&&!clientEnd.isAfter(Instant.parse(Json.required(result,"end_time"))),"Kafka client/invocation interval mismatch");
            require(!clientStart.isBefore(issue)&&clientStart.isBefore(expiry)&&!clientEnd.isAfter(expiry),"Kafka client/lease interval mismatch");
        } catch(java.time.DateTimeException e) {throw new IllegalArgumentException("Kafka historical client time format");}
    }
    private static void kafkaEndpoint(String endpoint){require(endpoint.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")&&Integer.parseInt(endpoint.substring(10))<=65535,"Kafka endpoint");}
    private static void kafkaExact(com.fasterxml.jackson.databind.JsonNode node,Set<String> fields){var actual=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(actual::add);require(node.isObject()&&actual.equals(fields),"Kafka closed fields");}

    Path result() { return result; }
    String verdict() { return Json.required(document, "status"); }
    String runId() { return Json.required(document, "run_id"); }
    Map<String, ProductTestReceipt.Artifact> artifacts() { return artifacts; }
    void verifyReport(Path report) throws Exception {
        var reported = Json.read(report);
        matchIdentity(document, reported, true);
        require(explicitFalse(reported.path("release_evidence_eligible")), "Local framework report cannot become release evidence");
        require(Set.of("review_ready", "review_ready_with_failures").contains(Json.required(reported, "report_status")), "Framework report is not reviewable");
        require("passed".equals(Json.required(reported, "masking_status")), "Framework report masking failed");
    }
    void verifyUnchanged() throws Exception {
        require(treeDigests.equals(tree(result.getParent(),grpcPayload)), "Canonical evidence changed during validation");
    }
    private static Map<String, String> outputFields(String stdout) {
        var fields = new LinkedHashMap<String, String>();
        for (String line : stdout.split("\\R")) {
            int colon = line.indexOf(": "); if (colon < 0) continue;
            String key = line.substring(0, colon);
            if (Set.of("result_json", "suite_id", "profile", "batch_id", "run_id").contains(key))
                require(fields.putIfAbsent(key, line.substring(colon + 2)) == null, "Ambiguous CLI identity: " + key);
        }
        return fields;
    }
    private static void matchIdentity(JsonNode expected, JsonNode actual, boolean status) {
        for (String key : List.of("suite_id", "profile", "batch_id", "run_id"))
            require(Json.required(expected, key).equals(Json.required(actual, key)), "Canonical evidence identity mismatch: " + key);
        if (status) require(Json.required(expected, "status").equals(Json.required(actual, "status")), "Canonical verdict mismatch");
    }
    private static void validateModes(JsonNode provenance, String declaredKey, String observedKey, boolean nonempty, boolean requireComplete, String mode) {
        var declared = provenance.path(declaredKey); var observed = provenance.path(observedKey);
        require(declared.isObject() && observed.isObject() && (!nonempty || !declared.isEmpty()), "Missing declared/observed mode maps");
        if (requireComplete) require(declared.equals(observed), "Declared/observed mode mismatch");
        observed.fields().forEachRemaining(entry -> require(entry.getValue().equals(declared.path(entry.getKey())), "Observed mode does not match declaration"));
        declared.elements().forEachRemaining(value -> require(value.isTextual() && mode.equals(value.asText()), "Unsupported local provider/dependency mode"));
    }
    private static boolean explicitFalse(JsonNode node) { return node.isBoolean() && !node.booleanValue(); }
    private static void rejectReleaseClaims(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if (Set.of("release_evidence_eligible", "downstream_release_evidence").contains(entry.getKey()))
                require(explicitFalse(entry.getValue()), "Local fixture cannot become release evidence");
            rejectReleaseClaims(entry.getValue());
        });
        else if (node.isArray()) node.forEach(CanonicalEvidence::rejectReleaseClaims);
    }
    private static Map<String, String> tree(Path root,boolean grpcPayload) throws IOException {
        var result = new LinkedHashMap<String, String>(); long bytes = 0; int count = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                require(++count <= 4096, "Evidence tree exceeds file limit");
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink evidence is forbidden");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Special evidence file is forbidden");
                long size = Files.size(path); bytes += size;
                long fileLimit=grpcPayload && path.equals(root.resolve("result.json")) ? 8L*1024*1024 : 4L*1024*1024;
                require(size <= fileLimit && bytes <= 64L * 1024 * 1024, "Evidence exceeds byte limit");
                result.put(root.relativize(path).toString(), LocalFiles.sha256(path));
            }
        }
        return result;
    }
    private static Path relative(Path root, String value) throws IOException {
        Path ref = Path.of(value);
        require(!ref.isAbsolute() && ref.getNameCount() > 0 && ref.normalize().equals(ref)
                && !value.contains("\\") && !value.contains(":") && !value.startsWith("..") && !value.isBlank(), "Evidence reference must be a normalized contained relative path");
        Path file = LocalFiles.contained(root, root.resolve(ref));
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Missing regular evidence file");
        return file;
    }
    private static void validateReferences(JsonNode node, Path root) throws IOException {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (Set.of("evidence_index_ref", "suite_summary_ref", "result_ref", "file_path").contains(entry.getKey()))
                    relative(root, entry.getValue().asText());
                else if (Set.of("evidence_refs", "provider_evidence_refs").contains(entry.getKey())) {
                    require(entry.getValue().isArray(), "Evidence references must be arrays");
                    for (JsonNode ref : entry.getValue()) { require(ref.isTextual(), "Evidence reference must be textual"); relative(root, ref.asText()); }
                } else validateReferences(entry.getValue(), root);
            }
        } else if (node.isArray()) for (JsonNode child : node) validateReferences(child, root);
    }
    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) if (value.isTextual() && value.asText().equals(expected)) return true;
        return false;
    }
    private static boolean containsFile(JsonNode entries, String expected) {
        for (JsonNode entry : entries) if (entry.path("file_path").asText().equals(expected)) return true;
        return false;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
