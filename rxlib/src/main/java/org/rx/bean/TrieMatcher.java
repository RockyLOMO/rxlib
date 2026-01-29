package org.rx.bean;

import lombok.RequiredArgsConstructor;
import org.rx.util.function.BiFunc;

import java.util.HashMap;
import java.util.Map;

public class TrieMatcher {
    @RequiredArgsConstructor
    public static class PrefixMatcher {
        final TrieNode root = new TrieNode();
        final boolean whitelistMode;
        final BiFunc<String, Object[]> splitFunc;

        public void insert(String prefix) {
            TrieNode node = root;
            Object[] idxs = splitFunc.apply(prefix);
            // 插入前缀到 Trie
            for (Object idx : idxs) {
                node = node.children.computeIfAbsent(idx, k -> new TrieNode());
            }
            node.isEnd = true;
        }

        public boolean matches(String input) {
            boolean matches = innerMatches(input);
            return whitelistMode == matches;
        }

        boolean innerMatches(String input) {
            if (input == null || input.isEmpty() || root.children.isEmpty()) {
                return false;
            }

            Object[] parts = splitFunc.apply(input);
            TrieNode node = root;
            int i = 0;
            // 遍历类名部分，匹配 Trie
            while (i < parts.length && (node = node.children.get(parts[i])) != null) {
                i++;
                // 如果到达前缀结尾，检查是否合法
                if (node.isEnd) {
                    // 前缀匹配成功，检查后续是否为 . 或结束
                    if (i == parts.length || i < parts.length) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    @RequiredArgsConstructor
    public static class SuffixMatcher {
        final TrieNode root = new TrieNode();
        final boolean whitelistMode;
        final BiFunc<String, Object[]> splitFunc;

        public void insert(String suffix) {
            TrieNode node = root;
            Object[] idxs = splitFunc.apply(suffix);
            // 反向插入，方便后缀匹配
            for (int i = idxs.length - 1; i >= 0; i--) {
                node = node.children.computeIfAbsent(idxs[i], k -> new TrieNode());
            }
            node.isEnd = true;
        }

        public boolean matches(String input) {
            boolean matches = innerMatches(input);
            return whitelistMode == matches;
        }

        boolean innerMatches(String input) {
            if (input == null || input.isEmpty() || root.children.isEmpty()) {
                return false;
            }

            TrieNode node = root;
            Object[] parts = splitFunc.apply(input);
            for (int i = parts.length - 1; i >= 0; i--) {
                node = node.children.get(parts[i]);
                if (node == null) {
                    return false;
                }
                if (node.isEnd) {
                    return true; // 找到任一后缀匹配
                }
            }
            return node.isEnd;
        }
    }

    // Trie 节点
    private static class TrieNode {
        final Map<Object, TrieNode> children = new HashMap<>();
        boolean isEnd;
    }

//    public static class SuffixACDoubleArrayTrie<V> extends AhoCorasickDoubleArrayTrie<V> {
//        final Map<String, String> reversedCache = new HashMap<>();
//
//        @Override
//        public void build(Map<String, V> map) {
//            for (String k : new HashSet<>(map.keySet())) {
//                map.put(reverse(k), map.remove(k));
//            }
//            super.build(map);
//        }
//
//        private String reverse(CharSequence k) {
//            char[] chars = k.toCharArray();
//            int left = 0, right = chars.length - 1;
//            while (left < right) {
//                char temp = chars[left];
//                chars[left++] = chars[right];
//                chars[right--] = temp;
//            }
//            return new String(chars);
//        }
//
//        @Override
//        public boolean matches(CharSequence text) {
//            return super.matches(reverse(text));
//        }for (Hit<Integer> hit : hits) {
//    int end = hit.end;  // 模式结束位置（在 input 文本中）
//    int start = hit.begin;
//    // 检查这是否是合法后缀边界
//    if (end == domain.length() ||
//        domain.charAt(end) == '.') {
//        // 合法后缀匹配
//    }
//}
//    }
}
