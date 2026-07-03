package com.specdriven.regression.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenFailsafeConfigurationTest {

    @Test
    void pomConfiguresFailsafeForFrameworkIntegrationVerification() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<artifactId>maven-failsafe-plugin</artifactId>");
        assertThat(pom).contains("<goal>integration-test</goal>");
        assertThat(pom).contains("<goal>verify</goal>");
    }

    @Test
    void pomConfiguresJacocoReportForVerifyAndDeliveryCoverageGate() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<artifactId>jacoco-maven-plugin</artifactId>");
        assertThat(pom).contains("<goal>prepare-agent</goal>");
        assertThat(pom).contains("<goal>report</goal>");
        assertThat(pom).contains("<id>v02-delivery-coverage</id>");
        assertThat(pom).contains("<id>v02-delivery-critical-class-check</id>");
        assertThat(pom).contains("<goal>check</goal>");
        assertThat(pom).contains("<counter>LINE</counter>");
        assertThat(pom).contains("<minimum>0.88</minimum>");
        assertThat(pom).contains("<minimum>0.97</minimum>");
    }
}
