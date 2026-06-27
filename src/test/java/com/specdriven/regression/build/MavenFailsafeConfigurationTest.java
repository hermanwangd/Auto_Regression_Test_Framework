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
}
