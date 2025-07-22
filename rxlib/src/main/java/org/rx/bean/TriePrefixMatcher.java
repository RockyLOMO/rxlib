package org.rx.bean;

import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TriePrefixMatcher {
    // Trie 节点
    private static class TrieNode {
        Map<String, TrieNode> children = new HashMap<>();
        boolean isEnd = false;
    }

    private final TrieNode root;
    boolean empty;

    // 构造函数，初始化 Trie
    public TriePrefixMatcher(List<String> prefixes) {
        root = new TrieNode();
        empty = prefixes.isEmpty();
        for (String prefix : prefixes) {
            insert(prefix);
        }
    }

    // 插入前缀到 Trie
    private void insert(String prefix) {
        TrieNode node = root;
        String[] parts = prefix.split("\\.", -1);
        for (String part : parts) {
            node = node.children.computeIfAbsent(part, k -> new TrieNode());
        }
        node.isEnd = true;
    }

    /**
     * 检查类名是否以某个前缀开头
     *
     * @param className 完整类名（如 org.rx.net.class1）
     * @return true 如果匹配，false 如果不匹配
     */
    public boolean matches(String className) {
        if (empty || className == null || className.isEmpty()) {
            return false;
        }

        String[] parts = className.split("\\.", -1);
        TrieNode node = root;
        int i = 0;
        // 遍历类名部分，匹配 Trie
        while (i < parts.length && (node = node.children.get(parts[i])) != null) {
            i++;
            // 如果到达前缀结尾，检查是否合法
            if (node.isEnd) {
                // 前缀匹配成功，检查后续是否为 . 或结束
                if (i == parts.length || (i < parts.length && !parts[i].isEmpty())) {
                    return true;
                }
            }
        }
        return false;
    }
}
