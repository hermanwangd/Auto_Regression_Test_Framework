package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.contract.v03.NoIbmMqRegistryTest;
import com.specdriven.regression.contract.v03.NoIbmMqRegistryTest.NoMqLoader;
import java.io.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoIbmMqBuildInfoTest {
    @Test void cliBuildInfoStartsWithoutSdkAndDeclaresTheOmission() throws Exception {
        try (NoMqLoader loader = new NoMqLoader(NoIbmMqRegistryTest.NO_MQ)) {
            Class<?> product = loader.loadClass("com.specdriven.regression.productrepo.ProductRepoService");
            Class<?> release = loader.loadClass("com.specdriven.regression.discovery.ReleasePackageService");
            Class<?> cli = loader.loadClass("com.specdriven.regression.cli.RegressionCommand");
            Object command = cli.getConstructor(product, release).newInstance(
                    product.getConstructor().newInstance(), release.getConstructor().newInstance());
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            int exit = (Integer)cli.getMethod("execute", String[].class, PrintStream.class, PrintStream.class).invoke(command,
                    (Object)new String[]{"build-info", "--format", "json"}, new PrintStream(out), new PrintStream(err));
            assertThat(exit).as(err.toString()).isZero();
            Map<?,?> info = new ObjectMapper().readValue(out.toByteArray(), Map.class);
            assertThat(info.get("variant")).isEqualTo("no-ibm-mq");
            assertThat(info.get("ibm_mq")).isEqualTo("UNSUPPORTED");
            assertThat(info.get("availability_scope")).isEqualTo("PACKAGING_NOT_PROTOCOL_ACCEPTANCE");
            assertThat(loader.vendorLoads).isEmpty();
        }
    }
}
