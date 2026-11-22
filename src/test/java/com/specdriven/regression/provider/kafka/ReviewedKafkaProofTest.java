package com.specdriven.regression.provider.kafka;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedKafkaProofTest {
    @TempDir Path root;
    JsonNode lease,result;String pin;ObjectNode proof,provenance;
    void fixture()throws Exception{
        pin=ReviewedLoopbackKafkaPolicyTest.make(root);var f=ReviewedLoopbackKafkaPolicy.fixture(root,ReviewedLoopbackKafkaPolicyTest.bindings());lease=ReviewedLoopbackKafkaPolicy.JSON.valueToTree(f.lease());
        var body=ReviewedKafkaPublishProof.create(f,"RUN-1","BATCH-1","publish","todo_api",new ReviewedKafkaProducer.Outcome(true,true,true,"ACK",0,0),Instant.parse("2000-01-01T00:01:00Z"),Instant.parse("2000-01-01T00:01:00Z"));
        proof=(ObjectNode)ReviewedLoopbackKafkaPolicy.parse(ReviewedLoopbackKafkaPolicy.JSON.writeValueAsBytes(body));
        result=ReviewedLoopbackKafkaPolicy.JSON.valueToTree(Map.of("run_id","RUN-1","batch_id","BATCH-1","status","passed","start_time","2000-01-01T00:01:00Z","end_time","2000-01-01T00:01:00Z","provider_results",List.of(Map.of("target","todo_api")),"step_results",List.of(Map.of("id","publish","target","todo_api","outputs",Map.of("partition",0,"offset",0L,"key","run."+ReviewedLoopbackKafkaPolicyTest.OWNER,"topic",ReviewedLoopbackKafkaPolicyTest.TOPIC)))));
        provenance=ReviewedLoopbackKafkaPolicy.JSON.createObjectNode();provenance.set("fixture_lease",lease);provenance.put("fixture_lease_sha256",pin).put("fixture_lease_base64",f.leaseBase64());
    }
    @Test void parsedProofJoinsTypedLongCanonicalOffsetAndHistoricalRawLease()throws Exception{fixture();assertThat(ReviewedKafkaPublishProof.frozenLease(provenance)).isEqualTo(lease);assertThatCode(()->ReviewedKafkaPublishProof.verify(proof,lease,pin,result)).doesNotThrowAnyException();}
    @ParameterizedTest @ValueSource(strings={"run_id","batch_id","step_id","provider_id","operation","fixture_lease_sha256","owner_run_id","endpoint","topic","consumer_group","key","payload_sha256","client_version","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode","status","extra","attempted","acknowledged","cleanup_confirmed","partition","offset"})
    void changedProofCannotBorrowFrozenIdentity(String field)throws Exception{fixture();switch(field){case "extra"->proof.put("raw_private","PRIVATE-CANARY");case "attempted","acknowledged","cleanup_confirmed"->proof.put(field,false);case "partition","offset"->proof.put(field,1);default->proof.put(field,"ALTERED");}assertThatThrownBy(()->ReviewedKafkaPublishProof.verify(proof,lease,pin,result)).isInstanceOf(IllegalArgumentException.class);}
    @Test void leaseObjectCannotChangeWhileKeepingOriginalRawBytesAndPin()throws Exception{fixture();((ObjectNode)provenance.path("fixture_lease")).put("endpoint","127.0.0.1:2");assertThatThrownBy(()->ReviewedKafkaPublishProof.frozenLease(provenance)).hasMessageContaining("frozen_lease_bytes");}
}
