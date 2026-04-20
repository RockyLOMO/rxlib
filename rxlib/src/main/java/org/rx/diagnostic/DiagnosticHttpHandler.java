package org.rx.diagnostic;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rx.core.RxConfig;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.core.Strings;
import org.rx.io.EntityDatabase;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiagnosticHttpHandler implements HttpServer.Handler {
    private static final String USERNAME = "rxlib";
    private static final String REALM = "rxlib-diagnostic";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_CHART_SERIES = 8;
    private static final Pattern SUMMARY_BYTES_PATTERN = Pattern.compile("([A-Za-z0-9_.-]*(?:BytesPerSecond|bytesPerSecond|Bytes|bytes))(=)(\\d+)");

    private final DiagnosticConfig config;

    public DiagnosticHttpHandler() {
        this(null);
    }

    public DiagnosticHttpHandler(DiagnosticConfig config) {
        this.config = config;
        if (this.config != null) {
            this.config.normalize();
        }
    }

    @Override
    public HttpMethod[] method() {
        return new HttpMethod[]{HttpMethod.GET};
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) throws Throwable {
        response.getHeaders().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        if (Strings.isEmpty(RxConfig.INSTANCE.getRtoken())) {
            response.setStatus(HttpResponseStatus.SERVICE_UNAVAILABLE);
            response.htmlBody(page("Diagnostic console disabled", "<p><code>app.rtoken</code> is empty.</p>"));
            return;
        }
        if (!authorize(request)) {
            response.setStatus(HttpResponseStatus.UNAUTHORIZED);
            response.getHeaders().set(HttpHeaderNames.WWW_AUTHENTICATE,
                    "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
            response.htmlBody(page("Authorization required", "<p>Use Basic Auth: <code>rxlib</code> / <code>app.rtoken</code>.</p>"));
            return;
        }

        int limit = limit(request);
        String stackHash = request.getQueryString().getFirst("stack");
        response.htmlBody(render(parseQuery(request, limit), stackHash));
    }

    private String render(Query filter, String stackHash) {
        DiagnosticConfig config = currentConfig();
        if (config.isFileH2Storage() && DiagnosticFileSupport.h2StorageBytes(config.getH2File()) <= 0L) {
            return page("RXlib Diagnostics", "<section class=\"card\"><h2>No H2 data</h2><p>Diagnostic H2 file was not found.</p></section>");
        }

        EntityDatabase db = H2DiagnosticStore.createDatabase(config);
        try {
            StringBuilder body = new StringBuilder(16384);
            appendHeader(body, config, filter);
            appendStack(body, db, stackHash);
            appendIncidents(body, query(db, "SELECT incident_id,type,level,start_ts,end_ts,summary,bundle_path FROM diag_incident ORDER BY start_ts DESC LIMIT ?", filter.limit));
            appendThreadCpu(body, query(db, "SELECT ts,thread_id,thread_name,cpu_nanos_delta,state,stack_hash,incident_id FROM diag_thread_cpu_sample ORDER BY ts DESC,cpu_nanos_delta DESC LIMIT ?", filter.limit));
            appendFileIo(body, query(db, "SELECT ts,path_sample,op,bytes,elapsed_nanos,stack_hash,incident_id FROM diag_file_io_sample ORDER BY ts DESC,id DESC LIMIT ?", filter.limit));
            appendFileSize(body, query(db, "SELECT ts,path_sample,size_bytes,last_modified,incident_id FROM diag_file_size_sample ORDER BY size_bytes DESC,ts DESC LIMIT ?", filter.limit));
            appendMetrics(body, queryMetrics(db, filter), filter);
            return page("RXlib Diagnostics", body.toString());
        } catch (Throwable e) {
            return page("RXlib Diagnostics", "<section class=\"card warn\"><h2>Read failed</h2><pre>"
                    + escape(e.getMessage()) + "</pre></section>");
        } finally {
            try {
                db.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void appendHeader(StringBuilder out, DiagnosticConfig config, Query filter) {
        out.append("<section class=\"hero\"><div><h1>RXlib Diagnostics</h1><p>H2 monitor snapshot, latest ")
                .append(filter.limit).append(" rows per section.</p></div><div class=\"pill\">")
                .append(escape(config.isFileH2Storage() ? config.getH2File().getPath() : "memory H2"))
                .append("</div></section>");
    }

    private void appendStack(StringBuilder out, EntityDatabase db, String stackHash) {
        if (Strings.isEmpty(stackHash)) {
            return;
        }
        Long hash = parseLong(stackHash);
        if (hash == null) {
            return;
        }
        List<Map<String, Object>> rows = queryOne(db,
                "SELECT stack_hash,stack_text,first_seen,last_seen FROM diag_stack_trace WHERE stack_hash=?",
                hash);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        out.append("<section class=\"card\"><h2>Stack Trace</h2><div class=\"meta\">hash ")
                .append(escape(value(row, "stack_hash"))).append(" · first ")
                .append(formatMillis(row.get("first_seen"))).append(" · last ")
                .append(formatMillis(row.get("last_seen"))).append("</div><pre>")
                .append(escape(value(row, "stack_text"))).append("</pre></section>");
    }

    private void appendIncidents(StringBuilder out, List<Map<String, Object>> rows) {
        out.append("<section class=\"card\"><h2>Incidents</h2><div class=\"grid\">");
        if (rows.isEmpty()) {
            out.append("<p class=\"empty\">No incident.</p>");
        }
        for (Map<String, Object> row : rows) {
            out.append("<article class=\"item\"><strong>").append(escape(value(row, "type"))).append("</strong><span>")
                    .append(escape(value(row, "level"))).append("</span><p class=\"summary\">")
                    .append(formatSummary(value(row, "summary"))).append("</p><div class=\"meta\">")
                    .append(formatMillis(row.get("start_ts"))).append(" · ")
                    .append(escape(value(row, "incident_id"))).append("</div><div class=\"path\">")
                    .append(escape(value(row, "bundle_path"))).append("</div></article>");
        }
        out.append("</div></section>");
    }

    private void appendThreadCpu(StringBuilder out, List<Map<String, Object>> rows) {
        out.append("<section class=\"card\"><h2>Thread CPU</h2><table><thead><tr><th>Time</th><th>Thread</th><th>CPU</th><th>State</th><th>Stack</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 5, "No CPU evidence yet. It is collected after CPU_HIGH is triggered.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "thread_name"))).append("</td><td>")
                    .append(formatNanos(row.get("cpu_nanos_delta"))).append("</td><td>")
                    .append(escape(value(row, "state"))).append("</td><td>")
                    .append(stackLink(row.get("stack_hash"))).append("</td></tr>");
        }
        out.append("</tbody></table></section>");
    }

    private void appendFileIo(StringBuilder out, List<Map<String, Object>> rows) {
        out.append("<section class=\"card\"><h2>File I/O</h2><table><thead><tr><th>Time</th><th>Path</th><th>Op</th><th>Bytes</th><th>Elapsed</th><th>Stack</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 6, "No file I/O sample yet. Add DiagnosticFileIo record calls around file reads/writes.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "path_sample"))).append("</td><td>")
                    .append(escape(value(row, "op"))).append("</td><td>")
                    .append(formatBytes(row.get("bytes"))).append("</td><td>")
                    .append(formatNanos(row.get("elapsed_nanos"))).append("</td><td>")
                    .append(stackLink(row.get("stack_hash"))).append("</td></tr>");
        }
        out.append("</tbody></table></section>");
    }

    private void appendFileSize(StringBuilder out, List<Map<String, Object>> rows) {
        out.append("<section class=\"card\"><h2>File Size</h2><table><thead><tr><th>Time</th><th>Path</th><th>Size</th><th>Modified</th><th>Incident</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 5, "No file size sample yet. It is collected after DISK_SPACE_HIGH scans configured roots.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "path_sample"))).append("</td><td>")
                    .append(formatBytes(row.get("size_bytes"))).append("</td><td>")
                    .append(formatMillis(row.get("last_modified"))).append("</td><td>")
                    .append(escape(value(row, "incident_id"))).append("</td></tr>");
        }
        out.append("</tbody></table></section>");
    }

    private void appendMetrics(StringBuilder out, List<Map<String, Object>> rows, Query filter) {
        out.append("<section class=\"card\" id=\"metrics\"><h2>Metrics</h2>");
        appendMetricFilter(out, filter);
        appendMetricCharts(out, rows);
        out.append("<table><thead><tr><th>Time</th><th>Metric</th><th>Value</th><th>Tags</th><th>Incident</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 5, "No metric found in the selected range.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "metric"))).append("</td><td>")
                    .append(escape(formatMetricValue(row.get("metric"), row.get("metric_value")))).append("</td><td>")
                    .append(escape(value(row, "tags"))).append("</td><td>")
                    .append(escape(value(row, "incident_id"))).append("</td></tr>");
        }
        out.append("</tbody></table></section>");
    }

    private void appendMetricFilter(StringBuilder out, Query filter) {
        out.append("<form class=\"filters\" method=\"get\" action=\"#metrics\">")
                .append("<label>From<input type=\"datetime-local\" name=\"from\" value=\"")
                .append(escape(formatInputMillis(filter.fromMillis))).append("\"></label>")
                .append("<label>To<input type=\"datetime-local\" name=\"to\" value=\"")
                .append(escape(formatInputMillis(filter.toMillis))).append("\"></label>")
                .append("<label>Metric<input type=\"text\" name=\"metric\" value=\"")
                .append(escape(filter.metric)).append("\" placeholder=\"disk.used.bytes\"></label>")
                .append("<label>Limit<input type=\"number\" min=\"1\" max=\"")
                .append(MAX_LIMIT).append("\" name=\"limit\" value=\"").append(filter.limit).append("\"></label>")
                .append("<button type=\"submit\">Search</button>")
                .append("<a class=\"button\" href=\"?limit=").append(DEFAULT_LIMIT).append("#metrics\">Reset</a>")
                .append("</form>");
    }

    private void appendMetricCharts(StringBuilder out, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> series = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (toLong(row.get("ts")) == null || toDouble(row.get("metric_value")) == null) {
                continue;
            }
            String key = value(row, "metric") + "\n" + value(row, "tags");
            List<Map<String, Object>> list = series.get(key);
            if (list == null) {
                if (series.size() >= MAX_CHART_SERIES) {
                    continue;
                }
                list = new ArrayList<>();
                series.put(key, list);
            }
            list.add(row);
        }
        if (series.isEmpty()) {
            return;
        }
        out.append("<div class=\"charts\">");
        for (Map.Entry<String, List<Map<String, Object>>> entry : series.entrySet()) {
            appendMetricChart(out, entry.getValue());
        }
        out.append("</div>");
    }

    private void appendMetricChart(StringBuilder out, List<Map<String, Object>> rows) {
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> o1, Map<String, Object> o2) {
                return Long.compare(toLong(o1.get("ts")), toLong(o2.get("ts")));
            }
        });
        String metric = value(rows.get(0), "metric");
        String tags = value(rows.get(0), "tags");
        long minTs = Long.MAX_VALUE;
        long maxTs = Long.MIN_VALUE;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Map<String, Object> row : rows) {
            long ts = toLong(row.get("ts"));
            double value = toDouble(row.get("metric_value"));
            minTs = Math.min(minTs, ts);
            maxTs = Math.max(maxTs, ts);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        final int width = 640;
        final int height = 160;
        final int pad = 14;
        StringBuilder points = new StringBuilder(rows.size() * 12);
        for (Map<String, Object> row : rows) {
            long ts = toLong(row.get("ts"));
            double value = toDouble(row.get("metric_value"));
            double x = maxTs == minTs ? width / 2D : pad + (double) (ts - minTs) * (width - pad * 2D) / (double) (maxTs - minTs);
            double y = max == min ? height / 2D : height - pad - (value - min) * (height - pad * 2D) / (max - min);
            if (points.length() != 0) {
                points.append(' ');
            }
            points.append(String.format(Locale.ENGLISH, "%.1f,%.1f", x, y));
        }

        out.append("<article class=\"chart\"><div class=\"chart-title\">")
                .append(escape(metric)).append("</div>");
        if (!Strings.isEmpty(tags)) {
            out.append("<div class=\"meta\">").append(escape(tags)).append("</div>");
        }
        out.append("<svg class=\"metric-chart\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" preserveAspectRatio=\"none\"><line class=\"axis\" x1=\"")
                .append(pad).append("\" y1=\"").append(height - pad).append("\" x2=\"")
                .append(width - pad).append("\" y2=\"").append(height - pad)
                .append("\"/><polyline class=\"series\" points=\"").append(points).append("\"/></svg>")
                .append("<div class=\"meta\">")
                .append(formatMillis(Long.valueOf(minTs))).append(" - ").append(formatMillis(Long.valueOf(maxTs)))
                .append(" · min ").append(escape(formatMetricValue(metric, Double.valueOf(min))))
                .append(" · max ").append(escape(formatMetricValue(metric, Double.valueOf(max))))
                .append("</div></article>");
    }

    private void appendEmptyRow(StringBuilder out, int columns, String message) {
        out.append("<tr><td class=\"empty\" colspan=\"").append(columns).append("\">")
                .append(escape(message)).append("</td></tr>");
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

    private List<Map<String, Object>> queryMetrics(EntityDatabase db, Query filter) {
        StringBuilder sql = new StringBuilder("SELECT ts,metric,metric_value,tags,incident_id FROM diag_metric_sample WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (filter.fromMillis != null) {
            sql.append(" AND ts >= ?");
            args.add(filter.fromMillis);
        }
        if (filter.toMillis != null) {
            sql.append(" AND ts <= ?");
            args.add(filter.toMillis);
        }
        if (!Strings.isEmpty(filter.metric)) {
            sql.append(" AND metric = ?");
            args.add(filter.metric);
        }
        sql.append(" ORDER BY ts DESC,id DESC LIMIT ?");
        args.add(Integer.valueOf(filter.limit));
        return query(db, sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> query(EntityDatabase db, String sql, Object... args) {
        return db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                bind(stmt, args);
                return readRows(stmt.executeQuery());
            }
        });
    }

    private List<Map<String, Object>> queryOne(EntityDatabase db, String sql, long arg) {
        return db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, arg);
                return readRows(stmt.executeQuery());
            }
        });
    }

    private void bind(PreparedStatement stmt, Object[] args) throws Exception {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            int index = i + 1;
            if (arg instanceof Integer) {
                stmt.setInt(index, ((Integer) arg).intValue());
            } else if (arg instanceof Long) {
                stmt.setLong(index, ((Long) arg).longValue());
            } else if (arg instanceof Double) {
                stmt.setDouble(index, ((Double) arg).doubleValue());
            } else {
                stmt.setObject(index, arg);
            }
        }
    }

    private List<Map<String, Object>> readRows(ResultSet rs) throws Exception {
        try (ResultSet rows = rs) {
            ResultSetMetaData meta = rows.getMetaData();
            int len = meta.getColumnCount();
            List<Map<String, Object>> result = new ArrayList<>();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= len; i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(Locale.ENGLISH), readValue(rows.getObject(i)));
                }
                result.add(row);
            }
            return result;
        }
    }

    private Object readValue(Object value) throws Exception {
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            long len = clob.length();
            if (len <= 0L) {
                return "";
            }
            return clob.getSubString(1L, (int) Math.min(len, Integer.MAX_VALUE));
        }
        return value;
    }

    private int limit(ServerRequest request) {
        String value = request.getQueryString().getFirst("limit");
        Integer parsed = parseInt(value);
        if (parsed == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, parsed));
    }

    private Query parseQuery(ServerRequest request, int limit) {
        Query query = new Query();
        query.limit = limit;
        query.fromMillis = parseTimeMillis(request.getQueryString().getFirst("from"));
        query.toMillis = parseTimeMillis(request.getQueryString().getFirst("to"));
        query.metric = Strings.trim(request.getQueryString().getFirst("metric"));
        return query;
    }

    private String stackLink(Object value) {
        Long hash = toLong(value);
        if (hash == null || hash == 0L) {
            return "";
        }
        return "<a href=\"?stack=" + hash + "\">" + hash + "</a>";
    }

    private String page(String title, String body) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", escape(title));
        vars.put("body", body);
        return HttpServer.renderHtmlTemplate("rx-diagnostic.html", vars);
    }

    private DiagnosticConfig currentConfig() {
        DiagnosticConfig config = this.config == null ? RxConfig.INSTANCE.getDiagnostic() : this.config;
        config.normalize();
        return config;
    }

    private static String formatMillis(Object value) {
        Long millis = toLong(value);
        if (millis == null || millis <= 0L) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(millis));
    }

    private static String formatInputMillis(Object value) {
        Long millis = toLong(value);
        if (millis == null || millis <= 0L) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(new Date(millis));
    }

    private static String formatBytes(Object value) {
        Long bytes = toLong(value);
        if (bytes == null) {
            return "";
        }
        double n = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (n >= 1024D && unit < units.length - 1) {
            n /= 1024D;
            unit++;
        }
        return String.format(Locale.ENGLISH, "%.2f %s", n, units[unit]);
    }

    private static String formatMetricValue(Object metricValue, Object value) {
        Double number = toDouble(value);
        if (number == null) {
            return "";
        }
        String metric = metricValue == null ? "" : String.valueOf(metricValue).toLowerCase(Locale.ENGLISH);
        if (metric.endsWith(".bytes") || metric.contains(".bytes.")) {
            String bytes = formatBytes(Long.valueOf(number.longValue()));
            return metric.endsWith("per.second") ? bytes + "/s" : bytes;
        }
        if (metric.endsWith(".percent")) {
            return String.format(Locale.ENGLISH, "%.2f %%", number);
        }
        if (metric.endsWith(".count")) {
            return String.format(Locale.ENGLISH, "%.0f", number);
        }
        if (Math.rint(number) == number) {
            return String.format(Locale.ENGLISH, "%.0f", number);
        }
        return String.format(Locale.ENGLISH, "%.3f", number);
    }

    private static String formatSummary(String summary) {
        if (summary == null || summary.length() == 0) {
            return "";
        }
        Matcher matcher = SUMMARY_BYTES_PATTERN.matcher(summary);
        StringBuffer formatted = new StringBuffer(summary.length() + 32);
        while (matcher.find()) {
            String key = matcher.group(1);
            String raw = matcher.group(3);
            Long bytes = parseLong(raw);
            if (bytes == null) {
                continue;
            }
            String unit = formatBytes(bytes);
            if (key.endsWith("PerSecond") || key.endsWith("perSecond")) {
                unit += "/s";
            }
            matcher.appendReplacement(formatted, Matcher.quoteReplacement(key + "=" + raw + " (" + unit + ")"));
        }
        matcher.appendTail(formatted);
        return escape(formatted.toString()).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
    }

    private static String formatNanos(Object value) {
        Long nanos = toLong(value);
        if (nanos == null) {
            return "";
        }
        return String.format(Locale.ENGLISH, "%.3f ms", nanos / 1000000D);
    }

    private static String value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseTimeMillis(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        String text = value.trim();
        Long millis = parseLong(text);
        if (millis != null) {
            return millis;
        }
        text = text.replace('T', ' ');
        String[] patterns = {"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try {
                return new SimpleDateFormat(pattern).parse(text).getTime();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLong(value == null ? null : String.valueOf(value));
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String escape(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static final class Query {
        int limit;
        Long fromMillis;
        Long toMillis;
        String metric;
    }
}
