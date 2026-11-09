package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned protocol-faithful Java streaming fixture, not the upstream Python product. */
public final class P04StreamingOwner {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,String> PINS=Map.of(
            "service.proto","9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21",
            "service.desc.b64","81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8",
            "request.json","1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471");
    public static void main(String[] args)throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("expected owned root and pinned fixture source");
        Path root=Path.of(args[0]),source=Path.of(args[1]).toRealPath();
        String profile=args.length==3?args[2]:"betterproto.getthingversions-reject.v1";
        if(!Set.of("betterproto.dothing-unary.v1","betterproto.domanythings-reject.v1","betterproto.getthingversions-reject.v1","betterproto.getdifferentthings-reject.v1").contains(profile))throw new IllegalArgumentException("unknown finite profile");
        String id=System.getProperty("swarm.grpc.owner_nonce");
        if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("owner nonce missing");
        Files.createDirectory(root);Files.createDirectory(root.resolve("fixtures"));
        for(var pin:PINS.entrySet()) {
            Path file=source.resolve(pin.getKey());
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)>4096)
                throw new IllegalArgumentException("unsafe source fixture");
            byte[] bytes=Files.readAllBytes(file);if(!hash(bytes).equals(pin.getValue()))throw new IllegalArgumentException("fixture pin differs");
            Files.write(root.resolve("fixtures").resolve(pin.getKey()),bytes,StandardOpenOption.CREATE_NEW);
        }
        byte[] encoded=Files.readAllBytes(root.resolve("fixtures/service.desc.b64"));
        byte[] descriptor=Base64.getDecoder().decode(encoded);
        if(descriptor.length!=587||!hash(descriptor).equals("e2a26040ccdbb5edf9c250074ab3fdb5e4188cdd8a868e1752a0bd4c6165ad0f"))throw new IllegalArgumentException("wrong descriptor");
        var set=DescriptorProtos.FileDescriptorSet.parseFrom(descriptor);
        var file=Descriptors.FileDescriptor.buildFrom(set.getFile(0),new Descriptors.FileDescriptor[0]);
        AtomicInteger calls=new AtomicInteger();
        Files.writeString(root.resolve("owner-receipts.jsonl"),"",StandardOpenOption.CREATE_NEW);
        JSON.writeValue(root.resolve("owner-calls.json").toFile(),Map.of("calls",0,"owner_run_id",id));
        var service=ServerServiceDefinition.builder("service.Test");
        for(var method:file.findServiceByName("Test").getMethods()) {
            var type=method.isClientStreaming()?(method.isServerStreaming()?MethodDescriptor.MethodType.BIDI_STREAMING:MethodDescriptor.MethodType.CLIENT_STREAMING)
                    :(method.isServerStreaming()?MethodDescriptor.MethodType.SERVER_STREAMING:MethodDescriptor.MethodType.UNARY);
            var wire=MethodDescriptor.<DynamicMessage,DynamicMessage>newBuilder().setType(type).setFullMethodName("service.Test/"+method.getName())
                    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                    .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getOutputType()))).build();
            if(type==MethodDescriptor.MethodType.UNARY)service.addMethod(wire,ServerCalls.asyncUnaryCall((request,observer)->{
                try {record(root,id,calls,method,request);observer.onNext(names(method,List.of(name(method,request))));observer.onCompleted();}
                catch(Exception error){observer.onError(error);}
            }));
            else if(type==MethodDescriptor.MethodType.SERVER_STREAMING)service.addMethod(wire,ServerCalls.asyncServerStreamingCall((request,observer)->{
                try {record(root,id,calls,method,request);versions(method,name(method,request),observer);observer.onCompleted();}
                catch(Exception error){observer.onError(error);}
            }));
            else {
                io.grpc.stub.ServerCalls.ClientStreamingMethod<DynamicMessage,DynamicMessage> handler=observer->new io.grpc.stub.StreamObserver<DynamicMessage>() {
                    final List<String> received=new ArrayList<>();boolean failed;
                    public void onNext(DynamicMessage request){if(failed)return;try {
                        if(received.isEmpty())record(root,id,calls,method,request);
                        if(received.size()>=4)throw new IllegalArgumentException("fixture stream input limit");
                        received.add(name(method,request));if(method.isServerStreaming())versions(method,name(method,request),observer);
                    }catch(Exception error){failed=true;observer.onError(error);}}
                    public void onError(Throwable error){failed=true;}
                    public void onCompleted(){if(failed)return;try{if(!method.isServerStreaming())observer.onNext(names(method,received));observer.onCompleted();}catch(Exception error){observer.onError(error);}}
                };
                if(type==MethodDescriptor.MethodType.CLIENT_STREAMING)service.addMethod(wire,ServerCalls.asyncClientStreamingCall(handler));
                else service.addMethod(wire,ServerCalls.asyncBidiStreamingCall(handler::invoke));
            }
        }
        var server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).directExecutor()
                .maxInboundMessageSize(65536).addService(service.build()).build().start();
        Instant issue=Instant.now();var lease=new LinkedHashMap<String,Object>();
        lease.put("version","reviewed-grpc-lease.v1");lease.put("profile",profile);
        lease.put("endpoint","grpc://127.0.0.1:"+server.getPort());lease.put("owner_run_id",id);
        lease.put("pid",ProcessHandle.current().pid());lease.put("process_start_instant",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        lease.put("issued_at",issue.toString());lease.put("expires_at",issue.plusSeconds(120).toString());
        lease.put("descriptor_sha256",hash(encoded));lease.put("request_sha256",PINS.get("request.json"));
        JSON.writeValue(root.resolve("fixtures/lease.json").toFile(),lease);
        JSON.writeValue(root.resolve("owner-ready.tmp").toFile(),lease);
        Files.move(root.resolve("owner-ready.tmp"),root.resolve("owner-ready.json"),StandardCopyOption.ATOMIC_MOVE);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);boolean stopped=false;
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            while(System.nanoTime()<deadline) {
                if(reader.ready()) {String command=reader.readLine();if(command==null||command.equals("stop")){stopped=true;break;}}
                Thread.sleep(10);
            }
        } finally {
            server.shutdownNow();boolean terminated=server.awaitTermination(2,TimeUnit.SECONDS);
            JSON.writeValue(root.resolve("owner-cleanup.json").toFile(),Map.of("owner_run_id",id,"pid",ProcessHandle.current().pid(),"server_terminated",terminated,"calls",calls.get()));
            if(!terminated)throw new IllegalStateException("listener cleanup uncertain");
        }
        if(!stopped)throw new IllegalStateException("owned fixture lifetime exceeded");
    }
    private static String name(Descriptors.MethodDescriptor method,DynamicMessage request){return String.valueOf(request.getField(method.getInputType().findFieldByName("name")));}
    private static DynamicMessage names(Descriptors.MethodDescriptor method,List<String> names){return DynamicMessage.newBuilder(method.getOutputType()).setField(method.getOutputType().findFieldByName("names"),List.copyOf(names)).build();}
    private static void versions(Descriptors.MethodDescriptor method,String name,io.grpc.stub.StreamObserver<DynamicMessage> observer){
        for(int version=1;version<=2;version++)observer.onNext(DynamicMessage.newBuilder(method.getOutputType()).setField(method.getOutputType().findFieldByName("name"),name).setField(method.getOutputType().findFieldByName("version"),version).build());
    }
    private static void record(Path root,String id,AtomicInteger calls,Descriptors.MethodDescriptor method,DynamicMessage request)throws Exception {
        int count=calls.incrementAndGet();
        Files.writeString(root.resolve("owner-receipts.jsonl"),JSON.writeValueAsString(Map.of("call",count,"method",method.getName(),"name",name(method,request),"owner_run_id",id))+"\n",StandardOpenOption.APPEND);
        JSON.writeValue(root.resolve("owner-calls.json").toFile(),Map.of("calls",count,"owner_run_id",id));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
