package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GrpcDescriptorAdmissionProofTest {
    @Test void exactNonnetworkFailureIsAcceptedAndThirtyOneCorruptionsAreRejected()throws Exception {
        JsonNode proof=Json.MAPPER.readTree("""
            {"schema":"reviewed-grpc-descriptor-rejection.v1","evidence_type":"grpc_request_response",
             "provider_type":"grpc_descriptor_admission","provider_id":"betterproto","operation":"unary_call",
             "run_id":"RUN-unit-proof","test_case_id":"TC-P04","step_id":"reject_streaming",
             "source_revision":"a3f7fd0c1fd788871c460088446099700f6679e4",
             "source_sha256":"9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21",
             "descriptor_sha256":"e2a26040ccdbb5edf9c250074ab3fdb5e4188cdd8a868e1752a0bd4c6165ad0f",
             "request_sha256":"1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471",
             "service":"service.Test","method":"GetThingVersions","input_type":"service.GetThingRequest","output_type":"service.GetThingResponse",
             "client_streaming":false,"server_streaming":true,"status":"UNKNOWN","operation_status":"failed","failure_code":"OPERATION_FAILED",
             "diagnostic":"gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method.",
             "rpc_attempted":false,"channel_created":false,"cleanup_confirmed":true,"descriptor_generation":"reviewed_descriptor_assembly","compiler":null}
            """);
        JsonNode result=Json.MAPPER.readTree("""
            {"status":"failed","run_id":"RUN-unit-proof",
             "step_results":[{"id":"reject_streaming","test_case_id":"TC-P04","target":"betterproto","status":"failed","failure_code":"OPERATION_FAILED","outputs":{"response.status":"UNKNOWN"}}],
             "provider_results":[{"target":"betterproto","status":"failed","provider_contract":"grpc_descriptor_admission.v0.3","provider_type":"grpc_descriptor_admission"}],
             "failure":{"code":"OPERATION_FAILED","reason":"gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method."}}
            """);
        JsonNode handoff=Json.MAPPER.readTree("""
            {"knowledge_mode":"not_used","repository_revisions":{"python-betterproto":"a3f7fd0c1fd788871c460088446099700f6679e4"},
             "reviewed_files":[
              {"path":"fixtures/service.proto","role":"fixture","sha256":"9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21"},
              {"path":"fixtures/service.desc.b64","role":"fixture","sha256":"81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8"},
              {"path":"fixtures/request.json","role":"fixture","sha256":"1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471"}]}
            """);
        assertEquals(32,GrpcDescriptorAdmissionProofProbe.check(proof,result,handoff));
    }
}
