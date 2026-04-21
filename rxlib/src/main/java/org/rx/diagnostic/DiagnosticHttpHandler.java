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
    private static final int MAX_OVERVIEW_CHART_SERIES = 16;
    private static final int MAX_CHART_POINTS_PER_SERIES = 240;
    private static final String[] CPU_CHART_METRICS = {
            "process.cpu.percent", "system.cpu.percent"
    };
    private static final String[] DISK_CHART_METRICS = {
            "disk.used.percent", "disk.free.percent"
    };
    private static final String[] MEMORY_CHART_METRICS = {
            "system.physical.used.percent", "jvm.app.memory.used.percent", "jvm.heap.used.percent",
            "jvm.direct.used.percent", "jvm.direct.capacity.percent"
    };
    private static final String[] NET_CHART_METRICS = {
            "net.io.inbound.bytes.per.second", "net.io.outbound.bytes.per.second"
    };
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
            appendTabsStart(body);
            appendTabPanelStart(body, "overview", true);
            appendOverview(body, queryNamedMetricChartRows(db, filter, CPU_CHART_METRICS),
                    queryNamedMetricChartRows(db, filter, MEMORY_CHART_METRICS),
                    queryNamedMetricChartRows(db, filter, DISK_CHART_METRICS),
                    queryNamedMetricChartRows(db, filter, NET_CHART_METRICS), filter);
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "incidents", false);
            appendIncidents(body, query(db, "SELECT incident_id,type,level,start_ts,end_ts,summary,bundle_path FROM diag_incident ORDER BY start_ts DESC LIMIT ?", filter.limit));
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "metrics", false);
            appendMetrics(body, queryMetrics(db, filter), queryMetricChartRows(db, filter), queryMetricTop(db, filter), filter);
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "thread-cpu", false);
            appendThreadCpu(body, query(db, "SELECT ts,thread_id,thread_name,cpu_nanos_delta,state,stack_hash,incident_id FROM diag_thread_cpu_sample ORDER BY ts DESC,cpu_nanos_delta DESC LIMIT ?", filter.limit));
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "thread-state", false);
            appendThreadState(body, queryOptional(db, "SELECT ts,thread_id,thread_name,state,blocked_millis,waited_millis,state_duration_millis,lock_name,lock_owner_id,lock_owner_name,stack_hash,incident_id FROM diag_thread_state_sample ORDER BY ts DESC,state_duration_millis DESC LIMIT ?", filter.limit));
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "file-io", false);
            appendFileIo(body, query(db, "SELECT ts,path_sample,op,bytes,elapsed_nanos,stack_hash,incident_id FROM diag_file_io_sample ORDER BY ts DESC,id DESC LIMIT ?", filter.limit));
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "net-io", false);
            appendNetIo(body, queryNetIoGroups(db, filter),
                    queryOptional(db, "SELECT ts,endpoint_sample,op,bytes,stack_hash,incident_id FROM diag_net_io_sample ORDER BY ts DESC,id DESC LIMIT ?", filter.limit));
            appendTabPanelEnd(body);
            appendTabPanelStart(body, "file-size", false);
            appendFileSize(body, query(db, "SELECT ts,path_sample,size_bytes,last_modified,incident_id FROM diag_file_size_sample ORDER BY size_bytes DESC,ts DESC LIMIT ?", filter.limit));
            appendTabPanelEnd(body);
            appendTabsEnd(body);
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

    private void appendTabsStart(StringBuilder out) {
        out.append("<nav class=\"tabs\">")
                .append("<a class=\"tab-link active\" href=\"#overview\" data-tab=\"overview\">Overview</a>")
                .append("<a class=\"tab-link\" href=\"#incidents\" data-tab=\"incidents\">Incidents</a>")
                .append("<a class=\"tab-link\" href=\"#metrics\" data-tab=\"metrics\">Metrics</a>")
                .append("<a class=\"tab-link\" href=\"#thread-cpu\" data-tab=\"thread-cpu\">Thread CPU</a>")
                .append("<a class=\"tab-link\" href=\"#thread-state\" data-tab=\"thread-state\">Thread State</a>")
                .append("<a class=\"tab-link\" href=\"#file-io\" data-tab=\"file-io\">File I/O</a>")
                .append("<a class=\"tab-link\" href=\"#net-io\" data-tab=\"net-io\">Net I/O</a>")
                .append("<a class=\"tab-link\" href=\"#file-size\" data-tab=\"file-size\">File Size</a>")
                .append("</nav><div class=\"tab-panels\">");
    }

    private void appendTabPanelStart(StringBuilder out, String id, boolean active) {
        out.append("<div class=\"tab-panel");
        if (active) {
            out.append(" active");
        }
        out.append("\" id=\"").append(id).append("\">");
    }

    private void appendTabPanelEnd(StringBuilder out) {
        out.append("</div>");
    }

    private void appendTabsEnd(StringBuilder out) {
        out.append("</div>");
    }

    private void appendOverview(StringBuilder out, List<Map<String, Object>> cpuRows,
                                List<Map<String, Object>> memoryRows,
                                List<Map<String, Object>> diskRows,
                                List<Map<String, Object>> netRows, Query filter) {
        out.append("<section class=\"card\"><h2>Overview</h2>");
        appendOverviewFilter(out, filter);
        appendChartGroup(out, "CPU Charts", cpuRows, "No CPU metric found in the selected range.");
        appendChartGroup(out, "Memory Charts", memoryRows, "No memory metric found in the selected range.");
        appendChartGroup(out, "Disk Charts", diskRows, "No disk metric found in the selected range.");
        appendChartGroup(out, "Net Charts", netRows, "No net bytes/sec metric found in the selected range.");
        out.append("</section>");
    }

    private void appendOverviewFilter(StringBuilder out, Query filter) {
        out.append("<form class=\"filters\" method=\"get\" action=\"#overview\">")
                .append("<label>From<input type=\"datetime-local\" name=\"from\" value=\"")
                .append(escape(formatInputMillis(filter.fromMillis))).append("\"></label>")
                .append("<label>To<input type=\"datetime-local\" name=\"to\" value=\"")
                .append(escape(formatInputMillis(filter.toMillis))).append("\"></label>")
                .append("<label>Limit<input type=\"number\" min=\"1\" max=\"")
                .append(MAX_LIMIT).append("\" name=\"limit\" value=\"").append(filter.limit).append("\"></label>")
                .append("<button type=\"submit\">Search</button>")
                .append("<a class=\"button\" href=\"?limit=").append(DEFAULT_LIMIT).append("#overview\">Reset</a>")
                .append("</form>");
    }

    private void appendChartGroup(StringBuilder out, String title, List<Map<String, Object>> rows, String emptyText) {
        out.append("<h3 class=\"chart-group-title\">").append(escape(title)).append("</h3>");
        if (rows.isEmpty()) {
            out.append("<p class=\"empty\">").append(escape(emptyText)).append("</p>");
            return;
        }
        appendMetricCharts(out, rows);
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

    private void appendThreadState(StringBuilder out, List<Map<String, Object>> rows) {
        out.append("<section class=\"card\"><h2>Thread State</h2><table><thead><tr><th>Time</th><th>Thread</th><th>State</th><th>Duration</th><th>Blocked</th><th>Waited</th><th>Lock</th><th>Owner</th><th>Stack</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 9, "No thread state evidence yet. It is collected after BLOCKED/WAITING/DEADLOCK incident is triggered.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "thread_name"))).append("<div class=\"meta\">id ")
                    .append(escape(value(row, "thread_id"))).append("</div></td><td>")
                    .append(escape(value(row, "state"))).append("</td><td>")
                    .append(formatMillisDuration(row.get("state_duration_millis"))).append("</td><td>")
                    .append(formatMillisDuration(row.get("blocked_millis"))).append("</td><td>")
                    .append(formatMillisDuration(row.get("waited_millis"))).append("</td><td>")
                    .append(escape(value(row, "lock_name"))).append("</td><td>")
                    .append(escape(value(row, "lock_owner_name"))).append("<div class=\"meta\">")
                    .append(escape(value(row, "lock_owner_id"))).append("</div></td><td>")
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

    private void appendNetIo(StringBuilder out, List<Map<String, Object>> groupRows, List<Map<String, Object>> rows) {
        out.append("<section class=\"card\"><h2>Net I/O</h2>");
        out.append("<h3 class=\"chart-group-title\">By Endpoint</h3><table><thead><tr><th>Endpoint</th><th>Op</th><th>Min</th><th>Avg</th><th>Max</th><th>Total</th><th>Samples</th><th>Last</th></tr></thead><tbody>");
        if (groupRows.isEmpty()) {
            appendEmptyRow(out, 8, "No net I/O endpoint group data in the selected range.");
        }
        for (Map<String, Object> row : groupRows) {
            out.append("<tr><td>").append(escape(value(row, "endpoint_sample"))).append("</td><td>")
                    .append(escape(value(row, "op"))).append("</td><td>")
                    .append(formatBytes(row.get("min_bytes"))).append("</td><td>")
                    .append(formatBytes(row.get("avg_bytes"))).append("</td><td>")
                    .append(formatBytes(row.get("max_bytes"))).append("</td><td>")
                    .append(formatBytes(row.get("total_bytes"))).append("</td><td>")
                    .append(escape(value(row, "sample_count"))).append("</td><td>")
                    .append(formatMillis(row.get("last_ts"))).append("</td></tr>");
        }
        out.append("</tbody></table>");

        out.append("<h3 class=\"chart-group-title\">Recent Samples</h3><table><thead><tr><th>Time</th><th>Endpoint</th><th>Op</th><th>Bytes</th><th>Stack</th><th>Incident</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 6, "No net I/O sample yet. Add DiagnosticNetIoHandler to Netty pipelines or call DiagnosticNetIo directly.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "endpoint_sample"))).append("</td><td>")
                    .append(escape(value(row, "op"))).append("</td><td>")
                    .append(formatBytes(row.get("bytes"))).append("</td><td>")
                    .append(stackLink(row.get("stack_hash"))).append("</td><td>")
                    .append(escape(value(row, "incident_id"))).append("</td></tr>");
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

    private void appendMetrics(StringBuilder out, List<Map<String, Object>> rows,
                               List<Map<String, Object>> chartRows, List<Map<String, Object>> topRows, Query filter) {
        out.append("<section class=\"card\"><h2>Metrics</h2>");
        appendMetricFilter(out, filter);
        appendMetricCharts(out, chartRows);
        appendMetricTop(out, topRows);
        out.append("<table><thead><tr><th>Time</th><th>Metric</th><th>Value</th><th>Tags</th><th>Incident</th><th>Stack</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            appendEmptyRow(out, 6, "No metric found in the selected range.");
        }
        for (Map<String, Object> row : rows) {
            out.append("<tr><td>").append(formatMillis(row.get("ts"))).append("</td><td>")
                    .append(escape(value(row, "metric"))).append("</td><td>")
                    .append(escape(formatMetricValue(row.get("metric"), row.get("metric_value")))).append("</td><td>")
                    .append(escape(value(row, "tags"))).append("</td><td>")
                    .append(escape(value(row, "incident_id"))).append("</td><td>")
                    .append(stackLink(row.get("stack_hash"))).append("</td></tr>");
        }
        out.append("</tbody></table></section>");
    }

    private void appendMetricTop(StringBuilder out, List<Map<String, Object>> rows) {
        out.append("<h3 class=\"chart-group-title\">Top N</h3>");
        if (rows.isEmpty()) {
            out.append("<table><tbody>");
            appendEmptyRow(out, 7, "No metric top data in the selected range.");
            out.append("</tbody></table>");
            return;
        }
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String group = metricGroup(value(row, "metric"));
            List<Map<String, Object>> groupRows = groups.get(group);
            if (groupRows == null) {
                groupRows = new ArrayList<>();
                groups.put(group, groupRows);
            }
            groupRows.add(row);
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            out.append("<h4 class=\"metric-group-title\">").append(escape(entry.getKey())).append("</h4>")
                    .append("<table><thead><tr><th>Metric</th><th>Min</th><th>Avg</th><th>Max</th><th>Samples</th><th>Tags</th></tr></thead><tbody>");
            for (Map<String, Object> row : entry.getValue()) {
                Object metric = row.get("metric");
                out.append("<tr><td>").append(escape(value(row, "metric"))).append("</td><td>")
                        .append(escape(formatMetricValue(metric, row.get("min_value")))).append("</td><td>")
                        .append(escape(formatMetricValue(metric, row.get("avg_value")))).append("</td><td>")
                        .append(escape(formatMetricValue(metric, row.get("max_value")))).append("</td><td>")
                        .append(escape(value(row, "sample_count"))).append("</td><td>")
                        .append(escape(value(row, "tags"))).append("</td></tr>");
            }
            out.append("</tbody></table>");
        }
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
        Map<String, Map<String, List<Map<String, Object>>>> groups = new LinkedHashMap<>();
        int seriesCount = 0;
        for (Map<String, Object> row : rows) {
            if (toLong(row.get("ts")) == null || toDouble(row.get("metric_value")) == null) {
                continue;
            }
            String group = metricGroup(value(row, "metric"));
            Map<String, List<Map<String, Object>>> series = groups.get(group);
            if (series == null) {
                series = new LinkedHashMap<>();
                groups.put(group, series);
            }
            String key = value(row, "metric") + "\n" + value(row, "tags");
            List<Map<String, Object>> list = series.get(key);
            if (list == null) {
                if (seriesCount >= MAX_CHART_SERIES) {
                    continue;
                }
                list = new ArrayList<>();
                series.put(key, list);
                seriesCount++;
            }
            list.add(row);
        }
        if (groups.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<String, List<Map<String, Object>>>> groupEntry : groups.entrySet()) {
            out.append("<h4 class=\"metric-group-title\">").append(escape(groupEntry.getKey())).append("</h4>")
                    .append("<div class=\"charts\">");
            for (Map.Entry<String, List<Map<String, Object>>> entry : groupEntry.getValue().entrySet()) {
                appendMetricChart(out, entry.getValue());
            }
            out.append("</div>");
        }
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
        double sum = 0D;
        for (Map<String, Object> row : rows) {
            long ts = toLong(row.get("ts"));
            double value = toDouble(row.get("metric_value"));
            minTs = Math.min(minTs, ts);
            maxTs = Math.max(maxTs, ts);
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
        }
        double avg = rows.isEmpty() ? 0D : sum / rows.size();

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
                .append("\" preserveAspectRatio=\"none\">");
        appendChartYAxis(out, metric, min, max, width, height, pad);
        out.append("<line class=\"axis\" x1=\"")
                .append(pad).append("\" y1=\"").append(height - pad).append("\" x2=\"")
                .append(width - pad).append("\" y2=\"").append(height - pad)
                .append("\"/><polyline class=\"series\" points=\"").append(points).append("\"/>");
        appendChartPoints(out, rows, minTs, maxTs, min, max, width, height, pad);
        out.append("</svg>")
                .append("<div class=\"meta\">")
                .append(formatMillis(Long.valueOf(minTs))).append(" - ").append(formatMillis(Long.valueOf(maxTs)))
                .append(" · samples ").append(rows.size())
                .append(" · min ").append(escape(formatMetricValue(metric, Double.valueOf(min))))
                .append(" · avg ").append(escape(formatMetricValue(metric, Double.valueOf(avg))))
                .append(" · max ").append(escape(formatMetricValue(metric, Double.valueOf(max))))
                .append("</div></article>");
    }

    private void appendChartYAxis(StringBuilder out, String metric, double min, double max, int width, int height, int pad) {
        double mid = min + (max - min) / 2D;
        appendChartYAxisLine(out, metric, max, pad, width, pad);
        appendChartYAxisLine(out, metric, mid, height / 2D, width, pad);
        appendChartYAxisLine(out, metric, min, height - pad, width, pad);
    }

    private void appendChartYAxisLine(StringBuilder out, String metric, double value, double y, int width, int pad) {
        out.append("<line class=\"grid-line\" x1=\"").append(pad).append("\" y1=\"")
                .append(String.format(Locale.ENGLISH, "%.1f", y)).append("\" x2=\"")
                .append(width - pad).append("\" y2=\"")
                .append(String.format(Locale.ENGLISH, "%.1f", y)).append("\"/>")
                .append("<text class=\"y-label\" x=\"2\" y=\"")
                .append(String.format(Locale.ENGLISH, "%.1f", Math.max(10D, y - 3D))).append("\">")
                .append(escape(formatMetricValue(metric, Double.valueOf(value)))).append("</text>");
    }

    private void appendChartPoints(StringBuilder out, List<Map<String, Object>> rows, long minTs, long maxTs,
                                   double min, double max, int width, int height, int pad) {
        for (Map<String, Object> row : rows) {
            long ts = toLong(row.get("ts"));
            double value = toDouble(row.get("metric_value"));
            double x = maxTs == minTs ? width / 2D : pad + (double) (ts - minTs) * (width - pad * 2D) / (double) (maxTs - minTs);
            double y = max == min ? height / 2D : height - pad - (value - min) * (height - pad * 2D) / (max - min);
            out.append("<circle class=\"point\" cx=\"")
                    .append(String.format(Locale.ENGLISH, "%.1f", x))
                    .append("\" cy=\"")
                    .append(String.format(Locale.ENGLISH, "%.1f", y))
                    .append("\" r=\"3\"/>");
        }
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
        StringBuilder sql = new StringBuilder("SELECT ts,metric,metric_value,tags,incident_id,stack_hash FROM diag_metric_sample WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendMetricWhere(sql, args, filter);
        if (!Strings.isEmpty(filter.metric)) {
            sql.append(" AND metric = ?");
            args.add(filter.metric);
        }
        sql.append(" ORDER BY ts DESC,id DESC LIMIT ?");
        args.add(Integer.valueOf(filter.limit));
        return query(db, sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> queryMetricTop(EntityDatabase db, Query filter) {
        StringBuilder sql = new StringBuilder("SELECT metric,COALESCE(tags,'') tags,MIN(metric_value) min_value,MAX(metric_value) max_value,"
                + "AVG(metric_value) avg_value,COUNT(*) sample_count"
                + " FROM diag_metric_sample WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendMetricWhere(sql, args, filter);
        if (!Strings.isEmpty(filter.metric)) {
            sql.append(" AND metric = ?");
            args.add(filter.metric);
        }
        sql.append(" GROUP BY metric,COALESCE(tags,'') ORDER BY metric ASC,max_value DESC,sample_count DESC LIMIT ?");
        args.add(Integer.valueOf(filter.limit));
        return query(db, sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> queryNetIoGroups(EntityDatabase db, Query filter) {
        StringBuilder sql = new StringBuilder("SELECT endpoint_sample,op,MIN(bytes) min_bytes,AVG(bytes) avg_bytes,"
                + "MAX(bytes) max_bytes,SUM(bytes) total_bytes,COUNT(*) sample_count,MAX(ts) last_ts"
                + " FROM diag_net_io_sample WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendMetricWhere(sql, args, filter);
        sql.append(" GROUP BY endpoint_sample,op ORDER BY total_bytes DESC,last_ts DESC LIMIT ?");
        args.add(Integer.valueOf(filter.limit));
        return queryOptional(db, sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> queryMetricChartRows(EntityDatabase db, Query filter) {
        List<Map<String, Object>> series = queryMetricSeries(db, filter);
        if (series.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<>(series.size() * MAX_CHART_POINTS_PER_SERIES);
        for (Map<String, Object> item : series) {
            String metric = value(item, "metric");
            String tags = value(item, "tags");
            StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT ts,metric,metric_value,COALESCE(tags,'') tags,incident_id,stack_hash"
                    + " FROM diag_metric_sample WHERE 1=1");
            List<Object> args = new ArrayList<>();
            appendMetricWhere(sql, args, filter);
            sql.append(" AND metric = ? AND COALESCE(tags,'') = ? ORDER BY ts DESC,id DESC LIMIT ?)"
                    + " ORDER BY ts ASC");
            args.add(metric);
            args.add(tags);
            args.add(Integer.valueOf(MAX_CHART_POINTS_PER_SERIES));
            rows.addAll(query(db, sql.toString(), args.toArray()));
        }
        return rows;
    }

    private List<Map<String, Object>> queryNamedMetricChartRows(EntityDatabase db, Query filter, String[] metrics) {
        List<Map<String, Object>> series = queryNamedMetricSeries(db, filter, metrics);
        if (series.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<>(series.size() * MAX_CHART_POINTS_PER_SERIES);
        for (Map<String, Object> item : series) {
            String metric = value(item, "metric");
            String tags = value(item, "tags");
            StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT ts,metric,metric_value,COALESCE(tags,'') tags,incident_id,stack_hash"
                    + " FROM diag_metric_sample WHERE 1=1");
            List<Object> args = new ArrayList<>();
            appendMetricWhere(sql, args, filter);
            sql.append(" AND metric = ? AND COALESCE(tags,'') = ? ORDER BY ts DESC,id DESC LIMIT ?)"
                    + " ORDER BY ts ASC");
            args.add(metric);
            args.add(tags);
            args.add(Integer.valueOf(MAX_CHART_POINTS_PER_SERIES));
            rows.addAll(query(db, sql.toString(), args.toArray()));
        }
        return rows;
    }

    private List<Map<String, Object>> queryMetricSeries(EntityDatabase db, Query filter) {
        StringBuilder sql = new StringBuilder("SELECT metric,COALESCE(tags,'') tags,MAX(ts) last_ts,COUNT(*) sample_count"
                + " FROM diag_metric_sample WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendMetricWhere(sql, args, filter);
        if (!Strings.isEmpty(filter.metric)) {
            sql.append(" AND metric = ?");
            args.add(filter.metric);
        }
        sql.append(" GROUP BY metric,COALESCE(tags,'') ORDER BY last_ts DESC,sample_count DESC LIMIT ?");
        args.add(Integer.valueOf(MAX_CHART_SERIES));
        return query(db, sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> queryNamedMetricSeries(EntityDatabase db, Query filter, String[] metrics) {
        StringBuilder sql = new StringBuilder("SELECT metric,COALESCE(tags,'') tags,MAX(ts) last_ts,COUNT(*) sample_count"
                + " FROM diag_metric_sample WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendMetricWhere(sql, args, filter);
        appendMetricNamesWhere(sql, args, metrics);
        sql.append(" GROUP BY metric,COALESCE(tags,'') ORDER BY metric,COALESCE(tags,'') LIMIT ?");
        args.add(Integer.valueOf(MAX_OVERVIEW_CHART_SERIES));
        return query(db, sql.toString(), args.toArray());
    }

    private void appendMetricWhere(StringBuilder sql, List<Object> args, Query filter) {
        if (filter.fromMillis != null) {
            sql.append(" AND ts >= ?");
            args.add(filter.fromMillis);
        }
        if (filter.toMillis != null) {
            sql.append(" AND ts <= ?");
            args.add(filter.toMillis);
        }
    }

    private void appendMetricNamesWhere(StringBuilder sql, List<Object> args, String[] metrics) {
        if (metrics == null || metrics.length == 0) {
            return;
        }
        sql.append(" AND metric IN (");
        for (int i = 0; i < metrics.length; i++) {
            if (i != 0) {
                sql.append(',');
            }
            sql.append('?');
            args.add(metrics[i]);
        }
        sql.append(')');
    }

    private List<Map<String, Object>> query(EntityDatabase db, String sql, Object... args) {
        return db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                bind(stmt, args);
                return readRows(stmt.executeQuery());
            }
        });
    }

    private List<Map<String, Object>> queryOptional(EntityDatabase db, String sql, Object... args) {
        try {
            return query(db, sql, args);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
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
        if (metric.endsWith(".nanos")) {
            return formatNanos(Long.valueOf(number.longValue()));
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
            if (bytes == null || hasReadableUnitAfter(summary, matcher.end())) {
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

    private static boolean hasReadableUnitAfter(String text, int index) {
        if (index < text.length() && text.charAt(index) == '.') {
            return true;
        }
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) {
            return false;
        }
        if (text.charAt(i) == '(') {
            return true;
        }
        String tail = text.substring(i).toLowerCase(Locale.ENGLISH);
        return isUnitPrefix(tail, "b")
                || tail.startsWith("bytes")
                || tail.startsWith("byte")
                || tail.startsWith("kb")
                || tail.startsWith("mb")
                || tail.startsWith("gb")
                || tail.startsWith("tb");
    }

    private static boolean isUnitPrefix(String tail, String unit) {
        if (!tail.startsWith(unit)) {
            return false;
        }
        return tail.length() == unit.length()
                || !Character.isLetterOrDigit(tail.charAt(unit.length()));
    }

    private static String formatNanos(Object value) {
        Long nanos = toLong(value);
        if (nanos == null) {
            return "";
        }
        return String.format(Locale.ENGLISH, "%.3f ms", nanos / 1000000D);
    }

    private static String formatMillisDuration(long millis) {
        return String.format(Locale.ENGLISH, "%d ms", millis);
    }

    private static String formatMillisDuration(Object value) {
        Long millis = toLong(value);
        if (millis == null) {
            return "";
        }
        return formatMillisDuration(millis.longValue());
    }

    private static String value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String metricGroup(String metric) {
        if (metric == null || metric.length() == 0) {
            return "other";
        }
        int index = metric.indexOf('.');
        return index <= 0 ? "other" : metric.substring(0, index) + ".*";
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
        if (value instanceof Date) {
            return Long.valueOf(((Date) value).getTime());
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
