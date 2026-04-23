package org.springframework.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.io.DuplexStream;
import org.rx.net.NetEventWait;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientConfig;
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
                case 12:
                    resText = invokeEx(params.getString("expr"), params.getJSONArray("args"));
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

        j.put("requestHeaders", Linq.from(Collections.list(request.getHeaderNames())).select(p -> String.format("%s: %s", p, String.join("; ", Collections.list(request.getHeaders(p))))));
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

        result.put("metrics", Collections.emptyList());
        return result;
    }

    Object invokeEx(String expr, List<Object> args) {
        if (expr == null || expr.lastIndexOf(DOT) == -1) {
            throw new InvalidException("Class name not fund");
        }
        return Reflects.invokeExpression(expr, args, p -> SpringContext.getBean(p, false));
    }

    @SneakyThrows
    JSONObject getParams(HttpServletRequest request, boolean xcha) {
        try {
            String b = request.getParameter(PARAMS_NAME);
            if (b == null) {
                b = DuplexStream.readString(request.getInputStream(), StandardCharsets.UTF_8);
            }
//            log.info("rauth body:{}", b);
            if (Strings.isBlank(b)) {
                return null;
            }

            b = xcha ? new String(XChaCha20Poly1305Util.decrypt(CodecUtil.convertFromBase64(b)), StandardCharsets.UTF_8) : b;
            if (Strings.startsWith(b, "https")) {
                try (HttpClient client = new HttpClient()) {
                    try (HttpClient.Response response = client.get(b)) {
                        b = response.bodyAsString();
                    }
                }
            }
            return toJsonObject(b);
        } catch (Throwable e) {
            log.debug("around {}", e.toString());
//            log.warn("around", e);
            return null;
        }
    }

    boolean auth(HttpServletRequest request) {
        String h = "rtoken";
        String t = RxConfig.INSTANCE.getRtoken();
        log.info("rauth {} = {} -> {}", request.getHeader(h), t, eq(request.getHeader(h), t));
        return eq(request.getHeader(h), t) || eq(request.getParameter(h), t);
    }
}
