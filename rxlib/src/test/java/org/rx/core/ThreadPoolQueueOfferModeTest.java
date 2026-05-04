package org.rx.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadPoolQueueOfferModeTest {
    @Test
    void parseShouldTrimAndAcceptRelaxedNames() {
        assertEquals(ThreadPoolQueueOfferMode.TIMEOUT_REJECT,
                ThreadPoolQueueOfferMode.parse(" timeout-reject ", ThreadPoolQueueOfferMode.BLOCK));
        assertEquals(ThreadPoolQueueOfferMode.CALLER_RUNS,
                ThreadPoolQueueOfferMode.parse("caller runs", ThreadPoolQueueOfferMode.BLOCK));
    }

    @Test
    void parseShouldFallbackWhenValueInvalid() {
        assertEquals(ThreadPoolQueueOfferMode.CALLER_RUNS,
                ThreadPoolQueueOfferMode.parse("bad-mode", ThreadPoolQueueOfferMode.CALLER_RUNS));
    }
}
