package org.rx.spring;

import com.alibaba.fastjson.TypeReference;
import com.sun.management.HotSpotDiagnosticMXBean;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.TraceHandler;
import org.rx.exception.ExceptionLevel;
import org.rx.io.Bytes;
import org.rx.net.http.tunnel.Server;
import org.rx.net.socks.SocksContext;
import org.rx.util.BeanMapper;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.rx.core.App.fromJson;
import static org.rx.core.Extends.eq;

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
                    Map<String, Object> t = App.toJsonObject(p);
                    t.remove("elapsedMicros");
                    t.put("elapsed", App.formatElapsed(p.getElapsedMicros()));
                    return t;
                }));

        result.put("metrics", TraceHandler.INSTANCE.queryMetrics(metricsName, take));
        return result;
    }

    @RequestMapping("health")
    public Object health(HttpServletRequest request) {
        if (!check(request)) {
            return null;
        }
        try {
            switch (Integer.parseInt(request.getParameter("method"))) {
                case 1:
                    String config = request.getParameter("config");
                    return BeanMapper.DEFAULT.map(fromJson(config, new TypeReference<RxConfig>() {
                    }.getType()), RxConfig.INSTANCE);
                case 2:
                    String k = request.getParameter("k"),
                            v = request.getParameter("v");
                    HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
                    bean.setVMOption(k, v);
                    return "ok";
                case 3:
                    String host = request.getParameter("host");
                    return Linq.from(InetAddress.getAllByName(host)).select(p -> p.getHostAddress()).toArray();
                case 4:
                    String a1 = request.getParameter("cmd"),
                            a2 = request.getParameter("workspace");
                    StringBuilder echo = new StringBuilder();
                    ShellCommander cmd = new ShellCommander(a1, a2);
                    cmd.onPrintOut.combine((s, e) -> echo.append(e.toString()));
                    cmd.start().waitFor(60);
                    return echo.toString();
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

    Map<String, Object> svrState(HttpServletRequest request) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("jarFile", App.getJarFile(this));
        j.put("inputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        try {
            bean.setVMOption("UnlockCommercialFeatures", Boolean.TRUE.toString());
        } catch (Exception e) {
            j.put("UnlockCommercialFeatures", e.toString());
        }
        j.put("vmOptions", bean.getDiagnosticOptions());
        j.put("systemProperties", System.getProperties());
        j.put("cpuThreads", Constants.CPU_THREADS);
        File root = new File("/");
        j.put("diskUsableSpace", Bytes.readableByteSize(root.getUsableSpace()));
        j.put("diskTotalSpace", Bytes.readableByteSize(root.getTotalSpace()));
        j.put("ntpOffset", Reflects.readStaticField(NtpClock.class, "offset"));

//        j.put("conf", conf);
        j.put("requestHeaders", Linq.from(Collections.list(request.getHeaderNames()))
                .select(p -> String.format("%s: %s", p, String.join("; ", Collections.list(request.getHeaders(p))))));
        j.putAll(queryTraces(null, null, null, null, null, 10, request));
        return j;
    }

    boolean check(HttpServletRequest request) {
        System.out.println("x1"+request.getHeader(RxConfig.ConfigNames.MXPWD)+";"+ RxConfig.INSTANCE.getMxpwd());
        return eq(request.getHeader(RxConfig.ConfigNames.MXPWD), RxConfig.INSTANCE.getMxpwd());
    }

    @SneakyThrows
    @PostConstruct
    public void init() {
        Class.forName(App.class.getName());
        Tasks.setTimeout(() -> {
            String omega = RxConfig.INSTANCE.getOmega();
            if (omega != null) {
                SocksContext.omega(omega, null);
            }
        }, 60 * 1000);
    }
}
