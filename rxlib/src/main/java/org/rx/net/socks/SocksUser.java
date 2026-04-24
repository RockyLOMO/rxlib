package org.rx.net.socks;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.DateTime;
import org.rx.core.Linq;
import org.rx.core.Strings;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocksUser implements Serializable, TrafficUser {
    private static final long serialVersionUID = 7845976131633777320L;
    public static final SocksUser ANONYMOUS = new SocksUser();

    final String username;
    @JSONField(serialize = false)
    final Map<InetAddress, TrafficLoginInfo> loginIps = new ConcurrentHashMap<>(4);
    @JSONField(serialize = false)
    String password;
    /**
     * 默认0 = 不启用账号
     */
    int ipLimit;
    DateTime lastResetTime;

    @JSONField(name = "loginIps")
    public Map<String, TrafficLoginInfo> getLoginIpsForJson() {
        return Linq.from(loginIps.entrySet()).toMap(p -> p.getKey().getHostAddress(), Map.Entry::getValue);
    }

    @Override
    public boolean isAnonymous() {
        return Strings.hashEquals(ANONYMOUS.getUsername(), username);
    }

    public SocksUser() {
        username = "anonymous";
    }
}
