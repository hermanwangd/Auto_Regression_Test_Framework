package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewedInvocationBoundaryTest {
    @TempDir Path temporary;
    @Test
    void invocationPinNeverFallsBackToAnUnreviewedRoute() throws Exception {
        List<String[]> routes = List.of(
                new String[]{"--suite", "samples/00-getting-started/golden_e2e/suite_manifest.yaml", "--profile", "local_v03"},
                new String[]{"--suite", "samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/suite_manifest.yaml", "--profile", "local_golden"},
                new String[]{"--suite", "samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml", "--profile", "local_v03"},
                new String[]{"--rp-id", "RP-001", "--env", "local"});
        for (String digest : List.of("a".repeat(64), "", "malformed")) {
            for (String[] route : routes) {
                Path output = Files.createTempDirectory(temporary, "output-");
                var command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService(),
                        Map.of("REGRESS_REVIEWED_HANDOFF_SHA256", digest));
                var args = new ArrayList<String>(); args.add("run");
                for (int i=0;i<route.length;i++) {
                    args.add(route[i]);
                    if ("--suite".equals(route[i])) args.add(Path.of(route[++i]).toAbsolutePath().toString());
                }
                args.addAll(List.of("--root", output.toString()));
                var stdout = new ByteArrayOutputStream();
                int exit = command.execute(args.toArray(String[]::new), new PrintStream(stdout),
                        new PrintStream(new ByteArrayOutputStream()));
                assertThat(exit).isNotZero();
                assertThat(stdout.toString()).contains("REVIEWED_HANDOFF_REQUIRED", "provider_runtime_invoked: false");
                assertThat(Files.exists(output.resolve("target/regression"))).isFalse();
            }
        }
    }

    @Test void removingOrNullingReviewedPolicyAndChangingProfileCannotDowngrade() throws Exception {
        for (String alteration : List.of("removed", "null", "different-profile")) {
            Path source = Path.of("samples/41-product-testing/reviewed_local");
            Path suiteRoot = Files.createTempDirectory(temporary.toRealPath(), "suite-");
            try (var files = Files.walk(source)) {
                for (Path path : files.toList()) {
                    Path target = suiteRoot.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path,target);
                }
            }
            String pin = Files.readString(suiteRoot.resolve("handoff/reviewed-tests.json.sha256")).trim();
            Path profile = suiteRoot.resolve("env_profiles/local_v03.yaml");
            var yaml = new org.yaml.snakeyaml.Yaml();
            Map<String,Object> document = yaml.load(Files.readString(profile));
            if (alteration.equals("removed")) document.remove("product_test");
            if (alteration.equals("null")) document.put("product_test",null);
            Files.writeString(profile,yaml.dump(document));
            var command = new RegressionCommand(new ProductRepoService(),new ReleasePackageService(),
                    Map.of("REGRESS_REVIEWED_HANDOFF_SHA256",pin));
            Path output = Files.createTempDirectory(temporary.toRealPath(),"output-");
            var stdout = new ByteArrayOutputStream();
            int exit = command.execute(new String[]{"run","--suite",suiteRoot.resolve("suite_manifest.yaml").toString(),
                    "--profile",alteration.equals("different-profile") ? "unreviewed" : "local_v03", "--root",output.toString()},
                    new PrintStream(stdout),new PrintStream(new ByteArrayOutputStream()));
            assertThat(exit).isNotZero();
            assertThat(stdout.toString()).contains("blocked", "REVIEWED_HANDOFF_REQUIRED");
            // Establishing the framework-owned empty output root is not a provider invocation.
            try (var artifacts = Files.walk(output)) {
                assertThat(artifacts.filter(Files::isRegularFile).toList()).isEmpty();
            }
        }
    }
}
