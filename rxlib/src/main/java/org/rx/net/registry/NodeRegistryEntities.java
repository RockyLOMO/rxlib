package org.rx.net.registry;

import lombok.Data;
import org.rx.annotation.DbColumn;

import java.io.Serializable;

/**
 * Cold-store H2 entities for {@link NodeRegistry} (3 initial tables: node, node_attribute, node_service).
 * Transient sessions, sockets, live service registrations, and plaintext secrets are NEVER stored here.
 */
public final class NodeRegistryEntities {
    private NodeRegistryEntities() {
    }

    @Data
    public static class NodeEntity implements Serializable {
        private static final long serialVersionUID = 1L;

        @DbColumn(primaryKey = true, length = 128)
        String nodeId;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 64)
        String tenant;
        /** Salted PBKDF2-HMAC-SHA256 verifier ("pbkdf2$<iter>$<saltHex>$<hashHex>"), never plaintext. */
        @DbColumn(length = 192)
        String credentialVerifier;
        @DbColumn(length = 512)
        String aclRoles;
        boolean disabled;
        @DbColumn(length = 4096)
        String metadataJson;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        long lastSeenMillis;
        long createdAtMillis;
        long updatedAtMillis;
    }

    @Data
    public static class NodeAttributeEntity implements Serializable {
        private static final long serialVersionUID = 1L;

        @DbColumn(primaryKey = true, length = 256)
        String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 128)
        String nodeId;
        @DbColumn(length = 128)
        String attrKey;
        @DbColumn(length = 2048)
        String attrValue;
        long updatedAtMillis;

        public static String idOf(String nodeId, String attrKey) {
            return nodeId + "#" + attrKey;
        }
    }

    @Data
    public static class NodeServiceEntity implements Serializable {
        private static final long serialVersionUID = 1L;

        @DbColumn(primaryKey = true, length = 384)
        String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 128)
        String nodeId;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 128)
        String appName;
        @DbColumn(length = 128)
        String instanceId;
        @DbColumn(length = 2048)
        String serviceConfigJson;
        long updatedAtMillis;

        public static String idOf(String nodeId, String appName, String instanceId) {
            return nodeId + "#" + appName + "#" + instanceId;
        }
    }
}
