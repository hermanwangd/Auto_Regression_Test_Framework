package com.featuredeliveryintelligence.fdi.producttest;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
class GrpcBindingTest {
 @Test void independentFixtureLeasePinIsDeploymentBound() {
  var binding=new FrameworkBinding(Path.of("java"),Path.of("framework.jar"),"a".repeat(64),"b".repeat(64),Path.of("inputs"),Path.of("outputs"),Duration.ofSeconds(30),4096,"c".repeat(64));
  assertThat(binding.fixtureLeaseSha256()).isEqualTo("c".repeat(64));
  assertThatThrownBy(()->new FrameworkBinding(Path.of("java"),Path.of("framework.jar"),"a".repeat(64),"b".repeat(64),Path.of("inputs"),Path.of("outputs"),Duration.ofSeconds(30),4096,"request-self-approval")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease pin");
 }
}
