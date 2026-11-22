package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
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

/** Independent disposable TCP oracle fixture. It does not call reviewed policy or runtime code. */
public final class GrpcEchoOwner {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); String mode=args[1];
        String id=System.getProperty("swarm.grpc.owner_nonce");
        if(id==null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("owner nonce missing");
        Files.createDirectories(root.resolve("fixtures"));
        var request=DescriptorProto.newBuilder().setName("EchoRequest").addField(FieldDescriptorProto.newBuilder().setName("message").setNumber(1).setType(FieldDescriptorProto.Type.TYPE_STRING).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
        var response=DescriptorProto.newBuilder().setName("EchoReply").addField(FieldDescriptorProto.newBuilder().setName("message").setNumber(1).setType(FieldDescriptorProto.Type.TYPE_STRING).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
        var proto=FileDescriptorProto.newBuilder().setName("echo.proto").setPackage("swarm.producttest").setSyntax("proto3").addMessageType(request).addMessageType(response).addService(ServiceDescriptorProto.newBuilder().setName("EchoService").addMethod(MethodDescriptorProto.newBuilder().setName("Echo").setInputType(".swarm.producttest.EchoRequest").setOutputType(".swarm.producttest.EchoReply"))).build();
        byte[] encoded=Base64.getEncoder().encode(FileDescriptorSet.newBuilder().addFile(proto).build().toByteArray());
        String requestText=mode.equals("request_64k")?"q".repeat(65500):"swarm-probe";
        byte[] payload=("{\"message\":\""+requestText+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("fixtures/echo.desc.b64"),encoded); Files.write(root.resolve("fixtures/request.json"),payload);
        var descriptor=Descriptors.FileDescriptor.buildFrom(proto,new Descriptors.FileDescriptor[0]);
        var method=descriptor.getServices().get(0).getMethods().get(0);
        var grpcMethod=MethodDescriptor.<DynamicMessage,DynamicMessage>newBuilder().setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("swarm.producttest.EchoService/Echo").setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType()))).setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getOutputType()))).build();
        ObjectMapper json=new ObjectMapper();
        Server server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).directExecutor().addService(ServerServiceDefinition.builder("swarm.producttest.EchoService").addMethod(grpcMethod,ServerCalls.asyncUnaryCall((message,observer)->{
            try { Files.writeString(root.resolve("owner-receipts.jsonl"),json.writeValueAsString(Map.of("owner_run_id",id,"pid",ProcessHandle.current().pid(),"request_message",message.getField(method.getInputType().findFieldByName("message"))))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
            catch(Exception e) {observer.onError(e);return;}
            if(mode.equals("timeout")) return;
            String text=switch(mode) {case "inbound_overflow"->"x".repeat(1024*1024);case "json_overflow"->"\u0001".repeat(200000);case "bounded_large"->"x".repeat(768*1024);case "escaped_large"->"\"".repeat(500000);default->String.valueOf(message.getField(method.getInputType().findFieldByName("message")));};
            observer.onNext(DynamicMessage.newBuilder(method.getOutputType()).setField(method.getOutputType().findFieldByName("message"),text).build());observer.onCompleted();
        })).build()).build().start();
        String endpoint="grpc://127.0.0.1:"+server.getPort(); Instant now=Instant.now();
        Map<String,Object> lease=new LinkedHashMap<>();
        lease.put("version","reviewed-grpc-lease.v1");lease.put("profile","echo.v1");lease.put("endpoint",endpoint);lease.put("owner_run_id",id);
        lease.put("pid",ProcessHandle.current().pid());lease.put("process_start_instant",ProcessHandle.current().info().startInstant().orElseThrow().toString());lease.put("issued_at",now.toString());lease.put("expires_at",now.plusSeconds(120).toString());
        lease.put("request_sha256",hash(payload));lease.put("descriptor_sha256",hash(encoded));
        json.writeValue(root.resolve("fixtures/lease.json").toFile(),lease);
        Files.writeString(root.resolve("owner-ready.tmp"),json.writeValueAsString(lease));
        Files.move(root.resolve("owner-ready.tmp"),root.resolve("owner-ready.json"),StandardCopyOption.ATOMIC_MOVE);
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            String command;
            while((command=reader.readLine())!=null) {
                if(command.equals("down")) {server.shutdownNow();if(!server.awaitTermination(2,TimeUnit.SECONDS))throw new IllegalStateException("listener cleanup uncertain");Files.writeString(root.resolve("owner-down"),"closed");}
                if(command.equals("stop")) break;
            }
        } finally {server.shutdownNow();if(!server.awaitTermination(2,TimeUnit.SECONDS))throw new IllegalStateException("owner cleanup uncertain");}
    }
    private static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
