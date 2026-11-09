package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** A separately reviewed real Go catalog process. No service launch or generic-provider fallback. */
public final class ReviewedA01CatalogPolicy {
    public static final String PROFILE = "microservices-demo.getproduct.v1";
    public static final String SERVICE = "hipstershop.ProductCatalogService", METHOD = "GetProduct";
    public static final String REVISION = "c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712";
    public static final String FDP_SHA = "8f6b5761a04e7d699c0e40c2776d94900581f2706a112544fa80ba71169f5d54";
    public static final String FDS_SHA = "b448856ccf1a0415e009fb841e8168db7d7e861938b573f280d9fdb644fc353d";
    public static final String ENCODED_SHA = "285bd375bc4bff89d44ed51a0ddaf205670b35ff070860311b1fc0f4bdab1921";
    public static final String PROTO_SHA = "b1f2fa42c0a9305211bf51fba7d8b5832cd3defd7b8855c327ebd5b1f0f86884";
    public static final String GENERATED_SHA = "799971d468d1e66e0d7fce8930ce74f8f1c894bf8425c50cad44c2a9b40cd9af";
    public static final String DATA_SHA = "766cd816fb1721ec0ce8d9261214fb5e10f6b89ce70600e2d20f149c7b5afffa";
    public static final String LOOPBACK_SHA = "db5cfbb858f0d9b94c5d3b3e6334e1d2821f2e740edf925aee1f58d76c9de530";
    public static final String JAVA_SHA = "1a7e0b17c39003c75b457d686775ccb032e56fa9c8afe3c492d8eb3715e8951a";
    public static final Set<String> REQUEST_HASHES = Set.of("079ffb4c48534e807f1bf07060f840290751bd4f011a94542d3b4f42eb8b98f7", "ac6b8a0d364523bd13a27b5fdb894ede0b3fec3f6cce891b8ccaaae3f2424dbf");
    public static final Set<String> EXTRA_FILES = Set.of("fixtures/catalog-owner-launch.json", "fixtures/catalog-runtime.json", "fixtures/catalog-source.json", "fixtures/products.json", "fixtures/demo.proto", "fixtures/generated-demo.pb.go");
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ReviewedA01CatalogPolicy() { }

    public static ReviewedLoopbackGrpcPolicy.Fixture fixture(Path root, Map<String,?> bindings, boolean live) {
        ReviewedLoopbackGrpcPolicy.validateBindings(bindings);
        try {
            byte[] leaseBytes = read(root,"fixtures/lease.json",16384);
            JsonNode lease = JSON.readTree(leaseBytes);
            byte[] sourceBytes = read(root,"fixtures/catalog-source.json",32768);
            byte[] runtimeBytes = read(root,"fixtures/catalog-runtime.json",32768);
            JsonNode source = JSON.readTree(sourceBytes), runtime = JSON.readTree(runtimeBytes);
            byte[] launchBytes=read(root,"fixtures/catalog-owner-launch.json",16384);
            JsonNode launch=JSON.readTree(launchBytes);
            require(Arrays.equals(launchBytes,ReviewedA01CatalogProof.launchBytes(launch)),"launch manifest must use exact compact JSON bytes");
            require(launch.equals(lease.path("owner_launch"))&&hash(launchBytes).equals(lease.path("owner_launch_sha256").asText()),"launch fixture identity differs");
            require(root.toRealPath().toString().equals(launch.path("suite_root").asText()),"launch suite differs");
            byte[] request = read(root,"fixtures/request.json",65536);
            ReviewedA01CatalogProof.validateLease(lease,source,runtime,hash(sourceBytes),hash(runtimeBytes),hash(request));
            require(bindings.get("target").equals(lease.path("endpoint").textValue()) && bindings.get("owner_run_id").equals(lease.path("owner_run_id").textValue()),"binding identity mismatch");
            require(PROTO_SHA.equals(hash(read(root,"fixtures/demo.proto",16384))),"canonical proto changed");
            require(GENERATED_SHA.equals(hash(read(root,"fixtures/generated-demo.pb.go",128*1024))),"generated proto changed");
            require(DATA_SHA.equals(hash(read(root,"fixtures/products.json",16384))),"product data changed");
            byte[] encoded = read(root,"fixtures/catalog.desc.b64",8192);
            require(encoded.length==5868 && ENCODED_SHA.equals(hash(encoded)),"descriptor encoding changed");
            byte[] descriptorBytes = Base64.getDecoder().decode(encoded);
            require(descriptorBytes.length==4399 && FDS_SHA.equals(hash(descriptorBytes)),"descriptor set changed");
            var descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes);
            require(descriptorSet.getFileCount()==1 && FDP_SHA.equals(hash(descriptorSet.getFile(0).toByteArray())),"generated descriptor changed");
            var method = GrpcDescriptorSupport.method(descriptorBytes,SERVICE,METHOD);
            GrpcDescriptorSupport.requireUnary(method);
            require(method.getInputType().getFullName().equals("hipstershop.GetProductRequest") && method.getOutputType().getFullName().equals("hipstershop.Product"),"catalog method types changed");
            require(REQUEST_HASHES.contains(hash(request)),"request bytes not reviewed");
            if(live) observe(lease,runtime);
            @SuppressWarnings("unchecked") Map<String,Object> values=JSON.convertValue(lease,Map.class);
            return new ReviewedLoopbackGrpcPolicy.Fixture(method,new String(request,StandardCharsets.UTF_8),Map.copyOf(values),hash(leaseBytes));
        } catch(IOException e) {throw new IllegalArgumentException("reviewed_a01: bounded fixture unavailable",e);}
    }

    /** Called again immediately before RPC. This method observes the OS; no supplied observation is trusted. */
    static void observe(JsonNode lease, JsonNode runtime) throws IOException {
        Instant now=Instant.now(),issued=Instant.parse(lease.path("issued_at").asText()),expires=Instant.parse(lease.path("expires_at").asText());
        require(!now.isBefore(issued) && now.isBefore(expires),"lease outside live interval");
        JsonNode supervisor=lease.path("supervisor"), child=lease.path("child"), listener=lease.path("listener");
        ProcessHandle parent=process(supervisor), process=process(child);
        require(process.parent().map(ProcessHandle::pid).orElse(-1L)==parent.pid(),"child parent differs");
        verifyLaunch(parent,lease,runtime);
        require(process.info().arguments().orElseThrow(()->new IllegalArgumentException("reviewed_a01: child arguments unavailable")).length==0,"child arguments differ");
        Path ownerRoot=absolute(runtime.path("owner_root").asText());
        require(Files.isDirectory(ownerRoot,LinkOption.NOFOLLOW_LINKS) && ownerRoot.toRealPath().equals(ownerRoot),"owner root differs");
        Path binary=absolute(child.path("executable_path").asText());
        require(binary.equals(ownerRoot.resolve("productcatalogservice")) && binary.equals(absolute(runtime.path("binary_path").asText())),"binary path differs");
        verifyExecutable(parent,supervisor,8*1024*1024);
        verifyExecutable(process,child,128*1024*1024);
        Path cwd=Path.of("/proc",Long.toString(process.pid()),"cwd").toRealPath();
        require(cwd.equals(ownerRoot) && cwd.toString().equals(child.path("cwd").asText()),"child cwd differs");
        require(DATA_SHA.equals(hash(read(ownerRoot,"products.json",16384))),"live products differ");
        String stat=text(Path.of("/proc",Long.toString(process.pid()),"stat"),8192);
        int closing=stat.lastIndexOf(')'); require(closing>0,"invalid proc stat");
        String[] statFields=stat.substring(closing+1).trim().split("\\s+");
        require(stat.startsWith(process.pid()+" (") && statFields.length>1 && Long.parseLong(statFields[1])==parent.pid(),"proc parent differs");
        String namespace=Files.readSymbolicLink(Path.of("/proc",Long.toString(process.pid()),"ns/net")).toString();
        require(namespace.equals(Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString()) && namespace.equals("net:["+listener.path("network_namespace_inode").asText()+"]"),"network namespace differs");
        long inode=listener.path("inode").longValue(); int count=0, matches=0; Set<String> socketInodes=new java.util.HashSet<>();
        try(var descriptors=Files.newDirectoryStream(Path.of("/proc",Long.toString(process.pid()),"fd"))) {
            for(Path fd:descriptors) {
                require(++count<=128,"too many child descriptors");String link=Files.readSymbolicLink(fd).toString();
                if(link.matches("socket:\\[[0-9]+\\]"))socketInodes.add(link.substring(8,link.length()-1));
                if(link.equals("socket:["+inode+"]"))matches++;
            }
        }
        require(matches==1,"child socket ownership missing or ambiguous");
        String local="0100007F:"+String.format(java.util.Locale.ROOT,"%04X",listener.path("port").intValue()); matches=0;
        int ownedListeners=0;
        for(String line:text(Path.of("/proc",Long.toString(process.pid()),"net/tcp"),65536).split("\\R")) {
            String[] fields=line.trim().split("\\s+");
            if(fields.length>9 && fields[3].equals("0A") && socketInodes.contains(fields[9]))ownedListeners++;
            if(fields.length>9 && fields[9].equals(Long.toString(inode))) {require(fields[1].equals(local) && fields[3].equals("0A"),"socket not exact loopback listener");matches++;}
        }
        require(matches==1&&ownedListeners==1,"listener row missing or ambiguous");
        for(String line:text(Path.of("/proc",Long.toString(process.pid()),"net/tcp6"),65536).split("\\R")) {
            String[] fields=line.trim().split("\\s+");require(!(fields.length>9&&fields[3].equals("0A")&&socketInodes.contains(fields[9])),"unexpected owned IPv6 listener");
        }
        String environment=text(Path.of("/proc",Long.toString(process.pid()),"environ"),8192);
        Set<String> expected=Set.of("PORT="+listener.path("port").intValue(),"DISABLE_PROFILER=1","ENABLE_TRACING=0","GOTRACEBACK=single");
        String[] entries=environment.split("\u0000"); require(entries.length==expected.size() && Set.copyOf(Arrays.asList(entries)).equals(expected),"child environment differs");
        // Fail closed if identity changed during observation; historical validators never call this method.
        process(child);process(supervisor);
        require(Instant.now().isBefore(expires),"lease expired during live observation");
    }
    /** Trust anchor comes from the reviewed external controller, never from a lease or supervisor. */
    static void verifyLaunch(ProcessHandle supervisor,JsonNode lease,JsonNode runtime)throws IOException {
        JsonNode launch=lease.path("owner_launch");ReviewedA01CatalogProof.validateLaunch(launch);
        String trusted=System.getProperty("swarm.grpc.a01.owner_launch_sha256","");
        require(trusted.matches("[a-f0-9]{64}")&&trusted.equals(hash(ReviewedA01CatalogProof.launchBytes(launch)))
            &&trusted.equals(lease.path("owner_launch_sha256").asText())&&trusted.equals(runtime.path("owner_launch_sha256").asText()),"independent reviewed launch pin missing/different");
        String actualJava=supervisor.info().command().orElseThrow(()->new IllegalArgumentException("reviewed_a01: Java command unavailable"));
        require(actualJava.equals(launch.path("java_path").asText()),"launch Java path differs");
        var paths=new java.util.ArrayList<String>();
        for(JsonNode member:launch.path("classpath")) {
            Path path=absolute(member.path("path").asText());
            require(path.toRealPath().equals(path)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)==member.path("bytes").asLong(),"launch JAR identity differs");
            require(fileHash(path,32*1024*1024).equals(member.path("sha256").asText()),"launch JAR bytecode digest differs");
            // External manifest classpath expansion would introduce unlisted code resolution.
            try(var jar=new java.util.jar.JarFile(path.toFile())) {
                var manifest=jar.getManifest();
                require(manifest==null||(manifest.getMainAttributes().getValue("Class-Path")==null),"unreviewed JAR classpath expansion");
            }
            paths.add(path.toString());
        }
        validateArguments(supervisor.info().arguments().orElseThrow(()->new IllegalArgumentException("reviewed_a01: supervisor arguments unavailable")),lease,trusted);
        String environment=text(Path.of("/proc",Long.toString(supervisor.pid()),"environ"),16384);
        for(String entry:environment.split("\u0000"))require(!(entry.startsWith("JAVA_TOOL_OPTIONS=")||entry.startsWith("JDK_JAVA_OPTIONS=")||entry.startsWith("_JAVA_OPTIONS=")),"unreviewed Java environment override");
    }
    static void validateArguments(String[] actual,JsonNode lease,String trusted) {
        JsonNode launch=lease.path("owner_launch");var paths=new java.util.ArrayList<String>();
        for(JsonNode member:launch.path("classpath"))paths.add(member.path("path").asText());
        java.util.List<String> expected=java.util.List.of("-Xmx256m","-XX:MaxMetaspaceSize=128m",
            "-Dswarm.grpc.owner_nonce="+lease.path("owner_run_id").asText(),"-Dswarm.grpc.a01.owner_launch_sha256="+trusted,
            "-cp",String.join(":",paths),"com.specdriven.regression.provider.grpc.A01CatalogOwner",
            launch.path("suite_root").asText(),lease.path("runtime_manifest_sha256").asText(),lease.path("source_manifest_sha256").asText(),lease.path("build_manifest_sha256").asText());
        require(Arrays.asList(actual).equals(expected),"exact ordered supervisor launch differs");
    }
    private static ProcessHandle process(JsonNode identity) {
        ProcessHandle p=ProcessHandle.of(identity.path("pid").longValue()).orElseThrow(()->new IllegalArgumentException("reviewed_a01: process missing"));
        require(p.isAlive() && p.info().startInstant().map(Instant::toString).filter(identity.path("process_start_instant").asText()::equals).isPresent(),"process start/liveness differs");return p;
    }
    private static void verifyExecutable(ProcessHandle p,JsonNode identity,int limit) throws IOException {
        Path expected=absolute(identity.path("executable_path").asText());
        require(expected.toRealPath().equals(expected) && expected.toString().equals(p.info().command().orElse("")),"observed executable differs");
        require(Path.of("/proc",Long.toString(p.pid()),"exe").toRealPath().equals(expected),"proc executable differs");
        require(fileHash(expected,limit).equals(identity.path("executable_sha256").asText()),"executable digest differs");
    }
    static Path absolute(String value) {Path p=Path.of(value);require(p.isAbsolute() && p.normalize().equals(p),"noncanonical absolute path");return p;}
    static byte[] read(Path root,String relative,int maximum) throws IOException {
        Path base=root.toRealPath(),file=base.resolve(relative);
        require(!Path.of(relative).isAbsolute() && file.normalize().startsWith(base) && file.toRealPath().equals(file) && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"unsafe fixture path");
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(maximum+1);require(bytes.length>0&&bytes.length<=maximum,"fixture size");return bytes;}
    }
    static String text(Path path,int maximum)throws IOException {try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(maximum+1);require(bytes.length<=maximum,"proc output too large");return new String(bytes,StandardCharsets.UTF_8);}}
    static String fileHash(Path path,int maximum)throws IOException {
        try {var digest=java.security.MessageDigest.getInstance("SHA-256");long n=0;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int length;while((length=in.read(buffer))!=-1){n+=length;require(n<=maximum,"executable too large");digest.update(buffer,0,length);}}require(n>0,"empty executable");return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static String hash(byte[] bytes){return ReviewedLoopbackGrpcPolicy.digest(bytes);}
    static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException("reviewed_a01: "+message);}
}
