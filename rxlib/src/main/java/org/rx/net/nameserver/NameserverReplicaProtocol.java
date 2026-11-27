package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializable peer-to-peer replication DTOs and HMAC-SHA256 authentication helpers for Nameserver RPC sync.
 */
public final class NameserverReplicaProtocol {
    private static final byte[] DELTA_AUTH_DOMAIN = "rns-replica-delta-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SNAPSHOT_AUTH_DOMAIN = "rns-replica-snapshot-v1".getBytes(StandardCharsets.UTF_8);

    private NameserverReplicaProtocol() {
    }

    public enum DeltaOp {
        UPSERT_INSTANCE,
        REMOVE_INSTANCE,
        SET_APP_ATTR,
        SYNC_ENDPOINTS
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class ReplicaRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        private String appName;
        private String instanceId;
        private InetAddress address;
        private int weight;
        private Map<String, Serializable> attributes;

        public ReplicaRecord(String appName, String instanceId, InetAddress address, int weight, Map<String, Serializable> attributes) {
            this.appName = appName;
            this.instanceId = instanceId;
            this.address = address;
            this.weight = weight;
            this.attributes = attributes == null ? new HashMap<String, Serializable>() : new HashMap<String, Serializable>(attributes);
        }

        public ReplicaRecord copy() {
            return new ReplicaRecord(appName, instanceId, address, weight, attributes);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class ReplicaTombstone implements Serializable {
        private static final long serialVersionUID = 1L;
        private String appName;
        private String instanceId;
        private long sourceEpoch;
        private long version;
        private long expiresAtMillis;

        public ReplicaTombstone(String appName, String instanceId, long sourceEpoch, long version, long expiresAtMillis) {
            this.appName = appName;
            this.instanceId = instanceId;
            this.sourceEpoch = sourceEpoch;
            this.version = version;
            this.expiresAtMillis = expiresAtMillis;
        }

        public boolean isExpired(long nowMillis) {
            return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString(exclude = {"authTag"})
    public static class ReplicaDeltaRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String sourceId;
        private long sourceEpoch;
        private long version;
        private long baseVersion;
        private long nonce;
        private byte[] authTag;
        private DeltaOp op;
        private ReplicaRecord record;
        private String appName;
        private String instanceId;
        private String attrKey;
        private Serializable attrValue;
        private Set<InetSocketAddress> serverEndpoints;

        public static ReplicaDeltaRequest upsert(String sourceId, long sourceEpoch, long version, long baseVersion,
                                                 long nonce, ReplicaRecord record) {
            ReplicaDeltaRequest req = new ReplicaDeltaRequest();
            req.sourceId = sourceId;
            req.sourceEpoch = sourceEpoch;
            req.version = version;
            req.baseVersion = baseVersion;
            req.nonce = nonce;
            req.op = DeltaOp.UPSERT_INSTANCE;
            req.record = record;
            return req;
        }

        public static ReplicaDeltaRequest remove(String sourceId, long sourceEpoch, long version, long baseVersion,
                                                 long nonce, String appName, String instanceId) {
            ReplicaDeltaRequest req = new ReplicaDeltaRequest();
            req.sourceId = sourceId;
            req.sourceEpoch = sourceEpoch;
            req.version = version;
            req.baseVersion = baseVersion;
            req.nonce = nonce;
            req.op = DeltaOp.REMOVE_INSTANCE;
            req.appName = appName;
            req.instanceId = instanceId;
            return req;
        }

        public static ReplicaDeltaRequest setAppAttr(String sourceId, long sourceEpoch, long version, long baseVersion,
                                                     long nonce, String appName, String key, Serializable value) {
            ReplicaDeltaRequest req = new ReplicaDeltaRequest();
            req.sourceId = sourceId;
            req.sourceEpoch = sourceEpoch;
            req.version = version;
            req.baseVersion = baseVersion;
            req.nonce = nonce;
            req.op = DeltaOp.SET_APP_ATTR;
            req.appName = appName;
            req.attrKey = key;
            req.attrValue = value;
            return req;
        }

        public static ReplicaDeltaRequest syncEndpoints(String sourceId, long sourceEpoch, long version, long baseVersion,
                                                        long nonce, Set<InetSocketAddress> endpoints) {
            ReplicaDeltaRequest req = new ReplicaDeltaRequest();
            req.sourceId = sourceId;
            req.sourceEpoch = sourceEpoch;
            req.version = version;
            req.baseVersion = baseVersion;
            req.nonce = nonce;
            req.op = DeltaOp.SYNC_ENDPOINTS;
            req.serverEndpoints = endpoints == null ? new HashSet<InetSocketAddress>() : new HashSet<InetSocketAddress>(endpoints);
            return req;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString(exclude = {"authTag"})
    public static class ReplicaSnapshotPage implements Serializable {
        private static final long serialVersionUID = 1L;
        private String sourceId;
        private long sourceEpoch;
        private long version;
        private long snapshotId;
        private int pageIndex;
        private int totalPages;
        private long nonce;
        private byte[] authTag;
        private List<ReplicaRecord> records = new ArrayList<ReplicaRecord>();
        private List<ReplicaTombstone> tombstones;
        private Map<String, Map<String, Serializable>> appAttrs;
        private Set<InetSocketAddress> serverEndpoints;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class ReplicaAck implements Serializable {
        private static final long serialVersionUID = 1L;
        private boolean accepted;
        private boolean needFullSync;
        private long appliedEpoch;
        private long appliedVersion;

        public ReplicaAck(boolean accepted, boolean needFullSync, long appliedEpoch, long appliedVersion) {
            this.accepted = accepted;
            this.needFullSync = needFullSync;
            this.appliedEpoch = appliedEpoch;
            this.appliedVersion = appliedVersion;
        }
    }

    public static byte[] signDelta(String token, ReplicaDeltaRequest req) {
        if (token == null || token.isEmpty() || req == null) {
            return null;
        }
        return hmac(token, DELTA_AUTH_DOMAIN, req.getSourceId(), req.getSourceEpoch(), req.getVersion(),
                req.getBaseVersion(), req.getNonce(), req.getOp() == null ? -1 : req.getOp().ordinal(), 0, 0);
    }

    public static boolean verifyDelta(String token, ReplicaDeltaRequest req) {
        if (token == null || token.isEmpty() || req == null || req.getAuthTag() == null) {
            return false;
        }
        byte[] expected = signDelta(token, req);
        return expected != null && MessageDigest.isEqual(expected, req.getAuthTag());
    }

    public static byte[] signSnapshotPage(String token, ReplicaSnapshotPage page) {
        if (token == null || token.isEmpty() || page == null) {
            return null;
        }
        return hmac(token, SNAPSHOT_AUTH_DOMAIN, page.getSourceId(), page.getSourceEpoch(), page.getVersion(),
                page.getSnapshotId(), page.getNonce(), page.getPageIndex(), page.getTotalPages(),
                page.getRecords() == null ? 0 : page.getRecords().size());
    }

    public static boolean verifySnapshotPage(String token, ReplicaSnapshotPage page) {
        if (token == null || token.isEmpty() || page == null || page.getAuthTag() == null) {
            return false;
        }
        byte[] expected = signSnapshotPage(token, page);
        return expected != null && MessageDigest.isEqual(expected, page.getAuthTag());
    }

    private static byte[] hmac(String token, byte[] domain, String sourceId, long epoch, long version,
                               long extraLong, long nonce, int a, int b, int c) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(domain);
            byte[] src = sourceId == null ? new byte[0] : sourceId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer bb = ByteBuffer.allocate(4 + 8 * 4 + 4 * 3);
            bb.putInt(src.length);
            bb.putLong(epoch);
            bb.putLong(version);
            bb.putLong(extraLong);
            bb.putLong(nonce);
            bb.putInt(a);
            bb.putInt(b);
            bb.putInt(c);
            mac.update(bb.array());
            mac.update(src);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute Nameserver replica HMAC", e);
        }
    }
}
