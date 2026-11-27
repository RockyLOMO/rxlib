package org.rx.util.rss;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.DateTime;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.net.BandwidthLimitConfig;
import org.rx.net.socks.TrafficLoginInfo;
import org.rx.net.socks.TrafficUser;
import org.rx.net.socks.encryption.CipherKind;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
@ToString
public class ShadowUser implements Serializable, TrafficUser {
    private static final long serialVersionUID = -3699206231570817990L;

    int ssPort;
    @ToString.Exclude
    String ssPwd;
    /** Empty keeps aes-256-gcm. Resolved when the SS listener is created, not per packet. */
    String ssCipher;
    String username;
    // 内部转发仍复用少量 socks 用户，统计与公网 IP 限制挂在外部 SS 用户上。
    String socksUser;
    RssTransport transport;
    // 为空表示使用全局 socksServers；非空时只使用这些上游 id。
    List<String> socksServers;
    UserRule route;
    BandwidthLimitConfig bandwidth;
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    @JSONField(serialize = false, deserialize = false)
    @ToString.Exclude
    transient UserRuleMatcher routeMatcher;
    int ipLimit = -1;
    DateTime lastResetTime;
    final Map<InetAddress, TrafficLoginInfo> loginIps = new ConcurrentHashMap<>(4);

    public Map<String, TrafficLoginInfo> snapshotLoginIps() {
        return Linq.from(loginIps.entrySet()).toMap(p -> p.getKey().getHostAddress(), Map.Entry::getValue);
    }

    String cipherName() {
        String name = ssCipher;
        if (name == null || (name = name.trim()).isEmpty()) {
            return CipherKind.AES_256_GCM.getCipherName();
        }
        return CipherKind.canonical(name);
    }

    @Override
    public String getUsername() {
        return Strings.isEmpty(username) ? socksUser : username;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }
}
