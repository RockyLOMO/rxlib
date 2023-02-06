package org.rx.spring;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.Subscribe;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.io.IOStream;
import org.rx.net.socks.SocksContext;
import org.rx.util.BeanMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;
import static org.rx.core.Sys.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("mx")
public class MxController {
    @RequestMapping("queryTraces")
    public Map<String, Object> queryTraces(Boolean newest, String level,
                                           Boolean methodOccurMost, String methodNamePrefix,
                                           String metricsName,
                                           Integer take,
                                           HttpServletRequest request) {
        if (!check(request)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>(3);
        ExceptionLevel el = null;
        if (!Strings.isBlank(level)) {
            el = ExceptionLevel.valueOf(level);
        }
        result.put("errorTraces", TraceHandler.INSTANCE.queryTraces(newest, el, take));

        result.put("methodTraces", Linq.from(TraceHandler.INSTANCE.queryTraces(methodOccurMost, methodNamePrefix, take))
                .select(p -> {
                    Map<String, Object> t = Sys.toJsonObject(p);
                    t.remove("elapsedMicros");
                    t.put("elapsed", Sys.formatNanosElapsed(p.getElapsedMicros(), 1));
                    return t;
                }));

        result.put("metrics", TraceHandler.INSTANCE.queryMetrics(metricsName, take));
        return result;
    }

    @RequestMapping("health")
    public Object health(HttpServletRequest request, HttpServletResponse response) {
        String x = request.getParameter("x");
        if (!check(request) || x == null) {
            return null;
        }
        try {
            switch (Integer.parseInt(x)) {
                case 1:
                    String type = request.getParameter("type");
                    String jsonVal = request.getParameter("jsonVal");
                    Object source = null, target;
                    if (!Strings.isBlank(type)) {
                        Class<?> clazz = Class.forName(type);
                        target = SpringContext.getBean(clazz);
                        if (target == null) {
                            return null;
                        }
                        if (jsonVal != null) {
                            source = fromJson(jsonVal, clazz);
                        }
                    } else {
                        if (jsonVal != null) {
                            source = fromJson(jsonVal, new TypeReference<RxConfig>() {
                            }.getType());
                        }
                        target = RxConfig.INSTANCE;
                    }
                    return source != null ? BeanMapper.DEFAULT.map(source, target) : target;
                case 2: {
                    String k = request.getParameter("k"),
                            v = request.getParameter("v");
                    Sys.diagnosticMx.setVMOption(k, v);
                    return "ok";
                }
                case 3:
                    boolean enable = Boolean.parseBoolean(request.getParameter("v"));
                    Sys.threadMx.setThreadContentionMonitoringEnabled(enable);
                    Sys.threadMx.setThreadCpuTimeEnabled(enable);
                    return "ok";
                case 4:
                    String host = request.getParameter("host");
                    return Linq.from(InetAddress.getAllByName(host)).select(p -> p.getHostAddress()).toArray();
                case 5:
                    response.setContentType("text/plain;charset=UTF-8");
                    return exec(request);
                case 6:
                    return invoke(request);
                case 7:
                    Class<?> ft = Class.forName(request.getParameter("ft"));
                    String fn = request.getParameter("fn");
                    String fu = request.getParameter("fu");
                    Map<Class<?>, Map<String, String>> fms = Interceptors.ControllerInterceptor.fms;
                    if (fu == null) {
                        Map<String, String> fts = fms.get(ft);
                        if (fts != null) {
                            fts.remove(fn);
                        }
                    } else {
                        fms.computeIfAbsent(ft, k -> new ConcurrentHashMap<>(8)).put(fn, fu);
                    }
                    return fms;
            }
            return svrState(request);
        } catch (Throwable e) {
            response.setContentType("text/plain;charset=UTF-8");
            return String.format("%s\n%s", e, ExceptionUtils.getStackTrace(e));
        }
    }

//    @PostMapping("directOffer")
//    public void directOffer(String appName, String socksId, String endpoint, MultipartFile binary) {
//        SendPack pack = new SendPack(appName, socksId, Sockets.parseEndpoint(endpoint));
//        pack.setBinary(binary);
//        server.frontendOffer(pack);
//    }
//
//    @SneakyThrows
//    @PostMapping("directPoll")
//    public void directPoll(String appName, String socksId, String endpoint, HttpServletResponse response) {
//        ReceivePack pack = server.frontendPoll(new SendPack(appName, socksId, Sockets.parseEndpoint(endpoint)));
//        ServletOutputStream out = response.getOutputStream();
//        for (IOStream<?, ?> binary : pack.getBinaries()) {
//            binary.read(out);
//        }
//    }

    @SneakyThrows
    Object invoke(HttpServletRequest request) {
        Map<String, Object> params = getParams(request);
        Class<?> kls = Class.forName((String) params.get("bean"));
        Object bean = SpringContext.getBean(kls);
        Method method = Reflects.getMethodMap(kls).get((String) params.get("method")).first();
        List<Object> args = (List<Object>) params.get("args");
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] a = Linq.from(method.getGenericParameterTypes()).select((p, i) -> {
            Object o = args.get(i);
            try {
                return fromJson(o, p);
            } catch (Exception e) {
//                log.info("mx invoke", e);
                return Reflects.changeType(o, parameterTypes[i]);
            }
        }).toArray();
//        log.info("mx invoke {}({})", method, toJsonString(a));
        return Reflects.invokeMethod(method, bean, a);
    }

    Object exec(HttpServletRequest request) {
        Map<String, Object> params = getParams(request);
        StringBuilder echo = new StringBuilder();
        ShellCommander cmd = new ShellCommander((String) params.get("cmd"), String.valueOf(params.get("workspace")))
//                            .setReadFullyThenExit()
                ;
        cmd.onPrintOut.combine((s, e) -> echo.append(e.toString()));
        cmd.start().waitFor(30);
        return echo.toString();
    }

    @SneakyThrows
    Map<String, Object> getParams(HttpServletRequest request) {
        String b = request.getParameter("body");
        if (b == null) {
            b = IOStream.readString(request.getInputStream(), StandardCharsets.UTF_8);
        }
        return toJsonObject(b);
    }

    Map<String, Object> svrState(HttpServletRequest request) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("jarFile", Sys.getJarFile(this));
        j.put("inputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        try {
            Sys.diagnosticMx.setVMOption("UnlockCommercialFeatures", Boolean.TRUE.toString());
        } catch (Exception e) {
            j.put("UnlockCommercialFeatures", e.toString());
        }
        j.put("vmOptions", Sys.diagnosticMx.getDiagnosticOptions());
        //jackson json issue when ntp enable
        j.put("sysProperties", Linq.from(System.getProperties().entrySet()).toMap());
        j.put("sysEnv", System.getenv());
        Sys.Info info = Sys.mxInfo();
        JSONObject infoJson = toJsonObject(info);
        infoJson.put("usedPhysicalMemory", Bytes.readableByteSize(info.getUsedPhysicalMemory()));
        infoJson.put("freePhysicalMemory", Bytes.readableByteSize(info.getFreePhysicalMemory()));
        infoJson.put("totalPhysicalMemory", Bytes.readableByteSize(info.getTotalPhysicalMemory()));
        JSONObject summedDisk = infoJson.getJSONObject("summedDisk");
        summedDisk.put("usedSpace", Bytes.readableByteSize(info.getSummedDisk().getUsedSpace()));
        summedDisk.put("freeSpace", Bytes.readableByteSize(info.getSummedDisk().getFreeSpace()));
        summedDisk.put("totalSpace", Bytes.readableByteSize(info.getSummedDisk().getTotalSpace()));
        JSONArray disks = infoJson.getJSONArray("disks");
        int i = 0;
        for (Sys.DiskInfo disk : info.getDisks()) {
            JSONObject diskJson = disks.getJSONObject(i);
            diskJson.put("usedSpace", Bytes.readableByteSize(disk.getUsedSpace()));
            diskJson.put("freeSpace", Bytes.readableByteSize(disk.getFreeSpace()));
            diskJson.put("totalSpace", Bytes.readableByteSize(disk.getTotalSpace()));
            i++;
        }
        j.put("sysInfo", infoJson);
        j.put("deadlockedThreads", Sys.findDeadlockedThreads());
        Linq<Sys.ThreadInfo> allThreads = Sys.getAllThreads();
        int take = Reflects.convertQuietly(request.getParameter("take"), Integer.class, 5);
        j.put("topUserTimeThreads", allThreads.orderByDescending(Sys.ThreadInfo::getUserNanos)
                .take(take).select(Sys.ThreadInfo::toString));
        j.put("topCpuTimeThreads", allThreads.orderByDescending(Sys.ThreadInfo::getCpuNanos)
                .take(take).select(Sys.ThreadInfo::toString));
        j.put("topBlockedTimeThreads", allThreads.orderByDescending(p -> p.getThread().getBlockedTime())
                .take(take).select(Sys.ThreadInfo::toString));
        j.put("topWaitedTimeThreads", allThreads.orderByDescending(p -> p.getThread().getWaitedTime())
                .take(take).select(Sys.ThreadInfo::toString));
        j.put("ntpOffset", Reflects.readStaticField(NtpClock.class, "offset"));

        j.put("rxConfig", RxConfig.INSTANCE);
        j.put("requestHeaders", Linq.from(Collections.list(request.getHeaderNames()))
                .select(p -> String.format("%s: %s", p, String.join("; ", Collections.list(request.getHeaders(p))))));
        j.putAll(queryTraces(null, null, null, null, null, take, request));
        return j;
    }

    boolean check(HttpServletRequest request) {
        return eq(request.getHeader(RxConfig.ConfigNames.MXPWD.replace(".", "-")), RxConfig.INSTANCE.getMxpwd());
    }

    @SneakyThrows
    @PostConstruct
    public void init() {
        Class.forName(Sys.class.getName());
        ObjectChangeTracker.DEFAULT.register(this);
    }

    @Subscribe(topicClass = RxConfig.class)
    void onChanged(ObjectChangedEvent event) {
        Tasks.setTimeout(() -> {
            String omega = event.<RxConfig>source().getOmega();
            if (omega != null) {
                SocksContext.omega(omega, null);
            }
        }, 60 * 1000);
    }
}
