package com.featuredeliveryintelligence.fdi.producttest;

import java.util.Arrays;

/** Nonserializable owned RAM. Callers own and must clear any returned copy. */
public final class JdbcCredentialLease implements AutoCloseable {
    private final char[] username, password;
    private boolean closed;
    public JdbcCredentialLease(char[] username, char[] password) {
        if (!valid(username,1,64) || !valid(password,32,128))
            throw new IllegalArgumentException("reviewed_mariadb: invalid credential frame");
        this.username = username.clone(); this.password = password.clone();
    }
    private static boolean valid(char[] chars,int min,int max) {
        if(chars==null||chars.length<min||chars.length>max)return false;
        for(char ch:chars)if(ch<33||ch>126)return false;
        return true;
    }
    public synchronized char[] copyUsername() { check(); return username.clone(); }
    public synchronized char[] copyPassword() { check(); return password.clone(); }
    private void check() { if (closed) throw new IllegalStateException("reviewed_mariadb: credential lease closed"); }
    @Override public synchronized void close() { Arrays.fill(username,'\0'); Arrays.fill(password,'\0'); closed=true; }
    @Override public String toString() { return "JdbcCredentialLease[protected]"; }
}
