package org.rx.core.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MemoryCacheTest {
    @Test
    void constructorShouldNotFailDuringStaticInitialization() {
        MemoryCache<String, String> cache = assertDoesNotThrow(() -> new MemoryCache<>());
        assertNotNull(cache);
    }
}
