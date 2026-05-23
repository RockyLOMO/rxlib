package org.rx.net.udp;

import lombok.RequiredArgsConstructor;
import org.rx.exception.InvalidException;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class UdpHolePunchRegistry {
    @RequiredArgsConstructor
    static final class Snapshot {
        final InetSocketAddress observedEndpoint;
        final String peerId;
        final InetSocketAddress peerEndpoint;
        final boolean waiting;
        final long expireAt;
    }

    @RequiredArgsConstructor
    static final class PeerState {
        final String peerId;
        volatile InetSocketAddress observedEndpoint;
        volatile long expireAt;
    }

    static final class RoomState {
        final ConcurrentMap<String, PeerState> peers = new ConcurrentHashMap<>();
    }

    private final ConcurrentMap<String, RoomState> rooms = new ConcurrentHashMap<>();
    private final int sessionTtlMillis;
    private final int maxPeersPerRoom;

    UdpHolePunchRegistry(int sessionTtlMillis, int maxPeersPerRoom) {
        if (sessionTtlMillis <= 0) {
            throw new InvalidException("sessionTtlMillis <= 0");
        }
        if (maxPeersPerRoom <= 1) {
            throw new InvalidException("maxPeersPerRoom <= 1");
        }
        this.sessionTtlMillis = sessionTtlMillis;
        this.maxPeersPerRoom = maxPeersPerRoom;
    }

    Snapshot register(String roomId, String peerId, InetSocketAddress observedEndpoint, long now) {
        String normalizedRoomId = requireText(roomId, "roomId");
        String normalizedPeerId = requireText(peerId, "peerId");
        if (observedEndpoint == null) {
            throw new InvalidException("observedEndpoint is null");
        }

        RoomState room = rooms.computeIfAbsent(normalizedRoomId, k -> new RoomState());
        synchronized (room) {
            cleanupRoom(room, now);

            PeerState self = room.peers.get(normalizedPeerId);
            if (self == null) {
                if (room.peers.size() >= maxPeersPerRoom) {
                    throw new InvalidException("Room {} is full {}", normalizedRoomId, maxPeersPerRoom);
                }
                self = new PeerState(normalizedPeerId);
                room.peers.put(normalizedPeerId, self);
            }
            self.observedEndpoint = observedEndpoint;
            self.expireAt = now + sessionTtlMillis;

            PeerState peer = null;
            for (PeerState candidate : room.peers.values()) {
                if (!normalizedPeerId.equals(candidate.peerId) && candidate.expireAt > now) {
                    peer = candidate;
                    break;
                }
            }
            return new Snapshot(self.observedEndpoint, peer == null ? null : peer.peerId,
                    peer == null ? null : peer.observedEndpoint, peer == null, self.expireAt);
        }
    }

    void cleanup(long now) {
        for (Map.Entry<String, RoomState> entry : rooms.entrySet()) {
            RoomState room = entry.getValue();
            synchronized (room) {
                cleanupRoom(room, now);
                if (room.peers.isEmpty()) {
                    rooms.remove(entry.getKey(), room);
                }
            }
        }
    }

    int roomCount() {
        return rooms.size();
    }

    int peerCount(String roomId) {
        RoomState room = rooms.get(roomId);
        return room == null ? 0 : room.peers.size();
    }

    private void cleanupRoom(RoomState room, long now) {
        List<String> expiredKeys = null;
        for (Map.Entry<String, PeerState> entry : room.peers.entrySet()) {
            if (entry.getValue().expireAt <= now) {
                if (expiredKeys == null) {
                    expiredKeys = new ArrayList<>();
                }
                expiredKeys.add(entry.getKey());
            }
        }
        if (expiredKeys == null) {
            return;
        }
        for (String key : expiredKeys) {
            room.peers.remove(key);
        }
    }

    private String requireText(String value, String name) {
        if (value == null) {
            throw new InvalidException("{} is null", name);
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new InvalidException("{} is empty", name);
        }
        return normalized;
    }
}
