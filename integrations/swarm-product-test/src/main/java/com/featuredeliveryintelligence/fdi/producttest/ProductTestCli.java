package com.featuredeliveryintelligence.fdi.producttest;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Narrow composition hook; existing Dev204 commands and Spring startup keep their own handlers. */
public final class ProductTestCli {
    private ProductTestCli() {}
    public static void main(String[] args) { System.exit(run(args, System.out, System.err, System.getenv(), Thread.currentThread().getContextClassLoader())); }
    public static boolean handles(String[] args) {
        if (args.length == 0 || !"product-test".equals(args[0])) return false;
        int result = run(args, System.out, System.err, System.getenv(), Thread.currentThread().getContextClassLoader());
        if (result != 0) throw new IllegalStateException("Product testing failed; inspect the product-test receipt or diagnostic above");
        return true;
    }
    static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> environment, ClassLoader loader) {
        try {
            if (args.length != 4 || !"product-test".equals(args[0]) || !"run".equals(args[1]) || !"--request".equals(args[2]))
                throw new IllegalArgumentException("usage: product-test run --request <request.json>");
            var request = Json.MAPPER.readValue(LocalFiles.read(Path.of(args[3]), 256 * 1024), ProductTestRequest.class);
            var binding = new FrameworkBinding(Path.of(System.getProperty("java.home"), "bin", "java"),
                    Path.of(required(environment, "SWARM_PRODUCT_TEST_FRAMEWORK_JAR")),
                    required(environment, "SWARM_PRODUCT_TEST_FRAMEWORK_SHA256"),
                    required(environment, "SWARM_PRODUCT_TEST_HANDOFF_SHA256"),
                    Path.of(required(environment, "SWARM_PRODUCT_TEST_ARTIFACT_ROOT")),
                    Path.of(required(environment, "SWARM_PRODUCT_TEST_OUTPUT_ROOT")),
                    Duration.ofMillis(Long.parseLong(environment.getOrDefault("SWARM_PRODUCT_TEST_TIMEOUT_MS", "30000"))),
                    Integer.parseInt(environment.getOrDefault("SWARM_PRODUCT_TEST_MAX_OUTPUT_BYTES", "262144")),
                    environment.get("SWARM_PRODUCT_TEST_FIXTURE_LEASE_SHA256"),environment.get("SWARM_PRODUCT_TEST_SUT_BINDING_SHA256"));
            var module = ProductTestingModules.resolve(request.moduleId(), request.moduleVersion(), new FrameworkCliTransport(binding), loader);
            var receipt = module.execute(request);
            out.println(Json.MAPPER.writeValueAsString(receipt));
            return receipt.outcome() == ProductTestReceipt.Outcome.COMPLETED && "passed".equals(receipt.frameworkVerdict()) ? 0 : 1;
        } catch (Exception e) {
            err.println("product-test: " + e.getMessage()); return 2;
        }
    }
    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing deployment binding: " + name);
        return value;
    }
}
