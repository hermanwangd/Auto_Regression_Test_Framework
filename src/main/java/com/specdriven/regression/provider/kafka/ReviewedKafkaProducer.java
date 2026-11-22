package com.specdriven.regression.provider.kafka;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

/** One operation deadline; ordinary Kafka producer/consumer behavior remains separate. */
final class ReviewedKafkaProducer {
    interface Factory { Producer<String,String> create(Properties p); }
    record Outcome(boolean attempted,boolean acknowledged,boolean cleanupConfirmed,String status,int partition,long offset){}
    static Properties properties(KafkaProviderRuntime.KafkaConnection c,Duration timeout){
        long ms=Math.max(1,timeout.toMillis());Properties p=new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,c.bootstrapServers());p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG,"all");p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,false);p.put(ProducerConfig.RETRIES_CONFIG,0);p.put(ProducerConfig.LINGER_MS_CONFIG,0);p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,1);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,ms);p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,(int)ms);p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,(int)ms);p.put(ProducerConfig.BUFFER_MEMORY_CONFIG,1048576L);p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG,1048576);return p;
    }
    static Outcome send(Factory factory,KafkaProviderRuntime.KafkaConnection c,String key,String payload,Duration budget,LongSupplier clock){
        long start=clock.getAsLong(),limit=budget.toNanos();Producer<String,String> producer=null;boolean attempted=false,ack=false,clean=false;String status="CLIENT_FAILED";int partition=-1;long offset=-1;
        try{
            producer=factory.create(properties(c,budget));long left=limit-(clock.getAsLong()-start);
            if(left<=0)throw new java.util.concurrent.TimeoutException();
            attempted=true;var future=producer.send(new ProducerRecord<>(c.topic(),key,payload));left=limit-(clock.getAsLong()-start);if(left<=0)throw new java.util.concurrent.TimeoutException();
            var metadata=future.get(left,TimeUnit.NANOSECONDS);ack=true;partition=metadata.partition();offset=metadata.offset();status="ACK";
            if(limit-(clock.getAsLong()-start)<=0)status="DEADLINE_EXCEEDED";
        }catch(java.util.concurrent.TimeoutException e){status="DEADLINE_EXCEEDED";}
        catch(InterruptedException e){Thread.currentThread().interrupt();status="INTERRUPTED";}
        catch(Exception e){status="CLIENT_FAILED";}
        finally{if(producer!=null){long left=Math.max(0,limit-(clock.getAsLong()-start));try{producer.close(Duration.ofNanos(Math.min(left,Duration.ofSeconds(2).toNanos())));clean=true;}catch(Exception e){status="CLEANUP_UNCERTAIN";}if(limit-(clock.getAsLong()-start)<0&&status.equals("ACK"))status="DEADLINE_EXCEEDED";}}
        return new Outcome(attempted,ack,clean,status,partition,offset);
    }
}
