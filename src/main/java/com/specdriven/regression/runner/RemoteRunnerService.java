package com.specdriven.regression.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Controller state machine. Native transport authority is deliberately separate from source admission. */
public final class RemoteRunnerService {
    public record Handle(String runtimeId,String ownerNonce,String runId){}
    public record Observation(Integer exitCode,boolean timedOut,String actualRuntimeVersion){}
    public interface Transport {
        /** True only for a separately authorized real transport, never a fake API/local process. */
        boolean nativeTransport();
        Handle launch(JsonNode protectedBinding,Path admittedBundle)throws IOException;
        Observation await(Handle handle,long monotonicDeadlineNanos)throws IOException;
        void stopOwned(Handle handle)throws IOException;
        /** Must retain an alive exporter or workspace after the main worker ends. */
        Path availableEvidence(Handle handle,JsonNode collectionSpec,long deadlineNanos)throws IOException;
        void acknowledgeSaved(Handle handle,String durableReceiptSha256)throws IOException;
        void cleanupOwned(Handle handle)throws IOException;
    }
    public record Result(Path receipt,JsonNode facts){}
    private final Transport transport; private final RunnerDurability durability;
    public RemoteRunnerService(Transport transport){this(transport,RunnerDurability.SYSTEM);}
    RemoteRunnerService(Transport transport,RunnerDurability durability){this.transport=Objects.requireNonNull(transport);this.durability=Objects.requireNonNull(durability);}
    public Result execute(Path bindingFile,String bindingPin,Path bundleFile,Path collectionSpec,Path freshOutput)throws IOException{
        JsonNode binding=RunnerFiles.pinned(bindingFile,bindingPin);RunnerFiles.fields(binding,"schema_version","binding_id","runtime_type","owner_nonce","run_id","correlation_id","evidence_classification","collection_spec_sha256","bundle_sha256","expected_sut_revision","observed_sut_revision","readiness_evidence_sha256","runtime_identity","maximum_run_ms");
        RunnerFiles.require(RunnerFiles.text(binding,"schema_version").equals("runner-runtime-binding.v1"),"runtime_binding_version_unsupported");
        String kind=RunnerFiles.text(binding,"runtime_type");
        // This source/offline phase has no approved native image/context/TLS/secret bindings.
        // Contract tests exercise the real state machine, but cannot dispatch native resources.
        RunnerFiles.require(kind.equals("contract_test")&&!transport.nativeTransport(),"NATIVE_RUNTIME_BINDING_UNBOUND_NO_RESOURCE_AUTHORITY");
        RunnerFiles.require(RunnerFiles.text(binding,"evidence_classification").equals("framework_verification_only"),"contract_transport_cannot_be_release_evidence");
        RunnerFiles.require(RunnerFiles.text(binding,"expected_sut_revision").equals(RunnerFiles.text(binding,"observed_sut_revision")),"SUT_DEPLOYED_REVISION_MISMATCH");RunnerFiles.digest(binding,"readiness_evidence_sha256");
        byte[] bundle=RunnerFiles.read(bundleFile,RunnerFiles.TEXT_LIMIT);RunnerFiles.require(RunnerFiles.sha(bundle).equals(RunnerFiles.digest(binding,"bundle_sha256")),"bundle_artifact_pin_mismatch");
        JsonNode spec=RunnerFiles.pinned(collectionSpec,RunnerFiles.digest(binding,"collection_spec_sha256"));String run=RunnerFiles.text(binding,"run_id"),nonce=RunnerFiles.text(binding,"owner_nonce");
        RunnerFiles.require(run.equals(spec.path("run_id").asText())&&RunnerFiles.text(binding,"correlation_id").equals(spec.path("correlation_id").asText()),"collection_run_or_correlation_mismatch");
        RunnerFiles.require(binding.path("maximum_run_ms").isIntegralNumber()&&binding.path("maximum_run_ms").asLong()>=1&&binding.path("maximum_run_ms").asLong()<=300000,"bounded_run_deadline_required");
        RunnerFiles.fresh(freshOutput,durability);ObjectNode facts=RunnerFiles.object();facts.put("schema_version","runner-controller-receipt.v1");facts.put("run_id",run);facts.put("correlation_id",spec.path("correlation_id").asText());facts.put("binding_sha256",bindingPin);facts.put("runtime_acceptance","CONTRACT_ONLY_NOT_NATIVE");facts.put("evidence_classification","framework_verification_only");facts.put("framework_verdict","UNKNOWN");facts.put("collection","MISSING");facts.put("publication","NOT_RUN");facts.put("cleanup","NOT_ATTEMPTED");facts.putNull("runner_exit_code");var events=facts.putArray("ordered_operations");
        Handle owned=null;Observation observation=null;boolean saved=false,stopConfirmed=true;String savedHash=null;long deadline=System.nanoTime()+binding.path("maximum_run_ms").asLong()*1_000_000;
        try{
            events.add("launch");Handle proposed=transport.launch(binding,bundleFile);
            RunnerFiles.require(proposed!=null&&nonce.equals(proposed.ownerNonce())&&run.equals(proposed.runId())&&!proposed.runtimeId().isBlank(),"runtime_owner_identity_mismatch_NO_FOREIGN_CLEANUP");owned=proposed;facts.put("runtime_id",owned.runtimeId());
            boolean requestStop=false;facts.put("stop_status","NOT_REQUESTED");
            try{
                events.add("supervise");observation=transport.await(owned,deadline);RunnerFiles.require(observation!=null,"missing_runtime_observation");
                boolean timeout=observation.timedOut()||System.nanoTime()>=deadline;facts.put("execution",timeout?"TIMED_OUT":"EXITED");
                if(observation.exitCode()!=null)facts.put("runner_exit_code",observation.exitCode());facts.put("actual_runtime_version",observation.actualRuntimeVersion());requestStop=timeout;
            }catch(IOException|RuntimeException failed){facts.put("execution","SUPERVISION_FAILED");facts.put("diagnostic","bounded supervision failed; collect available owned evidence");requestStop=true;}
            if(requestStop){
                events.add("stop_owned_execution_preserve_exporter");
                try{transport.stopOwned(owned);facts.put("stop_status","STOPPED");}
                catch(IOException|RuntimeException stopFailure){stopConfirmed=false;facts.put("stop_status","FAILED");facts.put("stop_diagnostic","owned stop unconfirmed; still collect available evidence and retain workspace");}
            }
            try{
                events.add("collect_available");Path available=transport.availableEvidence(owned,spec,System.nanoTime()+30_000_000_000L);
                var collected=new BoundedEvidenceCollector(durability).collect(collectionSpec,binding.path("collection_spec_sha256").asText(),available,freshOutput.resolve("collected"));
                facts.put("collection",collected.document().path("collection").asText());facts.put("publication",collected.document().path("publication").asText());facts.put("framework_verdict",collected.document().path("framework_verdict").asText());
                saved=collected.complete();String verdict=facts.path("framework_verdict").asText();
                if(observation!=null&&observation.exitCode()!=null&&!verdict.equals("UNKNOWN")&&((observation.exitCode()==0)!=verdict.equals("passed"))){saved=false;facts.put("exit_verdict_consistency","INVALID");}
                if(saved){events.add("verify_durable_SAVE");savedHash=RunnerFiles.sha(RunnerFiles.read(collected.receipt(),RunnerFiles.TEXT_LIMIT));RunnerFiles.require(Files.isRegularFile(freshOutput.resolve("collected/SAVE.complete"),LinkOption.NOFOLLOW_LINKS),"missing_collection_SAVE_marker");}
            }catch(IOException|RuntimeException failed){facts.put("collection","PARTIAL");facts.put("publication","FAILED");facts.put("diagnostic","collection or SAVE failed; evidence workspace must be retained");saved=false;}
            if(saved&&stopConfirmed){
                events.add("save_controller_receipt_before_ack");RunnerFiles.save(freshOutput,"controller-SAVE.json",RunnerFiles.bytes(facts),durability);durability.forceDirectory(freshOutput);
                events.add("acknowledge_receiver_saved");transport.acknowledgeSaved(owned,savedHash);
                events.add("cleanup_owned_after_SAVE");try{transport.cleanupOwned(owned);facts.put("cleanup","CLEAN");}catch(IOException|RuntimeException cleanupFailure){facts.put("cleanup","FAILED");facts.put("cleanup_diagnostic","owned resource remains; retain owner identity and saved evidence");}
            }else facts.put("cleanup",stopConfirmed?"NOT_ATTEMPTED_UNSAVED_OR_INCOMPLETE":"NOT_ATTEMPTED_STOP_UNCONFIRMED");
        }catch(IOException|RuntimeException failed){if(!facts.has("execution"))facts.put("execution",owned==null?"LAUNCH_FAILED_OR_IDENTITY_BLOCKED":"FAILED");facts.put("controller_failure","FAILED");facts.put("cleanup","NOT_ATTEMPTED_UNSAVED_OR_UNOWNED");facts.put("diagnostic","launch/identity/ACK/SAVE failed; no foreign or unsaved cleanup");}
        facts.put("acceptance","OFFLINE_CONTRACT_EVIDENCE_ONLY_NO_NATIVE_OR_PRODUCT_RELEASE");RunnerFiles.save(freshOutput,"execution.json",RunnerFiles.bytes(facts),durability);return new Result(freshOutput.resolve("execution.json"),facts.deepCopy());
    }
}
