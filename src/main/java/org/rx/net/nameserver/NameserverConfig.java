package org.rx.net.nameserver;

import lombok.Data;

import java.util.List;

@Data
public class NameserverConfig {
    private int dnsPort = 53;
    private int dnsTtl = 60;
    private int registerPort = 854;
    private List<String> replicaEndpoints;
}
