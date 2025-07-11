package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.FastDateFormat;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@ChannelHandler.Sharable
public class HttpPseudoHeaderEncoder extends MessageToByteEncoder<ByteBuf> {
    public static final HttpPseudoHeaderEncoder DEFAULT = new HttpPseudoHeaderEncoder();

    //region HttpPathGenerator
    // 常见 API 前缀
    private static final String[] PREFIXES = {
            "api", "v1", "v2", "rest", "web"
    };

    // 常见资源名
    private static final String[] RESOURCES = {
            "user", "login", "auth", "profile", "settings",
            "data", "order", "product", "cart", "payment",
            "post", "comment", "blog", "search", "info"
    };

    // 可选后缀（动作或 ID）
    private static final String[] SUFFIXES = {
            "profile", "settings", "list", "detail", "create",
            "update", "delete", "info", "status", ""
    };

    // 大陆常见顶级域名
    private static final String[] TLDS = {
            ".cn", ".com.cn", ".net.cn", ".org.cn", ".edu.cn",
            ".gov.cn", ".com", ".net", ".org"
    };

    // 常见子域名或品牌名
    private static final String[] SUBDOMAINS = {
            "api", "data", "web", "shop", "cloud", "app",
            "baidu", "alibaba", "tencent", "jd", "sina",
            "sohu", "163", "qq", "xiaomi", "huawei"
    };

    // 常见查询参数键
    private static final String[] QUERY_KEYS = {
            "id", "type", "page", "size", "query", "token",
            "category", "sort", "filter", "lang"
    };

    /**
     * 生成随机但逼真的 HTTP URL 路径，可选包含查询参数。
     *
     * @return 形如 /api/user 或 /v1/product/123?id=456 的路径
     */
    public static String generateRandomPath() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder path = new StringBuilder("/");

        // 50% 概率添加前缀（如 /api）
        if (random.nextBoolean()) {
            path.append(PREFIXES[random.nextInt(PREFIXES.length)]).append("/");
        }

        // 添加资源名（如 user, login）
        path.append(RESOURCES[random.nextInt(RESOURCES.length)]);

        // 50% 概率添加后缀（如 /profile 或 /123）
        if (random.nextBoolean()) {
            String suffix = SUFFIXES[random.nextInt(SUFFIXES.length)];
            if (!suffix.isEmpty()) {
                path.append("/").append(suffix);
            } else {
                // 30% 概率添加随机数字 ID
                if (random.nextDouble() < 0.3) {
                    path.append("/").append(random.nextInt(10000));
                }
            }
        }

        // 50% 概率添加查询参数
        if (random.nextBoolean()) {
            path.append(generateRandomQueryString());
        }

        return path.toString();
    }

    /**
     * 生成随机但逼真的中国大陆域名。
     *
     * @return 形如 example.cn 或 api.tencent.com 的域名
     */
    public static String generateRandomHost() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder host = new StringBuilder();

        // 30% 概率添加子域名（如 api）
        if (random.nextDouble() < 0.3) {
            host.append(SUBDOMAINS[random.nextInt(SUBDOMAINS.length)]).append(".");
        }

        // 添加主域名（随机或品牌）
        host.append(SUBDOMAINS[random.nextInt(SUBDOMAINS.length)]);

        // 添加顶级域名
        host.append(TLDS[random.nextInt(TLDS.length)]);

        return host.toString();
    }

    /**
     * 生成随机但逼真的查询参数。
     *
     * @return 形如 ?id=123 或 ?type=user&page=1 的查询字符串
     */
    private static String generateRandomQueryString() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder query = new StringBuilder("?");

        // 随机生成 1-3 个查询参数
        int paramCount = random.nextInt(3) + 1;
        for (int i = 0; i < paramCount; i++) {
            String key = QUERY_KEYS[random.nextInt(QUERY_KEYS.length)];
            String value;

            // 50% 概率生成数字值，其他生成短字符串
            if (random.nextBoolean()) {
                value = String.valueOf(random.nextInt(1000));
            } else {
                String[] values = {"user", "admin", "json", "xml", "asc", "desc", "en", "zh"};
                value = values[random.nextInt(values.length)];
            }

            query.append(key).append("=").append(value);
            if (i < paramCount - 1) {
                query.append("&");
            }
        }

        return query.toString();
    }
    //endregion

    static final String GET_REQUEST_PSEUDO_HEADER = "GET %s HTTP/1.1\r\n" +
            "Connection: keep-alive\r\n" +
            "Host: %s\r\n" +
            "Accept: */*\r\n" +
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n" +
            "Content-Length: %s\r\n\r\n";
    static final String POST_REQUEST_PSEUDO_HEADER = "POST %s HTTP/1.1\r\n" +
            "Connection: keep-alive\r\n" +
            "Host: %s\r\n" +
            "Accept: */*\r\n" +
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: %s\r\n\r\n";
    static final String RESPONSE_PSEUDO_HEADER = "HTTP/1.1 200 OK\r\n" +
            "Date: %s\r\n" +
            "Server: Tengine\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: %s\r\n\r\n";

    private static String generateDateHeader() {
        return FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"), Locale.US).format(new Date());
    }

//    short i = 0;

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
//        if (i > 1) {
//            throw new InvalidException("encode ex");
//        } else {
//            i++;
//        }

        String headers;
        if (Boolean.TRUE.equals(Sockets.getAttr(ctx.channel(), SocketConfig.ATTR_PSEUDO_SVR, false))) {
            headers = String.format(RESPONSE_PSEUDO_HEADER, generateDateHeader(), msg.readableBytes());
        } else {
            if (ThreadLocalRandom.current().nextInt(0, 100) >= 20) {
                headers = String.format(GET_REQUEST_PSEUDO_HEADER, generateRandomPath(), generateRandomHost(), msg.readableBytes());
            } else {
                headers = String.format(POST_REQUEST_PSEUDO_HEADER, generateRandomPath(), generateRandomHost(), msg.readableBytes());
            }
        }
        log.debug("pseudo encode {}", headers);

        out.writeCharSequence(headers, StandardCharsets.US_ASCII);
        out.writeBytes(msg);
    }
}
