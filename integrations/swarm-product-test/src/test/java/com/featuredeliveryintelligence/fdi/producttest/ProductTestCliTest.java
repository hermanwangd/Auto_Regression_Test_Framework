package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductTestCliTest {
    @TempDir Path root;
    @Test void existingCommandFamiliesAreNotHandled() {
        assertThat(ProductTestCli.handles(new String[0])).isFalse();
        assertThat(ProductTestCli.handles(new String[]{"dev204-knowledge-consume"})).isFalse();
        assertThat(ProductTestCli.handles(new String[]{"--spring.main.web-application-type=none"})).isFalse();
    }
    @Test void malformedProductCommandFailsInsteadOfFallingThroughToSpring() {
        assertThatThrownBy(() -> ProductTestCli.handles(new String[]{"product-test", "shell", "--command", "anything"}))
                .hasMessageContaining("failed");
    }
    @Test void requestCannotSupplyExecutableOrArbitraryOptions() throws Exception {
        Path request = root.resolve("request.json"); Files.writeString(request, "{\"executable\":\"untrusted\"}");
        var errors = new ByteArrayOutputStream();
        int exit = ProductTestCli.run(new String[]{"product-test", "run", "--request", request.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), Map.of(), getClass().getClassLoader());
        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString()).contains("Unrecognized field");
    }
    @Test void duplicateRequestFieldFailsClosed() throws Exception {
        Path request = root.resolve("request.json"); Files.writeString(request, "{\"module_id\":\"product-test\",\"module_id\":\"other\"}");
        var errors = new ByteArrayOutputStream();
        int exit = ProductTestCli.run(new String[]{"product-test", "run", "--request", request.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), Map.of(), getClass().getClassLoader());
        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString()).contains("Duplicate field");
    }
    @Test void numericRevisionIsNotSilentlyCoercedToAString() throws Exception {
        Path request = root.resolve("request.json"); Files.writeString(request, "{\"execution_revision\":17}");
        var errors = new ByteArrayOutputStream();
        int exit = ProductTestCli.run(new String[]{"product-test", "run", "--request", request.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), Map.of(), getClass().getClassLoader());
        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString()).contains("Cannot coerce");
    }
    @Test void invalidNewAuthorityEnvironmentPathNeverEchoesRawReference()throws Exception {String canary="PUBLIC_SYNTHETIC_CVA_ENV_CANARY_18061";Path file=root.resolve("request.json");var request=new ProductTestRequest("product-test-request.v1","product-test","1","mission","request","revision","workspace","project",Map.of("framework","revision"),Map.of(),"component","regression","local","not_used",root.toString(),root.resolve("suite.yaml").toString(),"SUITE","local",root.resolve("handoff.json").toString(),"a".repeat(64));Json.MAPPER.writeValue(file.toFile(),request);var environment=new java.util.HashMap<String,String>();environment.put("SWARM_PRODUCT_TEST_FRAMEWORK_JAR",root.resolve("framework.jar").toString());environment.put("SWARM_PRODUCT_TEST_FRAMEWORK_SHA256","a".repeat(64));environment.put("SWARM_PRODUCT_TEST_HANDOFF_SHA256","a".repeat(64));environment.put("SWARM_PRODUCT_TEST_ARTIFACT_ROOT",root.toString());environment.put("SWARM_PRODUCT_TEST_OUTPUT_ROOT",root.toString());environment.put("SWARM_PRODUCT_TEST_COVERAGE_AUTHORITY",canary+"\u0000.json");environment.put("SWARM_PRODUCT_TEST_COVERAGE_AUTHORITY_SHA256","a".repeat(64));var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();int rc=ProductTestCli.run(new String[]{"product-test","run","--request",file.toString()},new PrintStream(out),new PrintStream(err),environment,getClass().getClassLoader());assertThat(rc).isEqualTo(2);assertThat(out.toString()+err).doesNotContain(canary).contains("COVERAGE_AUTHORITY_INVALID_REFERENCE");}

}
