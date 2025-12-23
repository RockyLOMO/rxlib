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

//    private final TrieNode root;
//    boolean isWhitelistMode;
//
//    // 构造函数，初始化 Trie
//    public TriePrefixMatcher(Collection<String> prefixes, boolean isWhitelistMode) {
//        root = new TrieNode();
//        this.isWhitelistMode = isWhitelistMode;
//        for (String prefix : prefixes) {
//            insert(prefix);
//        }
//    }
//
//    // 插入前缀到 Trie
//    private void insert(String prefix) {
//        TrieNode node = root;
//        String[] parts = prefix.split("\\.", -1);
//        for (String part : parts) {
//            node = node.children.computeIfAbsent(part, k -> new TrieNode());
//        }
//        node.isEnd = true;
//    }

//    /**
//     * 检查类名是否以某个前缀开头
//     *
//     * @param className 完整类名（如 org.rx.net.class1）
//     * @return true 如果匹配，false 如果不匹配
//     */
//    public boolean matches(String className) {
//        boolean matches = innerMatches(className);
//        return isWhitelistMode ? matches : !matches;
//    }
//
//    boolean innerMatches(String className) {
//
//    }
}
