package org.rx.net.punch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.exception.InvalidException;

import java.io.Serializable;
import java.net.InetSocketAddress;

final class UdpHolePunchPackets {
    private UdpHolePunchPackets() {
    }

    @Getter
    @RequiredArgsConstructor
    static final class RendezvousRequest implements Serializable {
        private static final long serialVersionUID = 2918721018935141634L;

        final String roomId;
        final String peerId;
    }

    @Getter
    @RequiredArgsConstructor
    static final class RendezvousResponse implements Serializable {
        private static final long serialVersionUID = -1390996975991551603L;

        final String observedHost;
        final int observedPort;
        final String peerId;
        final String peerHost;
        final int peerPort;
        final boolean waiting;
        final long expireAt;

        InetSocketAddress observedEndpoint() {
            return endpoint(observedHost, observedPort);
        }

        InetSocketAddress peerEndpoint() {
            return peerHost == null ? null : endpoint(peerHost, peerPort);
        }

        private InetSocketAddress endpoint(String host, int port) {
            if (host == null || host.isEmpty()) {
                throw new InvalidException("endpoint.host is empty");
            }
            return new InetSocketAddress(host, port);
        }
    }

    @Getter
    @RequiredArgsConstructor
    static final class DirectProbe implements Serializable {
        private static final long serialVersionUID = 6717784673460875169L;

        final String roomId;
        final String peerId;
    }

    @Getter
    @RequiredArgsConstructor
    static final class DirectProbeAck implements Serializable {
        private static final long serialVersionUID = -3064086035916280894L;

        final String roomId;
        final String peerId;
    }
}
