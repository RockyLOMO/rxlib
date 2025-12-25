package org.rx.net.support;

import org.rx.exception.InvalidException;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 工业级域名匹配器：LabelPool + Int2Object Trie
 * 适用于百万级数据，极低内存消耗
 */
public class DomainTrieMatcher implements Serializable {
    private static final long serialVersionUID = 2L;

    // 1. 全局 Label 池：将 String 转换为唯一的 int ID
    // 使用 Map 确保同一个 "com" 在全树中只有一个 ID
    private final Map<String, Integer> labelPool = new HashMap<>(64);
    private int nextId = 1;

    // 2. Trie 节点定义
    static class Node implements Serializable {
        private static final long serialVersionUID = 2L;
        // Key 是 LabelID, Value 是子节点
        // 这里使用 HashMap<Integer, Node>，生产环境推荐替换为 fastutil 的 Int2ObjectOpenHashMap
        Map<Integer, Node> children;
        boolean isEnd;

        Node getOrCreate(int labelId) {
            if (children == null) {
                children = new HashMap<>(4);
            }
            return children.computeIfAbsent(labelId, k -> new Node());
        }

        Node getChild(int labelId) {
            if (children == null) return null;
            return children.get(labelId);
        }
    }

    private final Node root = new Node();

    /**
     * 获取或创建 Label ID
     */
    private synchronized int getLabelId(String label) {
        return labelPool.computeIfAbsent(label, k -> nextId++);
    }

    /**
     * 查询 Label ID (不创建新 ID)
     */
    private Integer findLabelId(String label) {
        return labelPool.get(label);
    }

    public void insert(String domain) {
        if (domain == null || domain.isEmpty()) return;

        Node current = root;
        int end = domain.length();
        String lowerDomain = domain.toLowerCase();

        for (int i = lowerDomain.length() - 1; i >= 0; i--) {
            if (lowerDomain.charAt(i) == '.') {
                if (i + 1 < end) {
                    int lid = getLabelId(lowerDomain.substring(i + 1, end));
                    current = current.getOrCreate(lid);
                }
                end = i;
            }
        }
        if (0 < end) {
            int lid = getLabelId(lowerDomain.substring(0, end));
            current = current.getOrCreate(lid);
        }
        current.isEnd = true;
    }

    /**
     * 高性能后缀匹配
     */
    public boolean matchSuffix(String domain) {
        if (domain == null || domain.isEmpty()) return false;

        Node current = root;
        int end = domain.length();
        String lowerDomain = domain.toLowerCase();

        for (int i = lowerDomain.length() - 1; i >= 0; i--) {
            if (lowerDomain.charAt(i) == '.') {
                if (i + 1 < end) {
                    Integer lid = findLabelId(lowerDomain.substring(i + 1, end));
                    if (lid == null) return false; // 路径中出现了未知的 Label，必定不匹配

                    current = current.getChild(lid);
                    if (current == null) return false;
                    if (current.isEnd) return true; // 命中了后缀
                }
                end = i;
            }
        }

        // 处理头部段
        Integer lid = findLabelId(lowerDomain.substring(0, end));
        if (lid == null) return false;
        current = current.getChild(lid);

        return current != null && current.isEnd;
    }
}

//public class DomainTrieMatcher implements Serializable {
//    private static final long serialVersionUID = -2577041860871978500L;
//
//    static class CompactTrieNode implements Serializable {
//        private static final long serialVersionUID = 1237957363773921057L;
//        CompactTrieNode[] children;
//        boolean isEnd;
//
//        CompactTrieNode getOrCreate(int index) {
////            if (children == null) {
////                children = new CompactTrieNode[ALPHABET_SIZE];
////            }
//            ensureCapacity(index + 1);
//            if (children[index] == null) {
//                children[index] = new CompactTrieNode();
//            }
//            return children[index];
//        }
//
//        void ensureCapacity(int minCapacity) {
//            if (children == null) {
//                children = new CompactTrieNode[minCapacity];
//                return;
//            }
//            int oldCapacity = children.length;
//            if (minCapacity <= oldCapacity) {
//                return; // 已足够，无需扩容
//            }
//            int newCapacity = Math.max(ALPHABET_SIZE, oldCapacity + (oldCapacity >> 1));
////            if (newCapacity < oldCapacity) {
////                throw new InvalidException("overflow");
////            }
//            children = Arrays.copyOf(children, newCapacity);
//        }
//    }
//
//    // 字符映射：只支持 a-z 0-9 . -（38 个字符）
//    static final int ALPHABET_SIZE = 38;
//
//    public static int charToIndex(char c) {
//        if (c >= 'a' && c <= 'z') return c - 'a';
//        if (c >= 'A' && c <= 'Z') return c - 'A'; // 处理大写
//        if (c >= '0' && c <= '9') return 26 + (c - '0');
//        if (c == '.') return 36;
//        if (c == '-') return 37;
//        throw new InvalidException("Char {} not supported", c);
//    }
//
//    final CompactTrieNode root = new CompactTrieNode();
//
//    /**
//     * 插入域名（反向插入）
//     */
//    public void insert(String domain) {
//        CompactTrieNode node = root;
//        for (int i = domain.length() - 1; i >= 0; i--) {
//            int idx = charToIndex(domain.charAt(i));
//            node = node.getOrCreate(idx);
//        }
//        node.isEnd = true;
//    }
//
//    /**
//     * 判断是否匹配（后缀匹配）
//     */
//    public boolean matchSuffix(String domain) {
//        CompactTrieNode node = root;
//        for (int i = domain.length() - 1; i >= 0; i--) {
//            if (node.children == null
//                    || (node = node.children[charToIndex(domain.charAt(i))]) == null) {
//                return false;
//            }
//            if (node.isEnd) {
//                // 边界判定
//                // 如果当前已经是字符串开头(i==0)，或者是子域名的分界点(前一个字符是 '.')
//                // 比如 trie里有 google.com, 输入是 mail.google.com
//                // 当匹配完 google.com 后，i 指向 'g'，i-1 必须是 '.'
//                if (i == 0 || domain.charAt(i - 1) == '.') {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }
//}
