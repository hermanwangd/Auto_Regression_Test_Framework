package com.featuredeliveryintelligence.fdi.producttest.preparation;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit root-provisioned PUBLIC_SYNTHETIC adoption probe; not a SUT/native agent test. */
public final class AcceptedD2ProducerClientProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=9)throw new IllegalArgumentException("PROTECTED_PUBLIC_FIXTURE_ARGUMENTS_REQUIRED");
        Path java=Path.of(args[0]),jar=Path.of(args[1]),lock=Path.of(args[4]),roots=Path.of(args[6]),out=Path.of(args[8]);Files.createDirectories(out);
        var fixture=D2SourceBindingsClientTest.wire();var policy=D2SourceBindingsClientTest.publicRefs(fixture);
        var results=new ArrayList<Map<String,Object>>();
        for(String kind:List.of("wrong_jar","wrong_schema","valid")) {
            var binding=new D2ProducerBinding(java,jar,kind.equals("wrong_jar")?"0".repeat(64):args[2],kind.equals("wrong_schema")?"0".repeat(64):args[3],lock,args[5],roots,args[7],out,Duration.ofSeconds(120),policy);
            if(!kind.equals("valid")){
                try {new D2SourceBindingsClient(binding).acquire();throw new AssertionError("WRONG_EXTERNAL_PIN_ADOPTED");}
                catch(PreparationContracts.Blocked rejected){if(rejected.reason!=ContextAcquisition.Reason.PRODUCER_PIN_MISMATCH)throw rejected;results.add(Map.of("case",kind,"source_adoption","BLOCKED","reason",rejected.reason.name()));}
            } else {
                var admitted=new D2SourceBindingsClient(binding).acquire();var metadata=admitted.metadata();
                if(!admitted.currentRepositoryRevisions().get("https://example.invalid/public-synthetic/main-product").equals("2e8d037fcb7da9b94a1d07b4d67cb61923a2bd04"))throw new AssertionError("COMPOSITION_X_LOST");
                if(!metadata.path("lock").path("artifact_source_testrepo_version").asText().equals("ce5272ebe156ecc1acba8723b1366d0f91756e22"))throw new AssertionError("ARTIFACT_A_LOST");
                if(!admitted.executionBindingState().equals("NOT_EVALUATED"))throw new AssertionError("SOURCE_ADMISSION_PROMOTED_TO_EXECUTION");
                var first=metadata.path("artifacts").get(0);String id=first.path("artifact_id").asText(),sha=first.path("sha256").asText();((com.fasterxml.jackson.databind.node.ObjectNode)admitted.artifact(id)).put("sha256","e".repeat(64));
                if(!admitted.artifact(id).path("sha256").asText().equals(sha))throw new AssertionError("ADMITTED_VALUE_MUTABLE");
                Files.write(out.resolve("admitted-source-bindings.json"),admitted.metadataBytes());
                results.add(Map.of("case",kind,"source_adoption","ADMITTED_FROM_PINNED_PRODUCER","metadata_sha256",admitted.metadataSha256(),"producer_jar_sha256",admitted.producerPins().jarSha256(),"producer_schema_sha256",admitted.producerPins().schemaSha256(),"execution_binding_state","NOT_EVALUATED","framework_provider_dispatch","NOT_INVOKED"));
            }
        }
        Files.write(out.resolve("accepted-producer-consumer-results.json"),PreparationContracts.bytes(Map.of("cases",results,"classification","PUBLIC_SYNTHETIC_SOURCE_CONTRACT_ONLY","live_cognee","NOT_RUN","native_swarm","NOT_RUN","SUT_execution","NOT_RUN"),65536));
        System.out.println("Pinned accepted D2 producer consumer: 3 cases; SUT execution NOT_RUN");
    }
}
