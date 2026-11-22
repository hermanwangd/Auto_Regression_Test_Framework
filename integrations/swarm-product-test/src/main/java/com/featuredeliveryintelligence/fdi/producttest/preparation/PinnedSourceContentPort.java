package com.featuredeliveryintelligence.fdi.producttest.preparation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;

/** Inject an already authorized exact local content binding; this port never grants retrieval authority. */
@FunctionalInterface
public interface PinnedSourceContentPort {
    Content resolve(String sourceRef);

    @JsonSerialize(using = Content.PrivateSerializer.class)
    final class Content {
        private final String qualifiedRef, sourceRef, ownerRef;
        private final byte[] bytes;
        public Content(String qualifiedRef, String sourceRef, String ownerRef, byte[] bytes) {
            this.qualifiedRef=PreparationContracts.qualified(qualifiedRef); this.sourceRef=PreparationContracts.ref(sourceRef);
            this.ownerRef=PreparationContracts.owner(ownerRef);
            PreparationContracts.require(bytes != null && bytes.length > 0 && bytes.length <= 65536, ContextAcquisition.Reason.OUTPUT_LIMIT);
            try { java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)); }
            catch(java.nio.charset.CharacterCodingException invalid) { throw PreparationContracts.fail(ContextAcquisition.Reason.INVALID_INPUT); }
            this.bytes=bytes.clone();
        }
        public String qualifiedRef() { return qualifiedRef; }
        public String sourceRef() { return sourceRef; }
        public String ownerRef() { return ownerRef; }
        public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "SourceContent[private]"; }
        public static final class PrivateSerializer extends JsonSerializer<Content> {
            @Override public void serialize(Content value, JsonGenerator out, SerializerProvider provider) throws IOException { throw new IOException("PRIVATE_CONTEXT_SERIALIZATION_FORBIDDEN"); }
        }
    }
}
