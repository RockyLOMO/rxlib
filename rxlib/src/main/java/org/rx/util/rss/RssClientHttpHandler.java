package org.rx.util.rss;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rx.bean.DateTime;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.io.Bytes;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.rx.net.socks.TrafficLoginInfo;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RssClientHttpHandler implements HttpServer.Handler {
    public static final String SHADOW_USERS_PAGE_PATH = "/ss-users";
    static final String SHADOW_USERS_PAGE_TITLE = "RSS SS 用户信息";
    private static final String USERNAME = "rxlib";
    private static final String REALM = "rxlib-diagnostic";
    private static final Comparator<ShadowUser> USER_COMPARATOR = new Comparator<ShadowUser>() {
        @Override
        public int compare(ShadowUser o1, ShadowUser o2) {
            String left = o1 == null || o1.getUsername() == null ? "" : o1.getUsername();
            String right = o2 == null || o2.getUsername() == null ? "" : o2.getUsername();
            int cmp = left.compareToIgnoreCase(right);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(o1 == null ? 0 : o1.getSsPort(), o2 == null ? 0 : o2.getSsPort());
        }
    };
    private static final Comparator<Map.Entry<InetAddress, TrafficLoginInfo>> LOGIN_IP_COMPARATOR = new Comparator<Map.Entry<InetAddress, TrafficLoginInfo>>() {
        @Override
        public int compare(Map.Entry<InetAddress, TrafficLoginInfo> o1, Map.Entry<InetAddress, TrafficLoginInfo> o2) {
            long left = latestTimeMillis(o1 == null ? null : o1.getValue());
            long right = latestTimeMillis(o2 == null ? null : o2.getValue());
            int cmp = Long.compare(right, left);
            if (cmp != 0) {
                return cmp;
            }
            String leftIp = o1 == null || o1.getKey() == null ? "" : o1.getKey().getHostAddress();
            String rightIp = o2 == null || o2.getKey() == null ? "" : o2.getKey().getHostAddress();
            return leftIp.compareTo(rightIp);
        }
    };

    private final Map<String, ShadowUser> shadowStore;

    public RssClientHttpHandler(Map<String, ShadowUser> shadowStore) {
        this.shadowStore = shadowStore;
    }

    @Override
    public HttpMethod[] method() {
        return new HttpMethod[]{HttpMethod.GET};
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) {
        response.getHeaders().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        if (Strings.isEmpty(RxConfig.INSTANCE.getRtoken())) {
            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
            response.htmlBody(page("RSS console disabled", "<p><code>app.rtoken</code> is empty.</p>"));
            return;
        }
        if (!authorize(request)) {
            response.setStatus(HttpResponseStatus.UNAUTHORIZED);
            response.getHeaders().set(HttpHeaderNames.WWW_AUTHENTICATE,
                    "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
            response.htmlBody(page("Authorization required", "<p>Use Basic Auth: <code>rxlib</code> / <code>app.rtoken</code>.</p>"));
            return;
        }
        response.htmlBody(renderShadowUsersPage(shadowStore));
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore) {
        List<ShadowUser> users = new ArrayList<>();
        if (shadowStore != null && !shadowStore.isEmpty()) {
            users.addAll(shadowStore.values());
            Collections.sort(users, USER_COMPARATOR);
        }

        long totalReadBytes = 0L;
        long totalWriteBytes = 0L;
        int totalLoginIpCount = 0;
        List<Map<String, Object>> userRows = new ArrayList<>(users.size());
        for (ShadowUser user : users) {
            if (user == null) {
                continue;
            }
            long userReadBytes = user.getTotalReadBytes();
            long userWriteBytes = user.getTotalWriteBytes();
            List<Map<String, Object>> loginIps = toShadowLoginIpRows(user);

            totalReadBytes += userReadBytes;
            totalWriteBytes += userWriteBytes;
            totalLoginIpCount += loginIps.size();

            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("username", user.getUsername());
            row.put("socksUser", Strings.isEmpty(user.getSocksUser()) ? "-" : user.getSocksUser());
            row.put("ssPort", user.getSsPort());
            row.put("ipLimit", user.getIpLimit() < 0 ? "不限" : String.valueOf(user.getIpLimit()));
            row.put("lastResetTime", formatDateTime(user.getLastResetTime()));
            row.put("loginIpCount", loginIps.size());
            row.put("totalReadBytes", Bytes.readableByteSize(userReadBytes));
            row.put("totalWriteBytes", Bytes.readableByteSize(userWriteBytes));
            row.put("totalReadPackets", user.getTotalReadPackets());
            row.put("totalWritePackets", user.getTotalWritePackets());
            row.put("hasLoginIps", !loginIps.isEmpty());
            row.put("loginIps", loginIps);
            userRows.add(row);
        }

        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("path", SHADOW_USERS_PAGE_PATH);
        vars.put("generatedAt", DateTime.now().toDateTimeString());
        vars.put("hasUsers", !userRows.isEmpty());
        vars.put("users", userRows);
        vars.put("stats", toShadowStats(userRows.size(), totalLoginIpCount, totalReadBytes, totalWriteBytes));

        LinkedHashMap<String, Object> pageVars = new LinkedHashMap<>();
        pageVars.put("title", SHADOW_USERS_PAGE_TITLE);
        pageVars.put("body", HttpServer.renderHtmlTemplate("rx-rss-users.html", vars));
        return HttpServer.renderHtmlTemplate("rx-diagnostic.html", pageVars);
    }

    private static List<Map<String, Object>> toShadowStats(int totalUsers, int totalLoginIpCount,
                                                           long totalReadBytes, long totalWriteBytes) {
        List<Map<String, Object>> stats = new ArrayList<>(4);
        stats.add(summaryItem("SS 用户数", totalUsers, "当前加载的 ShadowSocks 用户数量"));
        stats.add(summaryItem("登录 IP 数", totalLoginIpCount, "按用户聚合后的来源 IP 快照"));
        stats.add(summaryItem("累计下行", Bytes.readableByteSize(totalReadBytes), "所有用户累计读取字节"));
        stats.add(summaryItem("累计上行", Bytes.readableByteSize(totalWriteBytes), "所有用户累计写入字节"));
        return stats;
    }

    private static Map<String, Object> summaryItem(String label, Object value, String meta) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(3);
        row.put("label", label);
        row.put("value", value);
        row.put("meta", meta);
        return row;
    }

    private static List<Map<String, Object>> toShadowLoginIpRows(ShadowUser user) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (user == null || user.getLoginIps() == null || user.getLoginIps().isEmpty()) {
            return rows;
        }

        List<Map.Entry<InetAddress, TrafficLoginInfo>> entries = new ArrayList<>(user.getLoginIps().entrySet());
        Collections.sort(entries, LOGIN_IP_COMPARATOR);

        for (Map.Entry<InetAddress, TrafficLoginInfo> entry : entries) {
            TrafficLoginInfo info = entry.getValue();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("ip", entry.getKey() == null ? "" : entry.getKey().getHostAddress());
            row.put("latestTime", formatDateTime(info == null ? null : info.getLatestTime()));
            row.put("refCnt", info == null ? 0 : info.getRefCnt().get());
            row.put("activeSeconds", info == null ? 0L : info.getTotalActiveSeconds().get());
            row.put("totalReadBytes", info == null ? "0B" : Bytes.readableByteSize(info.getTotalReadBytes().get()));
            row.put("totalWriteBytes", info == null ? "0B" : Bytes.readableByteSize(info.getTotalWriteBytes().get()));
            row.put("totalReadPackets", info == null ? 0L : info.getTotalReadPackets().get());
            row.put("totalWritePackets", info == null ? 0L : info.getTotalWritePackets().get());
            rows.add(row);
        }
        return rows;
    }

    private static long latestTimeMillis(TrafficLoginInfo info) {
        DateTime latestTime = info == null ? null : info.getLatestTime();
        return latestTime == null ? Long.MIN_VALUE : latestTime.getTime();
    }

    private static String formatDateTime(DateTime value) {
        return value == null ? "-" : value.toDateTimeString();
    }

    private boolean authorize(ServerRequest request) {
        String header = request.getHeaders().get(HttpHeaderNames.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String actual = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            String expected = USERNAME + ":" + RxConfig.INSTANCE.getRtoken();
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String page(String title, String body) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", title);
        vars.put("body", body);
        return HttpServer.renderHtmlTemplate("rx-diagnostic.html", vars);
    }
}
