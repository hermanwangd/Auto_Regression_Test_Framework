package com.featuredeliveryintelligence.fdi.producttest.preparation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PreparationSerializationTest {
    @Test void outputLimitAbortsLazySerializationBeforeWholeInputIsMaterialized() {
        var input=new GeneratedValues();
        assertThatThrownBy(()->PreparationContracts.bytes(input,64)).hasMessage("OUTPUT_LIMIT");
        assertThat(input.emitted.get()).isLessThan(10000);
    }
    @Test void utf16AutoDetectionCannotBypassTheUtf8MetadataContract() {
        byte[] utf16="{\"private_context\":\"not admitted\"}".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        assertThatThrownBy(()->PreparationContracts.read(utf16,4096)).hasMessage("INVALID_INPUT");
    }
    @JsonSerialize(using=GeneratedSerializer.class)
    static final class GeneratedValues { final AtomicInteger emitted=new AtomicInteger(); }
    static final class GeneratedSerializer extends JsonSerializer<GeneratedValues> {
        @Override public void serialize(GeneratedValues input,JsonGenerator out,SerializerProvider provider) throws IOException {
            out.writeStartArray();
            for(int i=0;i<50000;i++) { input.emitted.incrementAndGet();out.writeString("public-value"); }
            out.writeEndArray();
        }
    }
}
