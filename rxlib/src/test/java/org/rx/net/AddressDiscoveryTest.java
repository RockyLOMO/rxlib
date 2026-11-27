package org.rx.net;

import org.junit.jupiter.api.Test;
import org.rx.net.AddressDiscovery.InterfaceSnapshot;
import org.rx.net.AddressDiscovery.LocalDiscoveryPolicy;
import org.rx.net.EndpointCandidate.AddressFamily;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.EndpointCandidate.Protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AddressDiscoveryTest {

    @Test
    void localDiscoveryFiltersLoopbackDownVirtualLinkLocalAndBoundsIpv6() throws Exception {
        AtomicLong clock = new AtomicLong(10_000L);
        List<InterfaceSnapshot> nics = Arrays.asList(
                new InterfaceSnapshot("lo", true, true, false, false,
                        Collections.singletonList(InetAddress.getByName("127.0.0.1"))),
                new InterfaceSnapshot("eth-down", false, false, false, false,
                        Collections.singletonList(InetAddress.getByName("192.168.1.99"))),
                new InterfaceSnapshot("docker0", true, false, false, false,
                        Collections.singletonList(InetAddress.getByName("172.17.0.1"))),
                new InterfaceSnapshot("veth123", true, false, true, false,
                        Collections.singletonList(InetAddress.getByName("10.0.0.99"))),
                new InterfaceSnapshot("eth0", true, false, false, false, Arrays.asList(
                        InetAddress.getByName("192.168.10.20"),
                        InetAddress.getByName("192.168.10.20"), // duplicate
                        InetAddress.getByName("169.254.1.2"),   // link-local
                        InetAddress.getByName("fe80::1"),       // IPv6 link-local
                        InetAddress.getByName("fd00::1"),       // ULA (excluded by default)
                        InetAddress.getByName("2001:db8::1"),   // documentation IPv6 (excluded)
                        InetAddress.getByName("2606:4700::1"),  // global IPv6 #1
                        InetAddress.getByName("2606:4700::2"),  // global IPv6 #2
                        InetAddress.getByName("2606:4700::3"),  // exceeds maxIpv6PerInterface=2
                        InetAddress.getByName("1.1.1.1")        // global IPv4 -> HOST
                ))
        );

        AddressDiscovery discovery = new AddressDiscovery(clock::get, () -> nics, eps -> "203.0.113.5");
        LocalDiscoveryPolicy policy = new LocalDiscoveryPolicy();
        policy.setMaxCandidates(16);
        policy.setMaxIpv6PerInterface(2);

        List<EndpointCandidate> list = discovery.discoverLocal(8443, Protocol.UDP, policy);
        assertEquals(4, list.size(), "Expected 1 LAN IPv4 + 1 HOST IPv4 + 2 GLOBAL_V6: " + list);
        assertEquals(CandidateType.LAN, list.get(0).getCandidateType());
        assertEquals("192.168.10.20", list.get(0).getIp());
        assertEquals(8443, list.get(0).getPort());
        assertEquals(Protocol.UDP, list.get(0).getProtocol());

        assertEquals(CandidateType.HOST, list.get(1).getCandidateType());
        assertEquals("1.1.1.1", list.get(1).getIp());

        assertEquals(CandidateType.GLOBAL_V6, list.get(2).getCandidateType());
        assertEquals(AddressFamily.IPV6, list.get(2).getAddressFamily());
        assertEquals(CandidateType.GLOBAL_V6, list.get(3).getCandidateType());

        // Enabling ULA + virtual includes them within maxCandidates cap
        policy.setIncludeVirtual(true);
        policy.setIncludeUniqueLocalIpv6(true);
        policy.setMaxIpv6PerInterface(4);
        policy.setMaxCandidates(5);
        List<EndpointCandidate> expanded = discovery.discoverLocal(8443, Protocol.UDP, policy);
        assertEquals(5, expanded.size(), "Must honor maxCandidates cap");
    }

    @Test
    void observationDomainsStaySeparateAndRespectTtlAndInvalidation() throws Exception {
        AtomicLong clock = new AtomicLong(100_000L);
        AtomicInteger httpsCalls = new AtomicInteger();
        AddressDiscovery discovery = new AddressDiscovery(clock::get, Collections::emptyList, eps -> {
            httpsCalls.incrementAndGet();
            return "198.51.100." + httpsCalls.get();
        });

        EndpointCandidate httpsCand = discovery.observeHttpsPublicIpv4("wan0", new String[]{"https://a.invalid"}, 5000L, true);
        assertEquals(CandidateType.HTTPS_PUBLIC, httpsCand.getCandidateType());
        assertEquals("198.51.100.1", httpsCand.getIp());
        assertEquals(0, httpsCand.getPort());
        assertNull(discovery.getObserved("wan0", CandidateType.TCP_OBSERVED), "HTTPS public IP must NOT populate TCP_OBSERVED");
        assertNull(discovery.getObserved("wan0", CandidateType.UDP_MAPPED), "HTTPS public IP must NOT populate UDP_MAPPED");

        // Cache hit within TTL
        assertEquals("198.51.100.1", discovery.observeHttpsPublicIpv4("wan0", new String[]{"https://a.invalid"}, 5000L, true).getIp());
        assertEquals(1, httpsCalls.get());

        // Record distinct TCP_OBSERVED and UDP_MAPPED endpoints on same scope
        discovery.recordTcpObserved("wan0", new InetSocketAddress(InetAddress.getByName("198.51.100.1"), 44123), "ns-reg", 5000L);
        discovery.recordUdpMapped("wan0", new InetSocketAddress(InetAddress.getByName("198.51.100.1"), 55123), "udp-probe", 5000L, true);

        List<EndpointCandidate> all = discovery.collectCandidates("wan0", 0, Protocol.ANY, null, 16);
        assertEquals(3, all.size());
        assertEquals(CandidateType.UDP_MAPPED, all.get(0).getCandidateType());
        assertEquals(55123, all.get(0).getPort());
        assertEquals(CandidateType.TCP_OBSERVED, all.get(1).getCandidateType());
        assertEquals(44123, all.get(1).getPort());
        assertEquals(CandidateType.HTTPS_PUBLIC, all.get(2).getCandidateType());

        // Invalidate only UDP_MAPPED -> TCP & HTTPS survive
        discovery.invalidate("wan0", CandidateType.UDP_MAPPED);
        assertNull(discovery.getObserved("wan0", CandidateType.UDP_MAPPED));
        assertNotNull(discovery.getObserved("wan0", CandidateType.TCP_OBSERVED));
        assertNotNull(discovery.getObserved("wan0", CandidateType.HTTPS_PUBLIC));

        // Advance past TTL -> entries expire and HTTPS refreshes
        clock.addAndGet(6000L);
        assertNull(discovery.getObserved("wan0", CandidateType.TCP_OBSERVED));
        assertEquals("198.51.100.2", discovery.observeHttpsPublicIpv4("wan0", new String[]{"https://a.invalid"}, 5000L, true).getIp());
        assertEquals(2, httpsCalls.get());
    }
}
