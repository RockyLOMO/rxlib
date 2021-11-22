package org.rx.bean;

/**
 * 1位标识部分    -      41位时间戳部分        -         10位节点部分     12位序列号部分
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
 */
public final class SnowFlake {
    /**
     * 起始的时间戳
     */
//    private static final long START_STAMP = 1480166465631L;
    private static final long START_STAMP = DateTime.valueOf("2020-02-04 00:00:00").getTime();
    /**
     * 每一部分占用的位数
     */
    private static final long SEQUENCE_BIT = 12; //序列号占用的位数
    private static final long DATACENTER_BIT = 5;//数据中心占用的位数
    private static final long MACHINE_BIT = 5;   //机器标识占用的位数
    /**
     * 每一部分的最大值
     */
    private static final long MAX_DATACENTER_NUM = ~(-1L << DATACENTER_BIT);
    private static final long MAX_MACHINE_NUM = ~(-1L << MACHINE_BIT);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BIT);
    /**
     * 每一部分向左的位移
     */
    private static final long DATACENTER_LEFT = SEQUENCE_BIT + MACHINE_BIT;
    private static final long MACHINE_LEFT = SEQUENCE_BIT;
    private static final long TIMESTAMP_LEFT = DATACENTER_LEFT + DATACENTER_BIT;

    private final long datacenterId;  //数据中心
    private final long machineId;     //机器标识
    private long sequence = 0L; //序列号
    private long lastStamp = -1L;//上一次时间戳
    /**
     * 步长, 1024
     */
    private static final long stepSize = 2 << 9;
    /**
     * 基础序列号, 每发生一次时钟回拨, basicSequence += stepSize
     */
    private long basicSequence = 0L;

    public SnowFlake(long datacenterId, long machineId) {
        if (datacenterId > MAX_DATACENTER_NUM || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId can't be greater than MAX_DATACENTER_NUM or less than 0");
        }
        if (machineId > MAX_MACHINE_NUM || machineId < 0) {
            throw new IllegalArgumentException("machineId can't be greater than MAX_MACHINE_NUM or less than 0");
        }
        this.datacenterId = datacenterId;
        this.machineId = machineId;
    }

    /**
     * 产生下一个ID
     *
     * @return
     */
    public synchronized long nextId() {
        long currStmp = System.currentTimeMillis();
        if (currStmp < lastStamp) {
//            throw new RuntimeException("Clock moved backwards.  Refusing to generate id");
            return handleClockBackwards(currStmp);
        }

        if (currStmp == lastStamp) {
            //相同毫秒内，序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            //同一毫秒的序列数已经达到最大
            if (sequence == 0L) {
                currStmp = getNextMill();
            }
        } else {
            //不同毫秒内，序列号置为0
//            sequence = 0L;
            // 不同毫秒内，序列号置为 basicSequence
            sequence = basicSequence;
        }

        lastStamp = currStmp;

        return (currStmp - START_STAMP) << TIMESTAMP_LEFT   //时间戳部分
                | datacenterId << DATACENTER_LEFT           //数据中心部分
                | machineId << MACHINE_LEFT                 //机器标识部分
                | sequence;                                 //序列号部分
    }

    /**
     * 处理时钟回拨
     */
    private long handleClockBackwards(long currStmp) {
        basicSequence += stepSize;
        if (basicSequence == MAX_SEQUENCE + 1) {
            basicSequence = 0;
            currStmp = getNextMill();
        }
        sequence = basicSequence;
        lastStamp = currStmp;
        return (currStmp - START_STAMP) << TIMESTAMP_LEFT
                | datacenterId << DATACENTER_LEFT
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
