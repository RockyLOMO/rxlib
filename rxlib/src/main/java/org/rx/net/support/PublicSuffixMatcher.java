package org.rx.net.support;

import org.rx.core.Strings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class PublicSuffixMatcher implements Serializable {
    private static final long serialVersionUID = -1658500581260881908L;
    private static final String RESOURCE = "public_suffix_list.dat";

    public static final PublicSuffixMatcher DEFAULT = loadDefault();

    private final Set<String> exactRules;
    private final Set<String> wildcardRules;
    private final Set<String> exceptionRules;

    PublicSuffixMatcher(Set<String> exactRules, Set<String> wildcardRules, Set<String> exceptionRules) {
        this.exactRules = Collections.unmodifiableSet(exactRules);
        this.wildcardRules = Collections.unmodifiableSet(wildcardRules);
        this.exceptionRules = Collections.unmodifiableSet(exceptionRules);
    }

    public boolean isPublicSuffix(String domain) {
        String value = normalize(domain);
        if (value == null) {
            return false;
        }
        return labelCount(value) == publicSuffixLabelCount(value);
    }

    public String publicSuffix(String domain) {
        String value = normalize(domain);
        if (value == null) {
            return null;
        }
        return lastLabels(value, publicSuffixLabelCount(value));
    }

    public String registrableDomain(String domain) {
        String value = normalize(domain);
        if (value == null) {
            return domain;
        }
        int labelCount = labelCount(value);
        int publicSuffixLabels = publicSuffixLabelCount(value);
        if (labelCount <= publicSuffixLabels) {
            return value;
        }
        return lastLabels(value, publicSuffixLabels + 1);
    }

    int publicSuffixLabelCount(String domain) {
        int[] starts = labelStarts(domain);
        int labelCount = starts.length;
        for (int i = 0; i < labelCount; i++) {
            String candidate = domain.substring(starts[i]);
            if (exceptionRules.contains(candidate)) {
                return labelCount - i - 1;
            }
        }

        int best = 1;
        for (int i = 0; i < labelCount; i++) {
            String candidate = domain.substring(starts[i]);
            if (exactRules.contains(candidate)) {
                int labels = labelCount - i;
                if (labels > best) {
                    best = labels;
                }
            }
        }
        for (int i = 1; i < labelCount; i++) {
            String candidate = domain.substring(starts[i]);
            if (wildcardRules.contains(candidate)) {
                int labels = labelCount - i + 1;
                if (labels > best) {
                    best = labels;
                }
            }
        }
        return best;
    }

    private static PublicSuffixMatcher loadDefault() {
        Set<String> exact = new HashSet<>();
        Set<String> wildcard = new HashSet<>();
        Set<String> exception = new HashSet<>();
        ClassLoader loader = PublicSuffixMatcher.class.getClassLoader();
        InputStream in = loader != null ? loader.getResourceAsStream(RESOURCE)
                : PublicSuffixMatcher.class.getResourceAsStream("/" + RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing " + RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String rule = parseRule(line);
                if (rule == null) {
                    continue;
                }
                if (rule.charAt(0) == '!') {
                    exception.add(rule.substring(1));
                } else if (rule.startsWith("*.")) {
                    wildcard.add(rule.substring(2));
                } else {
                    exact.add(rule);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Load " + RESOURCE + " failed", e);
        }
        return new PublicSuffixMatcher(exact, wildcard, exception);
    }

    private static String parseRule(String line) {
        String rule = line.trim();
        if (rule.length() == 0 || rule.startsWith("//")) {
            return null;
        }
        boolean exception = rule.charAt(0) == '!';
        boolean wildcard = rule.startsWith("*.");
        String body = exception ? rule.substring(1) : (wildcard ? rule.substring(2) : rule);
        String value = normalize(body);
        if (value == null) {
            return null;
        }
        return exception ? "!" + value : (wildcard ? "*." + value : value);
    }

    private static String normalize(String domain) {
        if (Strings.isEmpty(domain)) {
            return null;
        }
        String value = domain.trim();
        if (value.length() == 0 || value.charAt(0) == '.' || value.charAt(value.length() - 1) == '.') {
            return null;
        }
        boolean previousDot = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (previousDot) {
                    return null;
                }
                previousDot = true;
            } else {
                previousDot = false;
            }
        }
        try {
            return IDN.toASCII(value).toLowerCase(Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int[] labelStarts(String domain) {
        int count = labelCount(domain);
        int[] starts = new int[count];
        starts[0] = 0;
        int index = 1;
        for (int i = 0; i < domain.length(); i++) {
            if (domain.charAt(i) == '.') {
                starts[index++] = i + 1;
            }
        }
        return starts;
    }

    private static int labelCount(String domain) {
        int count = 1;
        for (int i = 0; i < domain.length(); i++) {
            if (domain.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }

    private static String lastLabels(String domain, int labels) {
        if (labels <= 0) {
            return Strings.EMPTY;
        }
        int count = labelCount(domain);
        if (labels >= count) {
            return domain;
        }
        int dotsToSkip = count - labels;
        for (int i = 0; i < domain.length(); i++) {
            if (domain.charAt(i) == '.') {
                dotsToSkip--;
                if (dotsToSkip == 0) {
                    return domain.substring(i + 1);
                }
            }
        }
        return domain;
    }
}
