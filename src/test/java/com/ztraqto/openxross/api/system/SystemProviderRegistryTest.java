package com.ztraqto.openxross.api.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemProviderRegistryTest {

    private record Provider(String id, int priority) implements XrossSystemProvider {
    }

    @Test
    void registersAndFindsProviderByNormalizedId() {
        SystemProviderRegistry registry = new SystemProviderRegistry();
        Provider provider = new Provider("Example-Provider", 5);
        registry.register(Provider.class, provider);

        assertSame(provider, registry.require(Provider.class, "example-provider"));
    }

    @Test
    void rejectsDuplicateProviderIds() {
        SystemProviderRegistry registry = new SystemProviderRegistry();
        registry.register(Provider.class, new Provider("same", 0));
        assertThrows(IllegalStateException.class,
                () -> registry.register(Provider.class, new Provider("same", 1)));
    }

    @Test
    void sealedRegistryRejectsFurtherRegistration() {
        SystemProviderRegistry registry = new SystemProviderRegistry();
        registry.seal();
        assertThrows(IllegalStateException.class,
                () -> registry.register(Provider.class, new Provider("late", 0)));
    }
}
