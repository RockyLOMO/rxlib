package org.rx.bean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 RandomList 权重区间采样，避免负载均衡权重退化成近似均匀随机。
 */
class RandomListProbabilityTest {
    private static final int SAMPLES = 50_000;

    @Test
    void next_RespectsNineToOneWeightDistribution() {
        RandomList<String> list = new RandomList<>();
        list.add("primary", 9);
        list.add("backup", 1);

        int primaryHits = 0;
        int backupHits = 0;
        for (int i = 0; i < SAMPLES; i++) {
            String selected = list.next();
            if ("primary".equals(selected)) {
                primaryHits++;
            } else if ("backup".equals(selected)) {
                backupHits++;
            }
        }

        assertEquals(SAMPLES, primaryHits + backupHits);
        double primaryRatio = primaryHits / (double) SAMPLES;
        assertTrue(primaryRatio >= 0.86D && primaryRatio <= 0.94D,
                "w=9 期望约 90%，实际 primary=" + primaryHits
                        + ", backup=" + backupHits + ", ratio=" + primaryRatio);
    }
}
