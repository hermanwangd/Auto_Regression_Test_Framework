package com.specdriven.regression.provider.ibmmq;

import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.contract.v03.NoIbmMqRegistryTest;
import com.specdriven.regression.contract.v03.NoIbmMqRegistryTest.NoMqLoader;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class IbmMqRuntimeFactoryTest {
    @Test void omittedFactoryDoesNotResolveSdkAndRefusesDirectExecution() throws Exception {
        try (NoMqLoader loader = new NoMqLoader(NoIbmMqRegistryTest.NO_MQ)) {
            Object runtime = loader.loadClass("com.specdriven.regression.provider.ibmmq.IbmMqRuntimeFactory")
                    .getMethod("create").invoke(null);
            Class<?> api = loader.loadClass("com.specdriven.regression.provider.runtime.ProviderRuntime");
            Throwable failure = null;
            try { api.getMethod("execute",
                    loader.loadClass("com.specdriven.regression.provider.runtime.ProviderExecutionContext"),
                    loader.loadClass("com.specdriven.regression.provider.runtime.ProviderOperationRequest"))
                    .invoke(runtime, null, null); }
            catch (InvocationTargetException error) { failure = error.getCause(); }
            assertThat(failure).isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("IBM MQ UNSUPPORTED");
            assertThat(loader.vendorLoads).isEmpty();
        }
    }
    @Test void aFullDescriptorWithoutTheSdkResourceDoesNotEnableMq() throws Exception {
        try (NoMqLoader loader = new NoMqLoader("schema=runner-build.v1\nvariant=full\nibm_mq=available\n")) {
            assertThat((Boolean)loader.loadClass("com.specdriven.regression.build.RunnerBuildVariant")
                    .getMethod("ibmMqAvailable").invoke(null)).isFalse();
            assertThat(loader.vendorLoads).isEmpty();
        }
    }

    @Test void missingAndDuplicateResourcesFailClosedWithoutSdk() throws Exception {
        for (String descriptor : new String[]{null, "unexpected=value\n"}) {
            try (NoMqLoader loader = new NoMqLoader(descriptor)) {
                assertThat((Boolean)loader.loadClass("com.specdriven.regression.build.RunnerBuildVariant")
                        .getMethod("ibmMqAvailable").invoke(null)).isFalse();
                assertThat(loader.vendorLoads).isEmpty();
            }
        }
        try (NoMqLoader loader = new NoMqLoader(NoIbmMqRegistryTest.NO_MQ, true)) {
            assertThat((Boolean)loader.loadClass("com.specdriven.regression.build.RunnerBuildVariant")
                    .getMethod("ibmMqAvailable").invoke(null)).isFalse();
            assertThat(loader.vendorLoads).isEmpty();
        }
    }
}
