package org.rx.net.support;

import java.io.Serializable;
import java.util.*;

/**
 * 方案二：高性能双数组 Trie (Double Array Trie)
 * 适用场景：百万级静态黑名单，极速查询 (O(段数))
 */
public class DomainDoubleArrayTrie implements Serializable {
    private static final long serialVersionUID = 1L;

    private int[] base;
    private int[] check;
    private boolean[] isEnd;

    private final Map<String, Integer> labelToId = new HashMap<>(64);
    private int nextLabelId = 1;

    /**
     * 构建 DAT (简化版：基于现成的 Trie 结构压缩)
     */
    public void build(List<String> domains) {
        // 1. 先构建普通的树形 Trie
        InternalNode root = new InternalNode();
        for (String domain : domains) {
            String[] labels = domain.toLowerCase().split("\\.");
            InternalNode curr = root;
            for (int i = labels.length - 1; i >= 0; i--) {
                int lid = labelToId.computeIfAbsent(labels[i], k -> nextLabelId++);
                curr = curr.children.computeIfAbsent(lid, k -> new InternalNode());
            }
            curr.isEnd = true;
        }

        // 2. 压缩为双数组 (分配空间)
        int initialSize = domains.size() * 5 + 65536;
        base = new int[initialSize];
        check = new int[initialSize];
        isEnd = new boolean[initialSize];

        // 3. 广度优先搜索并填充 base/check
        Queue<Map.Entry<Integer, InternalNode>> queue = new LinkedList<>();
        Queue<Integer> posQueue = new LinkedList<>();

        // Root 处理
        queue.add(new AbstractMap.SimpleEntry<>(0, root));
        posQueue.add(0);

        int posCursor = 1;
        while (!queue.isEmpty()) {
            Map.Entry<Integer, InternalNode> entry = queue.poll();
            InternalNode node = entry.getValue();
            int parentPos = posQueue.poll();

            if (node.children.isEmpty()) continue;

            // 寻找一个 base 偏移量，使得所有子节点 lid 都不冲突
            List<Integer> childrenLids = new ArrayList<>(node.children.keySet());
            int b = findBase(childrenLids, posCursor);
            base[parentPos] = b;

            for (int lid : childrenLids) {
                int targetPos = b + lid;
                if (targetPos >= base.length) expand(targetPos);

                check[targetPos] = parentPos;
                InternalNode childNode = node.children.get(lid);
                isEnd[targetPos] = childNode.isEnd;

                queue.add(new AbstractMap.SimpleEntry<>(lid, childNode));
                posQueue.add(targetPos);
                posCursor = Math.max(posCursor, targetPos);
            }
        }
    }

    private int findBase(List<Integer> lids, int start) {
        int b = start;
        while (true) {
            boolean conflict = false;
            for (int lid : lids) {
                int target = b + lid;
                if (target < check.length && check[target] != 0) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return b;
            b++;
        }
    }

    public boolean matchSuffix(String domain) {
        if (domain == null) return false;
        String[] labels = domain.toLowerCase().split("\\.");
        int curr = 0; // Root at 0

        for (int i = labels.length - 1; i >= 0; i--) {
            Integer lid = labelToId.get(labels[i]);
            if (lid == null) return false;

            int next = base[curr] + lid;
            if (next >= check.length || check[next] != curr) return false;

            curr = next;
            if (isEnd[curr]) return true;
        }
        return false;
    }

    private void expand(int target) {
        int newSize = Math.max(target + 1, base.length * 2);
        base = Arrays.copyOf(base, newSize);
        check = Arrays.copyOf(check, newSize);
        isEnd = Arrays.copyOf(isEnd, newSize);
    }

    static class InternalNode {
        Map<Integer, InternalNode> children = new HashMap<>();
        boolean isEnd = false;
    }
}
