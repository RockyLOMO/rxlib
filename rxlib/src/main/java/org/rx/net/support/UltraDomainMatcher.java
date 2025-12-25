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
 * 3. Fastutil 零分配
 * 4. CompactLabelMap (扁平化只读哈希)
 */
public class UltraDomainMatcher implements Serializable {
    private static final long serialVersionUID = 9L;

    /**
     * 核心组件：紧凑型只读 Label 映射表
     * 替代了运行时对 fastutil 的依赖，内存占用极低，且支持零拷贝查询
     */
    private static class CompactLabelMap implements Serializable {
        private static final long serialVersionUID = 1L;

        private int[] hashSlots; // 哈希桶，存储 labelId
        private char[] labelData; // 所有 Label 的字符拼接
        private int[] offsets;    // labelId -> labelData 的起始位置
        private int mask;         //用于取模: hash & mask

        // 构建：将 fastutil map 转为扁平数组
        public CompactLabelMap(Object2IntMap<Object> sourceMap, List<String> idToLabel) {
            int size = sourceMap.size();
            // 1. 初始化容量为 2 的幂次，负载因子 0.5 保证性能
            int capacity = 1;
            while (capacity < size * 2) capacity <<= 1;
            // 安全检查
            if (capacity < 0) capacity = 1 << 30;

            this.mask = capacity - 1;
            this.hashSlots = new int[capacity];

            // 2. 扁平化存储 Label 字符串
            StringBuilder sb = new StringBuilder();
            this.offsets = new int[idToLabel.size() + 1]; // id 从 1 开始

            for (int id = 1; id < idToLabel.size(); id++) {
                String label = idToLabel.get(id); // idToLabel[id]
                offsets[id] = sb.length();
                sb.append(label);
            }
            // 哨兵，方便计算最后一个长度
            offsets[offsets.length - 1] = sb.length();
            this.labelData = new char[sb.length()];
            sb.getChars(0, sb.length(), this.labelData, 0);

            // 3. 填充哈希槽 (线性探测法解决冲突)
            for (int id = 1; id < idToLabel.size(); id++) {
                String label = idToLabel.get(id);
                int h = mixHash(label, 0, label.length());
                int slot = h & mask;
                while (hashSlots[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                hashSlots[slot] = id;
            }
        }

        // 运行时查询 (Zero-Allocation)
        public int getInt(Window w) {
            int h = w.hashCode(); // Window 已经实现了特定的 hash 逻辑
            int slot = h & mask;

            // 开放寻址探测
            while (true) {
                int id = hashSlots[slot];
                if (id == 0) return -1; // 遇到空槽，说明不存在
                // 校验 Key 是否相等 (解决哈希冲突)
                if (equals(id, w)) return id;
                slot = (slot + 1) & mask;
            }
        }

        // 比较 labelData[id] 与 Window 是否相等
        private boolean equals(int id, Window w) {
            int start = offsets[id];
            int len = offsets[id + 1] - start;
            if (len != (w.end - w.start)) return false;

            for (int i = 0; i < len; i++) {
                char c1 = labelData[start + i];
                char c2 = w.s.charAt(w.start + i);
                if (c2 >= 'A' && c2 <= 'Z') c2 += 32;
                if (c1 != c2) return false;
            }
            return true;
        }
    }

    // 构建期 Strategy，仅用于 Object2IntMap，逻辑需与 mixHash 兼容但 equals 区分对象
    private static class LabelStrategy implements Object2IntOpenCustomHashMap.Strategy<Object> {
        public int hashCode(Object o) {
            return o.hashCode();
        }

        public boolean equals(Object a, Object b) {
            return Objects.equals(a, b);
        }
    }

    private static final int MASK_END = 0x80000000;
    private static final int MASK_TAIL = 0x40000000;
    private static final int MASK_VAL = 0x3FFFFFFF;
    private static final int EMPTY_CHECK = -1;
    private static final char TAIL_TERMINATOR = '\uffff';
    private static final FastThreadLocal<Window> windowThreadLocal = new FastThreadLocal<Window>() {
        @Override
        protected Window initialValue() {
            return new Window();
        }
    };

    private static int mixHash(String s, int start, int end) {
        int h = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            h = 31 * h + c;
        }
        return h;
    }

    // --- DAT 核心数组 ---
    private int[] base;   // 复用：Tail 节点存 tailData 偏移量
    private int[] check;
    private char[] tailData; // 扁平化的 Tail 数据
    // --- Label 映射 (替换了 Object2IntMap) ---
    private CompactLabelMap compactLabelMap;

    // --- 构建期临时变量 (transient) ---
    private transient Object2IntMap<Object> tempBuildPool;
    private transient List<String> tempIdToLabel;
    private transient int nextLabelId = 1;

    public UltraDomainMatcher() {
    }

    public void build(Collection<String> rawDomains) {
        // 1. 预处理：标准化输入 (转小写 + 去重)
        Set<String> domains = new HashSet<>();
        for (String d : rawDomains) {
            if (d != null && !d.isEmpty()) domains.add(d.toLowerCase());
        }

        // 2. 初始化构建器
        tempBuildPool = new Object2IntOpenCustomHashMap<>(domains.size() * 2, 0.75f, new LabelStrategy());
        tempBuildPool.defaultReturnValue(-1);
        tempIdToLabel = new ArrayList<>(domains.size() * 2);
        tempIdToLabel.add(null); // 占位 0 号 ID
        InternalNode rootNode = new InternalNode();

        // 3. 构建 Trie
        for (String domain : domains) {
            String[] labels = Strings.split(domain, ".");
            InternalNode curr = rootNode;
            for (int i = labels.length - 1; i >= 0; i--) {
                String label = labels[i];
                int lid = tempBuildPool.getInt(label);
                if (lid == -1) {
                    lid = nextLabelId++;
                    tempBuildPool.put(label, lid);
                    tempIdToLabel.add(label);
                }
                curr = curr.children.computeIfAbsent(lid, k -> new InternalNode());
            }
            curr.isEnd = true;
        }

        // 4. 固化 CompactLabelMap
        this.compactLabelMap = new CompactLabelMap(tempBuildPool, tempIdToLabel);

        // 5. 初始化 DAT 数组
        int size = (int) (tempBuildPool.size() * 1.5) + 65536;
        resizeArrays(size);
        tailData = new char[size * 4];
        int tailCursor = 0;

        // 6. DAT BFS 构建
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
                if (childIdx >= base.length) resizeArrays(childIdx);

                InternalNode childNode = parentNode.children.get(lid);

                if (childNode.children.size() == 1 && !childNode.isEnd) {
                    // Tail 压缩
                    int startOffset = tailCursor;
                    // 写入 tailData
                    InternalNode temp = childNode;
                    boolean first = true;
                    while (temp.children.size() == 1 && !temp.isEnd) {
                        int nextLid = temp.children.keySet().iterator().next();
                        String labelStr = tempIdToLabel.get(nextLid);

                        // 扩容 tailData
                        if (tailCursor + labelStr.length() + 2 >= tailData.length) {
                            tailData = Arrays.copyOf(tailData, tailData.length * 2);
                        }
                        if (!first) tailData[tailCursor++] = '.';
                        // 倒序写入
                        for (int k = labelStr.length() - 1; k >= 0; k--) {
                            tailData[tailCursor++] = labelStr.charAt(k);
                        }
                        temp = temp.children.get(nextLid);
                        first = false;
                    }
                    tailData[tailCursor++] = TAIL_TERMINATOR;

                    base[childIdx] = startOffset; // Base 复用为 Offset
                    int checkVal = parentIdx | MASK_TAIL;
                    if (temp.isEnd) checkVal |= MASK_END;
                    check[childIdx] = checkVal;
                } else {
                    int checkVal = parentIdx;
                    if (childNode.isEnd) checkVal |= MASK_END;
                    check[childIdx] = checkVal;
                    pq.add(new NodePlan(childIdx, childNode));
                }
                if (childIdx >= posCursor) posCursor = childIdx + 1;
            }
        }

        // 7. 清理
        tempBuildPool = null;
        tempIdToLabel = null;
        nextLabelId = 1;
        // 收缩 tailData
        tailData = Arrays.copyOf(tailData, tailCursor);
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
                        // 使用 CompactLabelMap 进行零拷贝查询
                        int lid = compactLabelMap.getInt(w);
                        if (lid == -1) return false;

                        int next = base[curr] + lid;
                        if (next >= check.length || (check[next] & MASK_VAL) != curr) return false;

                        if ((check[next] & MASK_TAIL) != 0) {
                            return matchTail(domain, i, base[next]); // base[next] is tail offset
                        }
                        curr = next;
                        if ((check[curr] & MASK_END) != 0) return true;
                    }
                    end = i;
                }
            }
            return false;
        } finally {
            w.set(null, 0, 0);
        }
    }

    private boolean matchTail(String domain, int dotPos, int tailOffset) {
        int cursor = dotPos - 1;
        int tPtr = tailOffset;
        while (true) {
            char tc = tailData[tPtr];
            if (tc == TAIL_TERMINATOR) {
                return cursor == -1 || domain.charAt(cursor) == '.';
            }
            if (cursor < 0) return false;
            char dc = domain.charAt(cursor);
            if (dc >= 'A' && dc <= 'Z') dc += 32;
            if (dc != tc) return false;
            cursor--;
            tPtr++;
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
        }
    }

    private void resizeArrays(int minSize) {
        int oldSize = base == null ? 0 : base.length;
        int newSize = Math.max(minSize + 1, oldSize + (oldSize >> 1));
        if (newSize < minSize) newSize = minSize + 1024;
        base = base == null ? new int[newSize] : Arrays.copyOf(base, newSize);
        check = check == null ? new int[newSize] : Arrays.copyOf(check, newSize);
        if (oldSize < newSize) Arrays.fill(check, oldSize, newSize, EMPTY_CHECK);
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

        @Override
        public int hashCode() {
            return mixHash(s, start, end);
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
}