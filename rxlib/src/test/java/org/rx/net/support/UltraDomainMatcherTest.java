package org.rx.net.support;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UltraDomainMatcherTest {
    @Test
    public void testFindBaseChecksConflictsAfterOutOfRangeLabel() throws Exception {
        UltraDomainTrieMatcher matcher = new UltraDomainTrieMatcher();
        Field checkField = UltraDomainTrieMatcher.class.getDeclaredField("check");
        checkField.setAccessible(true);

        int[] check = new int[10];
        Arrays.fill(check, -1);
        check[6] = 99;
        checkField.set(matcher, check);

        Method findBase = UltraDomainTrieMatcher.class.getDeclaredMethod("findBase", java.util.List.class, int.class);
        findBase.setAccessible(true);
        int base = (Integer) findBase.invoke(matcher, Arrays.asList(100, 1), 5);

        assertEquals(6, base);
    }
}
