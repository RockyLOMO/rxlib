package org.rx.spring;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.bean.RxConfig;
import org.rx.io.IOStream;
import org.rx.net.Sockets;
import org.rx.net.http.tunnel.ReceivePack;
import org.rx.net.http.tunnel.SendPack;
import org.rx.net.http.tunnel.Server;
import org.rx.util.BeanMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RequiredArgsConstructor
@RestController
@RequestMapping("apix")
public class ApiController {
    private final RxConfig rxConfig;
    private final Server server;

    @PostMapping("config")
    public RxConfig config(@RequestBody RxConfig config) {
        return BeanMapper.getInstance().map(config, rxConfig);
    }

    @PostMapping("directOffer")
    public void directOffer(String appName, String socksId, String endpoint, MultipartFile binary) {
        SendPack pack = new SendPack(appName, socksId, Sockets.parseEndpoint(endpoint));
        pack.setBinary(binary);
        server.frontendOffer(pack);
    }

    @SneakyThrows
    @PostMapping("directPoll")
    public void directPoll(String appName, String socksId, String endpoint, HttpServletResponse response) {
        ReceivePack pack = server.frontendPoll(new SendPack(appName, socksId, Sockets.parseEndpoint(endpoint)));
        ServletOutputStream out = response.getOutputStream();
        for (IOStream<?, ?> binary : pack.getBinaries()) {
            binary.copyTo(out);
        }
    }
}
