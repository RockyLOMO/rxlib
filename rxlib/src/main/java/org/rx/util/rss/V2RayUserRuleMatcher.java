package org.rx.util.rss;

import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.net.support.GeoSiteMatcher;
import org.rx.net.support.V2RayGeoIpMatcher;
import org.rx.net.support.V2RayGeoManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class V2RayUserRuleMatcher {
    static final long GEO_MATCHER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final List<String> DEFAULT_ROUTE_RULES = Collections.unmodifiableList(Arrays.asList(
            "geosite:cn direct",
            "geoip:cn direct",
            "default proxy"));
    private static final CompiledRule[] EMPTY_RULES = new CompiledRule[0];

    private final CompiledRule[] rules;

    private V2RayUserRuleMatcher(CompiledRule[] rules) {
        this.rules = rules == null ? EMPTY_RULES : rules;
    }

    public static V2RayUserRuleMatcher compile(V2RayUserRule rule, V2RayGeoManager manager, String username) {
        if (rule == null || Boolean.FALSE.equals(rule.getEnabled()) || CollectionUtils.isEmpty(rule.getRules())) {
            return null;
        }
        return compileRules(rule.getRules(), manager, username);
    }

    public static V2RayUserRuleMatcher compileDefaultRouteRules(List<String> rules, V2RayGeoManager manager) {
        return compileRules(CollectionUtils.isEmpty(rules) ? DEFAULT_ROUTE_RULES : rules, manager, "defaultRouteRules");
    }

    static List<String> defaultRouteRules() {
        return DEFAULT_ROUTE_RULES;
    }

    private static V2RayUserRuleMatcher compileRules(List<String> lines, V2RayGeoManager manager, String name) {
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
            compiled.add(new DefaultRule(V2RayRouteAction.PROXY));
        }
        return new V2RayUserRuleMatcher(compiled.toArray(new CompiledRule[compiled.size()]));
    }

    public boolean isEnabled() {
        return true;
    }

    public V2RayRouteAction match(String host) {
        return match(host, null);
    }

    public V2RayRouteAction match(String host, byte[] ipBytes) {
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

        for (CompiledRule rule : rules) {
            if (rule.matches(host, domainTarget, address)) {
                return rule.action;
            }
        }
        return V2RayRouteAction.PROXY;
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
        V2RayRouteAction action = parseAction(actionText);
        if (target == null || action == null) {
            throw new InvalidException("rss route rule invalid '{}'", line);
        }
        return compileTargetRule(action, target, manager);
    }

    private static CompiledRule compileTargetRule(V2RayRouteAction action, String target, V2RayGeoManager manager) {
        String value = Strings.trimToNull(target);
        if (value == null) {
            throw new InvalidException("rss route rule target is empty");
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

    private static CompiledRule compileIpRule(V2RayRouteAction action, String target, boolean strict) {
        String value = Strings.trimToNull(target);
        byte[] ip = value == null ? null : NetUtil.createByteArrayFromIpAddressString(value);
        if (ip != null) {
            return new ExactIpRule(action, ip);
        }
        if (strict) {
            throw new InvalidException("rss route rule ip invalid '{}'", target);
        }
        return null;
    }

    private static CompiledRule compileCidrRule(V2RayRouteAction action, String target, boolean strict) {
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

    private static V2RayRouteAction parseAction(String actionText) {
        if (Strings.equalsIgnoreCase(actionText, "block")) {
            return V2RayRouteAction.BLOCK;
        }
        if (Strings.equalsIgnoreCase(actionText, "direct")) {
            return V2RayRouteAction.DIRECT;
        }
        if (Strings.equalsIgnoreCase(actionText, "proxy")) {
            return V2RayRouteAction.PROXY;
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

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private abstract static class CompiledRule {
        final V2RayRouteAction action;

        CompiledRule(V2RayRouteAction action) {
            this.action = action;
        }

        abstract boolean matches(String host, boolean domainTarget, byte[] ipBytes);
    }

    private static final class DomainMatcherRule extends CompiledRule {
        final GeoSiteMatcher matcher;

        DomainMatcherRule(V2RayRouteAction action, GeoSiteMatcher matcher) {
            super(action);
            this.matcher = matcher;
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] ipBytes) {
            return domainTarget && matcher.matches(host);
        }
    }

    private static final class DefaultRule extends CompiledRule {
        DefaultRule(V2RayRouteAction action) {
            super(action);
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] ipBytes) {
            return true;
        }
    }

    private static final class GeoSiteCodeRule extends CompiledRule {
        final V2RayGeoManager manager;
        final String code;
        volatile GeoSiteMatcher matcher;
        volatile long nextTryNanos;

        GeoSiteCodeRule(V2RayRouteAction action, V2RayGeoManager manager, String code) {
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
        boolean matches(String host, boolean domainTarget, byte[] ipBytes) {
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

        GeoIpCodeRule(V2RayRouteAction action, V2RayGeoManager manager, String code) {
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
        boolean matches(String host, boolean domainTarget, byte[] ipBytes) {
            if (ipBytes == null) {
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
            return m != null && m.matches(ipBytes);
        }
    }

    private static final class ExactIpRule extends CompiledRule {
        final byte[] ip;

        ExactIpRule(V2RayRouteAction action, byte[] ip) {
            super(action);
            this.ip = ip;
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] ipBytes) {
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

    private static final class CidrRule extends CompiledRule {
        final byte[] network;
        final int prefix;

        CidrRule(V2RayRouteAction action, byte[] network, int prefix) {
            super(action);
            this.network = network;
            this.prefix = prefix;
        }

        @Override
        boolean matches(String host, boolean domainTarget, byte[] ipBytes) {
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

    private static void requestReload(V2RayGeoManager manager) {
        try {
            manager.reloadAsync();
        } catch (RuntimeException e) {
            log.warn("rss route rule geo reload request failed", e);
        }
    }
}
