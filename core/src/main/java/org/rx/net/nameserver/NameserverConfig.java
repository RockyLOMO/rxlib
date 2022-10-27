package org.rx.net.nameserver;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class NameserverConfig implements Serializable {
    private static final long serialVersionUID = -333728009047376209L;
    private int dnsPort = 53;
    private int dnsTtl = 60;
    private int registerPort = 854;
    private int syncPort;
    private List<String> replicaEndpoints;
}
