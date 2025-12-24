package org.rx.net.support;

import java.io.Serializable;

public class DomainTrieMatcher implements Serializable {
    private static final long serialVersionUID = -2577041860871978500L;

    static class CompactTrieNode implements Serializable{
        private static final long serialVersionUID = 1237957363773921057L;
        CompactTrieNode[] children;
        boolean isEnd;

        CompactTrieNode getOrCreate(int index) {
            if (children == null) {
                children = new CompactTrieNode[ALPHABET_SIZE];
            }
            if (children[index] == null) {
                children[index] = new CompactTrieNode();
            }
            return children[index];
        }
    }

    // 字符映射：只支持 a-z 0-9 . （37 个字符）
    static final int ALPHABET_SIZE = 37;

    private static int charToIndex(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= '0' && c <= '9') return 26 + (c - '0');
        if (c == '.') return 36;
        return -1;
    }

    final CompactTrieNode root = new CompactTrieNode();

    /**
     * 插入域名（反向插入）
     */
    public void insert(String domain) {
        CompactTrieNode node = root;
        for (int i = domain.length() - 1; i >= 0; i--) {
            char c = domain.charAt(i);
            int idx = charToIndex(c);
            if (idx < 0) return; // 非法字符，跳过
            node = node.getOrCreate(idx);
        }
        node.isEnd = true;
    }

    /**
     * 判断是否匹配（后缀匹配）
     */
    public boolean matchSuffix(String domain) {
        CompactTrieNode node = root;
        for (int i = domain.length() - 1; i >= 0; i--) {
            char c = domain.charAt(i);
            int idx = charToIndex(c);
            if (idx < 0 || node.children == null || node.children[idx] == null) {
                return false;
            }
            node = node.children[idx];
            if (node.isEnd) {
                return true;
            }
        }
        return node.isEnd;
    }
}
