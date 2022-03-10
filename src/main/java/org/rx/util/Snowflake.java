package org.rx.util;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.RxConfig;

/**
 * 1位标识部分    -      41位时间戳部分        -         10位节点部分     12位序列号部分
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 0000000000 - 000000000000
 */
@Slf4j
public final class Snowflake {
    public static final Snowflake DEFAULT;

    static {
        int m = Math.abs(RxConfig.INSTANCE.getIntId()) % 1023;
        DEFAULT = new Snowflake(m);
        log.info("Snowflake machineId={}", m);
    }

    public static Snowflake nextInstance() {
        return new Snowflake(ThreadLocalRandom.current().nextInt(0, (int) MAX_MACHINE_NUM));
    }

    /**
     * 起始时间戳
     */
//    private static final long START_STAMP = 1480166465631L;
    private static final long START_STAMP = DateTime.valueOf("2020-02-04 00:00:00").getTime();
    private static final short MACHINE_BIT = 10;
    private static final short SEQUENCE_BIT = 12;

    private static final long MAX_MACHINE_NUM = ~(-1L << MACHINE_BIT); //1023
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BIT);   //4095
    /**
     * 每一部分左位移
     */
    private static final short MACHINE_LEFT = SEQUENCE_BIT;
    private static final short TIMESTAMP_LEFT = MACHINE_LEFT + MACHINE_BIT;
    private static final short STEP_SIZE = 2 << 9; //1024

    private final long machineId;
    private long sequence;
    private long lastStamp = -1L;
    /**
     * 基础序列号，每发生一次时钟回拨，basicSequence += STEP_SIZE
     */
    private long basicSequence;

    public Snowflake(int machineId) {
        if (machineId > MAX_MACHINE_NUM || machineId < 0) {
            throw new IllegalArgumentException("machineId can't be greater than MAX_MACHINE_NUM or less than 0");
        }
        this.machineId = machineId;
    }

    public synchronized long nextId() {
        long curStamp = System.currentTimeMillis();
        if (curStamp < lastStamp) {
//            throw new RuntimeException("Clock moved backwards. Refusing to generate id");
            return handleClockBackwards(curStamp);
        }

        if (curStamp == lastStamp) {
            //相同毫秒内，序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            //同一毫秒的序列数已经达到最大
            if (sequence == 0L) {
                curStamp = getNextMill();
            }
        } else {
            //不同毫秒内，序列号置为0
//            sequence = 0L;
            //不同毫秒内，序列号置为 basicSequence
            sequence = basicSequence;
        }

        lastStamp = curStamp;

        return (curStamp - START_STAMP) << TIMESTAMP_LEFT   //时间戳部分
                | machineId << MACHINE_LEFT                 //机器标识部分
                | sequence;                                 //序列号部分
    }

    private long handleClockBackwards(long curStamp) {
        basicSequence += STEP_SIZE;
        if (basicSequence == MAX_SEQUENCE + 1) {
            basicSequence = 0;
            curStamp = getNextMill();
        }
        sequence = basicSequence;
        lastStamp = curStamp;
        return (curStamp - START_STAMP) << TIMESTAMP_LEFT
                | machineId << MACHINE_LEFT
                | sequence;
    }

    private long getNextMill() {
        long mill = System.currentTimeMillis();
        while (mill <= lastStamp) {
            mill = System.currentTimeMillis();
        }
        return mill;
    }
}
