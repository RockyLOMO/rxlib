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
    final DomainTrieMatcher domains = new DomainTrieMatcher();
    final AhoCorasickDoubleArrayTrie<String> keywords = new AhoCorasickDoubleArrayTrie<>();
    final List<Pattern> regexps = new ArrayList<>();

    @SafeVarargs
    public GeoSiteMatcher(@NonNull Iterator<String>... rulesSet) {
        // 构建 AC自动机 要求一次性传入全部关键词
        TreeMap<String, String> keywordMap = new TreeMap<>();
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
                    domains.insert(rule.toLowerCase());
                }
            }
        }
        keywords.build(keywordMap);
    }

    public boolean matches(String domain) {
        String d = domain.toLowerCase();
        // 1. 最快：精确匹配
        if (fulls.contains(d)) {
            return true;
        }
        // 2. 快速：后缀匹配（Trie 效率最高）
        //d.equals(baseDomain) || d.endsWith("." + baseDomain)
        if (domains.matchSuffix(d)) {
            return true;
        }
        // 3. keyword 多模式匹配
        if (keywords.matches(d)) {
            return true;
        }
        // 4. regexp（最后才查，性能最差）
        for (Pattern p : regexps) {
            if (p.matcher(d).find()) {
                return true;
            }
        }
        return false;
    }
}
