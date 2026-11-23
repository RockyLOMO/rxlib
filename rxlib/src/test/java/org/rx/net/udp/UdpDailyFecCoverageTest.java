package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class UdpDailyFecCoverageTest {
    private static final int COUNT = 512;
    private static final int[] RATES = {20, 50, 100, 200, 300, 1000};

    private static final class Run extends UdpDailyFecTest.Link {
        int dropped;
        long protectedShards, parityFrames;

        void transmit(Predicate<ByteBuf> lose) {
            while (!packets.isEmpty()) {
                ByteBuf frame = packets.remove();
                int pos = frame.readerIndex();
                boolean parity = frame.getUnsignedByte(pos) == UdpDailyFec.PARITY;
                if (parity) {
                    parityFrames++;
                    protectedShards += frame.getUnsignedByte(pos + 5);
                }
                if (lose.test(frame)) {
                    if (!parity) dropped++;
                    frame.release();
                } else {
                    receive(frame);
                }
            }
        }

        void advance(long nanos, Predicate<ByteBuf> lose) {
            long end = time.get() + nanos;
            long due = sender.nextDelayMillis();
            if (due >= 0 && TimeUnit.MILLISECONDS.toNanos(due) <= nanos) {
                time.addAndGet(TimeUnit.MILLISECONDS.toNanos(due));
                sender.tick(this::queue);
                transmit(lose);
            }
            time.set(end);
            receiver.tick(this::queue);
        }

        void measure(int first, long interval, Predicate<ByteBuf> lose) {
            for (int i = 0; i < COUNT; i++) {
                advance(interval, lose);
                send(first + i);
                transmit(lose);
            }
            advance(TimeUnit.MILLISECONDS.toNanos(300), lose);
            assertEquals(COUNT - dropped + receiver.recoveredData, delivered.size());
            assertEquals(delivered.size(), new HashSet<>(delivered).size());
            assertEquals(protectedShards, sender.protectedData);
            assertEquals(parityFrames, sender.paritySent);
            assertEquals(first + COUNT, sender.sentData);
        }
    }

    @Test
    void fixedTierCoverageOverAllLossPositions() {
        for (int pps : new int[]{20, 50, 100}) {
            for (int target : new int[]{16, 8, 4}) {
                long covered = 0, recovered = 0, dropped = 0;
                for (int offset = 0; offset < 16; offset++) {
                    try (Run run = new Run()) {
                        long interval = TimeUnit.SECONDS.toNanos(1) / pps;
                        run.fixedMissing = target == 16 ? 1 : target == 8 ? 2 : 4;
                        run.introduceLoss(interval);
                        final int phase = offset;
                        run.measure(40, interval, frame -> {
                            int pos = frame.readerIndex();
                            return frame.getUnsignedByte(pos) != UdpDailyFec.PARITY
                                    && ((frame.getInt(pos + 1) - 40) & 15) == phase;
                        });
                        assertTrue(run.parityFrames <= COUNT / target);
                        covered += run.protectedShards;
                        recovered += run.receiver.recoveredData;
                        dropped += run.dropped;
                    }
                }
                // One loss per 16 data frames, so every protected loss is correctable.
                assertEquals(covered / 16, recovered);
                // Allow a final partial group per phase while guarding the measured coverage floor.
                int shards = pps == 20 ? 4 : pps == 50 ? 8 : 16;
                long floor = COUNT * 16L * Math.min(shards, target) / target - 16L * target;
                assertTrue(covered >= floor, "coverage at pps=" + pps + " K=" + target);
                System.out.printf("Fixed FEC pps=%d K=%d covered=%d/%d recovered=%d/%d%n",
                        pps, target, covered, COUNT * 16, recovered, dropped);
            }
        }
    }

    @Test
    void adaptiveRecoveryWithRandomAndBurstLoss() {
        for (int pps : RATES) {
            for (int percent : new int[]{0, 1, 5, 10, 20}) {
                for (boolean burst : new boolean[]{false, true}) {
                    long recovered = 0, dropped = 0, parity = 0;
                    for (int seed : new int[]{17, 43, 89}) {
                        Random random = new Random(seed);
                        double probability = percent / 100.0;
                        try (Run run = new Run()) {
                            // In burst mode use a two-state chain with mean loss-run length three.
                            run.measure(0, TimeUnit.SECONDS.toNanos(1) / pps, new Predicate<ByteBuf>() {
                                boolean lost;

                                @Override public boolean test(ByteBuf frame) {
                                    double threshold = !burst ? probability : lost ? 2.0 / 3
                                            : probability / (3 * (1 - probability));
                                    return lost = random.nextDouble() < threshold;
                                }
                            });
                            assertTrue(run.parityFrames <= COUNT / 4);
                            if (percent == 0) assertEquals(0, run.parityFrames);
                            recovered += run.receiver.recoveredData;
                            dropped += run.dropped;
                            parity += run.parityFrames;
                        }
                    }
                    if (percent == 5 && !burst) assertTrue(recovered > 0);
                    System.out.printf("Adaptive FEC pps=%d loss=%d burst=%s recovered=%d/%d parity=%d%n",
                            pps, percent, burst, recovered, dropped, parity);
                }
            }
        }
    }
}
