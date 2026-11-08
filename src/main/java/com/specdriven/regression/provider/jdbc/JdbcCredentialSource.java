package com.specdriven.regression.provider.jdbc;

/** A trusted deployment dependency; never deserialize an implementation from a request. */
@FunctionalInterface
public interface JdbcCredentialSource {
    JdbcCredentialLease open(CredentialRequest request,JdbcDeadline deadline)throws Exception;
    record CredentialRequest(String invocationId,String handoffSha256,String leaseSha256) {
        public CredentialRequest {
            if(invocationId==null||!invocationId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||handoffSha256==null||!handoffSha256.matches("[a-f0-9]{64}")||leaseSha256==null||!leaseSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("reviewed_mariadb: invocation identity invalid");
        }
    }
}
