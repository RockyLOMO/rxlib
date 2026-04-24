package org.rx.net.socks;

import lombok.Getter;
import lombok.ToString;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;

@ToString
public class DefaultSocksAuthenticator implements Authenticator {
    @Getter
    final Map<String, SocksUser> store = new ConcurrentHashMap<>();

    public DefaultSocksAuthenticator(List<SocksUser> initUsers) {
        for (SocksUser usr : initUsers) {
            store.put(usr.getUsername(), usr);
        }
    }

    @Override
    public SocksUser login(String username, String password) {
        SocksUser user = store.get(username);
        if (user == null || !eq(user.getPassword(), password)) {
            return null;
        }
        return user;
    }
}
