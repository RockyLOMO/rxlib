package org.rx.util.rss;

import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.net.support.GeoSiteMatcher;
import org.rx.net.support.V2RayGeoIpMatcher;
import org.rx.net.support.V2RayGeoManager;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class UserRuleMatcher {
    static final long GEO_MATCHER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final List<String> DEFAULT_ROUTE_RULES = Collections.unmodifiableList(Arrays.asList(
            "geosite:cn direct",
            "geoip:cn direct",
            "default proxy"));
    private static final CompiledRule[] EMPTY_RULES = new CompiledRule[0];

    private final CompiledRule[] rules;
    private final boolean requiresSourceIp;

    private UserRuleMatcher(CompiledRule[] rules) {
        this.rules = rules == null ? EMPTY_RULES : rules;
        boolean sourceIp = false;
        for (CompiledRule rule : this.rules) {
            if (rule.requiresSourceIp()) {
                sourceIp = true;
                break;
            }
        }
        this.requiresSourceIp = sourceIp;
    }

    public static UserRuleMatcher compile(UserRule rule, V2RayGeoManager manager, String username) {
        if (rule == null || Boolean.FALSE.equals(rule.getEnabled()) || CollectionUtils.isEmpty(rule.getRules())) {
            return null;
        }
        return compileRules(rule.getRules(), manager, username);
    }

    public static UserRuleMatcher compileDefaultRouteRules(List<String> rules, V2RayGeoManager manager) {
        return compileRules(CollectionUtils.isEmpty(rules) ? DEFAULT_ROUTE_RULES : rules, manager, "defaultRouteRules");
    }

    static List<String> defaultRouteRules() {
        return DEFAULT_ROUTE_RULES;
    }

    private static UserRuleMatcher compileRules(List<String> lines, V2RayGeoManager manager, String name) {
        V2RayGeoManager geoManager = manager == null ? V2RayGeoManager.INSTANCE : manager;
        ArrayList<CompiledRule> compiled = new ArrayList<CompiledRule>(lines.size() + 1);
        boolean hasDefaultRule = false;
        for (String line : lines) {
            CompiledRule ordered = compileOrderedRule(line, geoManager);
            if (ordered != null) {
                compiled.add(ordered);
                hasDefaultRule |= ordered instanceof DefaultRule;
            }
        }
        if (!hasDefaultRule) {
            compiled.add(new DefaultRule(RouteAction.PROXY));
        }
        return new UserRuleMatcher(compiled.toArray(new CompiledRule[compiled.size()]));
    }

    public boolean isEnabled() {
        return true;
    }

    public RouteAction match(String host) {
        return match(host, null);
    }

    public RouteAction match(String host, byte[] ipBytes) {
        return match(host, ipBytes, -1, null);
    }

    public RouteAction match(String host, int dstPort, InetSocketAddress srcEp) {
        return match(host, null, dstPort, srcEp);
    }

    public RouteAction match(String host, byte[] ipBytes, int dstPort, InetSocketAddress srcEp) {
        byte[] address = ipBytes;
        boolean ipLiteral = false;
        boolean hasHost = !Strings.isBlank(host);
        if (hasHost && mayBeIpLiteral(host)) {
            byte[] parsed = NetUtil.createByteArrayFromIpAddressString(host);
            if (parsed != null) {
                ipLiteral = true;
                if (address == null) {
                    address = parsed;
                }
            }
        }
        boolean domainTarget = !ipLiteral && hasHost;
        byte[] sourceAddress = null;
        int srcPort = -1;
        if (srcEp != null) {
            srcPort = srcEp.getPort();
            if (requiresSourceIp) {
                InetAddress srcAddress = srcEp.getAddress();
                if (srcAddress != null) {
                    sourceAddress = srcAddress.getAddress();
                }
            }
        }

        for (CompiledRule rule : rules) {
            if (rule.matches(host, domainTarget, address, dstPort, sourceAddress, srcPort)) {
                return rule.action;
            }
        }
        return RouteAction.PROXY;
    }

    static boolean mayBeIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        boolean hasColon = false;
        boolean hasDot = false;
        boolean hasHexLetter = false;
        for (int i = 0, len = host.length(); i < len; i++) {
            char c = host.charAt(i);
            if (c == ':') {
                hasColon = true;
                continue;
            }
            if (c == '.') {
                hasDot = true;
                continue;
            }
            if (c >= '0' && c <= '9') {
                continue;
            }
            if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                hasHexLetter = true;
                continue;
            }
            return false;
        }
        return hasColon || (hasDot && !hasHexLetter);
    }

    private static CompiledRule compileOrderedRule(String line, V2RayGeoManager manager) {
        String value = Strings.trimToNull(line);
        if (value == null) {
            return null;
        }
        int split = lastWhitespace(value);
        if (split <= 0 || split == value.length() - 1) {
            throw new InvalidException("rss route rule invalid '{}'", line);
        }
        String target = Strings.trimToNull(value.substring(0, split));
        String actionText = Strings.trimToNull(value.substring(split + 1));
        RouteAction action = parseAction(actionText);
        if (target == null || action == null) {
            throw new InvalidException("rss route rule invalid '{}'", line);
        }
        return compileTargetRule(action, target, manager);
    }

    private static CompiledRule compileTargetRule(RouteAction action, String target, V2RayGeoManager manager) {
        String value = Strings.trimToNull(target);
        if (value == null) {
            throw new InvalidException("rss route rule target is empty");
        }
        CompiledRule endpointRule = compileEndpointRule(action, value);
        if (endpointRule != null) {
            return endpointRule;
        }
        if (startsWithIgnoreCase(value, "geoip:")) {
            return new GeoIpCodeRule(action, manager, value);
        }
        if (Strings.equalsIgnoreCase(value, "default")) {
            return new DefaultRule(action);
        }
        if (startsWithIgnoreCase(value, "geosite:")) {
            return new GeoSiteCodeRule(action, manager, value);
        }
        if (startsWithIgnoreCase(value, "ip:")) {
            return compileIpRule(action, value.substring(3), true);
        }
        if (startsWithIgnoreCase(value, "cidr:")) {
            return compileCidrRule(action, value.substring(5), true);
        }
        if (value.indexOf('/') > 0) {
            CompiledRule cidr = compileCidrRule(action, value, false);
            if (cidr != null) {
                return cidr;
            }
        }
        CompiledRule ip = compileIpRule(action, value, false);
        if (ip != null) {
            return ip;
        }
        if (startsWithIgnoreCase(value, "domain:")) {
            value = value.substring(7);
        }
        return new DomainMatcherRule(action, new GeoSiteMatcher(java.util.Collections.singleton(value).iterator()));
    }

    private static CompiledRule compileEndpointRule(RouteAction action, String target) {
        EndpointTarget targetValue = parseEndpointTarget(target);
        if (targetValue == null) {
            return null;
        }
        if (targetValue.ipRule) {
            IpBytesRule ipRule = compileIpBytesRule(action, targetValue.value, true);
            return new EndpointIpRule(action, targetValue.source, ipRule);
        }
        return compilePortRule(action, targetValue.source, targetValue.value);
    }

    private static CompiledRule compileIpRule(RouteAction action, String target, boolean strict) {
        return compileIpBytesRule(action, target, strict);
    }

    private static CompiledRule compileCidrRule(RouteAction action, String target, boolean strict) {
        return compileCidrBytesRule(action, target, strict);
    }

    private static IpBytesRule compileCidrBytesRule(RouteAction action, String target, boolean strict) {
        String value = Strings.trimToNull(target);
        int slash = value == null ? -1 : value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            if (strict) {
                throw new InvalidException("rss route rule cidr invalid '{}'", target);
            }
            return null;
        }
        byte[] network = NetUtil.createByteArrayFromIpAddressString(value.substring(0, slash));
        if (network == null) {
            if (strict) {
                throw new InvalidException("rss route rule cidr ip invalid '{}'", target);
            }
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException e) {
            if (strict) {
                throw new InvalidException("rss route rule cidr prefix invalid '{}'", target);
            }
            return null;
        }
        if (prefix < 0 || prefix > network.length * 8) {
            if (strict) {
                throw new InvalidException("rss route rule cidr prefix invalid '{}'", target);
            }
            return null;
        }
        return new CidrRule(action, network, prefix);
    }

    private static IpBytesRule compileIpBytesRule(RouteAction action, String target, boolean strict) {
        String value = Strings.trimToNull(target);
        if (value != null && startsWithIgnoreCase(value, "ip:")) {
            value = Strings.trimToNull(value.substring(3));
            strict = true;
        } else if (value != null && startsWithIgnoreCase(value, "cidr:")) {
            return compileCidrBytesRule(action, value.substring(5), true);
        }
        if (value != null && value.indexOf('/') > 0) {
            IpBytesRule cidr = compileCidrBytesRule(action, value, false);
            if (cidr != null) {
                return cidr;
            }
        }
        byte[] ip = value == null ? null : NetUtil.createByteArrayFromIpAddressString(value);
        if (ip != null) {
            return new ExactIpRule(action, ip);
        }
        if (strict) {
            throw new InvalidException("rss route rule ip invalid '{}'", target);
        }
        return null;
    }

    private static CompiledRule compilePortRule(RouteAction action, boolean source, String target) {
        String value = Strings.trimToNull(target);
        if (value == null) {
            throw new InvalidException("rss route rule port is empty");
        }
        int dash = value.indexOf('-');
        int min;
        int max;
        if (dash > 0 && dash < value.length() - 1) {
            min = parsePort(Strings.trimToNull(value.substring(0, dash)), target);
            max = parsePort(Strings.trimToNull(value.substring(dash + 1)), target);
        } else if (dash < 0) {
            min = parsePort(value, target);
            max = min;
        } else {
            throw new InvalidException("rss route rule port invalid '{}'", target);
        }
        if (min > max) {
            throw new InvalidException("rss route rule port range invalid '{}'", target);
        }
        return new PortRule(action, source, min, max);
    }

    private static int parsePort(String value, String raw) {
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new NumberFormatException(value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new InvalidException("rss route rule port invalid '{}'", raw);
        }
    }

    private static EndpointTarget parseEndpointTarget(String target) {
        String value = Strings.trimToNull(target);
        if (value == null) {
            return null;
        }
        int split = firstWhitespace(value);
        String key;
        String body;
        if (split > 0) {
            key = value.substring(0, split);
            body = Strings.trimToNull(value.substring(split + 1));
        } else {
            int colon = value.indexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                return null;
            }
            key = value.substring(0, colon);
            body = Strings.trimToNull(value.substring(colon + 1));
        }
        if (body == null) {
            throw new InvalidException("rss route rule target is empty '{}'", target);
        }
        if (Strings.equalsIgnoreCase(key, "srcIp")) {
            return new EndpointTarget(true, true, body);
        }
        if (Strings.equalsIgnoreCase(key, "dstIp")) {
            return new EndpointTarget(false, true, body);
        }
        if (Strings.equalsIgnoreCase(key, "srcPort")) {
            return new EndpointTarget(true, false, body);
        }
        if (Strings.equalsIgnoreCase(key, "dstPort")) {
            return new EndpointTarget(false, false, body);
        }
        return null;
    }

    private static RouteAction parseAction(String actionText) {
        if (Strings.equalsIgnoreCase(actionText, "block")) {
            return RouteAction.BLOCK;
        }
        if (Strings.equalsIgnoreCase(actionText, "direct")) {
            return RouteAction.DIRECT;
        }
        if (Strings.equalsIgnoreCase(actionText, "proxy")) {
            return RouteAction.PROXY;
        }
        return null;
    }

    private static int lastWhitespace(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (value.charAt(i) <= ' ') {
                return i;
            }
        }
        return -1;
    }

    private static int firstWhitespace(String value) {
        for (int i = 0, len = value.length(); i < len; i++) {
            if (value.charAt(i) <= ' ') {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private abstract static class CompiledRule {
        final RouteAction action;

        CompiledRule(RouteAction action) {
            this.action = action;
        }

        boolean requiresSourceIp() {
            return false;
        }

        abstract boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                                 int dstPort, byte[] srcIpBytes, int srcPort);
    }

    private static final class DomainMatcherRule extends CompiledRule {
        final GeoSiteMatcher matcher;

        DomainMatcherRule(RouteAction action, GeoSiteMatcher matcher) {
            super(action);
            this.matcher = matcher;
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            return domainTarget && matcher.matches(host);
        }
    }

    private static final class DefaultRule extends CompiledRule {
        DefaultRule(RouteAction action) {
            super(action);
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            return true;
        }
    }

    private static final class GeoSiteCodeRule extends CompiledRule {
        final V2RayGeoManager manager;
        final String code;
        volatile GeoSiteMatcher matcher;
        volatile long nextTryNanos;

        GeoSiteCodeRule(RouteAction action, V2RayGeoManager manager, String code) {
            super(action);
            this.manager = manager;
            this.code = code;
            this.matcher = manager.tryCompileGeoSiteMatcher(code);
            if (matcher == null) {
                nextTryNanos = System.nanoTime() + GEO_MATCHER_RETRY_NANOS;
                requestReload(manager);
            }
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            if (!domainTarget) {
                return false;
            }
            GeoSiteMatcher m = matcher;
            if (m == null) {
                long now = System.nanoTime();
                if (now < nextTryNanos) {
                    return false;
                }
                nextTryNanos = now + GEO_MATCHER_RETRY_NANOS;
                matcher = m = manager.tryCompileGeoSiteMatcher(code);
                if (m == null) {
                    requestReload(manager);
                }
            }
            return m != null && m.matches(host);
        }
    }

    private static final class GeoIpCodeRule extends CompiledRule {
        final V2RayGeoManager manager;
        final String code;
        volatile V2RayGeoIpMatcher.CodeMatcher matcher;
        volatile long nextTryNanos;

        GeoIpCodeRule(RouteAction action, V2RayGeoManager manager, String code) {
            super(action);
            this.manager = manager;
            this.code = code;
            this.matcher = manager.tryCompileGeoIpMatcher(code);
            if (matcher == null) {
                nextTryNanos = System.nanoTime() + GEO_MATCHER_RETRY_NANOS;
                requestReload(manager);
            }
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            if (dstIpBytes == null) {
                return false;
            }
            V2RayGeoIpMatcher.CodeMatcher m = matcher;
            if (m == null) {
                long now = System.nanoTime();
                if (now < nextTryNanos) {
                    return false;
                }
                nextTryNanos = now + GEO_MATCHER_RETRY_NANOS;
                matcher = m = manager.tryCompileGeoIpMatcher(code);
                if (m == null) {
                    requestReload(manager);
                }
            }
            return m != null && m.matches(dstIpBytes);
        }
    }

    private abstract static class IpBytesRule extends CompiledRule {
        IpBytesRule(RouteAction action) {
            super(action);
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            return matchesIp(dstIpBytes);
        }

        abstract boolean matchesIp(byte[] ipBytes);
    }

    private static final class ExactIpRule extends IpBytesRule {
        final byte[] ip;

        ExactIpRule(RouteAction action, byte[] ip) {
            super(action);
            this.ip = ip;
        }

        @Override
        boolean matchesIp(byte[] ipBytes) {
            if (ipBytes == null || ipBytes.length != ip.length) {
                return false;
            }
            for (int i = 0; i < ip.length; i++) {
                if (ipBytes[i] != ip[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class CidrRule extends IpBytesRule {
        final byte[] network;
        final int prefix;

        CidrRule(RouteAction action, byte[] network, int prefix) {
            super(action);
            this.network = network;
            this.prefix = prefix;
        }

        @Override
        boolean matchesIp(byte[] ipBytes) {
            if (ipBytes == null || ipBytes.length != network.length) {
                return false;
            }
            int fullBytes = prefix >>> 3;
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != network[i]) {
                    return false;
                }
            }
            int bits = prefix & 7;
            if (bits == 0) {
                return true;
            }
            int mask = 0xff << (8 - bits);
            return (ipBytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private static final class EndpointIpRule extends CompiledRule {
        final boolean source;
        final IpBytesRule rule;

        EndpointIpRule(RouteAction action, boolean source, IpBytesRule rule) {
            super(action);
            this.source = source;
            this.rule = rule;
        }

        @Override
        boolean requiresSourceIp() {
            return source;
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            return rule.matchesIp(source ? srcIpBytes : dstIpBytes);
        }
    }

    private static final class PortRule extends CompiledRule {
        final boolean source;
        final int min;
        final int max;

        PortRule(RouteAction action, boolean source, int min, int max) {
            super(action);
            this.source = source;
            this.min = min;
            this.max = max;
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] dstIpBytes,
                        int dstPort, byte[] srcIpBytes, int srcPort) {
            int port = source ? srcPort : dstPort;
            return port >= min && port <= max;
        }
    }

    private static final class EndpointTarget {
        final boolean source;
        final boolean ipRule;
        final String value;

        EndpointTarget(boolean source, boolean ipRule, String value) {
            this.source = source;
            this.ipRule = ipRule;
            this.value = value;
        }
    }

    private static void requestReload(V2RayGeoManager manager) {
        try {
            manager.reloadAsync();
        } catch (RuntimeException e) {
            log.warn("rss route rule geo reload request failed", e);
        }
    }
}
