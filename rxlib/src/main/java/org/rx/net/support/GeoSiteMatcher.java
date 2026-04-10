package org.rx.net.support;

import lombok.NonNull;
import org.rx.third.hankcs.AhoCorasickDoubleArrayTrie;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Pattern;

public class GeoSiteMatcher implements Serializable {
    private static final long serialVersionUID = 3722729671665278648L;
    static final String fullRule = "full:";
    static final String kwRule = "keyword:";
    static final String regexpRule = "regexp:";

    final Set<String> fulls = new HashSet<>();
    final UltraDomainMatcher domains = new UltraDomainMatcher();
    final AhoCorasickDoubleArrayTrie<String> keywords = new AhoCorasickDoubleArrayTrie<>();
    final List<Pattern> regexps = new ArrayList<>();

    @SafeVarargs
    public GeoSiteMatcher(@NonNull Iterator<String>... rulesSet) {
        // 构建 AC自动机 要求一次性传入全部关键词
        TreeMap<String, String> keywordMap = new TreeMap<>();
        ArrayList<String> domainRules = new ArrayList<>();
        for (Iterator<String> rules : rulesSet) {
            while (rules.hasNext()) {
                String rule = rules.next();
                if (rule.startsWith(fullRule)) {
                    fulls.add(rule.substring(fullRule.length()).toLowerCase());
                } else if (rule.startsWith(kwRule)) {
                    String kw = rule.substring(kwRule.length()).toLowerCase();
                    keywordMap.put(kw, kw);
                } else if (rule.startsWith(regexpRule)) {
                    String r = rule.substring(regexpRule.length());
                    regexps.add(Pattern.compile(r, Pattern.CASE_INSENSITIVE));
                } else {
//                    domains.insert(rule.toLowerCase());
                    domainRules.add(rule.toLowerCase());
                }
            }
        }
        domains.build(domainRules);
        keywords.build(keywordMap);
    }

    public boolean matches(String domain) {
        // 1. 最快：后缀匹配（Trie 内部已 case-insensitive，无需 toLowerCase）
        if (domains.matchSuffix(domain)) {
            return true;
        }
        // 2. 需要小写的匹配路径（仅在 suffix 未命中时才分配）
        String d = domain.toLowerCase();
        // 精确匹配
        if (fulls.contains(d)) {
            return true;
        }
        // keyword 多模式匹配
        if (keywords.matches(d)) {
            return true;
        }
        // 4. regexp（最后才查，性能最差；Pattern 已带 CASE_INSENSITIVE）
        for (Pattern p : regexps) {
            if (p.matcher(domain).find()) {
                return true;
            }
        }
        return false;
    }
}
