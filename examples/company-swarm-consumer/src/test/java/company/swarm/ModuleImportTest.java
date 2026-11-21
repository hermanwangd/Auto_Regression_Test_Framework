package company.swarm;

import com.featuredeliveryintelligence.fdi.producttest.ProductTestingModuleProvider;
import com.featuredeliveryintelligence.fdi.producttest.ProductTestingModules;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ModuleImportTest {
    @Test
    void aCleanConsumerCanImportAndDiscoverTheModuleWithoutSwarmHostSources() {
        var providers = ServiceLoader.load(ProductTestingModuleProvider.class)
                .stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(1, providers.size(), "The default combined JAR should register one provider");
        assertEquals("product-test", providers.get(0).id());
        assertEquals("1", providers.get(0).version());

        var module = ProductTestingModules.resolve(
                "product-test", "1",
                request -> { throw new AssertionError("Import-only test must not execute a framework"); },
                Thread.currentThread().getContextClassLoader());
        assertEquals("product-test", module.id());
        assertEquals("1", module.version());
    }
}
