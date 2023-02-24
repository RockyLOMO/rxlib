package org.rx.core;

import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.filter.ValueFilter;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.util.Timeout;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rx.annotation.Subscribe;
import org.rx.bean.DynamicProxyBean;
import org.rx.bean.LogStrategy;
import org.rx.bean.ProceedEventArgs;
import org.rx.codec.CodecUtil;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;
import org.rx.util.function.TripleFunc;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import org.springframework.cglib.proxy.Enhancer;

import java.io.File;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.fastjson2.JSONReader.Feature.AllowUnQuotedFieldNames;
import static com.alibaba.fastjson2.JSONReader.Feature.SupportClassForName;
import static com.alibaba.fastjson2.JSONWriter.Feature.NotWriteDefaultValue;
import static org.rx.core.Constants.PERCENT;
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

    @Getter
    @RequiredArgsConstructor
    public static class ThreadInfo {
        private final java.lang.management.ThreadInfo thread;
        private final long userNanos;
        private final long cpuNanos;

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(thread.toString());
            int i = buf.indexOf("\n");
            buf.insert(i, String.format(" BlockedTime=%s WaitedTime=%s UserTime=%s CpuTime=%s",
                    formatNanosElapsed(thread.getBlockedTime(), 2), formatNanosElapsed(thread.getWaitedTime(), 2),
                    formatNanosElapsed(userNanos), formatNanosElapsed(cpuNanos)));
            return buf.toString();
        }
    }

    public static final HotSpotDiagnosticMXBean diagnosticMx = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    public static final ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final OperatingSystemMXBean osMx = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
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

    static {
        RxConfig conf = RxConfig.INSTANCE;
        log.info("RxMeta {} {}_{}_{} @ {} & {}\n{}", JAVA_VERSION, OS_NAME, OS_VERSION, OS_ARCH,
                new File(Strings.EMPTY).getAbsolutePath(), Sockets.getLocalAddresses(false), JSON.toJSONString(conf));

        ObjectChangeTracker.DEFAULT.watch(conf, true)
                .register(Sys.class)
                .register(Tasks.class)
                .register(TraceHandler.INSTANCE);
        IOC.register(Cache.class, IOC.get(conf.cache.mainCache));
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
                NtpClock.TimeAdvice.transform();
            }, 60000);
        }
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

    public static <T> T targetObject(Object proxyObject) {
        return IOC.<String, T>weakIdentityMap(proxyObject).get(DPT);
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
            IOC.weakIdentityMap(proxyObj).put(DPT, rawObject);
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

    public static void logHttp(@NonNull ProceedEventArgs eventArgs, String url) {
        RxConfig conf = RxConfig.INSTANCE;
        eventArgs.setLogStrategy(conf.logStrategy);
        eventArgs.setLogTypeWhitelist(conf.logTypeWhitelist);
        log(eventArgs, msg -> {
            msg.appendLine("Url:\t%s", url)
                    .appendLine("Request:\t%s", toJsonString(eventArgs.getParameters()))
                    .appendLine("Response:\t%s", toJsonString(eventArgs.getReturnValue()));
            if (eventArgs.getError() != null) {
                msg.appendLine("Error:\t%s", eventArgs.getError());
            }
        });
    }

    @SneakyThrows
    public static void log(@NonNull ProceedEventArgs eventArgs, @NonNull BiAction<StringBuilder> formatMessage) {
        Map<String, String> mappedDiagnosticCtx = Collections.emptyMap();
        MDCAdapter mdc = MDC.getMDCAdapter();
        if (mdc != null) {
            LogbackMDCAdapter lb = as(mdc, LogbackMDCAdapter.class);
            Map<String, String> pm = lb != null ? lb.getPropertyMap() : mdc.getCopyOfContextMap();
            if (pm != null) {
                mappedDiagnosticCtx = pm;
            }
        }
        boolean doWrite = !mappedDiagnosticCtx.isEmpty();
        if (!doWrite) {
            if (eventArgs.getLogStrategy() == null) {
                eventArgs.setLogStrategy(eventArgs.getError() != null ? LogStrategy.WRITE_ON_ERROR : LogStrategy.WRITE_ON_NULL);
            }
            switch (eventArgs.getLogStrategy()) {
                case WRITE_ON_NULL:
                    doWrite = eventArgs.getError() != null
                            || (!eventArgs.isVoid() && eventArgs.getReturnValue() == null)
                            || (!Arrays.isEmpty(eventArgs.getParameters()) && Arrays.contains(eventArgs.getParameters(), null));
                    break;
                case WRITE_ON_ERROR:
                    if (eventArgs.getError() != null) {
                        doWrite = true;
                    }
                    break;
                case ALWAYS:
                    doWrite = true;
                    break;
            }
        }
        if (doWrite) {
            Set<String> whitelist = eventArgs.getLogTypeWhitelist();
            if (!CollectionUtils.isEmpty(whitelist)) {
                doWrite = Linq.from(whitelist).any(p -> eventArgs.getDeclaringType().getName().startsWith(p));
            }
        }
        if (doWrite) {
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(eventArgs.getDeclaringType());
            StringBuilder msg = new StringBuilder(Constants.HEAP_BUF_SIZE);
            formatMessage.invoke(msg);
            boolean first = true;
            for (Map.Entry<String, String> entry : mappedDiagnosticCtx.entrySet()) {
                if (first) {
                    msg.append("MDC:\t");
                    first = false;
                }
                msg.append("%s=%s ", entry.getKey(), entry.getValue());
            }
            if (!first) {
                msg.appendLine();
            }
            if (eventArgs.getError() != null) {
                TraceHandler.INSTANCE.log(msg.toString(), eventArgs.getError());
            } else {
                log.info(msg.toString());
            }
        }
    }
    //endregion

    //region mx
    public synchronized static void mxScheduleTask(BiAction<Info> mxHandler) {
        if (samplingTimeout != null) {
            samplingTimeout.cancel();
        }
        samplingTimeout = ThreadPool.timer.newTimeout(t -> {
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
        return new Info(osMx.getAvailableProcessors(), osMx.getSystemCpuLoad(), threadMx.getThreadCount(),
                osMx.getFreePhysicalMemorySize(), osMx.getTotalPhysicalMemorySize(),
                Linq.from(File.listRoots()).select(p -> new DiskInfo(p.getName(), p.getAbsolutePath(), p.getFreeSpace(), p.getTotalSpace(), bd.getAbsolutePath().equals(p.getAbsolutePath()))));
    }

    public static List<ThreadInfo> findDeadlockedThreads() {
        long[] deadlockedTids = Arrays.addAll(threadMx.findDeadlockedThreads(), threadMx.findMonitorDeadlockedThreads());
        if (Arrays.isEmpty(deadlockedTids)) {
            return Collections.emptyList();
        }
        return Linq.from(threadMx.getThreadInfo(deadlockedTids)).select((p, i) -> new ThreadInfo(p, -1, -1)).toList();
    }

    public static Linq<ThreadInfo> getAllThreads() {
        if (!threadMx.isThreadContentionMonitoringEnabled()) {
            threadMx.setThreadContentionMonitoringEnabled(true);
        }
        if (!threadMx.isThreadCpuTimeEnabled()) {
            threadMx.setThreadCpuTimeEnabled(true);
        }
        boolean includeLock = false;
        Linq<java.lang.management.ThreadInfo> allThreads = Linq.from(threadMx.dumpAllThreads(includeLock, includeLock));
        long[] tids = Arrays.toPrimitive(allThreads.select(java.lang.management.ThreadInfo::getThreadId).toArray());
        long[] threadUserTime = threadMx.getThreadUserTime(tids);
        long[] threadCpuTime = threadMx.getThreadCpuTime(tids);
        return allThreads.select((p, i) -> new ThreadInfo(p, threadUserTime[i], threadCpuTime[i]));
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
    public static String fastCacheKey(String method, Object... args) {
        if (method == null) {
            method = Reflects.stackClass(1).getSimpleName();
        }
        if (!Arrays.isEmpty(args)) {
            method += java.util.Arrays.hashCode(args);
        }
        return method;
//        return method.intern();
    }

    public static String cacheKey(String method, Object... args) {
        return cacheKey(null, method, args);
    }

    public static String cacheKey(String region, String method, Object... args) {
        if (method == null) {
            method = Reflects.stackClass(1).getSimpleName();
        }

        StringBuilder buf = new StringBuilder();
        if (region != null) {
            buf.append(region).append(Constants.CACHE_KEY_SUFFIX);
        }
        buf.append(method);
        if (!Arrays.isEmpty(args)) {
            Object p = args.length == 1 ? args[0] : args;
            buf.append(Constants.CACHE_KEY_SUFFIX).append(p instanceof String ? p : CodecUtil.hash64(p));
        }
        return buf.toString();
    }

    //region json
    public static <T> T readJsonValue(@NonNull Map<String, ?> json, String path,
                                      BiFunc<Object, ?> childSelect,
                                      boolean throwOnEmptyChild) {
        Object child = json.get(path);
        if (child != null) {
            if (childSelect != null) {
                child = childSelect.apply(child);
            }
            return (T) child;
        }

        String[] paths = Strings.split(path, ".");
        if (paths.length == 0) {
            return null;
        }

        int last = paths.length - 1;
        Map<String, ?> tmp = json;
        for (int i = 0; i < last; i++) {
            child = tmp.get(paths[i]);
            if (childSelect != null) {
                child = childSelect.apply(child);
            }
            if ((tmp = as(child, Map.class)) == null) {
                if (throwOnEmptyChild) {
                    throw new InvalidException("Get empty sub object by path {}", paths[i]);
                }
                return null;
            }
        }
        child = tmp.get(paths[last]);
        if (childSelect != null) {
            child = childSelect.apply(child);
        }
        return (T) child;
    }

    //TypeReference
    public static <T> T fromJson(Object src, Type type) {
        String js = toJsonString(src);
        try {
            return JSON.parseObject(js, type, JSON_READ_FLAGS);
        } catch (Exception e) {
            throw new InvalidException("Invalid json {}", js, e);
        }
    }

    public static JSONObject toJsonObject(Object src) {
        if (src instanceof JSONObject) {
            return (JSONObject) src;
        }
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
        if (src instanceof List) {
            return new JSONArray((List<Object>) src);
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