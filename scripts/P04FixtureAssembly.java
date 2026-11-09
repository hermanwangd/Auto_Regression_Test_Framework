import com.google.protobuf.DescriptorProtos.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reviewed full-schema assembly from pinned service.proto; this is not protoc. */
public class P04FixtureAssembly {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    byte[] source=Files.readAllBytes(root.resolve("service.proto"));
    if(!sha(source).equals("9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21"))throw new IllegalArgumentException("source pin mismatch");
    var file=FileDescriptorProto.newBuilder().setName("service.proto").setPackage("service").setSyntax("proto3");
    var enumeration=EnumDescriptorProto.newBuilder().setName("ThingType");
    String[] names={"UNKNOWN","LIVING","DEAD"};
    for(int i=0;i<names.length;i++)enumeration.addValue(EnumValueDescriptorProto.newBuilder().setName(names[i]).setNumber(i));
    file.addEnumType(enumeration);
    file.addMessageType(DescriptorProto.newBuilder().setName("DoThingRequest")
      .addField(field("name",1,FieldDescriptorProto.Type.TYPE_STRING,false))
      .addField(field("comments",2,FieldDescriptorProto.Type.TYPE_STRING,true))
      .addField(field("type",3,FieldDescriptorProto.Type.TYPE_ENUM,false).setTypeName(".service.ThingType")));
    file.addMessageType(DescriptorProto.newBuilder().setName("DoThingResponse")
      .addField(field("names",1,FieldDescriptorProto.Type.TYPE_STRING,true)));
    file.addMessageType(DescriptorProto.newBuilder().setName("GetThingRequest")
      .addField(field("name",1,FieldDescriptorProto.Type.TYPE_STRING,false)));
    file.addMessageType(DescriptorProto.newBuilder().setName("GetThingResponse")
      .addField(field("name",1,FieldDescriptorProto.Type.TYPE_STRING,false))
      .addField(field("version",2,FieldDescriptorProto.Type.TYPE_INT32,false)));
    file.addService(ServiceDescriptorProto.newBuilder().setName("Test")
      .addMethod(method("DoThing","DoThing",false,false))
      .addMethod(method("DoManyThings","DoThing",true,false))
      .addMethod(method("GetThingVersions","GetThing",false,true))
      .addMethod(method("GetDifferentThings","GetThing",true,true)));
    byte[] descriptor=FileDescriptorSet.newBuilder().addFile(file).build().toByteArray();
    Files.writeString(root.resolve("service.desc.b64"),Base64.getEncoder().encodeToString(descriptor));
    Files.writeString(root.resolve("request.json"),"{\"name\":\"my milkshakes\"}\n");
    System.out.println("reviewed_descriptor_assembly compiler=null bytes="+descriptor.length+" sha256="+sha(descriptor));
  }
  private static FieldDescriptorProto.Builder field(String name,int number,FieldDescriptorProto.Type type,boolean repeated) {
    return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
      .setLabel(repeated?FieldDescriptorProto.Label.LABEL_REPEATED:FieldDescriptorProto.Label.LABEL_OPTIONAL);
  }
  private static MethodDescriptorProto.Builder method(String name,String prefix,boolean client,boolean server) {
    return MethodDescriptorProto.newBuilder().setName(name).setInputType(".service."+prefix+"Request")
      .setOutputType(".service."+prefix+"Response").setClientStreaming(client).setServerStreaming(server);
  }
  private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
