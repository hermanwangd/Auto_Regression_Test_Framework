package com.specdriven.regression.build;

import com.specdriven.regression.testsupport.TestJvm;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoIbmMqPackagedArtifactIT {
    @TempDir Path temp;
    @Test void actualPackagedDescriptorAndDependencyInventoryMatchTheChosenBuild() throws Exception {
        String expected = System.getProperty("test.expected.runner.variant", "full");
        assertThat(expected).isIn("full", "no-ibm-mq");
        Path jar = TestJvm.packagedJar();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/regression/runner-build.properties");
            assertThat(entry).isNotNull(); assertThat(entry.getSize()).isLessThanOrEqualTo(4096);
            String descriptor = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(descriptor).contains("variant=" + expected + "\n");
            boolean sdk = zip.stream().anyMatch(e -> e.getName().startsWith("BOOT-INF/lib/com.ibm.mq."));
            assertThat(sdk).isEqualTo(expected.equals("full"));
        }
        for (boolean misleadingProperty : new boolean[]{false, true}) {
            Path out = temp.resolve("build-info-" + misleadingProperty + ".json");
            Path err = temp.resolve("build-info-" + misleadingProperty + ".stderr");
            List<String> vmOptions = misleadingProperty
                    ? List.of("-Xmx192m", expected.equals("full") ? "-Drunner.build.variant=no-ibm-mq" : "-Drunner.build.variant=full")
                    : List.of("-Xmx192m");
            Process child = TestJvm.forPackagedJar(vmOptions, "build-info", "--format", "json")
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            try {
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).as("owned build-info JVM deadline").isTrue();
                assertThat(child.exitValue()).as(Files.readString(err)).isZero();
                assertThat(Files.size(out)).isLessThan(1048576);
                String text = Files.readString(out);
                assertThat(text).contains("\"variant\":\"" + expected + "\"");
                assertThat(text).contains(expected.equals("full") ? "PACKAGED_UNVERIFIED" : "UNSUPPORTED");
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                assertThat(child.waitFor(2, TimeUnit.SECONDS)).as("owned child must terminate/reap").isTrue();
            }
        }
    }
}
