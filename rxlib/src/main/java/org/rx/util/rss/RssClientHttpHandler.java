package org.rx.util.rss;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rx.bean.RandomList;
import org.rx.bean.DateTime;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.io.Bytes;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.rx.net.socks.SocksUserTraffic;
import org.rx.net.socks.TrafficLoginInfo;
import org.rx.net.support.GeoManager;
import org.rx.net.support.IpGeolocation;
import org.rx.net.support.UpstreamSupport;
import org.rx.util.rss.RssUserTrafficStore.LoginIpTrafficSummary;
import org.rx.util.rss.RssUserTrafficStore.ProtocolTrafficSummary;
import org.rx.util.rss.RssUserTrafficStore.UserTrafficSummary;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RssClientHttpHandler implements HttpServer.Handler {
    public static final String SHADOW_USERS_PAGE_PATH = "/ss-users";
    static final String SHADOW_USERS_PAGE_TITLE = "RSS SS 用户信息";
    private static final String USERNAME = "rxlib";
    private static final String REALM = "rxlib-diagnostic";
    private static final long DEFAULT_QUERY_RANGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
    private static final String QUERY_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm";
    private static final Comparator<UserTrafficSummary> USER_SUMMARY_COMPARATOR = new Comparator<UserTrafficSummary>() {
        @Override
        public int compare(UserTrafficSummary o1, UserTrafficSummary o2) {
            return compareUsernames(o1 == null ? null : o1.getUsername(), o2 == null ? null : o2.getUsername());
        }
    };
    private static final Comparator<ProtocolTrafficSummary> PROTOCOL_SUMMARY_COMPARATOR = new Comparator<ProtocolTrafficSummary>() {
        @Override
        public int compare(ProtocolTrafficSummary o1, ProtocolTrafficSummary o2) {
            int cmp = compareUsernames(o1 == null ? null : o1.getUsername(), o2 == null ? null : o2.getUsername());
            if (cmp != 0) {
                return cmp;
            }
            return compareProtocol(o1 == null ? null : o1.getProtocol(), o2 == null ? null : o2.getProtocol());
        }
    };
    private static final Comparator<LoginIpTrafficSummary> LOGIN_IP_SUMMARY_COMPARATOR = new Comparator<LoginIpTrafficSummary>() {
        @Override
        public int compare(LoginIpTrafficSummary o1, LoginIpTrafficSummary o2) {
            int cmp = compareUsernames(o1 == null ? null : o1.getUsername(), o2 == null ? null : o2.getUsername());
            if (cmp != 0) {
                return cmp;
            }
            cmp = compareProtocol(o1 == null ? null : o1.getProtocol(), o2 == null ? null : o2.getProtocol());
            if (cmp != 0) {
                return cmp;
            }
            long left = latestTimeMillis(o1 == null ? null : o1.getLatestTime());
            long right = latestTimeMillis(o2 == null ? null : o2.getLatestTime());
            cmp = Long.compare(right, left);
            if (cmp != 0) {
                return cmp;
            }
            String leftIp = o1 == null || o1.getRemoteIp() == null ? "" : o1.getRemoteIp();
            String rightIp = o2 == null || o2.getRemoteIp() == null ? "" : o2.getRemoteIp();
            return leftIp.compareTo(rightIp);
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
    static volatile GeoLookup geoLookup = new GeoLookup() {
        @Override
        public IpGeolocation resolve(String ip) {
            return GeoManager.INSTANCE.resolveIp(ip);
        }
    };

    static final class Query {
        long fromMillis;
        long toMillis;
        String fromValue;
        String toValue;
    }

    interface GeoLookup {
        IpGeolocation resolve(String ip);
    }

    private final Map<String, ShadowUser> shadowStore;
    private final RssUserTrafficStore trafficStore;
    private final int memoryRetentionHours;

    public RssClientHttpHandler(Map<String, ShadowUser> shadowStore) {
        this(shadowStore, null, RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS);
    }

    public RssClientHttpHandler(Map<String, ShadowUser> shadowStore, RssUserTrafficStore trafficStore) {
        this(shadowStore, trafficStore, RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS);
    }

    public RssClientHttpHandler(Map<String, ShadowUser> shadowStore, RssUserTrafficStore trafficStore, int memoryRetentionHours) {
        this.shadowStore = shadowStore;
        this.trafficStore = trafficStore;
        this.memoryRetentionHours = Math.max(1, memoryRetentionHours);
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
        response.htmlBody(renderShadowUsersPage(shadowStore, trafficStore, parseQuery(request), effectiveMemoryRetentionHours(memoryRetentionHours)));
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore) {
        return renderShadowUsersPage(shadowStore, RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS);
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore, int memoryRetentionHours) {
        Query query = new Query();
        query.toMillis = System.currentTimeMillis();
        query.fromMillis = query.toMillis - DEFAULT_QUERY_RANGE_MILLIS;
        query.fromValue = formatQueryTime(query.fromMillis);
        query.toValue = formatQueryTime(query.toMillis);
        return renderShadowUsersPage(shadowStore, null, query, memoryRetentionHours);
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore, RssUserTrafficStore trafficStore, Query query) {
        return renderShadowUsersPage(shadowStore, trafficStore, query, RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS);
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore, RssUserTrafficStore trafficStore, Query query, int memoryRetentionHours) {
        return renderShadowUsersPage(shadowStore, trafficStore, query, memoryRetentionHours, currentUpstreamSnapshots());
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore, RssUserTrafficStore trafficStore, Query query,
                                        int memoryRetentionHours, RssRuntime.UpstreamSnapshot upstreamSnapshot) {
        List<RssRuntime.UpstreamSnapshot> snapshots = upstreamSnapshot == null
                ? Collections.<RssRuntime.UpstreamSnapshot>emptyList() : Collections.singletonList(upstreamSnapshot);
        return renderShadowUsersPage(shadowStore, trafficStore, query, memoryRetentionHours, snapshots);
    }

    static String renderShadowUsersPage(Map<String, ShadowUser> shadowStore, RssUserTrafficStore trafficStore, Query query,
                                        int memoryRetentionHours, List<RssRuntime.UpstreamSnapshot> upstreamSnapshots) {
        int effectiveMemoryRetentionHours = effectiveMemoryRetentionHours(memoryRetentionHours);
        int effectiveRetentionDays = trafficStore == null ? 0 : trafficStore.retentionDays();
        List<UserTrafficSummary> historyUsers = trafficStore == null ? Collections.<UserTrafficSummary>emptyList()
                : trafficStore.queryUserSummaries(query.fromMillis, query.toMillis);
        List<ProtocolTrafficSummary> protocolSummaries = trafficStore == null ? Collections.<ProtocolTrafficSummary>emptyList()
                : trafficStore.queryProtocolSummaries(query.fromMillis, query.toMillis);
        List<LoginIpTrafficSummary> historyIps = trafficStore == null ? Collections.<LoginIpTrafficSummary>emptyList()
                : trafficStore.queryLoginIpSummaries(query.fromMillis, query.toMillis);

        Collections.sort(historyUsers, USER_SUMMARY_COMPARATOR);
        Collections.sort(protocolSummaries, PROTOCOL_SUMMARY_COMPARATOR);
        Collections.sort(historyIps, LOGIN_IP_SUMMARY_COMPARATOR);

        List<Map<String, Object>> userRows = buildHistoryUserRows(historyUsers, shadowStore);
        List<Map<String, Object>> protocolRows = buildProtocolRows(protocolSummaries, shadowStore);
        List<Map<String, Object>> loginIpRows = buildHistoryLoginIpRows(historyIps, shadowStore);
        List<Map<String, Object>> liveIpRows = buildLiveLoginIpRows(shadowStore);
        List<Map<String, Object>> upstreamRows = buildUpstreamRows(upstreamSnapshots);

        LinkedHashMap<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put("path", SHADOW_USERS_PAGE_PATH);
        vars.put("generatedAt", DateTime.now().toDateTimeString());
        vars.put("queryFrom", query.fromValue);
        vars.put("queryTo", query.toValue);
        vars.put("selectedRange", formatDateTime(new Date(query.fromMillis)) + " - " + formatDateTime(new Date(query.toMillis)));
        vars.put("h2RetentionDays", effectiveRetentionDays);
        vars.put("memoryRetentionHours", effectiveMemoryRetentionHours);
        vars.put("memoryResetPolicy", "用户开始有连接后按 " + effectiveMemoryRetentionHours + " 小时窗口保留登录 IP；无连接用户不更新内存统计窗口。");
        vars.put("stats", buildStats(userRows.size(), protocolRows.size(), loginIpRows.size(), liveIpRows.size(),
                historyUsers, query, trafficStore, effectiveMemoryRetentionHours, upstreamRows));
        vars.put("hasUpstreams", !upstreamRows.isEmpty());
        vars.put("upstreams", upstreamRows);
        vars.put("hasHistoryUsers", !userRows.isEmpty());
        vars.put("historyUsers", userRows);
        vars.put("hasAnyUsers", !userRows.isEmpty() || !liveIpRows.isEmpty());
        vars.put("hasProtocolRows", !protocolRows.isEmpty());
        vars.put("protocolRows", protocolRows);
        vars.put("hasHistoryIps", !loginIpRows.isEmpty());
        vars.put("historyIps", loginIpRows);
        vars.put("hasLiveIps", !liveIpRows.isEmpty());
        vars.put("liveIps", liveIpRows);

        LinkedHashMap<String, Object> pageVars = new LinkedHashMap<String, Object>();
        pageVars.put("title", SHADOW_USERS_PAGE_TITLE);
        pageVars.put("body", HttpServer.renderHtmlTemplate("rx-rss-users.html", vars));
        return HttpServer.renderHtmlTemplate("rx-diagnostic.html", pageVars);
    }

    private static List<Map<String, Object>> buildStats(int userCount, int protocolRowCount, int historyIpCount, int liveIpCount,
                                                        List<UserTrafficSummary> historyUsers, Query query, RssUserTrafficStore trafficStore,
                                                        int memoryRetentionHours, List<Map<String, Object>> upstreamRows) {
        long totalReadBytes = 0L;
        long totalWriteBytes = 0L;
        long totalActiveSeconds = 0L;
        long totalSessions = 0L;
        for (UserTrafficSummary row : historyUsers) {
            totalReadBytes += row.getReadBytes();
            totalWriteBytes += row.getWriteBytes();
            totalActiveSeconds += row.getActiveSeconds();
            totalSessions += row.getSessionCount();
        }

        long healthyUpstreams = 0L;
        long activeUpstreamConnections = 0L;
        if (upstreamRows != null) {
            for (Map<String, Object> row : upstreamRows) {
                if (Boolean.TRUE.equals(row.get("healthyValue"))) {
                    healthyUpstreams++;
                }
                Object active = row.get("activeConnectionsValue");
                if (active instanceof Number) {
                    activeUpstreamConnections += ((Number) active).longValue();
                }
            }
        }

        List<Map<String, Object>> stats = new ArrayList<Map<String, Object> >(8);
        stats.add(summaryItem("查询范围", formatDateTime(new Date(query.fromMillis)) + " - " + formatDateTime(new Date(query.toMillis)),
                "默认近 1 个月，历史数据按 H2 查询。"));
        stats.add(summaryItem("H2 保留期", trafficStore == null ? "-" : trafficStore.retentionDays() + " 天",
                "超出保留期的小时聚合数据会自动清理。"));
        stats.add(summaryItem("内存保留期", Math.max(1, memoryRetentionHours) + " 小时",
                "从用户首次连接开始统计，空闲 IP 到期后从内存快照移除。"));
        stats.add(summaryItem("上游健康/连接", healthyUpstreams + "/" + (upstreamRows == null ? 0 : upstreamRows.size())
                        + " / " + activeUpstreamConnections,
                "可用上游数 / 展示上游数 / 当前活跃上游连接数，含等待释放旧配置。"));
        stats.add(summaryItem("历史用户数", userCount, "H2 中命中的用户聚合数量。"));
        stats.add(summaryItem("历史下行/上行", Bytes.readableByteSize(totalReadBytes) + " / " + Bytes.readableByteSize(totalWriteBytes),
                "按所选时间范围聚合的总流量。"));
        stats.add(summaryItem("历史活跃时长/会话数", formatDurationSeconds(totalActiveSeconds) + " / " + totalSessions,
                "基于已结束连接累计的活跃时长与会话数。协议与 IP 明细见下方表格。"));
        stats.add(summaryItem("协议/IP/实时行数", protocolRowCount + " / " + historyIpCount + " / " + liveIpCount,
                "协议历史行 / H2 IP 历史行 / 内存实时 IP 快照行。"));
        return stats;
    }

    static List<Map<String, Object>> buildUpstreamRows(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null) {
            return Collections.emptyList();
        }
        return buildUpstreamRows(Collections.singletonList(snapshot));
    }

    static List<Map<String, Object>> buildUpstreamRows(List<RssRuntime.UpstreamSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        long now = System.currentTimeMillis();
        for (RssRuntime.UpstreamSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.closed) {
                continue;
            }
            int snapshotActiveConnections = RssClient.activeConnectionCount(snapshot);
            appendUpstreamRows(rows, "socks", snapshot, snapshot.socksServers, snapshot.configuredSocksServers,
                    now, snapshotActiveConnections);
            appendUpstreamRows(rows, "udp2raw", snapshot, snapshot.udp2rawSocksServers,
                    snapshot.configuredUdp2rawSocksServers, now, snapshotActiveConnections);
        }
        return rows;
    }

    private static void appendUpstreamRows(List<Map<String, Object>> rows, String type, RssRuntime.UpstreamSnapshot snapshot,
                                           RandomList<UpstreamSupport> servers, List<?> configuredEndpoints,
                                           long now, int snapshotActiveConnections) {
        List<UpstreamSupport> supports = servers == null ? Collections.<UpstreamSupport>emptyList() : servers.readOnlySnapshot();
        if ((configuredEndpoints == null || configuredEndpoints.isEmpty()) && supports.isEmpty()) {
            return;
        }
        if (configuredEndpoints == null || configuredEndpoints.isEmpty()) {
            for (UpstreamSupport support : supports) {
                appendUpstreamRow(rows, type, snapshot, servers, null, null, support == null ? null : support.getEndpoint(),
                        support, now, snapshotActiveConnections);
            }
            return;
        }

        IdentityHashMap<AuthenticEndpoint, UpstreamSupport> supportByEndpoint = new IdentityHashMap<AuthenticEndpoint, UpstreamSupport>();
        for (UpstreamSupport support : supports) {
            if (support != null) {
                supportByEndpoint.put(support.getEndpoint(), support);
            }
        }
        for (Object configuredEndpoint : configuredEndpoints) {
            AuthenticEndpoint endpoint = configuredEndpoint(configuredEndpoint);
            appendUpstreamRow(rows, type, snapshot, servers, configuredEndpointId(configuredEndpoint),
                    configuredEndpoint, endpoint, supportByEndpoint.remove(endpoint),
                    now, snapshotActiveConnections);
        }
        for (UpstreamSupport support : supportByEndpoint.values()) {
            appendUpstreamRow(rows, type, snapshot, servers, null, null, support == null ? null : support.getEndpoint(),
                    support, now, snapshotActiveConnections);
        }
    }

    private static void appendUpstreamRow(List<Map<String, Object>> rows, String type, RssRuntime.UpstreamSnapshot snapshot,
                                          RandomList<UpstreamSupport> servers, String id, Object configuredEndpoint, AuthenticEndpoint endpoint,
                                          UpstreamSupport support, long now, int snapshotActiveConnections) {
        boolean healthy = support != null && support.isHealthy();
        int configuredWeight = support == null ? configuredEndpointWeight(configuredEndpoint, endpoint) : support.getConfiguredWeight();
        int activeConnections = support == null ? 0 : support.activeConnectionCount();
        int currentWeight = support == null ? 0 : currentWeight(servers, support);
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("type", type);
        row.put("id", Strings.isEmpty(id) ? "-" : id);
        row.put("state", upstreamState(snapshot));
        row.put("endpoint", formatEndpoint(support == null ? endpoint : support.getEndpoint()));
        row.put("health", upstreamHealth(support, configuredWeight));
        row.put("healthyValue", healthy);
        row.put("configuredWeight", configuredWeight);
        row.put("currentWeight", currentWeight);
        row.put("activeConnections", activeConnections);
        row.put("activeConnectionsValue", activeConnections);
        row.put("releaseWait", releaseWait(snapshot, now, snapshotActiveConnections));
        row.put("rpc", support == null || support.getFacade() == null ? "-" : "yes");
        rows.add(row);
    }

    private static AuthenticEndpoint configuredEndpoint(Object value) {
        if (value instanceof RssClientConf.SocksServer) {
            return ((RssClientConf.SocksServer) value).getEndpoint();
        }
        return value instanceof AuthenticEndpoint ? (AuthenticEndpoint) value : null;
    }

    private static String configuredEndpointId(Object value) {
        return value instanceof RssClientConf.SocksServer ? ((RssClientConf.SocksServer) value).getId() : null;
    }

    private static int configuredEndpointWeight(Object value, AuthenticEndpoint endpoint) {
        return value instanceof RssClientConf.SocksServer ? RssClient.weightOf((RssClientConf.SocksServer) value) : RssClient.weightOf(endpoint);
    }

    private static String upstreamState(RssRuntime.UpstreamSnapshot snapshot) {
        return snapshot != null && snapshot.closing ? "旧配置" : "当前配置";
    }

    private static String upstreamHealth(UpstreamSupport support, int configuredWeight) {
        if (support != null) {
            return support.isHealthy() ? "UP" : "DOWN";
        }
        return configuredWeight > 0 ? "UNKNOWN" : "DISABLED";
    }

    private static String releaseWait(RssRuntime.UpstreamSnapshot snapshot, long now, int snapshotActiveConnections) {
        if (snapshot == null || !snapshot.closing || snapshot.closed) {
            return "-";
        }
        long deadline = snapshot.closeDeadlineMillis;
        long target = deadline;
        if (snapshotActiveConnections <= 0 && snapshot.closeCheckMillis > 0L) {
            target = deadline > 0L ? Math.min(snapshot.closeCheckMillis, deadline) : snapshot.closeCheckMillis;
        }
        if (target <= 0L) {
            return "等待释放";
        }
        String wait = formatDurationMillis(Math.max(0L, target - now));
        return snapshotActiveConnections > 0 && deadline > 0L ? "<= " + wait : wait;
    }

    private static int currentWeight(RandomList<UpstreamSupport> servers, UpstreamSupport support) {
        try {
            return servers.getWeight(support);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String formatEndpoint(AuthenticEndpoint endpoint) {
        if (endpoint == null) {
            return "-";
        }
        SocketAddress address = endpoint.getEndpoint();
        String target = address == null ? "-" : Sockets.toString(address);
        return Strings.isEmpty(endpoint.getUsername()) ? target : endpoint.getUsername() + "@" + target;
    }

    private static List<RssRuntime.UpstreamSnapshot> currentUpstreamSnapshots() {
        RssRuntime rt = RssClient.runtime;
        return rt == null ? Collections.<RssRuntime.UpstreamSnapshot>emptyList() : rt.upstreamSnapshots();
    }

    private static Map<String, Object> summaryItem(String label, Object value, String meta) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>(3);
        row.put("label", label);
        row.put("value", value);
        row.put("meta", meta);
        return row;
    }

    private static List<Map<String, Object>> buildHistoryUserRows(List<UserTrafficSummary> summaries, Map<String, ShadowUser> shadowStore) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object> >(summaries.size());
        for (UserTrafficSummary summary : summaries) {
            ShadowUser shadowUser = shadowStore == null ? null : shadowStore.get(summary.getUsername());
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("username", summary.getUsername());
            row.put("socksUser", shadowUser == null || Strings.isEmpty(shadowUser.getSocksUser()) ? "-" : shadowUser.getSocksUser());
            row.put("ssPort", shadowUser == null ? "-" : shadowUser.getSsPort());
            row.put("ipLimit", shadowUser == null ? "-" : (shadowUser.getIpLimit() < 0 ? "不限" : String.valueOf(shadowUser.getIpLimit())));
            row.put("lastResetTime", shadowUser == null ? "-" : formatDateTime(shadowUser.getLastResetTime()));
            row.put("latestTime", formatDateTime(summary.getLatestTime()));
            row.put("activeDuration", formatDurationSeconds(summary.getActiveSeconds()));
            row.put("avgReadSpeed", formatSpeed(summary.getReadBytes(), summary.getActiveSeconds()));
            row.put("avgWriteSpeed", formatSpeed(summary.getWriteBytes(), summary.getActiveSeconds()));
            row.put("totalReadBytes", Bytes.readableByteSize(summary.getReadBytes()));
            row.put("totalWriteBytes", Bytes.readableByteSize(summary.getWriteBytes()));
            row.put("sessionCount", summary.getSessionCount());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildProtocolRows(List<ProtocolTrafficSummary> summaries, Map<String, ShadowUser> shadowStore) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object> >(summaries.size());
        for (ProtocolTrafficSummary summary : summaries) {
            ShadowUser shadowUser = shadowStore == null ? null : shadowStore.get(summary.getUsername());
            String protocol = normalizeProtocol(summary.getProtocol());
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("username", summary.getUsername());
            row.put("protocol", protocol.toUpperCase(Locale.ENGLISH));
            row.put("socksUser", shadowUser == null || Strings.isEmpty(shadowUser.getSocksUser()) ? "-" : shadowUser.getSocksUser());
            row.put("ssPort", shadowUser == null ? "-" : shadowUser.getSsPort());
            row.put("channelMetricLabel", SocksUserTraffic.PROTOCOL_TCP.equals(protocol) ? "TCP 通道数" : "UDP 活跃来源数");
            row.put("channelMetricValue", SocksUserTraffic.PROTOCOL_TCP.equals(protocol) ? summary.getSessionCount() : summary.getActiveIpCount());
            row.put("sessionCount", summary.getSessionCount());
            row.put("activeDuration", formatDurationSeconds(summary.getActiveSeconds()));
            row.put("avgReadSpeed", formatSpeed(summary.getReadBytes(), summary.getActiveSeconds()));
            row.put("avgWriteSpeed", formatSpeed(summary.getWriteBytes(), summary.getActiveSeconds()));
            row.put("totalReadBytes", Bytes.readableByteSize(summary.getReadBytes()));
            row.put("totalWriteBytes", Bytes.readableByteSize(summary.getWriteBytes()));
            row.put("latestTime", formatDateTime(summary.getLatestTime()));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildHistoryLoginIpRows(List<LoginIpTrafficSummary> summaries, Map<String, ShadowUser> shadowStore) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object> >(summaries.size());
        for (LoginIpTrafficSummary summary : summaries) {
            ShadowUser shadowUser = shadowStore == null ? null : shadowStore.get(summary.getUsername());
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("username", summary.getUsername());
            row.put("protocol", normalizeProtocol(summary.getProtocol()).toUpperCase(Locale.ENGLISH));
            row.put("socksUser", shadowUser == null || Strings.isEmpty(shadowUser.getSocksUser()) ? "-" : shadowUser.getSocksUser());
            row.put("ssPort", shadowUser == null ? "-" : shadowUser.getSsPort());
            row.put("ip", summary.getRemoteIp());
            row.put("geo", formatGeo(summary.getRemoteIp()));
            row.put("latestTime", formatDateTime(summary.getLatestTime()));
            row.put("activeDuration", formatDurationSeconds(summary.getActiveSeconds()));
            row.put("avgReadSpeed", formatSpeed(summary.getReadBytes(), summary.getActiveSeconds()));
            row.put("avgWriteSpeed", formatSpeed(summary.getWriteBytes(), summary.getActiveSeconds()));
            row.put("sessionCount", summary.getSessionCount());
            row.put("totalReadBytes", Bytes.readableByteSize(summary.getReadBytes()));
            row.put("totalWriteBytes", Bytes.readableByteSize(summary.getWriteBytes()));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildLiveLoginIpRows(Map<String, ShadowUser> shadowStore) {
        List<ShadowUser> users = new ArrayList<ShadowUser>();
        if (shadowStore != null && !shadowStore.isEmpty()) {
            users.addAll(shadowStore.values());
            Collections.sort(users, new Comparator<ShadowUser>() {
                @Override
                public int compare(ShadowUser o1, ShadowUser o2) {
                    return compareUsernames(o1 == null ? null : o1.getUsername(), o2 == null ? null : o2.getUsername());
                }
            });
        }

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        long now = System.currentTimeMillis();
        for (ShadowUser user : users) {
            List<Map.Entry<InetAddress, TrafficLoginInfo>> entries = new ArrayList<Map.Entry<InetAddress, TrafficLoginInfo> >(user.getLoginIps().entrySet());
            Collections.sort(entries, LOGIN_IP_COMPARATOR);
            for (Map.Entry<InetAddress, TrafficLoginInfo> entry : entries) {
                TrafficLoginInfo info = entry.getValue();
                long activeSeconds = liveActiveSeconds(info, now);
                long readBytes = info == null ? 0L : info.getTotalReadBytes().get();
                long writeBytes = info == null ? 0L : info.getTotalWriteBytes().get();
                LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("username", user.getUsername());
                row.put("protocol", "LIVE");
                row.put("socksUser", Strings.isEmpty(user.getSocksUser()) ? "-" : user.getSocksUser());
                row.put("ssPort", user.getSsPort());
                row.put("ip", entry.getKey() == null ? "" : entry.getKey().getHostAddress());
                row.put("geo", formatGeo(entry.getKey() == null ? null : entry.getKey().getHostAddress()));
                row.put("latestTime", formatDateTime(info == null ? null : info.getLatestTime()));
                row.put("currentConnections", info == null ? 0 : info.getRefCnt().get());
                row.put("activeDuration", formatDurationSeconds(activeSeconds));
                row.put("avgReadSpeed", formatSpeed(readBytes, activeSeconds));
                row.put("avgWriteSpeed", formatSpeed(writeBytes, activeSeconds));
                row.put("totalReadBytes", Bytes.readableByteSize(readBytes));
                row.put("totalWriteBytes", Bytes.readableByteSize(writeBytes));
                rows.add(row);
            }
        }
        return rows;
    }

    static long liveActiveSeconds(TrafficLoginInfo info, long nowMillis) {
        if (info == null) {
            return 0L;
        }
        long activeSinceMillis = info.getActiveSinceMillis().get();
        if (info.getRefCnt().get() > 0 && activeSinceMillis > 0L) {
            return Math.max(0L, (nowMillis - activeSinceMillis) / 1000L);
        }
        return info.getTotalActiveSeconds().get();
    }

    private static String formatGeo(String ip) {
        if (Strings.isEmpty(ip)) {
            return "-";
        }
        IpGeolocation geo = safeResolveGeo(ip);
        if (geo == null) {
            return "-";
        }
        String country = Strings.isEmpty(geo.getCountry()) ? "" : geo.getCountry();
        String code = Strings.isEmpty(geo.getCountryCode()) ? "" : geo.getCountryCode();
        String city = Strings.isEmpty(geo.getCity()) ? "" : geo.getCity();
        String category = Strings.isEmpty(geo.getCategory()) ? "" : geo.getCategory();
        if (!country.isEmpty()) {
            StringBuilder text = new StringBuilder(country);
            if (!city.isEmpty() && !country.equalsIgnoreCase(city)) {
                text.append(" / ").append(city);
            }
            if (!code.isEmpty() && !country.equalsIgnoreCase(code)) {
                text.append(" (").append(code).append(')');
            }
            return text.toString();
        }
        if ("private".equalsIgnoreCase(category)) {
            return "内网";
        }
        if ("unknown".equalsIgnoreCase(category)) {
            return "未知";
        }
        if ("notReady".equalsIgnoreCase(category)) {
            return "GeoIP加载中";
        }
        return category.isEmpty() ? "-" : category;
    }

    private static IpGeolocation safeResolveGeo(String ip) {
        try {
            return geoLookup == null ? null : geoLookup.resolve(ip);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int effectiveMemoryRetentionHours(int configuredHours) {
        RssClientConf conf = RssClient.rssConf;
        return conf != null ? Math.max(1, conf.memoryRetentionHours) : Math.max(1, configuredHours);
    }

    private Query parseQuery(ServerRequest request) {
        long now = System.currentTimeMillis();
        Query query = new Query();
        query.toMillis = parseQueryTime(request.getQueryString().getFirst("to"), now);
        query.fromMillis = parseQueryTime(request.getQueryString().getFirst("from"), query.toMillis - DEFAULT_QUERY_RANGE_MILLIS);
        if (query.fromMillis > query.toMillis) {
            long swap = query.fromMillis;
            query.fromMillis = query.toMillis;
            query.toMillis = swap;
        }
        query.fromValue = formatQueryTime(query.fromMillis);
        query.toValue = formatQueryTime(query.toMillis);
        return query;
    }

    private long parseQueryTime(String value, long defaultMillis) {
        if (Strings.isEmpty(value)) {
            return defaultMillis;
        }
        try {
            return DateTime.valueOf(value, QUERY_TIME_FORMAT).getTime();
        } catch (Throwable ignored) {
            return defaultMillis;
        }
    }

    private static int compareUsernames(String left, String right) {
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        return l.compareToIgnoreCase(r);
    }

    private static int compareProtocol(String left, String right) {
        return normalizeProtocol(left).compareTo(normalizeProtocol(right));
    }

    private static String normalizeProtocol(String protocol) {
        if (Strings.isEmpty(protocol)) {
            return SocksUserTraffic.PROTOCOL_TCP;
        }
        return protocol.toLowerCase(Locale.ENGLISH);
    }

    private static long latestTimeMillis(TrafficLoginInfo info) {
        return latestTimeMillis(info == null ? null : info.getLatestTime());
    }

    private static long latestTimeMillis(Date value) {
        return value == null ? Long.MIN_VALUE : value.getTime();
    }

    private static String formatDateTime(Date value) {
        return value == null ? "-" : DateTime.of(value).toDateTimeString();
    }

    private static String formatDurationSeconds(long seconds) {
        if (seconds <= 0L) {
            return "0 sec";
        }
        if (seconds < 60L) {
            return seconds + " sec";
        }
        double minutes = seconds / 60D;
        if (minutes < 60D) {
            return String.format(Locale.ENGLISH, "%.2f min", minutes);
        }
        double hours = minutes / 60D;
        if (hours < 24D) {
            return String.format(Locale.ENGLISH, "%.2f hour", hours);
        }
        return String.format(Locale.ENGLISH, "%.2f day", hours / 24D);
    }

    private static String formatDurationMillis(long millis) {
        if (millis <= 0L) {
            return "0 sec";
        }
        double seconds = millis / 1000D;
        if (seconds < 60D) {
            return String.format(Locale.ENGLISH, "%.1f sec", seconds);
        }
        return String.format(Locale.ENGLISH, "%.2f min", seconds / 60D);
    }

    private static String formatSpeed(long bytes, long activeSeconds) {
        if (bytes <= 0L || activeSeconds <= 0L) {
            return "-";
        }
        double bytesPerSecond = bytes / (double) activeSeconds;
        long rounded = Math.max(1L, Math.round(bytesPerSecond));
        return Bytes.readableByteSize(rounded) + "/s";
    }

    private static String formatQueryTime(long millis) {
        return new SimpleDateFormat(QUERY_TIME_FORMAT).format(new Date(millis));
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
        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put("title", title);
        vars.put("body", body);
        return HttpServer.renderHtmlTemplate("rx-diagnostic.html", vars);
    }
}
