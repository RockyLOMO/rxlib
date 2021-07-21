package org.rx.net.socks;

import lombok.Data;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class SocksUser implements Serializable {
    private static final long serialVersionUID = 7845976131633777320L;

    private final String username;
    private String password;
    private final List<InetAddress> loginIps = new CopyOnWriteArrayList<>();
    private final AtomicLong totalReadBytes = new AtomicLong();
    private final AtomicLong totalWriteBytes = new AtomicLong();
}
