package org.rx.core.cache;

import org.junit.jupiter.api.Test;
import org.rx.core.CachePolicy;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryCacheTest {
    @Test
    void constructorShouldNotFailDuringStaticInitialization() {
        MemoryCache<String, String> cache = assertDoesNotThrow(() -> new MemoryCache<>());
        assertNotNull(cache);
    }

    @Test
    void putWithoutPolicyShouldClearPreviousPolicy() {
        MemoryCache<String, String> cache = new MemoryCache<>();
        cache.put("k", "v1", CachePolicy.absolute(60));
        assertTrue(cache.policyMap.containsKey("k"));

        cache.put("k", "v2");

        assertFalse(cache.policyMap.containsKey("k"));
    }

    @Test
    void putAllAndRemoveShouldClearPolicies() {
        MemoryCache<String, String> cache = new MemoryCache<>();
        cache.put("k1", "v1", CachePolicy.absolute(60));
        cache.put("k2", "v2", CachePolicy.absolute(60));
        assertTrue(cache.policyMap.containsKey("k1"));
        assertTrue(cache.policyMap.containsKey("k2"));

        cache.putAll(Collections.singletonMap("k1", "v3"));
        assertFalse(cache.policyMap.containsKey("k1"));

        cache.remove("k2");
        assertFalse(cache.policyMap.containsKey("k2"));
    }

    @Test
    void clearShouldClearPoliciesImmediately() {
        MemoryCache<String, String> cache = new MemoryCache<>();
        cache.put("k", "v", CachePolicy.absolute(60));
        assertTrue(cache.policyMap.containsKey("k"));

        cache.clear();

        assertFalse(cache.policyMap.containsKey("k"));
    }
}
