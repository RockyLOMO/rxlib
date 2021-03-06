package org.rx.net.socks;

import lombok.Data;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class SocksUser implements Serializable {
    private static final long serialVersionUID = 7845976131633777320L;

    public static final SocksUser ANONYMOUS = new SocksUser("anonymous");

    private final String username;
    private String password;
    private final Map<InetAddress, AtomicInteger> loginIps = new ConcurrentHashMap<>();
    private final AtomicLong totalReadBytes = new AtomicLong();
    private final AtomicLong totalWriteBytes = new AtomicLong();

    public boolean isAnonymous() {
        return ANONYMOUS.getUsername().equals(username);
    }
}
