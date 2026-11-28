package org.rx.net.nameserver;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@ToString(exclude = {"replicaAuthToken"})
public class NameserverConfig implements Serializable {
    private static final long serialVersionUID = -333728009047376209L;
    private int dnsPort = 53;
    private int dnsTtl = 60;
    private int registerPort = 854;
    /** Optional stable node identifier for replication source ownership; defaults to RxConfig appId + registerPort. */
    private String nodeId;
    private long replicaFullSyncPeriodMillis = 60_000L;
    private List<String> replicaEndpoints;
    /** Shared secret for HMAC authentication between Nameserver peers; falls back to RxConfig.INSTANCE.getRtoken(). */
    private String replicaAuthToken;
    /** Optional allowlist of authorized peer sourceIds or IP addresses; empty allows any peer presenting valid auth token. */
    private Set<String> authorizedReplicaPeers;
    private int replicaSyncTimeoutMillis = 5_000;
    private int replicaMaxSnapshotPageSize = 256;
    private int replicaMaxSnapshotPages = 64;
    private int replicaMaxPeers = 64;
    private int replicaMaxPendingDeltasPerPeer = 256;
    private int replicaMaxTombstonesPerSource = 4096;
    private long replicaTombstoneTtlMillis = 300_000L;
    /** Remote replica source partition lease expiry; 0 disables expiry. */
    private long replicaSourceLeaseMillis = 180_000L;
    private long replicaLeaseSweepPeriodMillis = 30_000L;
    private int replicaMaxInstancesPerSource = 4096;
    private int replicaMaxRemoteSources = 64;
    private long replicaRetryBaseMillis = 500L;
    private long replicaRetryMaxMillis = 15_000L;
}
