package org.rx.net.support;

import com.github.benmanes.caffeine.cache.Cache;
import org.rx.core.cache.MemoryCache;
import org.rx.exception.InvalidException;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class V2RayGeoSiteIndex implements Closeable {
    private static final String GEOSITE_PREFIX = "geosite:";
    private static final String EMPTY_FILTER = "";

    final Map<String, CodeRules> groups;
    final Cache<String, GeoSiteMatcher> matcherCache = MemoryCache.<String, GeoSiteMatcher>rootBuilder()
            .maximumSize(256)
            .build();

    V2RayGeoSiteIndex(V2RayGeoDataReader.GeoSiteListData data) {
        this.groups = buildGroups(data);
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public Set<String> codes() {
        return groups.keySet();
    }

    public boolean matches(String code, String domain) {
        return matches(code, null, domain);
    }

    public boolean matches(String code, String attrFilter, String domain) {
        GeoSiteMatcher matcher = matcher(code, attrFilter);
        return matcher != null && matcher.matches(domain);
    }

    public GeoSiteMatcher matcher(String code) {
        return matcher(code, null);
    }

    public GeoSiteMatcher matcher(String code, String attrFilter) {
        CodeSelector selector = CodeSelector.parse(code, attrFilter);
        if (selector.code == null) {
            return null;
        }
        CodeRules rules = groups.get(selector.code);
        if (rules == null) {
            return null;
        }
        String cacheKey = selector.code + '\0' + selector.attrSelector.cacheKey;
        return matcherCache.get(cacheKey, k -> buildMatcher(rules, selector.attrSelector));
    }

    @Override
    public void close() {
        matcherCache.invalidateAll();
    }

    private static Map<String, CodeRules> buildGroups(V2RayGeoDataReader.GeoSiteListData data) {
        LinkedHashMap<String, ArrayList<Rule>> temp = new LinkedHashMap<>();
        for (V2RayGeoDataReader.GeoSiteEntry entry : data.entries) {
            String code = normalizeCode(entry.code, entry.countryCode);
            if (code == null) {
                continue;
            }
            ArrayList<Rule> rules = temp.get(code);
            if (rules == null) {
                rules = new ArrayList<>();
                temp.put(code, rules);
            }
            for (V2RayGeoDataReader.GeoSiteDomain domain : entry.domains) {
                String value = GeoIPSearcher.trimAscii(domain.value);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                validateDomainType(domain.type);
                rules.add(new Rule(domain.type, value, normalizeAttributes(domain.attributes)));
            }
        }

        LinkedHashMap<String, CodeRules> groups = new LinkedHashMap<>(temp.size());
        for (Map.Entry<String, ArrayList<Rule>> e : temp.entrySet()) {
            ArrayList<Rule> rules = e.getValue();
            groups.put(e.getKey(), new CodeRules(rules.toArray(new Rule[rules.size()])));
        }
        return Collections.unmodifiableMap(groups);
    }

    private GeoSiteMatcher buildMatcher(CodeRules group, AttrSelector selector) {
        ArrayList<String> rules = new ArrayList<>(group.rules.length);
        for (Rule rule : group.rules) {
            if (!selector.matches(rule.attributes)) {
                continue;
            }
            switch (rule.type) {
                case V2RayGeoDataReader.DOMAIN_TYPE_PLAIN:
                    rules.add(GeoSiteMatcher.kwRule + rule.value);
                    break;
                case V2RayGeoDataReader.DOMAIN_TYPE_REGEX:
                    rules.add(GeoSiteMatcher.regexpRule + rule.value);
                    break;
                case V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN:
                    rules.add(rule.value);
                    break;
                case V2RayGeoDataReader.DOMAIN_TYPE_FULL:
                    rules.add(GeoSiteMatcher.fullRule + rule.value);
                    break;
                default:
                    throw new InvalidException("v2ray geosite domain type unsupported {}", rule.type);
            }
        }
        return new GeoSiteMatcher(rules.iterator());
    }

    private static String normalizeCode(String code, String fallback) {
        String value = GeoIPSearcher.trimAscii(code);
        if (value == null || value.isEmpty()) {
            value = GeoIPSearcher.trimAscii(fallback);
        }
        if (value == null || value.isEmpty()) {
            return null;
        }
        return GeoSiteMatcher.toLowerAscii(value);
    }

    private static void validateDomainType(int type) {
        if (type < V2RayGeoDataReader.DOMAIN_TYPE_PLAIN || type > V2RayGeoDataReader.DOMAIN_TYPE_FULL) {
            throw new InvalidException("v2ray geosite domain type unsupported {}", type);
        }
    }

    private static String[] normalizeAttributes(String[] attributes) {
        if (attributes == null || attributes.length == 0) {
            return new String[0];
        }
        ArrayList<String> values = new ArrayList<>(attributes.length);
        for (String attribute : attributes) {
            String value = normalizeAttr(attribute);
            if (value != null && !contains(values, value)) {
                values.add(value);
            }
        }
        return values.toArray(new String[values.size()]);
    }

    private static String normalizeAttr(String attribute) {
        String value = GeoIPSearcher.trimAscii(attribute);
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.charAt(0) == '@') {
            value = GeoIPSearcher.trimAscii(value.substring(1));
            if (value == null || value.isEmpty()) {
                return null;
            }
        }
        return GeoSiteMatcher.toLowerAscii(value);
    }

    private static boolean contains(ArrayList<String> values, String value) {
        for (String v : values) {
            if (v.equals(value)) {
                return true;
            }
        }
        return false;
    }

    static final class CodeRules {
        final Rule[] rules;

        CodeRules(Rule[] rules) {
            this.rules = rules;
        }
    }

    static final class Rule {
        final int type;
        final String value;
        final String[] attributes;

        Rule(int type, String value, String[] attributes) {
            this.type = type;
            this.value = value;
            this.attributes = attributes;
        }
    }

    private static final class CodeSelector {
        final String code;
        final AttrSelector attrSelector;

        CodeSelector(String code, AttrSelector attrSelector) {
            this.code = code;
            this.attrSelector = attrSelector;
        }

        static CodeSelector parse(String code, String attrFilter) {
            String value = GeoIPSearcher.trimAscii(code);
            if (value == null || value.isEmpty()) {
                return new CodeSelector(null, AttrSelector.ALL);
            }
            if (startsWithIgnoreCase(value, GEOSITE_PREFIX)) {
                value = GeoIPSearcher.trimAscii(value.substring(GEOSITE_PREFIX.length()));
            }
            String filter = GeoIPSearcher.trimAscii(attrFilter);
            int attrAt = value.indexOf('@');
            if (attrAt >= 0) {
                if (filter == null || filter.isEmpty()) {
                    filter = value.substring(attrAt + 1);
                }
                value = value.substring(0, attrAt);
            }
            return new CodeSelector(normalizeCode(value, null), AttrSelector.parse(filter));
        }

        private static boolean startsWithIgnoreCase(String value, String prefix) {
            return value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
        }
    }

    private static final class AttrSelector {
        static final AttrSelector ALL = new AttrSelector(EMPTY_FILTER, new String[0], new String[0]);

        final String cacheKey;
        final String[] includes;
        final String[] excludes;

        AttrSelector(String cacheKey, String[] includes, String[] excludes) {
            this.cacheKey = cacheKey;
            this.includes = includes;
            this.excludes = excludes;
        }

        static AttrSelector parse(String filter) {
            String value = GeoIPSearcher.trimAscii(filter);
            if (value == null || value.isEmpty()) {
                return ALL;
            }
            if (value.charAt(0) == '@') {
                value = GeoIPSearcher.trimAscii(value.substring(1));
                if (value == null || value.isEmpty()) {
                    return ALL;
                }
            }

            ArrayList<String> includes = new ArrayList<>(2);
            ArrayList<String> excludes = new ArrayList<>(2);
            int start = 0;
            for (int i = 0; i <= value.length(); i++) {
                if (i != value.length() && value.charAt(i) != ',') {
                    continue;
                }
                String token = GeoIPSearcher.trimAscii(value.substring(start, i));
                start = i + 1;
                if (token == null || token.isEmpty()) {
                    continue;
                }
                if (token.charAt(0) == '@') {
                    token = GeoIPSearcher.trimAscii(token.substring(1));
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                }
                boolean exclude = token.charAt(0) == '!';
                if (exclude) {
                    token = GeoIPSearcher.trimAscii(token.substring(1));
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                }
                String attr = normalizeAttr(token);
                if (attr == null) {
                    continue;
                }
                if (exclude) {
                    if (!contains(excludes, attr)) {
                        excludes.add(attr);
                    }
                } else if (!contains(includes, attr)) {
                    includes.add(attr);
                }
            }
            if (includes.isEmpty() && excludes.isEmpty()) {
                return ALL;
            }
            Collections.sort(includes);
            Collections.sort(excludes);
            String cacheKey = buildCacheKey(includes, excludes);
            return new AttrSelector(cacheKey, includes.toArray(new String[includes.size()]),
                    excludes.toArray(new String[excludes.size()]));
        }

        boolean matches(String[] attributes) {
            for (String include : includes) {
                if (!hasAttribute(attributes, include)) {
                    return false;
                }
            }
            for (String exclude : excludes) {
                if (hasAttribute(attributes, exclude)) {
                    return false;
                }
            }
            return true;
        }

        private static String buildCacheKey(ArrayList<String> includes, ArrayList<String> excludes) {
            StringBuilder key = new StringBuilder();
            for (String include : includes) {
                key.append('+').append(include);
            }
            for (String exclude : excludes) {
                key.append('-').append(exclude);
            }
            return key.toString();
        }

        private static boolean hasAttribute(String[] attributes, String target) {
            if (attributes == null || attributes.length == 0) {
                return false;
            }
            for (String attribute : attributes) {
                if (target.equals(attribute)) {
                    return true;
                }
            }
            return false;
        }
    }
}
