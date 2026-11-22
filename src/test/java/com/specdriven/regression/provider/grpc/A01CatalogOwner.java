package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-side supervisor of an independently built real Go catalog, never a replacement catalog server. */
public final class A01CatalogOwner {
    private A01CatalogOwner() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("reviewed suite root and independent runtime/source/build SHA256 pins required");
        String nonce=System.getProperty("swarm.grpc.owner_nonce");
        require(nonce!=null&&nonce.matches("[a-f0-9]{32}"),"missing owner nonce");
        Path suite=Path.of(args[0]).toRealPath();
        byte[] sourceBytes=ReviewedA01CatalogPolicy.read(suite,"fixtures/catalog-source.json",32768);
        byte[] runtimeBytes=ReviewedA01CatalogPolicy.read(suite,"fixtures/catalog-runtime.json",32768);
        JsonNode source=ReviewedA01CatalogPolicy.JSON.readTree(sourceBytes),runtime=ReviewedA01CatalogPolicy.JSON.readTree(runtimeBytes);
        require(args[1].matches("[a-f0-9]{64}")&&args[2].matches("[a-f0-9]{64}")&&args[3].matches("[a-f0-9]{64}"),"independent pin format");
        require(args[1].equals(ReviewedA01CatalogPolicy.hash(runtimeBytes))&&args[2].equals(ReviewedA01CatalogPolicy.hash(sourceBytes))&&args[3].equals(runtime.path("build_manifest_sha256").asText()),"independent owner input pins differ");
        ReviewedA01CatalogProof.validateArtifacts(source,runtime,args[2],args[1]);
        byte[] launchBytes=ReviewedA01CatalogPolicy.read(suite,"fixtures/catalog-owner-launch.json",16384);
        JsonNode launch=ReviewedA01CatalogPolicy.JSON.readTree(launchBytes);
        ReviewedA01CatalogProof.validateLaunch(launch);
        String launchSha=ReviewedA01CatalogPolicy.hash(launchBytes);
        require(java.util.Arrays.equals(launchBytes,ReviewedA01CatalogProof.launchBytes(launch))&&launchSha.equals(System.getProperty("swarm.grpc.a01.owner_launch_sha256"))&&launchSha.equals(runtime.path("owner_launch_sha256").asText()),"independent launch manifest pin differs");
        require(suite.toString().equals(launch.path("suite_root").asText()),"launch suite root differs");
        ObjectNode launchIdentity=ReviewedA01CatalogPolicy.JSON.createObjectNode();launchIdentity.set("owner_launch",launch);launchIdentity.put("owner_launch_sha256",launchSha);launchIdentity.put("owner_run_id",nonce);launchIdentity.put("runtime_manifest_sha256",args[1]);launchIdentity.put("source_manifest_sha256",args[2]);launchIdentity.put("build_manifest_sha256",args[3]);
        ReviewedA01CatalogPolicy.verifyLaunch(ProcessHandle.current(),launchIdentity,runtime);
        Path root=ReviewedA01CatalogPolicy.absolute(runtime.path("owner_root").asText()).toRealPath();
        Path binary=ReviewedA01CatalogPolicy.absolute(runtime.path("binary_path").asText());
        require(binary.equals(root.resolve("productcatalogservice"))&&binary.toRealPath().equals(binary),"binary containment");
        require(ReviewedA01CatalogPolicy.fileHash(binary,128*1024*1024).equals(runtime.path("binary_sha256").asText()),"binary pin");
        require(ReviewedA01CatalogPolicy.DATA_SHA.equals(ReviewedA01CatalogPolicy.hash(ReviewedA01CatalogPolicy.read(root,"products.json",16384))),"data pin");
        int port;try(ServerSocket reservation=new ServerSocket()) {reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));port=reservation.getLocalPort();}
        Path log=root.resolve("catalog-child.log");require(!Files.exists(log),"fresh child log required");
        ProcessBuilder builder=new ProcessBuilder(binary.toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();builder.environment().putAll(Map.of("PORT",Integer.toString(port),"DISABLE_PROFILER","1","ENABLE_TRACING","0","GOTRACEBACK","single"));
        Object launchLock=new Object();var active=new java.util.concurrent.atomic.AtomicReference<Lifecycle>();
        Thread shutdown=new Thread(()->{synchronized(launchLock){Lifecycle owned=active.get();if(owned!=null){owned.fail("JVM_SHUTDOWN");owned.cleanup();}}},"a01-owner-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        final Process child;final Lifecycle lifecycle;
        try {synchronized(launchLock){child=builder.start();lifecycle=new Lifecycle(child,root,log,nonce);active.set(lifecycle);}}
        catch(Throwable startFailure){try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException ignored){}throw startFailure;}
        Thread guard=new Thread(()->{
            try {while(!lifecycle.stopping.get()) {lifecycle.check();Thread.sleep(20);}}
            catch(Throwable failure){lifecycle.fail("GUARD: "+failure);lifecycle.cleanup();}
        },"a01-owner-bound");guard.setDaemon(true);guard.start();
        ObjectNode lease=null;
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);ObjectNode listener=null;
            while(System.nanoTime()<deadline&&child.isAlive()) {lifecycle.check();listener=listener(child.pid(),port);if(listener!=null)break;Thread.sleep(20);}
            require(listener!=null,"owned loopback listener not observed within ten seconds");
            Instant now=Instant.now();lease=ReviewedA01CatalogPolicy.JSON.createObjectNode();
            lease.put("version",ReviewedA01CatalogProof.LEASE_VERSION);lease.put("profile",ReviewedA01CatalogPolicy.PROFILE);lease.put("endpoint","grpc://127.0.0.1:"+port);lease.put("owner_run_id",nonce);lease.put("issued_at",now.toString());lease.put("expires_at",now.plusSeconds(180).toString());
            ObjectNode parent=identity(ProcessHandle.current());parent.put("nonce_argument","-Dswarm.grpc.owner_nonce="+nonce);lease.set("supervisor",parent);
            ObjectNode process=identity(child.toHandle());process.put("ppid",ProcessHandle.current().pid());process.put("cwd",root.toString());lease.set("child",process);lease.set("listener",listener);
            for(String field:Set.of("upstream_revision","canonical_proto_sha256","generated_proto_sha256","descriptor_fdp_sha256","descriptor_sha256","data_sha256"))lease.put(field,source.path(field).asText());
            lease.set("owner_launch",launch);lease.put("owner_launch_sha256",launchSha);
            lease.put("source_manifest_sha256",ReviewedA01CatalogPolicy.hash(sourceBytes));lease.put("runtime_manifest_sha256",ReviewedA01CatalogPolicy.hash(runtimeBytes));lease.put("build_manifest_sha256",runtime.path("build_manifest_sha256").asText());lease.put("request_sha256",ReviewedA01CatalogPolicy.hash(ReviewedA01CatalogPolicy.read(suite,"fixtures/request.json",65536)));
            ReviewedA01CatalogProof.validateLease(lease,source,runtime,ReviewedA01CatalogPolicy.hash(sourceBytes),ReviewedA01CatalogPolicy.hash(runtimeBytes),lease.path("request_sha256").asText());
            lifecycle.lease=lease;
            ReviewedA01CatalogPolicy.observe(lease,runtime);
            atomic(suite.resolve("fixtures/lease.json"),lease);atomic(root.resolve("owner-ready.json"),lease);
            // A blocked input reader must never own process lifetime. It is daemon-only and bounded.
            var commands=new java.util.concurrent.ArrayBlockingQueue<String>(1);
            Thread input=new Thread(()->{
                try {StringBuilder value=new StringBuilder();int ch;
                    while((ch=System.in.read())!=-1&&ch!='\n'){if(value.length()>=32)throw new IllegalArgumentException("command too long");value.append((char)ch);}
                    commands.offer(ch==-1&&value.isEmpty()?"stop":value.toString());
                } catch(Throwable failure){lifecycle.fail("STDIN: "+failure);commands.offer("invalid");}
            },"a01-owner-input");input.setDaemon(true);input.start();
            while(!lifecycle.finished.get()) {lifecycle.check();String command=commands.poll(20,TimeUnit.MILLISECONDS);if(command!=null){require(command.equals("stop"),"unsupported owner command");break;}}
        } catch(Throwable failure) {
            lifecycle.fail("MAIN: "+failure);throw failure;
        } finally {
            lifecycle.cleanup();
            try {Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException shuttingDown){/* shared cleanup already owns termination */}
        }
        require(lifecycle.success,"owner cleanup unconfirmed: "+lifecycle.failure.get());
    }
    /** One absolute lifetime, one idempotent bounded reap/receipt path, also used by the shutdown hook. */
    static final class Lifecycle {
        final Process child;final Path root,log;final String nonce;
        final long deadline;
        final AtomicBoolean finished=new AtomicBoolean(),stopping=new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>();
        volatile ObjectNode lease;volatile boolean success;
        Lifecycle(Process child,Path root,Path log,String nonce){this(child,root,log,nonce,180000);}
        Lifecycle(Process child,Path root,Path log,String nonce,long milliseconds){require(milliseconds>=0&&milliseconds<=180000,"lifetime bound");this.child=child;this.root=root;this.log=log;this.nonce=nonce;this.deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(milliseconds);}
        void fail(String reason){failure.compareAndSet(null,reason);}
        void check()throws Exception {
            if(stopping.get())return;
            require(System.nanoTime()<deadline,"absolute owner deadline exceeded");
            require(Files.size(log)<=65536,"child output bound exceeded");
            require(stopping.get()||child.isAlive(),"child exited before requested cleanup");
            require(failure.get()==null,"owner guard previously failed");
        }
        synchronized void cleanup() {
            if(finished.get())return;stopping.set(true);
            boolean terminated=false,socketGone=false;String socketState="UNKNOWN";
            try {
                child.destroy();terminated=child.waitFor(2,TimeUnit.SECONDS);
                if(!terminated){child.destroyForcibly();terminated=child.waitFor(2,TimeUnit.SECONDS);}
                if(!terminated)fail("CHILD_REAP_UNCONFIRMED");
                if(lease!=null&&terminated){socketGone=!hasInode(lease.path("listener").path("inode").asLong());socketState=socketGone?"GONE":"PRESENT";}
                if(!socketGone)fail("SOCKET_"+socketState);
            } catch(Throwable problem) {fail("CLEANUP: "+problem);child.destroyForcibly();
                try{terminated=child.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            try {
                ObjectNode receipt=ReviewedA01CatalogPolicy.JSON.createObjectNode();receipt.put("version","a01-owner-teardown.v1");receipt.put("owner_run_id",nonce);receipt.put("child_pid",child.pid());receipt.put("terminated",terminated);receipt.put("reaped",terminated);receipt.put("finished_at",Instant.now().toString());
                if(lease!=null){receipt.set("supervisor",lease.path("supervisor"));receipt.set("child",lease.path("child"));receipt.set("listener",lease.path("listener"));}
                receipt.put("owned_socket_gone",socketGone);receipt.put("socket_state",socketState);receipt.put("guard_failure",failure.get()!=null);receipt.put("failure_reason",failure.get()==null?"":failure.get());
                receipt.put("graceful",terminated&&socketGone&&failure.get()==null);receipt.put("sigkill_observable_by_java",false);
                atomic(root.resolve("owner-teardown.json"),receipt);success=terminated&&socketGone&&failure.get()==null;
            } catch(Throwable unavailable){fail("RECEIPT_UNAVAILABLE: "+unavailable);success=false;}
            finally {finished.set(true);}
        }
    }
    private static ObjectNode identity(ProcessHandle process)throws Exception {
        ObjectNode node=ReviewedA01CatalogPolicy.JSON.createObjectNode();node.put("pid",process.pid());node.put("process_start_instant",process.info().startInstant().orElseThrow().toString());Path exe=Path.of("/proc",Long.toString(process.pid()),"exe").toRealPath();node.put("executable_path",exe.toString());node.put("executable_sha256",ReviewedA01CatalogPolicy.fileHash(exe,128*1024*1024));return node;
    }
    private static ObjectNode listener(long pid,int port)throws Exception {
        Set<String> sockets=new HashSet<>();int count=0;
        try(var descriptors=Files.newDirectoryStream(Path.of("/proc",Long.toString(pid),"fd"))) {for(Path fd:descriptors){require(++count<=128,"fd bound");String target=Files.readSymbolicLink(fd).toString();if(target.startsWith("socket:[")&&target.endsWith("]"))sockets.add(target.substring(8,target.length()-1));}}
        String wanted="0100007F:"+String.format(java.util.Locale.ROOT,"%04X",port);ObjectNode found=null;
        for(String row:ReviewedA01CatalogPolicy.text(Path.of("/proc",Long.toString(pid),"net/tcp"),65536).split("\\R")) {String[] fields=row.trim().split("\\s+");if(fields.length>9&&fields[1].equals(wanted)&&fields[3].equals("0A")&&sockets.contains(fields[9])) {require(found==null,"ambiguous owned listener");found=ReviewedA01CatalogPolicy.JSON.createObjectNode();found.put("family","ipv4");found.put("address","127.0.0.1");found.put("port",port);found.put("inode",Long.parseLong(fields[9]));String ns=Files.readSymbolicLink(Path.of("/proc",Long.toString(pid),"ns/net")).toString();require(ns.matches("net:\\[[0-9]+\\]"),"namespace format");found.put("network_namespace_inode",Long.parseLong(ns.substring(5,ns.length()-1)));}}
        return found;
    }
    private static boolean hasInode(long inode)throws Exception {for(String row:ReviewedA01CatalogPolicy.text(Path.of("/proc/self/net/tcp"),65536).split("\\R")){String[] f=row.trim().split("\\s+");if(f.length>9&&f[9].equals(Long.toString(inode)))return true;}return false;}
    private static void atomic(Path path,JsonNode value)throws Exception {require(!Files.exists(path),"fresh owner output required");Path temporary=path.resolveSibling(path.getFileName()+".tmp");Files.write(temporary,ReviewedA01CatalogPolicy.JSON.writeValueAsBytes(value),java.nio.file.StandardOpenOption.CREATE_NEW);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);}
    private static void require(boolean ok,String reason){ReviewedA01CatalogPolicy.require(ok,reason);}
}
