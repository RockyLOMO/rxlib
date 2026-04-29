package org.rx.net.nameserver;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.net.Sockets;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.rx.net.transport.hybrid.HybridSession;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.rx.core.Extends.ifNull;

public class NameserverHttpHandler implements HttpServer.Handler {
    public static final String PAGE_PATH = "/rns";
    private static final String PAGE_TITLE = "RXlib Nameserver";
    private static final String USERNAME = "rxlib";
    private static final String REALM = "rxlib-diagnostic";
    private static final Comparator<Map<String, Object>> ROW_COMPARATOR = new Comparator<Map<String, Object>>() {
        @Override
        public int compare(Map<String, Object> o1, Map<String, Object> o2) {
            int c = value(o1, "appName").compareToIgnoreCase(value(o2, "appName"));
            if (c != 0) {
                return c;
            }
            return value(o1, "address").compareToIgnoreCase(value(o2, "address"));
        }
    };

    final NameserverImpl nameserver;

    public NameserverHttpHandler(NameserverImpl nameserver) {
        this.nameserver = nameserver;
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
            response.htmlBody(page("Nameserver console disabled", "<p><code>app.rtoken</code> is empty.</p>"));
            return;
        }
        if (!authorize(request)) {
            response.setStatus(HttpResponseStatus.UNAUTHORIZED);
            response.getHeaders().set(HttpHeaderNames.WWW_AUTHENTICATE,
                    "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
            response.htmlBody(page("Authorization required", "<p>Use Basic Auth: <code>rxlib</code> / <code>app.rtoken</code>.</p>"));
            return;
        }
        response.htmlBody(render(nameserver));
    }

    static String render(NameserverImpl nameserver) {
        List<Map<String, Object>> instanceRows = buildInstanceRows(nameserver);
        List<Map<String, Object>> appRows = buildAppRows(instanceRows);
        List<Map<String, Object>> replicaRows = buildReplicaRows(nameserver);

        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put("path", PAGE_PATH);
        vars.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
        vars.put("dnsPort", nameserver.config.getDnsPort());
        vars.put("dnsTtl", nameserver.config.getDnsTtl());
        vars.put("registerPort", nameserver.config.getRegisterPort());
        vars.put("syncPort", nameserver.getSyncPort());
        vars.put("sessionCount", nameserver.rs.sessions().size());
        vars.put("appCount", appRows.size());
        vars.put("instanceCount", instanceRows.size());
        vars.put("replicaCount", replicaRows.size());
        vars.put("hasApps", !appRows.isEmpty());
        vars.put("apps", appRows);
        vars.put("hasInstances", !instanceRows.isEmpty());
        vars.put("instances", instanceRows);
        vars.put("hasReplicas", !replicaRows.isEmpty());
        vars.put("replicas", replicaRows);

        Map<String, Object> pageVars = new LinkedHashMap<String, Object>();
        pageVars.put("title", PAGE_TITLE);
        pageVars.put("body", HttpServer.renderHtmlTemplate("rx-nameserver.html", vars));
        return HttpServer.renderHtmlTemplate("rx-diagnostic.html", pageVars);
    }

    static List<Map<String, Object>> buildInstanceRows(NameserverImpl nameserver) {
        Map<Long, HybridSession> sessions = nameserver.rs.sessions();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(sessions.size());
        for (HybridSession session : sessions.values()) {
            String appName = ifNull(session.<String>attr(Nameserver.APP_NAME_KEY), "NOT_REG");
            InetSocketAddress remoteEndpoint = session.tcpRemoteEndpoint();
            InetAddress address = remoteEndpoint == null ? null : remoteEndpoint.getAddress();
            Map<String, Serializable> attrs = address == null ? null : nameserver.attrs.get(address);

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("appName", appName);
            row.put("address", address == null ? "-" : address.getHostAddress());
            row.put("tcpRemote", Sockets.toString(remoteEndpoint));
            row.put("connected", session.isConnected());
            row.put("appId", attr(attrs, RxConfig.ConfigNames.APP_ID));
            row.put("publicIp", attr(attrs, Nameserver.PUBLIC_IP_KEY));
            row.put("ping", heartbeat(session.heartbeatRttMillis()));
            row.put("attrs", formatAttrs(attrs));
            rows.add(row);
        }
        Collections.sort(rows, ROW_COMPARATOR);
        return rows;
    }

    static List<Map<String, Object>> buildAppRows(List<Map<String, Object>> instanceRows) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> row : instanceRows) {
            String appName = value(row, "appName");
            Integer count = counts.get(appName);
            counts.put(appName, count == null ? 1 : count + 1);
        }

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("appName", entry.getKey());
            row.put("instanceCount", entry.getValue());
            rows.add(row);
        }
        Collections.sort(rows, ROW_COMPARATOR);
        return rows;
    }

    static List<Map<String, Object>> buildReplicaRows(NameserverImpl nameserver) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(nameserver.svrEps.size());
        for (InetSocketAddress endpoint : nameserver.svrEps) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("endpoint", Sockets.toString(endpoint));
            rows.add(row);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> o1, Map<String, Object> o2) {
                return value(o1, "endpoint").compareToIgnoreCase(value(o2, "endpoint"));
            }
        });
        return rows;
    }

    private static String attr(Map<String, Serializable> attrs, String key) {
        if (attrs == null) {
            return "-";
        }
        Object value = attrs.get(key);
        return value == null ? "-" : String.valueOf(value);
    }

    private static String formatAttrs(Map<String, Serializable> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder(64);
        for (Map.Entry<String, Serializable> entry : attrs.entrySet()) {
            if (out.length() != 0) {
                out.append(", ");
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static String heartbeat(long rttMillis) {
        return rttMillis < 0 ? "-" : Long.toString(rttMillis) + "ms";
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

    private static String value(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
