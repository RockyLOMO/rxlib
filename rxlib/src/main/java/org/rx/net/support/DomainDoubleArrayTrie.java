package org.rx.net.support;

import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.concurrent.FastThreadLocal;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import org.rx.core.Strings;

import java.io.Serializable;
import java.util.*;

/**
 * 极致优化版双数组 Trie：
 * 1. Suffix Compression (Tail 压缩)
 * 2. Zero-Allocation Label 查找
 * 3. 启发式构建 (出度优先)
 * 4. Fastutil 零分配
 */
public class DomainDoubleArrayTrie implements Serializable {
    private static final long serialVersionUID = 7L;

    private static class LabelStrategy implements Object2IntOpenCustomHashMap.Strategy<Object> {
        @Override
        public int hashCode(Object o) {
            if (o instanceof String) {
                String s = (String) o;
                int h = 0;
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c >= 'A' && c <= 'Z') c += 32;
                    h = 31 * h + c;
                }
                return h;
            } else if (o instanceof Window) {
                Window w = (Window) o;
                int h = 0;
                for (int i = w.start; i < w.end; i++) {
                    char c = w.s.charAt(i);
                    if (c >= 'A' && c <= 'Z') c += 32;
                    h = 31 * h + c;
                }
                return h;
            }
            return 0;
        }

        @Override
        public boolean equals(Object o1, Object o2) {
            if (o1 == o2) return true;
            if (o1 == null || o2 == null) return false;

            int len1 = getLen(o1);
            int len2 = getLen(o2);
            if (len1 != len2) return false;

            for (int i = 0; i < len1; i++) {
                if (getChar(o1, i) != getChar(o2, i)) return false;
            }
            return true;
        }

        private int getLen(Object o) {
            if (o instanceof String) return ((String) o).length();
            if (o instanceof Window) return ((Window) o).end - ((Window) o).start;
            return 0;
        }

        private char getChar(Object o, int i) {
            char c;
            if (o instanceof String) {
                c = ((String) o).charAt(i);
            } else {
                Window w = (Window) o;
                c = w.s.charAt(w.start + i);
            }
            if (c >= 'A' && c <= 'Z') c += 32;
            return c;
        }
    }

    private static class Window {
        String s;
        int start;
        int end;

        void set(String s, int start, int end) {
            this.s = s;
            this.start = start;
            this.end = end;
        }
    }

    private static final int MASK_END = 0x80000000;
    private static final int MASK_TAIL = 0x40000000;
    private static final int MASK_VAL = 0x3FFFFFFF;
    private static final int EMPTY_CHECK = -1;
    private static final FastThreadLocal<Window> windowThreadLocal = new FastThreadLocal<Window>() {
        @Override
        protected Window initialValue() {
            return new Window();
        }
    };
    private static final FastThreadLocal<Window> tailWindowThreadLocal = new FastThreadLocal<Window>() {
        @Override
        protected Window initialValue() {
            return new Window();
        }
    };

    private int[] base;
    private int[] check; // Bit 31: isEnd, Bit 30: hasTail, Bit 0-29: parentIdx/tailIdx
    private String[][] tailTable; // 存储剩余的 Label 数组

    // --- Label 池 ---
    private final transient LabelStrategy strategy = new LabelStrategy();
    private final Object2IntMap<Object> labelPool;
    private String[] idToLabel;
    private int nextLabelId = 1;

    public DomainDoubleArrayTrie() {
        this(128);
    }

    public DomainDoubleArrayTrie(int initCap) {
        labelPool = new Object2IntOpenCustomHashMap<>(initCap, 0.75f, strategy);
        idToLabel = new String[initCap];
    }

    public void build(List<String> domains) {
        InternalNode rootNode = new InternalNode();
        labelPool.defaultReturnValue(-1);

        for (String domain : domains) {
            String[] labels = Strings.split(domain, ".");
            InternalNode curr = rootNode;
            for (int i = labels.length - 1; i >= 0; i--) {
                String label = labels[i];
                int lid = labelPool.getInt(label);
                if (lid == -1) {
                    lid = nextLabelId++;
                    labelPool.put(label, lid);
                    if (lid >= idToLabel.length) idToLabel = Arrays.copyOf(idToLabel, idToLabel.length * 2);
                    idToLabel[lid] = label;
                }
                curr = curr.children.computeIfAbsent(lid, k -> new InternalNode());
            }
            curr.isEnd = true;
        }

        int size = (int) (labelPool.size() * 1.5) + 65536;
        base = new int[size];
        check = new int[size];
        Arrays.fill(check, EMPTY_CHECK);
        tailTable = new String[size][];

        PriorityQueue<NodePlan> pq = new PriorityQueue<>((a, b) -> b.node.children.size() - a.node.children.size());
        pq.add(new NodePlan(0, rootNode));
        int posCursor = 1;

        while (!pq.isEmpty()) {
            NodePlan plan = pq.poll();
            int parentIdx = plan.idx;
            InternalNode parentNode = plan.node;
            if (parentNode.children.isEmpty()) continue;

            List<Integer> lids = new ArrayList<>(parentNode.children.keySet());
            int b = findBase(lids, posCursor);
            base[parentIdx] = b;

            for (int lid : lids) {
                int childIdx = b + lid;
                if (childIdx >= base.length) expand(childIdx);

                InternalNode childNode = parentNode.children.get(lid);
                if (childNode.children.size() == 1 && !childNode.isEnd) {
                    List<String> tails = new ArrayList<>();
                    InternalNode temp = childNode;
                    while (temp.children.size() == 1 && !temp.isEnd) {
                        int nextLid = temp.children.keySet().iterator().next();
                        tails.add(idToLabel[nextLid]);
                        temp = temp.children.get(nextLid);
                    }
                    int checkVal = parentIdx | MASK_TAIL;
                    if (temp.isEnd) checkVal |= MASK_END;
                    check[childIdx] = checkVal;
                    tailTable[childIdx] = tails.toArray(new String[0]);
                } else {
                    int checkVal = parentIdx;
                    if (childNode.isEnd) checkVal |= MASK_END;
                    check[childIdx] = checkVal;
                    pq.add(new NodePlan(childIdx, childNode));
                }
                if (childIdx >= posCursor) posCursor = childIdx + 1;
            }
        }
    }

    private int findBase(List<Integer> lids, int start) {
        int b = start;
        while (true) {
            boolean conflict = false;
            for (int lid : lids) {
                int target = b + lid;
                if (target >= check.length) break;
                if (check[target] != EMPTY_CHECK) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return b;
            b++;
            if (b > 10000000) throw new RuntimeException("Base seek overflow");
        }
    }

    private void expand(int min) {
        int oldSize = base.length;
        int newSize = Math.max(min + 1, oldSize * 2);
        base = Arrays.copyOf(base, newSize);
        check = Arrays.copyOf(check, newSize);
        Arrays.fill(check, oldSize, newSize, EMPTY_CHECK);
        tailTable = Arrays.copyOf(tailTable, newSize);
    }

    public boolean matchSuffix(String domain) {
        if (domain == null || domain.isEmpty()) return false;
        Window w = windowThreadLocal.get();
        try {
            int end = domain.length();
            int curr = 0;

            for (int i = domain.length() - 1; i >= -1; i--) {
                if (i == -1 || domain.charAt(i) == '.') {
                    int start = i + 1;
                    if (start < end) {
                        w.set(domain, start, end);
                        int lid = labelPool.getInt(w);
                        if (lid == -1) return false;

                        int next = base[curr] + lid;
                        if (next >= check.length || (check[next] & MASK_VAL) != curr) return false;

                        if ((check[next] & MASK_TAIL) != 0) {
                            return matchTail(domain, i, tailTable[next]);
                        }
                        curr = next;
                        if ((check[curr] & MASK_END) != 0) return true;
                    }
                    end = i;
                }
            }
            return false;
        } finally {
            w.set(null, 0, 0); // 清空对 domain String 的引用，方便 GC
        }
    }

    private boolean matchTail(String domain, int dotPos, String[] tails) {
        int end = dotPos;
        // 临时借用一个 Window 进行比较
        Window tempWin = tailWindowThreadLocal.get();
        try {
            for (String tail : tails) {
                int start = -1;
                for (int i = end - 1; i >= -1; i--) {
                    if (i == -1 || domain.charAt(i) == '.') {
                        start = i + 1;
                        break;
                    }
                }
                if (start == -1 || start >= end) return false;

                tempWin.set(domain, start, end);
                if (!strategy.equals(tail, tempWin)) return false;
                end = start - 1;
            }
            return true;
        } finally {
            tempWin.set(null, 0, 0);
        }
    }

    static class InternalNode {
        //        Map<Integer, InternalNode> children = new HashMap<>();
        IntObjectHashMap<InternalNode> children = new IntObjectHashMap<>();
        boolean isEnd = false;
    }

    static class NodePlan {
        int idx;
        InternalNode node;

        NodePlan(int i, InternalNode n) {
            idx = i;
            node = n;
        }
    }

    // --- 测试方法 ---
    public static void main(String[] args) {
        DomainDATMatcher matcher = new DomainDATMatcher();
        matcher.build(Arrays.asList("google.com", "baidu.com", "my.site.cn", "long.suffix.domain.net"));

        System.out.println("True: " + matcher.matchSuffix("www.google.com"));
        System.out.println("True: " + matcher.matchSuffix("mail.baidu.com"));
        System.out.println("True: " + matcher.matchSuffix("test.my.site.cn"));
        System.out.println("False: " + matcher.matchSuffix("mygoogle.com"));
        System.out.println("False: " + matcher.matchSuffix("site.cn"));
        System.out.println("True: " + matcher.matchSuffix("a.b.long.suffix.domain.net"));
    }
}

///**
// * 方案二：高性能双数组 Trie (Double Array Trie)
// * 适用场景：百万级静态黑名单，极速查询 (O(段数))
// */
//public class DomainDoubleArrayTrie implements Serializable {
//    private static final long serialVersionUID = 1L;
//
//    private int[] base;
//    private int[] check;
//    private boolean[] isEnd;
//
//    private final Map<String, Integer> labelToId = new HashMap<>(64);
//    private int nextLabelId = 1;
//
//    /**
//     * 构建 DAT (简化版：基于现成的 Trie 结构压缩)
//     */
//    public void build(List<String> domains) {
//        // 1. 先构建普通的树形 Trie
//        InternalNode root = new InternalNode();
//        for (String domain : domains) {
//            String[] labels = domain.toLowerCase().split("\\.");
//            InternalNode curr = root;
//            for (int i = labels.length - 1; i >= 0; i--) {
//                int lid = labelToId.computeIfAbsent(labels[i], k -> nextLabelId++);
//                curr = curr.children.computeIfAbsent(lid, k -> new InternalNode());
//            }
//            curr.isEnd = true;
//        }
//
//        // 2. 压缩为双数组 (分配空间)
//        int initialSize = domains.size() * 5 + 65536;
//        base = new int[initialSize];
//        check = new int[initialSize];
//        isEnd = new boolean[initialSize];
//
//        // 3. 广度优先搜索并填充 base/check
//        Queue<Map.Entry<Integer, InternalNode>> queue = new LinkedList<>();
//        Queue<Integer> posQueue = new LinkedList<>();
//
//        // Root 处理
//        queue.add(new AbstractMap.SimpleEntry<>(0, root));
//        posQueue.add(0);
//
//        int posCursor = 1;
//        while (!queue.isEmpty()) {
//            Map.Entry<Integer, InternalNode> entry = queue.poll();
//            InternalNode node = entry.getValue();
//            int parentPos = posQueue.poll();
//
//            if (node.children.isEmpty()) continue;
//
//            // 寻找一个 base 偏移量，使得所有子节点 lid 都不冲突
//            List<Integer> childrenLids = new ArrayList<>(node.children.keySet());
//            int b = findBase(childrenLids, posCursor);
//            base[parentPos] = b;
//
//            for (int lid : childrenLids) {
//                int targetPos = b + lid;
//                if (targetPos >= base.length) expand(targetPos);
//
//                check[targetPos] = parentPos;
//                InternalNode childNode = node.children.get(lid);
//                isEnd[targetPos] = childNode.isEnd;
//
//                queue.add(new AbstractMap.SimpleEntry<>(lid, childNode));
//                posQueue.add(targetPos);
//                posCursor = Math.max(posCursor, targetPos);
//            }
//        }
//    }
//
//    private int findBase(List<Integer> lids, int start) {
//        int b = start;
//        while (true) {
//            boolean conflict = false;
//            for (int lid : lids) {
//                int target = b + lid;
//                if (target < check.length && check[target] != 0) {
//                    conflict = true;
//                    break;
//                }
//            }
//            if (!conflict) return b;
//            b++;
//        }
//    }
//
//    public boolean matchSuffix(String domain) {
//        if (domain == null) return false;
//        String[] labels = domain.toLowerCase().split("\\.");
//        int curr = 0; // Root at 0
//
//        for (int i = labels.length - 1; i >= 0; i--) {
//            Integer lid = labelToId.get(labels[i]);
//            if (lid == null) return false;
//
//            int next = base[curr] + lid;
//            if (next >= check.length || check[next] != curr) return false;
//
//            curr = next;
//            if (isEnd[curr]) return true;
//        }
//        return false;
//    }
//
//    private void expand(int target) {
//        int newSize = Math.max(target + 1, base.length * 2);
//        base = Arrays.copyOf(base, newSize);
//        check = Arrays.copyOf(check, newSize);
//        isEnd = Arrays.copyOf(isEnd, newSize);
//    }
//
//    static class InternalNode {
//        Map<Integer, InternalNode> children = new HashMap<>();
//        boolean isEnd = false;
//    }
//}
