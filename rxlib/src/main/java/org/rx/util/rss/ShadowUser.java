package org.rx.util.rss;

import com.alibaba.fastjson2.annotation.JSONField;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
@ToString
public class ShadowUser implements Serializable, TrafficUser {
    private static final long serialVersionUID = -3699206231570817990L;

    int ssPort;
    @JSONField(serialize = false)
    String ssPwd;
    String username;
    // 内部转发仍复用少量 socks 用户，统计与公网 IP 限制挂在外部 SS 用户上。
    String socksUser;
    int ipLimit = -1;
    DateTime lastResetTime;
    @JSONField(serialize = false)
    final Map<InetAddress, TrafficLoginInfo> loginIps = new ConcurrentHashMap<>(4);

    @JSONField(name = "loginIps")
    public Map<String, TrafficLoginInfo> getLoginIpsForJson() {
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
