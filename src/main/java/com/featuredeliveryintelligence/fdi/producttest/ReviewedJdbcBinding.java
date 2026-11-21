package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/** Operator provisioned local-owner guard and RAM source. Never deserialize either from mission input. */
public record ReviewedJdbcBinding(Path driverPath,String driverSha256,String fixtureLeaseSha256,
        OwnedFixtureGuard ownerGuard,JdbcCredentialSource credentials) {
    public ReviewedJdbcBinding {
        if(driverPath==null||driverSha256==null||!driverSha256.equals("85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706")||fixtureLeaseSha256==null||!fixtureLeaseSha256.matches("[a-f0-9]{64}")||ownerGuard==null||credentials==null)throw new IllegalArgumentException("Trusted finite JDBC deployment binding required");
    }
    /** The embedding operator must supply an actual anonymous stdin/pipe and a nonsecret challenge sink. */
    public static JdbcCredentialSource anonymousPipe(java.io.InputStream input,java.util.function.Consumer<CredentialRequest> challenge) {
        java.util.Objects.requireNonNull(input);java.util.Objects.requireNonNull(challenge);
        return request->{challenge.accept(request);return JdbcCredentialFrame.read(input,request.invocationId(),request.handoffSha256(),request.leaseSha256(),System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2));};
    }
    @FunctionalInterface public interface OwnedFixtureGuard {void verify(JsonNode frozenLease,CredentialRequest invocation)throws Exception;}
    @FunctionalInterface public interface JdbcCredentialSource {JdbcCredentialLease open(CredentialRequest request)throws Exception;}
    public record CredentialRequest(String invocationId,String handoffSha256,String leaseSha256) {}
}
