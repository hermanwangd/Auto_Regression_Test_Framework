package com.specdriven.regression.provider.jdbc;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** One operation's explicitly injected state; no global secret, environment value or context map. */
public final class ReviewedJdbcInvocation {
    private final JdbcCredentialSource source;
    private final JdbcCredentialSource.CredentialRequest request;
    private final JdbcDeadline deadline;
    private final Path driverPath;
    private final AtomicBoolean consumed=new AtomicBoolean();
    ReviewedJdbcInvocation(Path driverPath,JdbcCredentialSource source,JdbcCredentialSource.CredentialRequest request,long started) {
        this.driverPath=java.util.Objects.requireNonNull(driverPath);this.source=java.util.Objects.requireNonNull(source);this.request=java.util.Objects.requireNonNull(request);this.deadline=new JdbcDeadline(started);
    }
    public static ReviewedJdbcInvocation fromProtectedChild(com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement pin,long started) {
        String id=System.getProperty("swarm.jdbc.invocation_id"),path=System.getProperty("swarm.jdbc.driver_path");
        if(id==null||path==null||!java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Dswarm.jdbc.invocation_id="+id)||!java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Dswarm.jdbc.driver_path="+path))throw new IllegalArgumentException("reviewed_mariadb: protected RAM carrier binding missing");
        var request=new JdbcCredentialSource.CredentialRequest(id,pin.expectedSha256(),pin.fixtureLeaseSha256());
        return new ReviewedJdbcInvocation(Path.of(path),(expected,deadline)->JdbcCredentialFrame.read(System.in,expected.invocationId(),expected.handoffSha256(),expected.leaseSha256(),deadline.startedNanos()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)),request,started);
    }
    public JdbcCredentialLease consume()throws Exception {
        if(!consumed.compareAndSet(false,true))throw new IllegalArgumentException("reviewed_mariadb: credential invocation replay");
        deadline.remainingNanos();return source.open(request,deadline);
    }
    boolean consumed(){return consumed.get();}
    public JdbcDeadline deadline(){return deadline;}
    public Path driverPath(){return driverPath;}
    public JdbcCredentialSource.CredentialRequest request(){return request;}
    @Override public String toString(){return "ReviewedJdbcInvocation[protected]";}
}
