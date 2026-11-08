package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Per-invocation RAM carrier and parent-owned run-child observations. No driver is loaded here. */
final class JdbcProcessChannel implements AutoCloseable {
    private static final String DRIVER="85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706";
    private final ReviewedJdbcBinding binding;
    private final ReviewedJdbcBinding.CredentialRequest request;
    private final JsonNode lease;
    private final List<char[]> sentinels=new ArrayList<>();
    private JdbcCredentialLease secret;
    private boolean closed;
    private volatile boolean failed,sourceOpened,sourcePipeCloseFailed;
    private volatile String sourceStage="NOT_STARTED";
    private Thread writer;
    private long sourceDeadline,pid;
    private Instant start;
    private String osArgumentsObservation="UNKNOWN";
    private boolean terminated,pipesClosed;
    private Integer exitCode;
    private JdbcProcessChannel(ReviewedJdbcBinding binding,ReviewedJdbcBinding.CredentialRequest request,JsonNode lease){this.binding=binding;this.request=request;this.lease=lease;}
    static boolean selected(ProductTestRequest request)throws Exception {
        if(!"integration".equals(request.testLevel()))return false;
        var handoff=Json.read(Path.of(request.reviewedHandoff()));var contracts=handoff.path("reviewed_contracts");
        return contracts.isArray()&&contracts.size()==1&&"jdbc.v0.3".equals(contracts.get(0).path("provider_contract").asText());
    }
    static JdbcProcessChannel prepare(FrameworkBinding parent,ProductTestRequest input,String invocation)throws Exception {
        ReviewedJdbcBinding b=parent.reviewedJdbc();require(b!=null,"trusted JDBC owner/source binding missing");
        require(b.fixtureLeaseSha256().equals(parent.fixtureLeaseSha256()),"independent JDBC lease binding mismatch");admitDriver(b);
        Path root=Path.of(input.suiteManifest()).getParent();Path path=LocalFiles.contained(root,root.resolve("fixtures/lease.json"));byte[] bytes=LocalFiles.read(path,16384);
        require(b.fixtureLeaseSha256().equals(LocalFiles.sha256(bytes)),"JDBC lease bytes changed");JsonNode lease=Json.MAPPER.readTree(bytes);ReviewedJdbcProof.validateLease(lease);liveTime(lease);
        var handoff=Json.read(Path.of(input.reviewedHandoff()));int leases=0,queries=0;
        for(var file:handoff.path("reviewed_files")){
            if("fixtures/lease.json".equals(file.path("path").asText())){require("fixture".equals(file.path("role").asText())&&b.fixtureLeaseSha256().equals(file.path("sha256").asText()),"JDBC lease closure mismatch");leases++;}
            if("queries/owned-readonly.sql".equals(file.path("path").asText())){require("fixture".equals(file.path("role").asText())&&ReviewedJdbcProof.querySha().equals(file.path("sha256").asText()),"JDBC query closure mismatch");queries++;}
        }
        require(leases==1&&queries==1,"JDBC query/lease closure missing or ambiguous");
        byte[] query=LocalFiles.read(LocalFiles.contained(root,root.resolve("queries/owned-readonly.sql")),1024);require(ReviewedJdbcProof.querySha().equals(LocalFiles.sha256(query)),"JDBC query bytes changed");
        return new JdbcProcessChannel(b,new ReviewedJdbcBinding.CredentialRequest(invocation,input.expectedHandoffSha256(),b.fixtureLeaseSha256()),lease);
    }
    void addJavaArguments(List<String> args) {args.add("-XX:-HeapDumpOnOutOfMemoryError");args.add("-XX:ErrorFile=/dev/null");args.add("-Dswarm.jdbc.invocation_id="+request.invocationId());args.add("-Dswarm.jdbc.driver_path="+binding.driverPath());}
    void start(Process process,long operationDeadline)throws Exception {
        pid=process.pid();start=process.info().startInstant().orElseThrow(()->new IllegalArgumentException("JDBC child start identity unavailable"));
        osArgumentsObservation=process.info().arguments().isPresent()?"OBSERVED":"UNKNOWN";
        sourceDeadline=Math.min(operationDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));
        writer=new Thread(()->{
            byte[] bytes=null;char[] user=null,password=null;
            try {
                sourceStage="OWNER_GUARD";liveTime(lease);binding.ownerGuard().verify(lease.deepCopy(),request);checkSourceTime();
                sourceStage="SOURCE_OPEN";JdbcCredentialLease offered=binding.credentials().open(request);require(offered!=null,"JDBC source returned no lease");accept(offered);checkSourceTime();
                sourceStage="FRAME_ENCODING";user=offered.copyUsername();password=offered.copyPassword();bytes=JdbcCredentialFrame.encode(request.invocationId(),request.handoffSha256(),request.leaseSha256(),user,password);
                sourceStage="FRAME_WRITE";process.getOutputStream().write(bytes);process.getOutputStream().flush();checkSourceTime();
            } catch(Throwable failure){failed=true;}
            finally {if(bytes!=null)Arrays.fill(bytes,(byte)0);if(user!=null)Arrays.fill(user,'\0');if(password!=null)Arrays.fill(password,'\0');try{process.getOutputStream().close();}catch(Throwable failure){sourcePipeCloseFailed=true;failed=true;}}
        },"product-test-jdbc-source");writer.setDaemon(true);writer.start();
    }
    private synchronized void accept(JdbcCredentialLease offered) {
        if(closed){offered.close();throw new IllegalStateException("JDBC carrier already closed");}
        secret=offered;sentinels.add(offered.copyUsername());sentinels.add(offered.copyPassword());sourceOpened=true;
    }
    private void checkSourceTime(){require(System.nanoTime()<sourceDeadline,"JDBC source deadline");liveTime(lease);}
    void check() {require(!failed,"JDBC protected owner/source/pipe failed");if(writer!=null&&writer.isAlive())require(System.nanoTime()<sourceDeadline,"JDBC source deadline");}
    synchronized void scan(String text) {
        for(char[] value:sentinels)outer:for(int i=0;i+value.length<=text.length();i++){for(int j=0;j<value.length;j++)if(text.charAt(i+j)!=value[j])continue outer;throw new IllegalArgumentException("JDBC protected output rejected");}
    }
    void finish(Process process,Thread stdout,Thread stderr,long operationStart) {
        long end=Math.min(System.nanoTime()+TimeUnit.SECONDS.toNanos(2),operationStart+TimeUnit.SECONDS.toNanos(7));boolean interrupted=Thread.interrupted();
        try {
            if(writer!=null)writer.interrupt();
            if(process.isAlive())process.destroyForcibly();
            long remaining=end-System.nanoTime();if(remaining>0)terminated=process.waitFor(remaining,TimeUnit.NANOSECONDS)&&!process.isAlive();
            if(terminated)exitCode=process.exitValue();
            var closeFailed=new java.util.concurrent.atomic.AtomicBoolean();
            Thread closer=new Thread(()->{for(var stream:List.of(process.getInputStream(),process.getErrorStream(),process.getOutputStream()))try{stream.close();}catch(Throwable ignored){closeFailed.set(true);}},"product-test-jdbc-pipes");closer.setDaemon(true);closer.start();
            var threads=new ArrayList<>(List.of(stdout,stderr,closer));if(writer!=null)threads.add(writer);
            for(Thread thread:threads){remaining=end-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.timedJoin(thread,remaining);}
            pipesClosed=!sourcePipeCloseFailed&&!closeFailed.get()&&threads.stream().noneMatch(Thread::isAlive);
        } catch(InterruptedException e){interrupted=true;}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    void observation(Path output)throws Exception {
        var observed=new LinkedHashMap<String,Object>();observed.put("invocation_id",request.invocationId());observed.put("fixture_lease_sha256",request.leaseSha256());observed.put("handoff_sha256",request.handoffSha256());observed.put("configured_framework_boundary","parent_owned_processbuilder");observed.put("child_pid",pid);observed.put("child_start_instant",start==null?"UNKNOWN":start.toString());observed.put("launch_nonce",request.invocationId());observed.put("os_arguments_observation",osArgumentsObservation);observed.put("terminated",terminated);observed.put("reaped",terminated);observed.put("exit_code",exitCode);observed.put("pipes_closed",pipesClosed);observed.put("credential_source_opened",sourceOpened);observed.put("source_failed",failed);observed.put("source_pipe_close_failed",sourcePipeCloseFailed);observed.put("source_stage",sourceStage);observed.put("source_thread_terminated",writer==null||!writer.isAlive());
        Files.writeString(output.resolve("jdbc-child-observation.json"),Json.MAPPER.writeValueAsString(observed),StandardOpenOption.CREATE_NEW);
    }
    void requireFinished(){require(terminated&&pipesClosed&&!failed,"JDBC child termination/pipe cleanup unconfirmed");}
    void verifyCanonicalChild(Path result)throws Exception {
        var document=Json.read(result);if(document.path("provider_results").isEmpty())return;
        var proof=Json.read(result.getParent().resolve(ReviewedJdbcProof.PATH));
        require(request.invocationId().equals(proof.path("invocation_id").textValue())&&proof.path("process_pid").isIntegralNumber()&&proof.path("process_pid").longValue()==pid&&start!=null&&start.toString().equals(proof.path("process_start_instant").textValue()),"JDBC proof differs from actual owned run child");requireFinished();
    }
    void verifyDriver()throws Exception {admitDriver(binding);}
    static void admitDriver(ReviewedJdbcBinding b)throws Exception {
        Path p=b.driverPath();require(p!=null&&p.isAbsolute()&&p.normalize().equals(p)&&!Files.isSymbolicLink(p)&&p.toRealPath().equals(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<=1024*1024,"JDBC driver binding invalid");
        require(((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,"JDBC driver hardlink rejected");
        require(DRIVER.equals(b.driverSha256())&&DRIVER.equals(LocalFiles.sha256(LocalFiles.read(p,1024*1024))),"JDBC driver pin mismatch");
    }
    private static void liveTime(JsonNode lease){Instant now=Instant.now();require(!now.isBefore(Instant.parse(lease.path("issued_at").textValue()))&&now.isBefore(Instant.parse(lease.path("expires_at").textValue())),"JDBC lease expired or future");}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalArgumentException(reason);}
    @Override public synchronized void close(){closed=true;if(secret!=null)secret.close();for(char[] value:sentinels)Arrays.fill(value,'\0');sentinels.clear();}
}
