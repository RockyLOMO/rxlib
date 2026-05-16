package org.rx.util.rss;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.DateTime;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.net.socks.TrafficLoginInfo;
import org.rx.net.socks.TrafficUser;

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
    String username;
    // 内部转发仍复用少量 socks 用户，统计与公网 IP 限制挂在外部 SS 用户上。
    String socksUser;
    // 为空表示使用全局 socksServers；非空时只使用这些上游 id。
    List<String> socksServers;
    UserRule route;
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

    @Override
    public String getUsername() {
        return Strings.isEmpty(username) ? socksUser : username;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }
}
