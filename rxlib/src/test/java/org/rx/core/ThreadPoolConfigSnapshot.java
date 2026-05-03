package org.rx.core;

import org.rx.bean.IntWaterMark;

import java.util.ArrayList;
import java.util.List;

final class ThreadPoolConfigSnapshot implements AutoCloseable {
    private final RxConfig.ThreadPoolConfig conf;
    private final int initSize;
    private final int keepAliveSeconds;
    private final int queueCapacity;
    private final int cpuWaterMarkLow;
    private final int cpuWaterMarkHigh;
    private final boolean watchSystemCpu;
    private final int replicas;
    private final String traceName;
    private final int maxTraceDepth;
    private final int slowMethodSamplingPercent;
    private final List<String> slowMethodAutoSampleTime;
    private final int cpuLoadWarningThreshold;
    private final long samplingPeriod;
    private final int samplingTimes;
    private final int minIdleSize;
    private final int maxPoolSize;
    private final int resizeStep;
    private final ThreadPoolQueueOfferMode queueOfferMode;
    private final long queueOfferTimeoutMillis;
    private final int serialQueueCapacity;
    private final int serialQueueHardLimit;
    private final boolean patchCompletableFutureAsyncPool;
    private final long resizeCooldownMillis;

    static ThreadPoolConfigSnapshot capture() {
        return new ThreadPoolConfigSnapshot(RxConfig.INSTANCE.getThreadPool());
    }

    private ThreadPoolConfigSnapshot(RxConfig.ThreadPoolConfig conf) {
        this.conf = conf;
        initSize = conf.getInitSize();
        keepAliveSeconds = conf.getKeepAliveSeconds();
        queueCapacity = conf.getQueueCapacity();
        IntWaterMark cpuWaterMark = conf.getCpuWaterMark();
        cpuWaterMarkLow = cpuWaterMark.getLow();
        cpuWaterMarkHigh = cpuWaterMark.getHigh();
        watchSystemCpu = conf.isWatchSystemCpu();
        replicas = conf.getReplicas();
        traceName = conf.getTraceName();
        maxTraceDepth = conf.getMaxTraceDepth();
        slowMethodSamplingPercent = conf.getSlowMethodSamplingPercent();
        slowMethodAutoSampleTime = new ArrayList<>(conf.getSlowMethodAutoSampleTime());
        cpuLoadWarningThreshold = conf.getCpuLoadWarningThreshold();
        samplingPeriod = conf.getSamplingPeriod();
        samplingTimes = conf.getSamplingTimes();
        minIdleSize = conf.getMinIdleSize();
        maxPoolSize = conf.getMaxPoolSize();
        resizeStep = conf.getResizeStep();
        queueOfferMode = conf.getQueueOfferMode();
        queueOfferTimeoutMillis = conf.getQueueOfferTimeoutMillis();
        serialQueueCapacity = conf.getSerialQueueCapacity();
        serialQueueHardLimit = conf.getSerialQueueHardLimit();
        patchCompletableFutureAsyncPool = conf.isPatchCompletableFutureAsyncPool();
        resizeCooldownMillis = conf.getResizeCooldownMillis();
    }

    @Override
    public void close() {
        conf.setInitSize(initSize);
        conf.setKeepAliveSeconds(keepAliveSeconds);
        conf.setQueueCapacity(queueCapacity);
        conf.getCpuWaterMark().setLow(cpuWaterMarkLow);
        conf.getCpuWaterMark().setHigh(cpuWaterMarkHigh);
        conf.setWatchSystemCpu(watchSystemCpu);
        conf.setReplicas(replicas);
        conf.setTraceName(traceName);
        conf.setMaxTraceDepth(maxTraceDepth);
        conf.setSlowMethodSamplingPercent(slowMethodSamplingPercent);
        conf.getSlowMethodAutoSampleTime().clear();
        conf.getSlowMethodAutoSampleTime().addAll(slowMethodAutoSampleTime);
        conf.setCpuLoadWarningThreshold(cpuLoadWarningThreshold);
        conf.setSamplingPeriod(samplingPeriod);
        conf.setSamplingTimes(samplingTimes);
        conf.setMinIdleSize(minIdleSize);
        conf.setMaxPoolSize(maxPoolSize);
        conf.setResizeStep(resizeStep);
        conf.setQueueOfferMode(queueOfferMode);
        conf.setQueueOfferTimeoutMillis(queueOfferTimeoutMillis);
        conf.setSerialQueueCapacity(serialQueueCapacity);
        conf.setSerialQueueHardLimit(serialQueueHardLimit);
        conf.setPatchCompletableFutureAsyncPool(patchCompletableFutureAsyncPool);
        conf.setResizeCooldownMillis(resizeCooldownMillis);
        RxConfig.INSTANCE.afterSet();
    }
}
