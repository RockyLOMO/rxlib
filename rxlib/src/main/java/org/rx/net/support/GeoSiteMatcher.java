package org.rx.net.support;

import io.netty.util.concurrent.FastThreadLocal;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import lombok.NonNull;
import org.rx.third.hankcs.AhoCorasickDoubleArrayTrie;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeoSiteMatcher implements Serializable {
    private static final long serialVersionUID = 3722729671665278648L;
    static final String fullRule = "full:";
    static final String kwRule = "keyword:";
    static final String regexpRule = "regexp:";
    private static final AsciiCaseInsensitiveStrategy asciiCaseInsensitiveStrategy = new AsciiCaseInsensitiveStrategy();
    private static final FastThreadLocal<LowerAsciiCharSequence> keywordInput = new FastThreadLocal<LowerAsciiCharSequence>() {
        @Override
        protected LowerAsciiCharSequence initialValue() {
            return new LowerAsciiCharSequence();
        }
    };

    final Set<String> fulls = new ObjectOpenCustomHashSet<>(asciiCaseInsensitiveStrategy);
    final UltraDomainTrieMatcher domains = new UltraDomainTrieMatcher();
    final AhoCorasickDoubleArrayTrie<String> keywords = new AhoCorasickDoubleArrayTrie<>();
    final List<ReusablePattern> regexps = new ArrayList<>();
    final boolean hasFullRules;
    final boolean hasKeywordRules;
    final boolean hasRegexpRules;

    @SafeVarargs
    public GeoSiteMatcher(@NonNull Iterator<String>... rulesSet) {
        // 构建 AC自动机 要求一次性传入全部关键词
        TreeMap<String, String> keywordMap = new TreeMap<>();
        ArrayList<String> domainRules = new ArrayList<>();
        for (Iterator<String> rules : rulesSet) {
            while (rules.hasNext()) {
                String rule = rules.next();
                if (rule == null || rule.isEmpty()) {
                    continue;
                }
                if (rule.startsWith(fullRule)) {
                    fulls.add(toLowerAscii(rule.substring(fullRule.length())));
                } else if (rule.startsWith(kwRule)) {
                    String kw = toLowerAscii(rule.substring(kwRule.length()));
                    keywordMap.put(kw, kw);
                } else if (rule.startsWith(regexpRule)) {
                    String r = rule.substring(regexpRule.length());
                    regexps.add(new ReusablePattern(Pattern.compile(r, Pattern.CASE_INSENSITIVE)));
                } else {
                    domainRules.add(toLowerAscii(rule));
                }
            }
        }
        domains.build(domainRules);
        keywords.build(keywordMap);
        hasFullRules = !fulls.isEmpty();
        hasKeywordRules = !keywordMap.isEmpty();
        hasRegexpRules = !regexps.isEmpty();
    }

    public boolean matches(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        // 1. 最快：后缀匹配（Trie 内部已 case-insensitive，无需 toLowerCase）
        if (domains.matchSuffix(domain)) {
            return true;
        }
        if (hasFullRules && fulls.contains(domain)) {
            return true;
        }
        if (hasKeywordRules) {
            LowerAsciiCharSequence input = keywordInput.get();
            try {
                input.reset(domain);
                if (keywords.matches(input)) {
                    return true;
                }
            } finally {
                input.reset(null);
            }
        }
        if (hasRegexpRules) {
            for (ReusablePattern p : regexps) {
                if (p.find(domain)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String toLowerAscii(String value) {
        int len = value.length();
        char[] chars = null;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (chars == null) {
                    chars = value.toCharArray();
                }
                chars[i] = (char) (c + 32);
            }
        }
        return chars == null ? value : new String(chars);
    }

    static char toLowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    private static final class LowerAsciiCharSequence implements CharSequence, Serializable {
        private static final long serialVersionUID = 1L;
        String value;

        void reset(String value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            return toLowerAscii(value.charAt(index));
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private static final class ReusablePattern implements Serializable {
        private static final long serialVersionUID = 1L;
        final Pattern pattern;
        transient FastThreadLocal<Matcher> matcherLocal;

        ReusablePattern(Pattern pattern) {
            this.pattern = pattern;
        }

        boolean find(CharSequence text) {
            Matcher matcher = matcherLocal().get();
            matcher.reset(text);
            return matcher.find();
        }

        private FastThreadLocal<Matcher> matcherLocal() {
            FastThreadLocal<Matcher> local = matcherLocal;
            if (local == null) {
                synchronized (this) {
                    local = matcherLocal;
                    if (local == null) {
                        final Pattern p = pattern;
                        matcherLocal = local = new FastThreadLocal<Matcher>() {
                            @Override
                            protected Matcher initialValue() {
                                return p.matcher("");
                            }
                        };
                    }
                }
            }
            return local;
        }
    }

    private static final class AsciiCaseInsensitiveStrategy implements Hash.Strategy<String>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int hashCode(String value) {
            if (value == null) {
                return 0;
            }
            int h = 0;
            for (int i = 0; i < value.length(); i++) {
                h = 31 * h + toLowerAscii(value.charAt(i));
            }
            return h;
        }

        @Override
        public boolean equals(String a, String b) {
            if (a == b) {
                return true;
            }
            if (a == null || b == null || a.length() != b.length()) {
                return false;
            }
            for (int i = 0; i < a.length(); i++) {
                if (toLowerAscii(a.charAt(i)) != toLowerAscii(b.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
