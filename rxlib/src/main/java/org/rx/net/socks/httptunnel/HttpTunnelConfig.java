package org.rx.net.socks.httptunnel;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.net.SocketConfig;

@Data
@EqualsAndHashCode(callSuper = true)
public class HttpTunnelConfig extends SocketConfig {
    private static final long serialVersionUID = 7629483716253948201L;

    // ---- Client side ----
    /**
     * 本地 SOCKS5 监听端口
     */
    private int listenPort;
    /**
     * 远程 HttpTunnelServer 的 URL, 例如 http://1.2.3.4:8080/tunnel
     */
    private String tunnelUrl;
    /**
     * 长轮询超时秒数
     */
    private int pollTimeoutSeconds = 55;

    // ---- Server side ----
    /**
     * HTTP 监听端口
     */
    private int httpPort;
    /**
     * URL 路径
     */
    private String tunnelPath = "/tunnel";

    // ---- Shared ----
    /**
     * 简单 token 认证 (可选)
     */
    private String token;
    /**
     * UDP 读超时秒数
     */
    private int udpReadTimeoutSeconds = 120;
}
