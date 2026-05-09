package org.rx.net.support;

import com.github.benmanes.caffeine.cache.Cache;
import io.netty.util.NetUtil;
import org.rx.core.cache.MemoryCache;

import java.io.Closeable;
import java.util.Collections;
import java.util.Set;

import static org.rx.core.Extends.tryClose;

public final class V2RayGeoIpMatcher implements Closeable {
    private static final String NO_CODE = "";

    final V2RayGeoIpIndex index;
    final Cache<String, String> lookupCache = MemoryCache.<String, String>rootBuilder()
            .maximumSize(8192)
            .build();

    V2RayGeoIpMatcher(V2RayGeoIpIndex index) {
        this.index = index;
    }

    public boolean isEmpty() {
        return index == null || index.isEmpty();
    }

    public Set<String> codes() {
        return index == null ? Collections.<String>emptySet() : index.codes();
    }

    public boolean matches(String code, String ip) {
        byte[] ipBytes = parseIp(ip);
        return ipBytes != null && matches(code, ipBytes);
    }

    public boolean matches(String code, byte[] ipBytes) {
        return index != null && index.matches(code, ipBytes);
    }

    /**
     * 配置期预绑定 code；请求热点路径直接复用返回对象并传入已解析的 IP bytes。
     */
    public CodeMatcher matcher(String code) {
        if (index == null) {
            return null;
        }
        V2RayGeoIpIndex.CodeMatcher matcher = index.matcher(code);
        return matcher == null ? null : new CodeMatcher(matcher);
    }

    public String lookupCode(String ip) {
        String normalizedIp = GeoIPSearcher.trimAscii(ip);
        if (normalizedIp == null || normalizedIp.isEmpty()) {
            return null;
        }
        String code = lookupCache.get(normalizedIp, k -> {
            byte[] bytes = NetUtil.createByteArrayFromIpAddressString(k);
            if (bytes == null) {
                return NO_CODE;
            }
            String value = lookupCode(bytes);
            return value == null ? NO_CODE : value;
        });
        return NO_CODE.equals(code) ? null : code;
    }

    public String lookupCode(byte[] ipBytes) {
        return index == null ? null : index.lookupCode(ipBytes);
    }

    @Override
    public void close() {
        lookupCache.invalidateAll();
        tryClose(index);
    }

    private static byte[] parseIp(String ip) {
        String normalizedIp = GeoIPSearcher.trimAscii(ip);
        return normalizedIp == null || normalizedIp.isEmpty()
                ? null
                : NetUtil.createByteArrayFromIpAddressString(normalizedIp);
    }

    public static final class CodeMatcher {
        final V2RayGeoIpIndex.CodeMatcher matcher;

        CodeMatcher(V2RayGeoIpIndex.CodeMatcher matcher) {
            this.matcher = matcher;
        }

        public boolean matches(byte[] ipBytes) {
            return V2RayGeoIpIndex.isIpBytes(ipBytes) && matcher.matches(ipBytes);
        }

        public boolean matches(String ip) {
            byte[] ipBytes = parseIp(ip);
            return ipBytes != null && matcher.matches(ipBytes);
        }
    }
}
