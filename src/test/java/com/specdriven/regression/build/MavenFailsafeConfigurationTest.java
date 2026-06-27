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
    void pomConfiguresJacocoCoverageGateForFrameworkVerification() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<artifactId>jacoco-maven-plugin</artifactId>");
        assertThat(pom).contains("<goal>prepare-agent</goal>");
        assertThat(pom).contains("<goal>report</goal>");
        assertThat(pom).contains("<goal>check</goal>");
        assertThat(pom).contains("<counter>INSTRUCTION</counter>");
        assertThat(pom).contains("<minimum>0.90</minimum>");
        assertThat(pom).contains("<counter>CLASS</counter>");
        assertThat(pom).contains("<minimum>1.00</minimum>");
    }
}
