package org.rx.core;

import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.filter.ValueFilter;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import io.netty.util.Timeout;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rx.annotation.Subscribe;
import org.rx.bean.DynamicProxyBean;
import org.rx.bean.LogStrategy;
import org.rx.codec.CodecUtil;
import org.rx.exception.FallbackException;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.util.Lazy;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;
import org.rx.util.function.Func;
import org.rx.util.function.TripleFunc;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import org.springframework.cglib.proxy.Enhancer;

import java.io.File;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.fastjson2.JSONReader.Feature.AllowUnQuotedFieldNames;
import static com.alibaba.fastjson2.JSONReader.Feature.SupportClassForName;
import static com.alibaba.fastjson2.JSONWriter.Feature.NotWriteDefaultValue;
import static org.rx.core.Constants.*;
import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.RxConfig.ConfigNames.NTP_ENABLE_FLAGS;
import static org.rx.core.RxConfig.ConfigNames.getWithoutPrefix;

@Slf4j
@SuppressWarnings(Constants.NON_UNCHECKED)
public final class Sys extends SystemUtils {
    @Getter
    @RequiredArgsConstructor
    public static class Info implements Serializable {
        private static final long serialVersionUID = -1263477025428108392L;
        private final int cpuThreads;
        private final double cpuLoad;
        private final int liveThreadCount;
        private final long freePhysicalMemory;
        private final long totalPhysicalMemory;
        private final Linq<DiskInfo> disks;

        public int getCpuLoadPercent() {
            return Numbers.toPercent(cpuLoad);
        }

        public long getUsedPhysicalMemory() {
            return totalPhysicalMemory - freePhysicalMemory;
        }

        public int getUsedPhysicalMemoryPercent() {
            return Numbers.toPercent((double) getUsedPhysicalMemory() / totalPhysicalMemory);
        }

        public boolean hasCpuLoadWarning() {
            return getCpuLoadPercent() > RxConfig.INSTANCE.threadPool.cpuLoadWarningThreshold;
        }

        public boolean hasPhysicalMemoryUsageWarning() {
            return getUsedPhysicalMemoryPercent() > RxConfig.INSTANCE.cache.physicalMemoryUsageWarningThreshold;
        }

        public boolean hasDiskUsageWarning() {
            return disks.any(DiskInfo::hasDiskUsageWarning);
        }

        public DiskInfo getSummedDisk() {
            return disks.groupBy(p -> true, (p, x) -> new DiskInfo("SummedDisk", "/", (long) x.sum(y -> y.freeSpace), (long) x.sum(y -> y.totalSpace), false)).first();
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class DiskInfo implements Serializable {
        private static final long serialVersionUID = -9137708658583628112L;
        private final String name;
        private final String path;
        private final long freeSpace;
        private final long totalSpace;
        private final boolean bootstrapDisk;

        public long getUsedSpace() {
            return totalSpace - freeSpace;
        }

        public int getUsedPercent() {
            return Numbers.toPercent((double) getUsedSpace() / totalSpace);
        }

        public boolean hasDiskUsageWarning() {
            return getUsedPercent() > RxConfig.INSTANCE.disk.diskUsageWarningThreshold;
        }
    }

    public static final HotSpotDiagnosticMXBean diagnosticMx = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    static final String DPT = "_DPT";
    static final Pattern PATTERN_TO_FIND_OPTIONS = Pattern.compile("(?<=-).*?(?==)");
    static final JSONReader.Feature[] JSON_READ_FLAGS = new JSONReader.Feature[]{SupportClassForName, AllowUnQuotedFieldNames};
    static final JSONWriter.Feature[] JSON_WRITE_FLAGS = new JSONWriter.Feature[]{NotWriteDefaultValue};
    public static final ValueFilter JSON_WRITE_SKIP_TYPES = (o, k, v) -> {
        if (v == null) {
            return null;
        }
        Iterable<Object> iter = Linq.asIterable(v, false);
        if (iter != null) {
            return Linq.from(iter).select(iv -> jsonValueFilter(o, k, iv)).toList(); //fastjson2 iterable issues
        }
        return jsonValueFilter(o, k, v);
    };
    static final String[] seconds = {"ns", "µs", "ms", "s"};
    static Timeout samplingTimeout;
    static byte transformedFlags;

    static {
        RxConfig conf = RxConfig.INSTANCE;
        log.info("RxMeta {} {}_{}_{} @ {} & {}\n{}", JAVA_VERSION, OS_NAME, OS_VERSION, OS_ARCH,
                new File(Strings.EMPTY).getAbsolutePath(), Sockets.getLocalAddresses(false), JSON.toJSONString(conf));

//        ObjectChangeTracker.DEFAULT.watch(conf, true)
//                .register(Sys.class)
//                .register(Tasks.class)
//                .register(TraceHandler.INSTANCE);
        ObjectChangeTracker.DEFAULT.watch(conf).register(Sys.class);
        onChanged(new ObjectChangedEvent(conf, Collections.emptyMap()));
    }

    @Subscribe(topicClass = RxConfig.class)
    static void onChanged(ObjectChangedEvent event) {
        Map<String, ObjectChangeTracker.ChangedValue> changedMap = event.getChangedMap();
//        log.info("RxMeta Sys changed {}", changedMap);
        Integer enableFlags = event.readValue(getWithoutPrefix(NTP_ENABLE_FLAGS));
        if (enableFlags == null) {
            return;
        }
        log.info("RxMeta {} changed {}", NTP_ENABLE_FLAGS, enableFlags);
        if ((enableFlags & 1) == 1) {
            NtpClock.scheduleTask();
        }
        if ((enableFlags & 2) == 2) {
            Tasks.setTimeout(() -> {
                log.info("TimeAdvice inject..");
                NtpClock.transform();
            }, 60000);
        }
    }

    static void checkAdviceShare(boolean isInit) {
        Properties props = System.getProperties();
        Object v = props.get(ADVICE_SHARE_KEY);
        boolean changed = false;
        Object[] share;
        if (!(v instanceof Object[]) || (share = (Object[]) v).length != ADVICE_SHARE_LEN) {
            share = new Object[ADVICE_SHARE_LEN];
            changed = true;
        }

        v = share[ADVICE_SHARE_TIME_INDEX];
        long[] time;
        if (!(v instanceof long[]) || ((long[]) v).length != 2) {
            if (isInit) {
                time = new long[2];
                time[1] = System.currentTimeMillis();
                time[0] = System.nanoTime();
                share[ADVICE_SHARE_TIME_INDEX] = time;
            } else {
                share[ADVICE_SHARE_TIME_INDEX] = null;
            }
        }

        v = share[ADVICE_SHARE_FORK_JOIN_FUNC_INDEX];
        if (!(v instanceof Function)) {
            share[ADVICE_SHARE_FORK_JOIN_FUNC_INDEX] = ForkJoinPoolWrapper.ADVICE_FN;
        }

        if (changed) {
            props.put(ADVICE_SHARE_KEY, share);
        }
    }

    static long[] getAdviceShareTime() {
        Properties props = System.getProperties();
        Object v = props.get(ADVICE_SHARE_KEY);
        Object[] share;
        if (!(v instanceof Object[]) || (share = (Object[]) v).length != ADVICE_SHARE_LEN) {
            return null;
        }
        v = share[ADVICE_SHARE_TIME_INDEX];
        long[] time;
        return !(v instanceof long[]) || (time = (long[]) v).length != 2 ? null : time;
    }

    //region basic
    public static Map<String, String> mainOptions(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                Matcher matcher = PATTERN_TO_FIND_OPTIONS.matcher(arg);
                if (matcher.find()) {
                    result.put(matcher.group(), arg.replaceFirst("-.*?=", Strings.EMPTY));
                }
            }
        }
        return result;
    }

    public static List<String> mainOperations(String[] args) {
        List<String> result = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                break;
            }
            result.add(arg);
        }
        return result;
    }

    public static File getJarFile(Object obj) {
        return getJarFile(obj.getClass());
    }

    @SneakyThrows
    public static File getJarFile(Class<?> klass) {
        String url = klass.getClassLoader()
                .getResource(klass.getPackage().getName().replace(".", "/")).toString()
                .replace(" ", "%20");
        URI uri = new URI(url);
        if (uri.getPath() != null) {
            return new File(uri);
        }
        String path = uri.toString();
        if (!path.startsWith("jar:file:")) {
            return null;
        }
        //Update Path and Define Zipped File
        path = path.substring(path.indexOf("file:/"));
        path = path.substring(0, path.toLowerCase().indexOf(".jar") + 4);
        if (path.startsWith("file://")) { //UNC Path
            path = "C:/" + path.substring(path.indexOf("file:/") + 7);
            path = "/" + new URI(path).getPath();
        } else {
            path = new URI(path).getPath();
        }
        return new File(path);
    }

    public static <T> T fallbackProxy(Class<T> targetType, Lazy<T> target, Lazy<T> fallbackTarget) {
        return fallbackProxy(targetType, target, fallbackTarget, null);
    }

    public static <T> T fallbackProxy(@NonNull Class<T> targetType, Lazy<T> target, Lazy<T> fallbackTarget, BiFunc<FallbackException, Object> onError) {
        return proxy(targetType, (m, p) -> innerFallbackProxy(m, p, target.getValue(), fallbackTarget, onError));
    }

    public static <T> T fallbackProxy(Class<T> targetType, T target, Lazy<T> fallbackTarget) {
        return fallbackProxy(targetType, target, fallbackTarget, null);
    }

    public static <T> T fallbackProxy(@NonNull Class<T> targetType, T target, Lazy<T> fallbackTarget, BiFunc<FallbackException, Object> onError) {
        return proxy(targetType, (m, p) -> innerFallbackProxy(m, p, target, fallbackTarget, onError));
    }

    static <T> Object innerFallbackProxy(Method m, DynamicProxyBean p, @NonNull T target, @NonNull Lazy<T> fallbackTarget, BiFunc<FallbackException, Object> onError) {
        try {
            return p.fastInvoke(target);
        } catch (Throwable e) {
            T fallbackTargetValue = fallbackTarget.getValue();
            try {
                Object r = p.fastInvoke(fallbackTargetValue);
                log.warn("fallbackProxy", e);
                return r;
            } catch (Throwable fe) {
                FallbackException fb = new FallbackException(m, p, target, fallbackTargetValue, e, fe);
                if (onError == null) {
                    throw fb;
                }
                return onError.apply(fb);
            }
        }
    }

    public static <T> T targetObject(Object proxyObject) {
        return IOC.<String, T>weakMap(proxyObject).get(DPT);
    }

    public static <T> T proxy(Class<?> type, TripleFunc<Method, DynamicProxyBean, Object> func) {
        return proxy(type, func, false);
    }

    public static <T> T proxy(Class<?> type, TripleFunc<Method, DynamicProxyBean, Object> func, boolean jdkProxy) {
        return proxy(type, func, null, jdkProxy);
    }

    public static <T> T proxy(Class<?> type, TripleFunc<Method, DynamicProxyBean, Object> func, T rawObject, boolean jdkProxy) {
        T proxyObj;
        if (jdkProxy) {
            proxyObj = (T) Proxy.newProxyInstance(Reflects.getClassLoader(), new Class[]{type}, new DynamicProxyBean(func));
        } else {
            proxyObj = (T) Enhancer.create(type, new DynamicProxyBean(func));
        }
        if (rawObject != null) {
            IOC.weakMap(proxyObj).put(DPT, rawObject);
        }
        return proxyObj;
    }

    public static <T> ArrayList<T> proxyList(ArrayList<T> source, BiAction<ArrayList<T>> onSet) {
        return proxy(ArrayList.class, (m, p) -> {
            Object val = p.fastInvoke(source);
            if (onSet != null && Reflects.List_WRITE_METHOD_NAMES.contains(m.getName())) {
                onSet.invoke(source);
            }
            return val;
        });
    }

    public static <T> T logCtx(String name) {
        MDCAdapter mdc = MDC.getMDCAdapter();
        if (mdc == null) {
            return null;
        }
        return (T) mdc.get(name);
    }

    public static void logCtxIfAbsent(String name, Object value) {
        MDCAdapter mdc = MDC.getMDCAdapter();
        if (mdc == null) {
            return;
        }
        String v = mdc.get(name);
        if (v != null) {
            return;
        }
        logCtx(name, value);
    }

    public static void logCtx(String name, Object value) {
        MDCAdapter mdc = MDC.getMDCAdapter();
        if (mdc == null) {
            return;
        }
        if (value == null) {
            mdc.remove(name);
            return;
        }
        mdc.put(name, toJsonString(value));
    }

    public static void clearLogCtx() {
        MDCAdapter mdc = MDC.getMDCAdapter();
        if (mdc == null) {
            return;
        }
        mdc.clear();
    }

    public interface ProceedFunc<T> extends Func<T> {
        default boolean isVoid() {
            return false;
        }
    }

    public interface CallLogBuilder {
        String buildParamSnapshot(Class<?> declaringType, String methodName, Object[] parameters);

        String buildLog(Class<?> declaringType, String methodName, Object[] parameters, String paramSnapshot, Object returnValue, Throwable error, long elapsedNanos);
    }

    public static class DefaultCallLogBuilder implements CallLogBuilder {
        public static final byte HTTP_KEYWORD_FLAG = 1, LOG_MDC_FLAG = 1 << 1;
        @Getter
        @Setter
        byte flags;

        public DefaultCallLogBuilder() {
            this((byte) 0);
        }

        public DefaultCallLogBuilder(byte flags) {
            this.flags = flags;
        }

        @Override
        public String buildParamSnapshot(Class<?> declaringType, String methodName, Object[] args) {
            if (Arrays.isEmpty(args)) {
                return "{}";
            }
            List<Object> list = Linq.from(args).select(p -> shortArg(declaringType, methodName, p)).toList();
            return toString(list.size() == 1 ? list.get(0) : list);
        }

        protected Object shortArg(Class<?> declaringType, String methodName, Object arg) {
            if (arg instanceof byte[]) {
                byte[] b = (byte[]) arg;
                if (b.length > MAX_FIELD_SIZE) {
                    return "[BigBytes]";
                }
            }
            if (arg instanceof String) {
                String s = (String) arg;
                if (s.length() > MAX_FIELD_SIZE) {
                    return "[BigString]";
                }
            }
            return arg;
        }

        protected String toString(Object arg) {
            if (arg == null) {
                return "null";
            }
            return toJsonString(arg);
        }

        @Override
        public String buildLog(Class<?> declaringType, String methodName, Object[] parameters, String paramSnapshot, Object returnValue, Throwable error, long elapsedNanos) {
            StringBuilder msg = new StringBuilder(Constants.HEAP_BUF_SIZE);
            if ((flags & HTTP_KEYWORD_FLAG) == HTTP_KEYWORD_FLAG) {
                msg.appendLine("Call:\t%s", methodName)
                        .appendLine("Request:\t%s", paramSnapshot)
                        .appendLine("Response:\t%s\tElapsed=%s", toString(shortArg(declaringType, methodName, returnValue)), formatNanosElapsed(elapsedNanos));
            } else {
                msg.appendLine("Call:\t%s", methodName)
                        .appendLine("Parameters:\t%s", paramSnapshot)
                        .appendLine("ReturnValue:\t%s\tElapsed=%s", toString(shortArg(declaringType, methodName, returnValue)), formatNanosElapsed(elapsedNanos));
            }
            if (error != null) {
                msg.appendLine("Error:\t%s", error.getMessage());
            }

            if ((flags & LOG_MDC_FLAG) == LOG_MDC_FLAG) {
                Map<String, String> mappedDiagnosticCtx = getMDCCtxMap();
                boolean first = true;
                for (Map.Entry<String, String> entry : mappedDiagnosticCtx.entrySet()) {
                    if (first) {
                        msg.append("MDC:\t");
                        first = false;
                    }
                    msg.appendFormat("%s=%s ", entry.getKey(), entry.getValue());
                }
                if (!first) {
                    msg.appendLine();
                }
            }
            return msg.toString();
        }
    }

    static final int MAX_FIELD_SIZE = 1024 * 4;
    public static final CallLogBuilder DEFAULT_LOG_BUILDER = new DefaultCallLogBuilder();
    public static final CallLogBuilder HTTP_LOG_BUILDER = new DefaultCallLogBuilder(DefaultCallLogBuilder.HTTP_KEYWORD_FLAG);

    public static <T> T callLog(Class<?> declaringType, String methodName, Object[] parameters, ProceedFunc<T> proceed) {
        return callLog(declaringType, methodName, parameters, proceed, DEFAULT_LOG_BUILDER, null);
    }

    @SneakyThrows
    public static <T> T callLog(@NonNull Class<?> declaringType, @NonNull String methodName, Object[] parameters, @NonNull ProceedFunc<T> proceed, @NonNull CallLogBuilder builder, LogStrategy strategy) {
        Object returnValue = null;
        Throwable error = null;
        String paramSnapshot = builder.buildParamSnapshot(declaringType, methodName, parameters);

        long start = System.nanoTime();
        try {
            T retVal = proceed.invoke();
            returnValue = retVal;
            return retVal;
        } catch (Throwable e) {
            throw error = e;
        } finally {
            long elapsedNanos = System.nanoTime() - start;
            try {
                TraceHandler.INSTANCE.saveMethodTrace(Thread.currentThread(), declaringType.getName(), methodName, parameters, returnValue, error, elapsedNanos);

                RxConfig conf = RxConfig.INSTANCE;
                if (strategy == null) {
                    strategy = conf.logStrategy;
                }
                if (strategy == null) {
                    strategy = error != null ? LogStrategy.WRITE_ON_ERROR : LogStrategy.WRITE_ON_NULL;
                }

                boolean doWrite;
                switch (strategy) {
                    case WRITE_ON_NULL:
                        doWrite = error != null
                                || (!proceed.isVoid() && returnValue == null)
                                || (!Arrays.isEmpty(parameters) && Arrays.contains(parameters, null));
                        break;
                    case WRITE_ON_ERROR:
                        doWrite = error != null;
                        break;
                    case ALWAYS:
                        doWrite = true;
                        break;
                    case NONE:
                        doWrite = false;
                        break;
                    default:
                        doWrite = conf.getLogNameMatcher().matches(declaringType.getName());
                        break;
                }
                if (doWrite) {
                    try {
                        String msg = builder.buildLog(declaringType, methodName, parameters, paramSnapshot, returnValue, error, elapsedNanos);
                        if (msg != null) {
                            if (error != null) {
                                TraceHandler.INSTANCE.log(msg, error);
                            } else {
                                org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(declaringType);
                                log.info(msg);
                            }
                        }
                    } catch (Throwable e) {
                        log.warn("buildLog", e);
                    }
                }
            } catch (Throwable e) {
                log.warn("callLog", e);
            }
        }
    }

    public static Map<String, String> getMDCCtxMap() {
        MDCAdapter mdc = MDC.getMDCAdapter();
        if (mdc == null) {
            return Collections.emptyMap();
        }
        LogbackMDCAdapter lb = as(mdc, LogbackMDCAdapter.class);
        Map<String, String> ctxMap = lb != null ? lb.getPropertyMap() : mdc.getCopyOfContextMap();
        if (ctxMap == null) {
            ctxMap = Collections.emptyMap();
        }
        return ctxMap;
    }
    //endregion

    //region mx
    public synchronized static void mxScheduleTask(BiAction<Info> mxHandler) {
        if (samplingTimeout != null) {
            samplingTimeout.cancel();
        }
        samplingTimeout = CpuWatchman.timer.newTimeout(t -> {
            try {
                mxHandler.invoke(mxInfo());
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log(e);
            } finally {
                t.timer().newTimeout(t.task(), RxConfig.INSTANCE.getMxSamplingPeriod(), TimeUnit.MILLISECONDS);
            }
        }, RxConfig.INSTANCE.getMxSamplingPeriod(), TimeUnit.MILLISECONDS);
    }

    public static Info mxInfo() {
        File bd = new File("/");
        OperatingSystemMXBean osMx = CpuWatchman.osMx;
        return new Info(osMx.getAvailableProcessors(), osMx.getSystemCpuLoad(), CpuWatchman.threadMx.getThreadCount(),
                osMx.getFreePhysicalMemorySize(), osMx.getTotalPhysicalMemorySize(),
                Linq.from(File.listRoots()).select(p -> new DiskInfo(p.getName(), p.getAbsolutePath(), p.getFreeSpace(), p.getTotalSpace(), bd.getAbsolutePath().equals(p.getAbsolutePath()))));
    }

    public static String formatCpuLoad(double val) {
        String p = String.valueOf(val * PERCENT);
        int ix = p.indexOf(".") + 1;
        String percent = p.substring(0, ix) + p.charAt(ix);
        return percent + "%";
    }

    public static String formatNanosElapsed(long nanoseconds) {
        return formatNanosElapsed(nanoseconds, 0);
    }

    public static String formatNanosElapsed(long nanoseconds, int i) {
        long d = 1000L, v = nanoseconds;
        while (v >= d) {
            v /= d;
            if (++i >= 3) {
                break;
            }
        }
        return v + seconds[i];
    }
    //endregion

    //region common
    public static <T> T deepClone(T obj) {
        return Serializer.DEFAULT.deserialize(Serializer.DEFAULT.serialize(obj));
    }

    public static String fastCacheKey(String region, Object... args) {
        if (region == null) {
            region = Reflects.CLASS_TRACER.getClassTrace(1).getSimpleName();
        }
        if (!Arrays.isEmpty(args)) {
            region += java.util.Arrays.hashCode(args);
        }
        return region;
    }

    public static String cacheKey(String region, Object... args) {
        if (region == null) {
            region = Reflects.CLASS_TRACER.getClassTrace(1).getSimpleName();
        }

        StringBuilder buf = new StringBuilder();
        buf.append(region);
        if (!Arrays.isEmpty(args)) {
            buf.append(Constants.CACHE_KEY_SUFFIX);
            if (args.length == 1 && args[0] instanceof String) {
                buf.append(args[0]);
            } else {
                buf.append(CodecUtil.hash64(args));
            }
        }
        return buf.toString();
    }

    //region json
    public static <T> T readJsonValue(Object json, String path) {
        return readJsonValue(json, path, null, true);
    }

    /**
     * @param json
     * @param path
     * @param childSelect       !Reflects.isBasicType(cur.getClass())
     * @param throwOnEmptyChild
     * @param <T>
     * @return
     */
    public static <T> T readJsonValue(@NonNull Object json, @NonNull String path,
                                      BiFunc<Object, ?> childSelect, boolean throwOnEmptyChild) {
        if (json instanceof Map) {
            Map<String, Object> jObj = (Map<String, Object>) json;
            Object cur = jObj.get(path);
            if (cur != null) {
                if (childSelect != null) {
                    cur = childSelect.apply(cur);
                }
                return (T) cur;
            }
        }

        Object cur = json;
        int max = path.length() - 1;

        AtomicInteger i = new AtomicInteger();
        StringBuilder buf = new StringBuilder();
        for (; i.get() <= max; i.incrementAndGet()) {
            char c = path.charAt(i.get());
            if (c != objKey && c != arrBeginKey) {
                buf.append(c);
                continue;
            }

            cur = visitJson(cur, path, i, c, buf.toString(), max, childSelect, throwOnEmptyChild);
            buf.setLength(0);
        }
        if (!buf.isEmpty()) {
            cur = visitJson(cur, path, i, objKey, buf.toString(), max, childSelect, throwOnEmptyChild);
        }
        return (T) cur;
    }

    static final char objKey = '.', arrBeginKey = '[', arrEndKey = ']';

    static Object visitJson(Object cur, String path, AtomicInteger i, char c, String visitor,
                            int max, BiFunc<Object, ?> childSelect, boolean throwOnEmptyChild) {
        if (!visitor.isEmpty()) {
            if (cur instanceof Map) {
                Map<String, ?> obj = (Map<String, ?>) cur;
                cur = obj.get(visitor);
            } else if (cur instanceof Iterable) {
                //ignore
            } else {
                if (cur == null) {
                    if (throwOnEmptyChild) {
                        throw new InvalidException("Get empty child by path {}", visitor);
                    }
                    return null;
                }
                try {
                    cur = Reflects.readField(cur, visitor);
                } catch (Throwable e) {
                    throw new InvalidException("Object \"{}\" is not a map or not found field with path {}", cur, visitor, e);
                }
            }
        }

        if (c == arrBeginKey) {
            StringBuilder idxBuf = new StringBuilder();
            for (i.incrementAndGet(); i.get() < path.length(); i.incrementAndGet()) {
                char ic = path.charAt(i.get());
                if (ic != arrEndKey) {
                    idxBuf.append(ic);
                    continue;
                }
                break;
            }
            int idx;
            try {
                idx = Integer.parseInt(idxBuf.toString());
                visitor = String.format("%s[%s]", visitor, idxBuf);
            } catch (Throwable e) {
                throw new InvalidException("Index \"{}\" is not a number", idxBuf, e);
            }

            if (cur != null) {
                if (cur instanceof Iterable) {
                    try {
                        cur = IterableUtils.get((Iterable<?>) cur, idx);
                    } catch (IndexOutOfBoundsException e) {
                        if (throwOnEmptyChild) {
                            throw new InvalidException("Array \"{}\" is index out of bounds with path {}", cur, visitor, e);
                        }
                        cur = null;
                    }
                } else if (cur.getClass().isArray()) {
                    try {
                        cur = Array.get(cur, idx);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        if (throwOnEmptyChild) {
                            throw new InvalidException("Array \"{}\" is index out of bounds with path {}", cur, visitor, e);
                        }
                        cur = null;
                    }
                } else {
                    throw new InvalidException("Object \"{}\" is not a array with path {}", cur, visitor);
                }
            }
        }
        if (cur != null && childSelect != null) {
            cur = childSelect.apply(cur);
        }
        if (i.get() < max && cur == null) {
            if (throwOnEmptyChild) {
                throw new InvalidException("Get empty child by path {}", visitor);
            }
            return null;
        }
        return cur;
    }

    //TypeReference
    public static <T> T fromJson(Object src, Type type) {
//        if (src == null) {
//            return null;
//        }
        String js = toJsonString(src);
        try {
            return JSON.parseObject(js, type, JSON_READ_FLAGS);
        } catch (Exception e) {
            throw new InvalidException("Invalid json {}", js, e);
        }
    }

    public static JSONObject toJsonObject(Object src) {
//        if (src instanceof JSONObject) {
//            return (JSONObject) src;
//        }
        if (src instanceof Map) {
            return new JSONObject((Map<String, Object>) src);
        }

        String js = toJsonString(src);
        try {
            return JSON.parseObject(js);
        } catch (Exception e) {
            throw new InvalidException("Invalid json {}", js, e);
        }
    }

    public static JSONArray toJsonArray(Object src) {
        if (src instanceof JSONArray) {
            return (JSONArray) src;
        }
        if (src instanceof Collection) {
            return new JSONArray((Collection<Object>) src);
        }

        String js = toJsonString(src);
        try {
            return JSON.parseArray(js);
        } catch (Exception e) {
            throw new InvalidException("Invalid json {}", js, e);
        }
    }

    public static String toJsonString(Object src) {
        return toJsonString(src, null);
    }

    public static String toJsonString(Object src, ValueFilter valueFilter) {
        if (src == null) {
            return "{}";
        }
        String s;
        if ((s = as(src, String.class)) != null) {
            return s;
        }

        try {
            return JSON.toJSONString(JSON_WRITE_SKIP_TYPES.apply(null, null, src), ifNull(valueFilter, JSON_WRITE_SKIP_TYPES), JSON_WRITE_FLAGS);
        } catch (Throwable e) {
            Linq<Object> q;
            if (Linq.tryAsIterableType(src.getClass())) {
                q = Linq.fromIterable(src);
            } else {
                q = Linq.from(src);
            }
            Set<Class<?>> jsonSkipTypes = RxConfig.INSTANCE.jsonSkipTypes;
            jsonSkipTypes.addAll(q.where(x -> x != null && !Reflects.isBasicType(x.getClass())).select(Object::getClass).toSet());
            TraceHandler.INSTANCE.log("toJsonString {}", Linq.from(jsonSkipTypes).toJoinString(",", Class::getName), e);

            JSONObject json = new JSONObject();
            json.put("_input", src.toString());
            json.put("_error", e.getMessage());
            return json.toString();
        }
    }

    static Object jsonValueFilter(Object o, String k, Object v) {
        if (v != null) {
            if (v instanceof InetAddress) {
                return v.toString();
            }
            if (Linq.from(RxConfig.INSTANCE.jsonSkipTypes).any(t -> Reflects.isInstance(v, t))) {
                return v.getClass().getName();
            }
        }
        return v;
    }
    //endregion
    //endregion
}
