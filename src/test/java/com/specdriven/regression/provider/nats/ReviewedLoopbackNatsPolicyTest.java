package com.specdriven.regression.provider.nats;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedLoopbackNatsPolicyTest {
    @TempDir Path temporary;
    static final String SUBJECT="sit."+"a".repeat(32)+".echo";
    @Test void acceptsOnlyTheFiniteLiteralPublishScope() {
        ReviewedLoopbackNatsPolicy.validateBindings(Map.of("connection.uri","nats://127.0.0.1:65535","subject",SUBJECT));
        ReviewedLoopbackNatsPolicy.validateInputs(Map.of("payload_ref",ReviewedLoopbackNatsPolicy.PAYLOAD_REF,"timeout","PT0.001S"));
        ReviewedLoopbackNatsPolicy.validateInputs(Map.of("payload_ref",ReviewedLoopbackNatsPolicy.PAYLOAD_REF,"timeout","PT5S"));
    }
    @ParameterizedTest @ValueSource(strings={"nats://localhost:4222","nats://192.0.2.1:4222","nats://user@127.0.0.1:4222","nats://127.0.0.1:0","nats://127.0.0.1:65536","nats://127.0.0.1:4222/","nats://127.0.0.1:4222?x=1","nats://127.0.0.1:4222#x","env://NATS_CONNECTION"})
    void rejectsNonliteralOrNonloopbackEndpoints(String uri) {
        assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateBindings(Map.of("connection.uri",uri,"subject",SUBJECT))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"sit.*.echo","sit.>.echo","orders.ready","sit."+"a"+".echo","sit.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.echo\r\nPING"})
    void rejectsUnownedOrInjectedSubjects(String subject) {
        assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateBindings(Map.of("connection.uri","nats://127.0.0.1:4222","subject",subject))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"PT0S","PT6S","PT-1S","bad","PT0.0001S"})
    void rejectsUnboundedTimeouts(String timeout) {
        assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateInputs(Map.of("payload_ref",ReviewedLoopbackNatsPolicy.PAYLOAD_REF,"timeout",timeout))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsOverridesAndDynamicPayloads() {
        for(var inputs:List.of(Map.of("payload_ref","env://PAYLOAD"),Map.of("payload_ref",ReviewedLoopbackNatsPolicy.PAYLOAD_REF,"subject","other"),Map.of("payload_ref",ReviewedLoopbackNatsPolicy.PAYLOAD_REF,"payload","unreviewed")))
            assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateInputs(inputs)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fixtureMustBeBoundedStrictJsonAndContainNoSymlinks() throws Exception {
        Files.createDirectory(temporary.resolve("fixtures")); Path fixture=temporary.resolve("fixtures/payload.json");
        Files.writeString(fixture,"{\"id\":\"reviewed\"}");
        ReviewedLoopbackNatsPolicy.validateFixture(temporary.toRealPath());
        for(String invalid:List.of(" ","null","[]","{\"x\":1,\"x\":2}","{} {}"," ".repeat(65537))) {
            Files.writeString(fixture,invalid);
            assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateFixture(temporary.toRealPath())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reviewed_nats");
        }
        Files.write(fixture,new byte[]{(byte)0xc3,(byte)0x28});
        assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateFixture(temporary.toRealPath())).isInstanceOf(IllegalArgumentException.class);
        Files.delete(fixture);Path outside=Files.writeString(temporary.resolve("outside.json"),"{}");Files.createSymbolicLink(fixture,outside);
        assertThatThrownBy(()->ReviewedLoopbackNatsPolicy.validateFixture(temporary.toRealPath())).isInstanceOf(IllegalArgumentException.class);
    }
}
