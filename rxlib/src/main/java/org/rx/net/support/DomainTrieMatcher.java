package org.rx.net.support;

import org.rx.exception.InvalidException;

import java.io.Serializable;

public class DomainTrieMatcher implements Serializable {
    private static final long serialVersionUID = -2577041860871978500L;

    static class CompactTrieNode implements Serializable {
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

    // 字符映射：只支持 a-z 0-9 . -（38 个字符）
    static final int ALPHABET_SIZE = 38;

    private static int charToIndex(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= 'A' && c <= 'Z') return c - 'A'; // 处理大写
        if (c >= '0' && c <= '9') return 26 + (c - '0');
        if (c == '.') return 36;
        if (c == '-') return 37;
        throw new InvalidException("Char {} not supported", c);
    }

    final CompactTrieNode root = new CompactTrieNode();

    /**
     * 插入域名（反向插入）
     */
    public void insert(String domain) {
        CompactTrieNode node = root;
        for (int i = domain.length() - 1; i >= 0; i--) {
            int idx = charToIndex(domain.charAt(i));
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
            if (node.children == null
                    || (node = node.children[charToIndex(domain.charAt(i))]) == null) {
                return false;
            }
            if (node.isEnd) {

                if (i == 0 || domain.charAt(i - 1) == '.') {
                    return true;
                }
//                return true;
            }
        }
//        return node.isEnd;
        return false;
    }
}
