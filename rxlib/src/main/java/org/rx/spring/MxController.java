package org.rx.spring;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.Subscribe;
import org.rx.bean.DateTime;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.io.IOStream;
import org.rx.net.NetEventWait;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksContext;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.fromJson;
import static org.rx.core.Sys.toJsonObject;

@RequiredArgsConstructor
@RestController
@RequestMapping("mx")
public class MxController {
    @SneakyThrows
    @RequestMapping("health")
    public Object health(HttpServletRequest request) {
        final String rt = "1";
        String multicast = request.getParameter("multicast");
        if (multicast != null) {
            String group = request.getParameter("group");
            Integer mcId = Reflects.changeType(request.getParameter("mcId"), Integer.class);
            NetEventWait.multicastLocal(Sockets.parseEndpoint(multicast), group, ifNull(mcId, 0));
            return rt;
        }

        final HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.valueOf("text/plain;charset=UTF-8"));
        headers.setContentType(MediaType.TEXT_PLAIN);
        String x = request.getParameter("x");
        if (!check(request) || x == null) {
            StringBuilder buf = new StringBuilder();
            buf.appendLine("%s %s", request.getMethod(), request.getRequestURL().toString());
            for (String n : Collections.list(request.getHeaderNames())) {
                buf.appendLine("%s: %s", n, request.getHeader(n));
            }
            ServletInputStream inStream = request.getInputStream();
            if (inStream != null) {
                buf.appendLine(IOStream.readString(inStream, StandardCharsets.UTF_8));
            }
            TraceHandler.INSTANCE.log("rx replay {}", buf);
            return new ResponseEntity<>(buf, headers, HttpStatus.OK);
        }
        try {
            switch (Integer.parseInt(x)) {
                case 1:
                    Sys.diagnosticMx.setVMOption(request.getParameter("k"), request.getParameter("v"));
                    return rt;
                case 2:
                    boolean enable = Boolean.parseBoolean(request.getParameter("v"));
                    if (enable) {
                        CpuWatchman.startWatch();
                    } else {
                        CpuWatchman.stopWatch();
                    }
                    return rt;
                case 3:
                    String type = request.getParameter("type");
                    String jsonVal = request.getParameter("jsonVal");
                    Object target;
                    if (!Strings.isBlank(type)) {
                        Class<?> clazz = Class.forName(type);
                        target = SpringContext.getBean(clazz, false);
                        if (target == null) {
                            return null;
                        }
                        if (jsonVal != null) {
                            BeanMapper.DEFAULT.map(fromJson(jsonVal, clazz), target, BeanMapFlag.SKIP_NULL.flags());
                        }
                    } else {
                        target = RxConfig.INSTANCE;
                        if (jsonVal != null) {
                            RxConfig.INSTANCE.refreshFrom(toJsonObject(jsonVal));
                        }
                    }
                    return target;
                case 4:
                    Class<?> ft = Class.forName(request.getParameter("ft"));
                    String fn = request.getParameter("fn");
                    String fu = request.getParameter("fu");
                    Map<Class<?>, Map<String, String>> fms = RxConfig.INSTANCE.getMxHttpForwards();
                    if (fu == null) {
                        Map<String, String> fts = fms.get(ft);
                        if (fts != null) {
                            fts.remove(fn);
                        }
                    } else {
                        fms.computeIfAbsent(ft, k -> new ConcurrentHashMap<>(8)).put(fn, fu);
                    }
                    return fms;
                case 5:
                    return Linq.from(InetAddress.getAllByName(request.getParameter("host"))).select(p -> p.getHostAddress()).toArray();
                case 6:
                    return new ResponseEntity<>(exec(request), headers, HttpStatus.OK);
                case 7:
                    SocksContext.omegax(Reflects.convertQuietly(request.getParameter("p"), Integer.class, 22));
                    return rt;
                case 10:
                    String startTime = request.getParameter("startTime");
                    DateTime st = startTime == null ? null : DateTime.valueOf(startTime);
                    String endTime = request.getParameter("endTime");
                    DateTime et = endTime == null ? null : DateTime.valueOf(endTime);
                    String level = request.getParameter("level");
                    String kw = request.getParameter("keyword");
                    Boolean newest = Reflects.changeType(request.getParameter("newest"), Boolean.class);
                    Boolean methodOccurMost = Reflects.changeType(request.getParameter("methodOccurMost"), Boolean.class);
                    String methodNamePrefix = request.getParameter("methodNamePrefix");
                    String metricsName = request.getParameter("metricsName");
                    Integer take = Reflects.changeType(request.getParameter("take"), Integer.class);
                    return queryTraces(st, et, level, kw, newest, methodOccurMost, methodNamePrefix, metricsName, take);
                case 11:
                    DateTime begin = Reflects.changeType(request.getParameter("begin"), DateTime.class);
                    DateTime end = Reflects.changeType(request.getParameter("end"), DateTime.class);
                    return findTopUsage(begin, end);
                case 12:
                    return invoke(request);
            }
            return svrState(request);
        } catch (Throwable e) {
            return new ResponseEntity<>(String.format("%s\n%s", e, ExceptionUtils.getStackTrace(e)), headers, HttpStatus.OK);
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
//        for (IOStream binary : pack.getBinaries()) {
//            binary.read(out);
//        }
//    }

    Object exec(HttpServletRequest request) {
        JSONObject params = getParams(request);
        StringBuilder echo = new StringBuilder();
        ShellCommand cmd = new ShellCommand(params.getString("cmd"), params.getString("workspace"));
        cmd.onPrintOut.combine((s, e) -> echo.append(e.toString()));
        cmd.start().waitFor(30000);
        return echo.toString();
    }

    @SneakyThrows
    Object invoke(HttpServletRequest request) {
        JSONObject params = getParams(request);
        Class<?> kls = Class.forName(params.getString("bean"));
        Object bean = SpringContext.getBean(kls);
        Method method = Reflects.getMethodMap(kls).get(params.getString("method")).first();
        List<Object> args = params.getJSONArray("args");
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

    Map<String, Object> findTopUsage(Date begin, Date end) {
        Map<String, Object> result = new LinkedHashMap<>(5);
        Linq<CpuWatchman.ThreadUsageView> topUsage = CpuWatchman.findTopUsage(begin, end);
        result.put("deadlocked", topUsage.where(p -> p.getBegin().isDeadlocked() || p.getEnd().isDeadlocked()).select(CpuWatchman.ThreadUsageView::toString));

        result.put("topCpuTime", topUsage.orderByDescending(CpuWatchman.ThreadUsageView::getCpuNanosElapsed).select(CpuWatchman.ThreadUsageView::toString));

        result.put("topUserTime", topUsage.orderByDescending(CpuWatchman.ThreadUsageView::getUserNanosElapsed).select(CpuWatchman.ThreadUsageView::toString));

        result.put("topBlockedTime", topUsage.orderByDescending(CpuWatchman.ThreadUsageView::getBlockedElapsed).select(CpuWatchman.ThreadUsageView::toString));

        result.put("topWaitedTime", topUsage.orderByDescending(CpuWatchman.ThreadUsageView::getWaitedElapsed).select(CpuWatchman.ThreadUsageView::toString));
        return result;
    }

    Map<String, Object> queryTraces(Date startTime, Date endTime, String level, String keyword, Boolean newest,
                                    Boolean methodOccurMost, String methodNamePrefix, String metricsName,
                                    Integer take) {
        Map<String, Object> result = new LinkedHashMap<>(3);
        ExceptionLevel el = null;
        if (!Strings.isBlank(level)) {
            el = ExceptionLevel.valueOf(level);
        }
        result.put("errorTraces", TraceHandler.INSTANCE.queryExceptionTraces(startTime, endTime, el, keyword, newest, take));

        result.put("methodTraces", Linq.from(TraceHandler.INSTANCE.queryMethodTraces(methodNamePrefix, methodOccurMost, take)).select(p -> {
            Map<String, Object> t = Sys.toJsonObject(p);
            t.remove("elapsedMicros");
            t.put("elapsed", Sys.formatNanosElapsed(p.getElapsedMicros(), 1));
            return t;
        }));

        result.put("metrics", TraceHandler.INSTANCE.queryMetrics(metricsName, take));
        return result;
    }

    @SneakyThrows
    JSONObject getParams(HttpServletRequest request) {
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
        Map<String, Object> threadInfo = new LinkedHashMap<>(5);
        j.put("threadInfo", threadInfo);
        Linq<ThreadEntity> ts = CpuWatchman.getLatestSnapshot();
        int take = Reflects.convertQuietly(request.getParameter("take"), Integer.class, 5);
        threadInfo.put("deadlocked", ts.where(ThreadEntity::isDeadlocked).select(p -> p.toString()));
        threadInfo.put("topUserTime", ts.orderByDescending(ThreadEntity::getUserNanos).take(take).select(p -> p.toString()));
        threadInfo.put("topCpuTime", ts.orderByDescending(ThreadEntity::getCpuNanos).take(take).select(p -> p.toString()));
        threadInfo.put("topBlockedTime", ts.orderByDescending(ThreadEntity::getBlockedTime).take(take).select(p -> p.toString()));
        threadInfo.put("topWaitedTime", ts.orderByDescending(ThreadEntity::getWaitedTime).take(take).select(p -> p.toString()));
        j.put("ntpOffset", Reflects.readStaticField(NtpClock.class, "offset"));

        j.put("rxConfig", RxConfig.INSTANCE);
        j.put("requestHeaders", Linq.from(Collections.list(request.getHeaderNames())).select(p -> String.format("%s: %s", p, String.join("; ", Collections.list(request.getHeaders(p))))));
        j.putAll(queryTraces(null, null, null, null, null,
                null, null, null,
                take));
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
        //todo all topic
        Tasks.setTimeout(() -> {
            String omega = event.<RxConfig>source().getOmega();
            if (omega != null) {
                SocksContext.omega(omega, null);
            }
        }, 60 * 1000);
    }
}
