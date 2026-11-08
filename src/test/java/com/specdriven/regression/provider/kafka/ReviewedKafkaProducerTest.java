package com.specdriven.regression.provider.kafka;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** Factory/clock spies only, no KafkaProducer constructor or broker. */
class ReviewedKafkaProducerTest {
    final KafkaProviderRuntime.KafkaConnection connection=new KafkaProviderRuntime.KafkaConnection("127.0.0.1:19092","sit."+"b".repeat(32)+".publish","sit."+"b".repeat(32)+".group");
    static class ProducerSpy extends MockProducer<String,String> {
        Duration closed;boolean closeFails;
        ProducerSpy(){super(true,new StringSerializer(),new StringSerializer());}
        @Override public void close(Duration d){closed=d;if(closeFails)throw new IllegalStateException("PRIVATE-CANARY");super.close(d);}
    }
    @Test void boundedProducerPropertiesAreInternallyValidAndDoNotEnableRetryOrImplicitIdempotence(){var p=ReviewedKafkaProducer.properties(connection,Duration.ofMillis(20));new ProducerConfig(p);assertThat(p).containsEntry("enable.idempotence",false).containsEntry("retries",0).containsEntry("acks","all").containsEntry("linger.ms",0).containsEntry("max.block.ms",20L).containsEntry("request.timeout.ms",20).containsEntry("delivery.timeout.ms",20);}
    @Test void ackAndExplicitCloseShareOneMonotonicBudget(){var n=new AtomicLong();var spy=new ProducerSpy();var result=ReviewedKafkaProducer.send(p->{n.addAndGet(2_000_000);return spy;},connection,"run."+"b".repeat(32),"{}",Duration.ofMillis(20),n::get);assertThat(result.acknowledged()).isTrue();assertThat(result.cleanupConfirmed()).isTrue();assertThat(result.partition()).isZero();assertThat(result.offset()).isZero();assertThat(spy.closed).isEqualTo(Duration.ofMillis(18));assertThat(spy.history()).hasSize(1);}
    @Test void constructionBudgetExhaustionDoesNotSendAndClosesWithZeroRemaining(){var n=new AtomicLong();var spy=new ProducerSpy();var result=ReviewedKafkaProducer.send(p->{n.set(20_000_000);return spy;},connection,"run."+"b".repeat(32),"{}",Duration.ofMillis(20),n::get);assertThat(result.attempted()).isFalse();assertThat(result.acknowledged()).isFalse();assertThat(result.status()).isEqualTo("DEADLINE_EXCEEDED");assertThat(spy.history()).isEmpty();assertThat(spy.closed).isEqualTo(Duration.ZERO);}
    @Test void constructorFailureNeverInventsAcquisitionOrCleanupConfirmation(){var result=ReviewedKafkaProducer.send(p->{throw new IllegalStateException("PRIVATE-CANARY");},connection,"key","{}",Duration.ofMillis(20),System::nanoTime);assertThat(result.attempted()).isFalse();assertThat(result.cleanupConfirmed()).isFalse();assertThat(result.status()).isEqualTo("CLIENT_FAILED");assertThat(result.toString()).doesNotContain("PRIVATE-CANARY");}
    @Test void ackIsRetainedWhenCloseCannotBeConfirmed(){var spy=new ProducerSpy();spy.closeFails=true;var result=ReviewedKafkaProducer.send(p->spy,connection,"key","{}",Duration.ofMillis(20),System::nanoTime);assertThat(result.attempted()).isTrue();assertThat(result.acknowledged()).isTrue();assertThat(result.cleanupConfirmed()).isFalse();assertThat(result.status()).isEqualTo("CLEANUP_UNCERTAIN");}
    @Test void aBoundedFailedFutureStillClosesOwnedClient(){var spy=new ProducerSpy();spy.sendException=new org.apache.kafka.common.KafkaException("PRIVATE-CANARY");var result=ReviewedKafkaProducer.send(p->spy,connection,"key","{}",Duration.ofMillis(20),System::nanoTime);assertThat(result.attempted()).isTrue();assertThat(result.acknowledged()).isFalse();assertThat(result.cleanupConfirmed()).isTrue();assertThat(result.status()).isEqualTo("CLIENT_FAILED");assertThat(spy.closed).isNotNull();}
}
