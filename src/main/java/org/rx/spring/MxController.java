package org.rx.spring;

import com.alibaba.fastjson.JSONObject;
import com.sun.management.HotSpotDiagnosticMXBean;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.ExceptionHandler;
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
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("mx")
public class MxController {
    final Server server;

    @RequestMapping("queryTraces")
    public List<ExceptionHandler.ErrorEntity> queryTraces(Boolean newest, String level, Integer take) {
        ExceptionLevel el = null;
        if (!Strings.isBlank(level)) {
            el = ExceptionLevel.valueOf(level);
        }
        return ExceptionHandler.INSTANCE.queryTraces(newest, el, take);
    }

    @RequestMapping("queryMetrics")
    public List<ExceptionHandler.MetricsEntity> queryMetrics(String name, Integer take) {
        return ExceptionHandler.INSTANCE.queryMetrics(name, take);
    }

    @RequestMapping("setConfig")
    public RxConfig setConfig(@RequestBody RxConfig config) {
        return BeanMapper.DEFAULT.map(config, RxConfig.INSTANCE);
    }

    @RequestMapping("svr")
    public JSONObject server(HttpServletRequest request) {
        JSONObject j = new JSONObject(true);
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

//        j.put("conf", conf);
        j.put("requestHeaders", NQuery.of(Collections.list(request.getHeaderNames()))
                .select(p -> String.format("%s: %s", p, String.join("; ", Collections.list(request.getHeaders(p))))));
        j.put("errorTraces", queryTraces(null, null, 10));
        j.put("metrics", queryMetrics(null, 15));
        return j;
    }

    @RequestMapping("setVMOption")
    public void setVMOption(String k, String v) {
        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        bean.setVMOption(k, v);
    }

    @RequestMapping("shell")
    public String shell(String shell, String workspace) {
        StringBuilder echo = new StringBuilder();
        ShellCommander cmd = new ShellCommander(shell, workspace);
        cmd.onOutPrint.combine((s, e) -> echo.append(e.toString()));
        cmd.start().waitFor(60);
        return echo.toString();
    }

    @SneakyThrows
    @RequestMapping("resolveHost")
    public Object[] resolveHost(String host) {
        return NQuery.of(InetAddress.getAllByName(host)).select(p -> p.getHostAddress()).toArray();
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
