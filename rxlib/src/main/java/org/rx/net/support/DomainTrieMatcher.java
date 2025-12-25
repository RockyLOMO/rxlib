//package org.rx.net.support;
//
//import org.rx.exception.InvalidException;
//
//import java.io.Serializable;
//import java.util.Arrays;
//import java.util.HashMap;
//import java.util.Map;
//
///**
// * 方案一：哈希偏移扁平化 Trie
// * 适用场景：高性能、内存敏感、需要较快构建速度
// */
//public class DomainTrieMatcher implements Serializable {
//    private static final long serialVersionUID = 1L;
//
//    private int capacity;
//    private static final float LOAD_FACTOR = 0.75f;
//
//    // 1. 标签池：去重存储字符串 Label
//    private final Map<String, Integer> labelToId = new HashMap<>(500000);
//    private int nextLabelId = 1;
//
//    // 2. 核心状态机：使用线性探测哈希表存储跳转
//    private long[] table;      // Key: (parentIdx << 32 | labelId)
//    private int[] childIds;    // Value: nextStateIdx
//    private boolean[] isEnd;   // 状态标记
//
//    private int nodeCount = 1; // 0 是 root
//    private int entryCount = 0;
//
//    public DomainTrieMatcher(int initialCapacity) {
//        this.capacity = powerOfTwo(initialCapacity);
//        this.table = new long[capacity];
//        Arrays.fill(table, -1L);
//        this.childIds = new int[capacity];
//        this.isEnd = new boolean[capacity];
//    }
//
//    private int powerOfTwo(int n) {
//        int cap = 1;
//        while (cap < n) cap <<= 1;
//        return cap;
//    }
//
//    private int getLabelId(String label) {
//        return labelToId.computeIfAbsent(label, k -> nextLabelId++);
//    }
//
//    public void insert(String domain) {
//        if (domain == null || domain.isEmpty()) return;
//        String[] labels = domain.toLowerCase().split("\\.");
//
//        int currentIdx = 0;
//        for (int i = labels.length - 1; i >= 0; i--) {
//            int lid = getLabelId(labels[i]);
//            int nextIdx = findChild(currentIdx, lid);
//
//            if (nextIdx == -1) {
//                if (entryCount >= capacity * LOAD_FACTOR) rehash();
//                nextIdx = nodeCount++;
//                putChild(currentIdx, lid, nextIdx);
//            }
//            currentIdx = nextIdx;
//        }
//        ensureIsEndCapacity(currentIdx);
//        isEnd[currentIdx] = true;
//    }
//
//    public boolean matchSuffix(String domain) {
//        if (domain == null || domain.isEmpty()) return false;
//
//        int end = domain.length();
//        int currentIdx = 0;
//
//        // 无 Split 手动扫描指针
//        for (int i = domain.length() - 1; i >= 0; i--) {
//            if (domain.charAt(i) == '.') {
//                if (i + 1 < end) {
//                    currentIdx = step(currentIdx, domain, i + 1, end);
//                    if (currentIdx == -1) return false;
//                    if (isEnd[currentIdx]) return true;
//                }
//                end = i;
//            }
//        }
//        currentIdx = step(currentIdx, domain, 0, end);
//        return currentIdx != -1 && isEnd[currentIdx];
//    }
//
//    private int step(int currentIdx, String domain, int start, int end) {
//        String label = domain.substring(start, end).toLowerCase();
//        Integer lid = labelToId.get(label);
//        if (lid == null) return -1;
//        return findChild(currentIdx, lid);
//    }
//
//    private int findChild(int parentIdx, int labelId) {
//        long key = ((long) parentIdx << 32) | (labelId & 0xFFFFFFFFL);
//        int h = Long.hashCode(key);
//        int idx = (h ^ (h >>> 16)) & (capacity - 1);
//
//        while (table[idx] != -1L) {
//            if (table[idx] == key) return childIds[idx];
//            idx = (idx + 1) & (capacity - 1);
//        }
//        return -1;
//    }
//
//    private void putChild(int parentIdx, int labelId, int childIdx) {
//        long key = ((long) parentIdx << 32) | (labelId & 0xFFFFFFFFL);
//        int h = Long.hashCode(key);
//        int idx = (h ^ (h >>> 16)) & (capacity - 1);
//
//        while (table[idx] != -1L) {
//            idx = (idx + 1) & (capacity - 1);
//        }
//        table[idx] = key;
//        childIds[idx] = childIdx;
//        entryCount++;
//    }
//
//    private void rehash() {
//        int oldCap = capacity;
//        long[] oldTable = table;
//        int[] oldChildren = childIds;
//
//        capacity <<= 1;
//        table = new long[capacity];
//        Arrays.fill(table, -1L);
//        childIds = new int[capacity];
//        entryCount = 0;
//
//        for (int i = 0; i < oldCap; i++) {
//            if (oldTable[i] != -1L) {
//                int pIdx = (int) (oldTable[i] >> 32);
//                int lId = (int) oldTable[i];
//                putChild(pIdx, lId, oldChildren[i]);
//            }
//        }
//    }
//
//    private void ensureIsEndCapacity(int idx) {
//        if (idx >= isEnd.length) {
//            isEnd = Arrays.copyOf(isEnd, Math.max(idx + 1, isEnd.length * 2));
//        }
//    }
//}
//
////public class DomainTrieMatcher implements Serializable {
////    private static final long serialVersionUID = 2L;
////
////    // 1. 全局 Label 池：将 String 转换为唯一的 int ID
////    // 使用 Map 确保同一个 "com" 在全树中只有一个 ID
////    private final Map<String, Integer> labelPool = new HashMap<>(64);
////    private int nextId = 1;
////
////    // 2. Trie 节点定义
////    static class Node implements Serializable {
////        private static final long serialVersionUID = 2L;
////        // Key 是 LabelID, Value 是子节点
////        // 这里使用 HashMap<Integer, Node>，生产环境推荐替换为 fastutil 的 Int2ObjectOpenHashMap
////        Map<Integer, Node> children;
////        boolean isEnd;
////
////        Node getOrCreate(int labelId) {
////            if (children == null) {
////                children = new HashMap<>(4);
////            }
////            return children.computeIfAbsent(labelId, k -> new Node());
////        }
////
////        Node getChild(int labelId) {
////            if (children == null) return null;
////            return children.get(labelId);
////        }
////    }
////
////    private final Node root = new Node();
////
////    /**
////     * 获取或创建 Label ID
////     */
////    private synchronized int getLabelId(String label) {
////        return labelPool.computeIfAbsent(label, k -> nextId++);
////    }
////
////    /**
////     * 查询 Label ID (不创建新 ID)
////     */
////    private Integer findLabelId(String label) {
////        return labelPool.get(label);
////    }
////
////    public void insert(String domain) {
////        if (domain == null || domain.isEmpty()) return;
////
////        Node current = root;
////        int end = domain.length();
////        String lowerDomain = domain.toLowerCase();
////
////        for (int i = lowerDomain.length() - 1; i >= 0; i--) {
////            if (lowerDomain.charAt(i) == '.') {
////                if (i + 1 < end) {
////                    int lid = getLabelId(lowerDomain.substring(i + 1, end));
////                    current = current.getOrCreate(lid);
////                }
////                end = i;
////            }
////        }
////        if (0 < end) {
////            int lid = getLabelId(lowerDomain.substring(0, end));
////            current = current.getOrCreate(lid);
////        }
////        current.isEnd = true;
////    }
////
////    /**
////     * 高性能后缀匹配
////     */
////    public boolean matchSuffix(String domain) {
////        if (domain == null || domain.isEmpty()) return false;
////
////        Node current = root;
////        int end = domain.length();
////        String lowerDomain = domain.toLowerCase();
////
////        for (int i = lowerDomain.length() - 1; i >= 0; i--) {
////            if (lowerDomain.charAt(i) == '.') {
////                if (i + 1 < end) {
////                    Integer lid = findLabelId(lowerDomain.substring(i + 1, end));
////                    if (lid == null) return false; // 路径中出现了未知的 Label，必定不匹配
////
////                    current = current.getChild(lid);
////                    if (current == null) return false;
////                    if (current.isEnd) return true; // 命中了后缀
////                }
////                end = i;
////            }
////        }
////
////        // 处理头部段
////        Integer lid = findLabelId(lowerDomain.substring(0, end));
////        if (lid == null) return false;
////        current = current.getChild(lid);
////
////        return current != null && current.isEnd;
////    }
////}
//
////public class DomainTrieMatcher implements Serializable {
////    private static final long serialVersionUID = -2577041860871978500L;
////
////    static class CompactTrieNode implements Serializable {
////        private static final long serialVersionUID = 1237957363773921057L;
////        CompactTrieNode[] children;
////        boolean isEnd;
////
////        CompactTrieNode getOrCreate(int index) {
//////            if (children == null) {
//////                children = new CompactTrieNode[ALPHABET_SIZE];
//////            }
////            ensureCapacity(index + 1);
////            if (children[index] == null) {
////                children[index] = new CompactTrieNode();
////            }
////            return children[index];
////        }
////
////        void ensureCapacity(int minCapacity) {
////            if (children == null) {
////                children = new CompactTrieNode[minCapacity];
////                return;
////            }
////            int oldCapacity = children.length;
////            if (minCapacity <= oldCapacity) {
////                return; // 已足够，无需扩容
////            }
////            int newCapacity = Math.max(ALPHABET_SIZE, oldCapacity + (oldCapacity >> 1));
////            children = Arrays.copyOf(children, newCapacity);
////        }
////    }
////
////    // 字符映射：只支持 a-z 0-9 . -（38 个字符）
////    static final int ALPHABET_SIZE = 38;
////
////    public static int charToIndex(char c) {
////        if (c >= 'a' && c <= 'z') return c - 'a';
////        if (c >= 'A' && c <= 'Z') return c - 'A'; // 处理大写
////        if (c >= '0' && c <= '9') return 26 + (c - '0');
////        if (c == '.') return 36;
////        if (c == '-') return 37;
////        throw new InvalidException("Char {} not supported", c);
////    }
////
////    final CompactTrieNode root = new CompactTrieNode();
////
////    /**
////     * 插入域名（反向插入）
////     */
////    public void insert(String domain) {
////        CompactTrieNode node = root;
////        for (int i = domain.length() - 1; i >= 0; i--) {
////            int idx = charToIndex(domain.charAt(i));
////            node = node.getOrCreate(idx);
////        }
////        node.isEnd = true;
////    }
////
////    /**
////     * 判断是否匹配（后缀匹配）
////     */
////    public boolean matchSuffix(String domain) {
////        CompactTrieNode node = root;
////        for (int i = domain.length() - 1; i >= 0; i--) {
////            if (node.children == null
////                    || (node = node.children[charToIndex(domain.charAt(i))]) == null) {
////                return false;
////            }
////            if (node.isEnd) {
////                // 边界判定
////                // 如果当前已经是字符串开头(i==0)，或者是子域名的分界点(前一个字符是 '.')
////                // 比如 trie里有 google.com, 输入是 mail.google.com
////                // 当匹配完 google.com 后，i 指向 'g'，i-1 必须是 '.'
////                if (i == 0 || domain.charAt(i - 1) == '.') {
////                    return true;
////                }
////            }
////        }
////        return false;
////    }
////}
