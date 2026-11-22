package com.specdriven.regression.producttest;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
class ReviewedGrpcCapabilityTest {
 @Test void finiteGrpcUnaryCapabilityIsReviewed() {
  var capability=ReviewedNativeCapability.fromContract("grpc_client.v0.3");
  assertThat(capability.providerType()).isEqualTo("grpc_client");
  assertThat(capability.operation()).isEqualTo("unary_call");
  capability.validateBindings(Map.of("target","grpc://127.0.0.1:12345","owner_run_id","a".repeat(32)));
  capability.validateInputs(Map.of("grpc.service","swarm.producttest.EchoService","grpc.method","Echo","grpc.descriptor_ref","artifact://fixtures/echo.desc.b64","grpc.message","artifact://fixtures/request.json"));
  assertThatThrownBy(()->capability.validateBindings(Map.of("target","grpc://example.com:12345","owner_run_id","a".repeat(32)))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->capability.validateInputs(Map.of("grpc.service","arbitrary.Service","grpc.method","Echo","grpc.descriptor_ref","artifact://fixtures/echo.desc.b64","grpc.message","artifact://fixtures/request.json"))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void finitePublicGreeterCapabilityIsReviewed() {
  var capability=ReviewedNativeCapability.fromContract("grpc_client.v0.3");
  assertThatCode(()->capability.validateInputs(Map.of("grpc.service","helloworld.Greeter","grpc.method","SayHello","grpc.descriptor_ref","artifact://fixtures/greeter.desc.b64","grpc.message","artifact://fixtures/request.json"))).doesNotThrowAnyException();
 }
 @Test void routeGuideCapabilityAdmitsOnlyGetFeatureAndItsFixedArtifact() {
  var capability=ReviewedNativeCapability.fromContract("grpc_client.v0.3");
  assertThatCode(()->capability.validateInputs(Map.of("grpc.service","routeguide.RouteGuide","grpc.method","GetFeature","grpc.descriptor_ref","artifact://fixtures/routeguide.desc.b64","grpc.message","artifact://fixtures/request.json"))).doesNotThrowAnyException();
  for(String method:java.util.List.of("ListFeatures","RecordRoute","RouteChat"))
   assertThatThrownBy(()->capability.validateInputs(Map.of("grpc.service","routeguide.RouteGuide","grpc.method",method,"grpc.descriptor_ref","artifact://fixtures/routeguide.desc.b64","grpc.message","artifact://fixtures/request.json"))).isInstanceOf(IllegalArgumentException.class);
  for(String descriptor:java.util.List.of("artifact://fixtures/greeter.desc.b64","artifact://fixtures/unknown.desc.b64","artifact://fixtures/../routeguide.desc.b64"))
   assertThatThrownBy(()->capability.validateInputs(Map.of("grpc.service","routeguide.RouteGuide","grpc.method","GetFeature","grpc.descriptor_ref",descriptor,"grpc.message","artifact://fixtures/request.json"))).isInstanceOf(IllegalArgumentException.class);
 }

}
