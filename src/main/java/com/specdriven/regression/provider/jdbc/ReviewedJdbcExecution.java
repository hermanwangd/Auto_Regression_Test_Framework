package com.specdriven.regression.provider.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.provider.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One bounded readonly operation, always inside an externally owned short-lived run child. */
final class ReviewedJdbcExecution {
    interface DriverHandle extends AutoCloseable {Driver driver();}
    interface DriverFactory {default void admit(Path path)throws Exception{PinnedMariaDbDriver.admit(path);}DriverHandle open(Path path)throws Exception;}
    interface FixtureVerifier {ReviewedOwnedMariaDbPolicy.Fixture verify(Path root,Map<String,?> bindings);}
    private final DriverFactory drivers;
    private final FixtureVerifier fixtures;
    ReviewedJdbcExecution(DriverFactory drivers,FixtureVerifier fixtures){this.drivers=drivers;this.fixtures=fixtures;}
    static ReviewedJdbcExecution protectedChild() {
        return new ReviewedJdbcExecution(path->{var loaded=PinnedMariaDbDriver.open(path);return new DriverHandle(){public Driver driver(){return loaded.driver();}public void close()throws Exception{loaded.close();}};},(root,binding)->ReviewedOwnedMariaDbPolicy.fixture(root,binding,true));
    }
    ProviderOperationResult execute(ProviderExecutionContext context,ProviderOperationRequest request,ReviewedJdbcInvocation invocation) {
        var bindings=runtimeBindings(context.bindingValues());
        ReviewedOwnedMariaDbPolicy.require("native".equals(context.runtimeMode())&&"db_query".equals(request.operation())&&request.parameters().size()==1&&"query_ref".equals(request.parameters().get(0).get("bind_as"))&&ReviewedOwnedMariaDbPolicy.QUERY_PATH.equals(request.parameters().get(0).get("ref")),"only fixed reviewed db_query is supported");
        if(invocation==null)throw new IllegalArgumentException("reviewed_mariadb: protected RAM carrier binding missing");
        if(invocation.consumed())return ProviderOperationResult.blocked(Map.of(),List.of(),ProviderFailure.of("CREDENTIAL_REJECTED","DB_QUERY_FAILED","Reviewed JDBC invocation replay rejected","Use a fresh independently bound invocation."));
        JdbcDeadline deadline=invocation.deadline();java.time.Instant operationStartedAt=java.time.Instant.now().minusNanos(System.nanoTime()-deadline.startedNanos());var state=new Observation();var resources=new Resources();
        var deadlineExceeded=new AtomicBoolean();var body=new AtomicReference<>("");var normalized=new AtomicReference<Map<String,Object>>(Map.of());
        FutureTask<Void> work=new FutureTask<>(()->{
            Properties properties=new Properties();
            try {
                deadline.remainingNanos();var fixture=fixtures.verify(context.suiteRoot(),bindings);pin(fixture,invocation);state.admittedAt=java.time.Instant.now().toString();
                drivers.admit(invocation.driverPath());deadline.remainingNanos();state.stage="SOURCE";
                JdbcCredentialLease secret=invocation.consume();if(secret==null)throw new IllegalArgumentException("credential rejected");resources.hold(secret);resources.remember(secret);
                deadline.remainingNanos();state.stage="DRIVER";DriverHandle loaded=drivers.open(invocation.driverPath());resources.hold(loaded);deadline.remainingNanos();
                pin(fixtures.verify(context.suiteRoot(),bindings),invocation);
                char[] user=secret.copyUsername(),password=secret.copyPassword();
                try {properties.setProperty("user",new String(user));properties.setProperty("password",new String(password));}
                finally{Arrays.fill(user,'\0');Arrays.fill(password,'\0');}
                int millis=(int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline.cappedNanos(TimeUnit.SECONDS.toNanos(2))));
                properties.setProperty("connectTimeout",Integer.toString(millis));properties.setProperty("socketTimeout",Integer.toString(millis));
                String url="jdbc:mariadb://"+bindings.get("endpoint")+"/swarm_probe";
                state.stage="CONNECT";deadline.remainingNanos();state.attempted=true;Connection connection=loaded.driver().connect(url,properties);
                if(connection==null)throw new SQLException("", "08000",0);resources.hold(connection);state.connected=true;deadline.remainingNanos();
                connection.setReadOnly(true);state.stage="QUERY";PreparedStatement statement=connection.prepareStatement(ReviewedOwnedMariaDbPolicy.SQL);resources.hold(statement);
                statement.setMaxRows(3);statement.setQueryTimeout((int)Math.max(1,Math.min(2,(deadline.remainingNanos()+999_999_999L)/1_000_000_000L)));
                deadline.remainingNanos();state.sql=true;ResultSet result=statement.executeQuery();resources.hold(result);deadline.remainingNanos();state.stage="RESULT";
                Map<String,Object> payload=ReviewedJdbcResult.read(result,resources::scan);String text=new ObjectMapper().writeValueAsString(payload);resources.scan(text);deadline.remainingNanos();body.set(text);normalized.set(payload);state.status="OK";state.stage="COMPLETE";
            } catch(SQLException e) {
                state.sqlState=e.getSQLState()!=null&&e.getSQLState().matches("[A-Z0-9]{5}")?e.getSQLState():"";state.vendor=e.getErrorCode();state.status=state.connected?"QUERY_FAILED":"CONNECT_FAILED";
            } catch(Exception e) {
                String message=e.getMessage();
                if(message!=null&&message.contains("deadline"))state.status="DEADLINE_EXCEEDED";
                else if(message!=null&&message.contains("RESULT_LIMIT"))state.status="RESULT_LIMIT";
                else if(message!=null&&message.contains("RESULT_TYPE"))state.status="RESULT_TYPE";
                else if(message!=null&&message.contains("SECRET_OUTPUT_BLOCKED"))state.status="SECRET_OUTPUT_BLOCKED";
                else state.status=state.stage.equals("SOURCE")?"CREDENTIAL_REJECTED":state.stage.equals("DRIVER")?"DRIVER_INVALID":"OPERATION_FAILED";
            } finally {properties.clear();}
            return null;
        });
        Thread worker=new Thread(work,"reviewed-jdbc-operation");worker.setDaemon(true);
        try {
            long remaining=deadline.remainingNanos();worker.start();work.get(remaining,TimeUnit.NANOSECONDS);
        } catch(TimeoutException e){deadlineExceeded.set(true);state.status="DEADLINE_EXCEEDED";work.cancel(true);}
        catch(InterruptedException e){deadlineExceeded.set(true);Thread.currentThread().interrupt();state.status="DEADLINE_EXCEEDED";work.cancel(true);}
        catch(Exception e){deadlineExceeded.set(true);state.status="DEADLINE_EXCEEDED";work.cancel(true);}
        long cleanupStarted=System.nanoTime(),operationMillis=TimeUnit.NANOSECONDS.toMillis(cleanupStarted-deadline.startedNanos());
        boolean cleanup=cleanup(resources,worker,deadline.cleanupDeadlineNanos(cleanupStarted));
        long end=System.nanoTime(),cleanupMillis=TimeUnit.NANOSECONDS.toMillis(end-cleanupStarted),totalMillis=TimeUnit.NANOSECONDS.toMillis(end-deadline.startedNanos());
        if(!cleanup||end>deadline.cleanupDeadlineNanos(cleanupStarted)){state.status="CLEANUP_UNCONFIRMED";state.stage="CLEANUP";cleanup=false;body.set("");}
        if(cleanup&&(operationMillis>5000||deadlineExceeded.get())){state.status="DEADLINE_EXCEEDED";body.set("");}
        Map<String,Object> proof=new LinkedHashMap<>();proof.put("evidence_type","jdbc_query");proof.put("operation","db_query");proof.put("provider_type","jdbc");proof.put("provider_id",context.providerId());
        proof.put("fixture_lease_sha256",invocation.request().leaseSha256());proof.put("owner_run_id",bindings.get("owner_run_id"));proof.put("endpoint",bindings.get("endpoint"));proof.put("schema","swarm_probe");proof.put("profile",ReviewedOwnedMariaDbPolicy.PROFILE);proof.put("query_sha256",ReviewedJdbcProof.querySha());proof.put("driver_sha256",ReviewedOwnedMariaDbPolicy.DRIVER_SHA);
        proof.put("driver_connect_attempted",state.attempted);proof.put("connected",state.connected);proof.put("sql_attempted",state.sql);proof.put("cleanup_confirmed",cleanup);proof.put("status",state.status);proof.put("stage",state.stage);proof.put("sql_state",state.sqlState);proof.put("vendor_code",state.vendor);proof.put("result",state.status.equals("OK")?body.get():"");
        proof.put("invocation_id",invocation.request().invocationId());proof.put("process_pid",ProcessHandle.current().pid());proof.put("process_start_instant",ProcessHandle.current().info().startInstant().orElseThrow().toString());proof.put("operation_ms",operationMillis);proof.put("cleanup_ms",cleanupMillis);proof.put("total_ms",totalMillis);proof.put("operation_started_at",operationStartedAt.toString());proof.put("operation_ended_at",operationStartedAt.plusNanos(end-deadline.startedNanos()).toString());proof.put("fixture_admitted_at",state.admittedAt);
        try {
            String encoded=new ObjectMapper().writeValueAsString(proof);resources.scan(encoded);if(encoded.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("JDBC evidence exceeds bound");
            Path target=context.runDir().resolve(ReviewedJdbcProof.PATH);Files.createDirectories(target.getParent());Files.writeString(target,encoded,StandardOpenOption.CREATE_NEW);
            var evidence=List.of(new ProviderEvidence("jdbc_query",ReviewedJdbcProof.PATH,true));
            if(state.status.equals("OK"))return ProviderOperationResult.passed(Map.of("result",body.get(),"row_count",normalized.get().get("row_count"),"duration_ms",totalMillis,"query_evidence_ref",ReviewedJdbcProof.PATH),evidence);
            return ProviderOperationResult.failed(Map.of("duration_ms",totalMillis,"query_evidence_ref",ReviewedJdbcProof.PATH),evidence,ProviderFailure.of(state.status,state.status.equals("DEADLINE_EXCEEDED")?"PROVIDER_TIMEOUT":"DB_QUERY_FAILED","Reviewed JDBC operation failed at "+state.stage+" ("+state.status+")","Inspect typed bounded JDBC evidence; no automatic retry."));
        } catch(Exception e){throw new IllegalStateException("reviewed_mariadb: protected evidence publication failed");}
        finally {resources.clearSecrets();}
    }
    private static void pin(ReviewedOwnedMariaDbPolicy.Fixture fixture,ReviewedJdbcInvocation invocation){ReviewedOwnedMariaDbPolicy.require(invocation.request().leaseSha256().equals(fixture.leaseSha256()),"independent fixture lease mismatch");}
    private static Map<String,?> runtimeBindings(Map<String,Object> values) {
        Map<String,Object> result=new LinkedHashMap<>(values);Object nested=result.remove("connection");
        if(nested!=null)ReviewedOwnedMariaDbPolicy.require(Map.of("secret_ref",ReviewedOwnedMariaDbPolicy.SECRET_REF).equals(nested),"ambiguous connection binding");
        ReviewedOwnedMariaDbPolicy.validateBindings(result);return Map.copyOf(result);
    }
    private static boolean cleanup(Resources resources,Thread worker,long end) {
        boolean interrupted=Thread.interrupted();var task=new FutureTask<Boolean>(resources::closeAll);Thread closer=new Thread(task,"reviewed-jdbc-cleanup");closer.setDaemon(true);closer.start();
        try {
            long remaining=end-System.nanoTime();if(remaining<=0)return false;boolean closed=task.get(remaining,TimeUnit.NANOSECONDS);
            remaining=end-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.timedJoin(worker,remaining);
            remaining=end-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.timedJoin(closer,remaining);
            return closed&&!worker.isAlive()&&!closer.isAlive()&&!resources.closeFailed.get();
        } catch(Exception e){task.cancel(true);if(e instanceof InterruptedException)interrupted=true;return false;}
        finally {if(interrupted)Thread.currentThread().interrupt();}
    }
    private static final class Observation {volatile String stage="PREFLIGHT",status="OPERATION_FAILED",sqlState="",admittedAt="";volatile int vendor;volatile boolean attempted,connected,sql;}
    private static final class Resources {
        private final List<AutoCloseable> handles=new ArrayList<>();private final List<char[]> sentinels=new ArrayList<>();private final AtomicBoolean closeFailed=new AtomicBoolean();private boolean closing;
        void hold(AutoCloseable value)throws Exception {synchronized(this){if(!closing){handles.add(value);return;}}try{value.close();}catch(Throwable failure){closeFailed.set(true);throw new IllegalStateException("reviewed_mariadb: resource cleanup failed");}throw new IllegalStateException("operation deadline");}
        synchronized void remember(JdbcCredentialLease value){remember(value.copyUsername());remember(value.copyPassword());}
        private void remember(char[] raw){sentinels.add(raw);char[] current=raw;for(int layer=0;layer<2;layer++){current=jsonEscaped(current);sentinels.add(current);}}
        /** Printable ASCII credentials only need quote/backslash escaping at the two known JSON layers. */
        private static char[] jsonEscaped(char[] value){int size=value.length;for(char c:value)if(c=='"'||c=='\\')size++;char[] encoded=new char[size];int i=0;for(char c:value){if(c=='"'||c=='\\')encoded[i++]='\\';encoded[i++]=c;}return encoded;}
        synchronized void scan(String text){for(char[] value:sentinels){outer:for(int i=0;i+value.length<=text.length();i++){for(int j=0;j<value.length;j++)if(text.charAt(i+j)!=value[j])continue outer;throw new IllegalArgumentException("SECRET_OUTPUT_BLOCKED");}}}
        boolean closeAll(){List<AutoCloseable> owned;synchronized(this){closing=true;owned=new ArrayList<>(handles);handles.clear();}boolean success=true;Collections.reverse(owned);for(var resource:owned)try{resource.close();}catch(Throwable ignored){closeFailed.set(true);success=false;}return success;}
        synchronized void clearSecrets(){for(char[] value:sentinels)Arrays.fill(value,'\0');sentinels.clear();}
    }
}
