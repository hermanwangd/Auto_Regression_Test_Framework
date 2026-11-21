package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Protected caller configuration, never parsed from proposal/mission or a self-observed producer response. */
@JsonSerialize(using=D2ProducerBinding.PrivateSerializer.class)
public record D2ProducerBinding(Path javaExecutable,Path producerJar,String expectedProducerJarSha256,
        String expectedRuntimeSchemaSha256,Path dependenciesLock,String expectedLockSha256,
        Path trustedRootsBinding,String expectedTrustedRootsSha256,Path outputParent,Duration totalTimeout,
        Set<String> approvedPublicReferences) {
    public D2ProducerBinding {
        for(Path path:List.of(javaExecutable,producerJar,dependenciesLock,trustedRootsBinding,outputParent))
            PreparationContracts.require(path.isAbsolute()&&path.normalize().equals(path),ContextAcquisition.Reason.INVALID_INPUT);
        for(String pin:List.of(expectedProducerJarSha256,expectedRuntimeSchemaSha256,expectedLockSha256,expectedTrustedRootsSha256))PreparationContracts.sha(pin);
        PreparationContracts.require(totalTimeout!=null&&!totalTimeout.isNegative()&&!totalTimeout.isZero()&&totalTimeout.compareTo(Duration.ofSeconds(120))<=0,ContextAcquisition.Reason.INVALID_INPUT);
        PreparationContracts.require(approvedPublicReferences!=null&&approvedPublicReferences.size()<=8192,ContextAcquisition.Reason.OUTPUT_LIMIT);
        approvedPublicReferences=Set.copyOf(approvedPublicReferences);
    }
    @Override public String toString(){return "D2ProducerBinding[protected]";}
    public static final class PrivateSerializer extends JsonSerializer<D2ProducerBinding> {
        @Override public void serialize(D2ProducerBinding value,JsonGenerator out,SerializerProvider provider)throws IOException{throw new IOException("PROTECTED_BINDING_SERIALIZATION_FORBIDDEN");}
    }
}
