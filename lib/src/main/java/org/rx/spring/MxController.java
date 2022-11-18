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
import org.rx.net.http.tunnel.Server;
import org.rx.net.socks.SocksContext;
import org.rx.util.BeanMapper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.rx.core.Constants.RX_CONF_TOPIC;
import static org.rx.core.Extends.eq;
import static org.rx.core.Sys.fromJson;
import static org.rx.core.Sys.toJsonObject;

@RequiredArgsConstructor
@RestController
@RequestMapping("mx")
public class MxController {
    final Server server;

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
    public Object health(HttpServletRequest request) {
        String x = request.getParameter("x");
        if (!check(request) || x == null) {
            return null;
        }
        try {
            switch (Integer.parseInt(x)) {
                case 1:
                    String config = request.getParameter("conf");
                    return BeanMapper.DEFAULT.map(fromJson(config, new TypeReference<RxConfig>() {
                    }.getType()), RxConfig.INSTANCE);
                case 2:
                    String k = request.getParameter("k"),
                            v = request.getParameter("v");
                    Sys.diagnosticMx.setVMOption(k, v);
                    return "ok";
                case 3:
                    boolean enable = Boolean.parseBoolean(request.getParameter("v"));
                    Sys.threadMx.setThreadContentionMonitoringEnabled(enable);
                    Sys.threadMx.setThreadCpuTimeEnabled(enable);
                    return "ok";
                case 4:
                    String a1 = request.getParameter("cmd"),
                            a2 = request.getParameter("workspace");
                    StringBuilder echo = new StringBuilder();
                    ShellCommander cmd = new ShellCommander(a1, a2).setReadFullyThenExit();
                    cmd.onPrintOut.combine((s, e) -> echo.append(e.toString()));
                    cmd.start().waitFor(60);
                    return echo.toString();
                case 5:
                    String host = request.getParameter("host");
                    return Linq.from(InetAddress.getAllByName(host)).select(p -> p.getHostAddress()).toArray();
                case 6:
                    return invoke(request);
            }
            return svrState(request);
        } catch (Throwable e) {
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
        String b = request.getParameter("body");
        if (b == null) {
            b = IOStream.readString(request.getInputStream(), StandardCharsets.UTF_8);
        }
        Map<String, Object> params = toJsonObject(b);

        Class<?> kls = Class.forName((String) params.get("bean"));
        Object bean = SpringContext.getBean(kls);
        Method method = Reflects.getMethodMap(kls).get((String) params.get("method")).first();
        Object arg = fromJson(params.get("args"), kls);

        String genericField = (String) params.get("genericField");
        if (genericField != null) {
            Object subArg = Reflects.readField(arg, genericField);
            Type type = method.getGenericParameterTypes()[0];
            if (subArg instanceof JSONObject && type instanceof ParameterizedTypeImpl) {
                ParameterizedTypeImpl pt = (ParameterizedTypeImpl) type;
                Reflects.writeField(arg, genericField, fromJson(subArg, pt.getActualTypeArguments()[0]));
            }
        }
        return Reflects.invokeMethod(method, bean, new Object[]{arg});
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
        j.put("sysProperties", System.getProperties());
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
        int take = 10;
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
        j.putAll(queryTraces(null, null, null, null, null, 10, request));
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

    @Subscribe(RX_CONF_TOPIC)
    void onChanged(ObjectChangedEvent event) {
        Tasks.setTimeout(() -> {
            String omega = event.<RxConfig>source().getOmega();
            if (omega != null) {
                SocksContext.omega(omega, null);
            }
        }, 60 * 1000);
    }
}
