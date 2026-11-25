"""Classic Shadowsocks AEAD interoperability against the loopback Java test server."""
import contextlib
import hashlib
import os
import socket
import socketserver
import struct
import sys
import threading

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

METHOD, TCP_PORT, UDP_PORT = sys.argv[1:]
SALT_LEN = 16 if METHOD == "aes-128-gcm" else 32
PASSWORD = b"interop-fixture"
MD5_1 = hashlib.md5(PASSWORD).digest()
MASTER = (MD5_1 + hashlib.md5(MD5_1 + PASSWORD).digest())[:SALT_LEN]


def cipher(salt):
    key = HKDF(hashes.SHA1(), SALT_LEN, salt, b"ss-subkey").derive(MASTER)
    return ChaCha20Poly1305(key) if METHOD == "chacha20-ietf-poly1305" else AESGCM(key)


def addr(family, port):
    if family == "v4":
        host = b"\x01" + socket.inet_pton(socket.AF_INET, "127.0.0.1")
    elif family == "v6":
        host = b"\x04" + socket.inet_pton(socket.AF_INET6, "::1")
    else:
        host = b"\x03\x09localhost"
    return host + struct.pack("!H", port)


class TcpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            data = self.request.recv(65536)
            if not data:
                return
            self.request.sendall(data)


class UdpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        sock.sendto(data, self.client_address)


class Tcp6(socketserver.ThreadingTCPServer):
    address_family = socket.AF_INET6


class Udp6(socketserver.UDPServer):
    address_family = socket.AF_INET6


def echo_pair(stack, ipv4_type, ipv6_type, handler):
    ipv4 = stack.enter_context(ipv4_type(("127.0.0.1", 0), handler))
    port = ipv4.server_address[1]
    ipv6 = stack.enter_context(ipv6_type(("::1", port), handler))
    for server in (ipv4, ipv6):
        threading.Thread(target=server.serve_forever, args=(0.05,), daemon=True).start()
        stack.callback(server.shutdown)
    return port


def nonce(n):
    return n.to_bytes(12, "little")


def encode_record(tx, record_index, chunk):
    out = bytearray()
    out.extend(tx.encrypt(nonce(record_index * 2), struct.pack("!H", len(chunk)), None))
    out.extend(tx.encrypt(nonce(record_index * 2 + 1), chunk, None))
    return out


def read_tcp_reply(response, expected_len):
    salt_bytes = response.read(SALT_LEN)
    assert len(salt_bytes) == SALT_LEN
    rx = cipher(salt_bytes)
    out = bytearray()
    i = 0
    while len(out) < expected_len:
        len_chunk = response.read(18)
        assert len(len_chunk) == 18
        length = rx.decrypt(nonce(i), len_chunk, None)
        n, = struct.unpack("!H", length)
        assert n <= 0x3fff
        body = response.read(n + 16)
        assert len(body) == n + 16
        out.extend(rx.decrypt(nonce(i + 1), body, None))
        i += 2
    return bytes(out)


def tcp_roundtrip(family, echo_port):
    salt = os.urandom(SALT_LEN)
    tx = cipher(salt)
    # Exercise both halves of the 16 KiB boundary and carry the little-endian nonce past 255.
    payloads = [os.urandom(n) for n in (1, 15, 16, 17, 63, 64, 65, 16382, 16383, 16384)]
    payloads += [bytes([i]) for i in range(140)]
    expected = b"".join(payloads)
    chunks = [addr(family, echo_port)]
    for data in payloads:
        chunks.extend(data[pos:pos + 0x3fff] for pos in range(0, len(data), 0x3fff))
    wire = bytearray(salt)
    for i, chunk in enumerate(chunks):
        wire.extend(encode_record(tx, i, chunk))

    with socket.create_connection(("127.0.0.1", int(TCP_PORT)), timeout=5) as client:
        # Exercise small socket writes and then several coalesced records.
        for b in wire[:SALT_LEN + 18]:
            client.sendall(bytes([b]))
        client.sendall(wire[SALT_LEN + 18:])
        with client.makefile("rb") as response:
            out = read_tcp_reply(response, len(expected))
        assert out == expected, (family, len(out), len(expected))


def tcp_split_address_half_close_and_replay(family, echo_port):
    salt = os.urandom(SALT_LEN)
    tx = cipher(salt)
    header = addr(family, echo_port)
    expected = b"split-addr-half-close"
    # Split destination across independent AEAD records (2B prefix, remainder with no payload, 0-len chunk, payload).
    records = [header[:2], header[2:], b"", expected]
    wire = bytearray(salt)
    for i, rec in enumerate(records):
        wire.extend(encode_record(tx, i, rec))

    with socket.create_connection(("127.0.0.1", int(TCP_PORT)), timeout=5) as client:
        client.sendall(wire)
        client.shutdown(socket.SHUT_WR)
        with client.makefile("rb") as response:
            out = read_tcp_reply(response, len(expected))
            assert response.read(1) == b""
        assert out == expected, (family, out)

    # Replay the authenticated TCP salt + records onto a new connection; server must close without echoing.
    with socket.create_connection(("127.0.0.1", int(TCP_PORT)), timeout=5) as replay:
        replay.sendall(wire)
        replay.shutdown(socket.SHUT_WR)
        assert replay.recv(64) == b"", ("TCP replay was not rejected", family)


def udp_roundtrip(family, echo_port, seen_salts):
    target = ("127.0.0.1", int(UDP_PORT))
    # Each source sends distinct datagrams, including payloads near the IPv6 minimum MTU.
    for source in range(2):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.connect(target)
            client.settimeout(5)
            payloads = [os.urandom(n) for n in (1, 15, 16, 17, 63, 64, 65, 1200, 1232)]
            # Build salts before sending, then vary transmission order independently.
            datagrams = []
            for data in payloads:
                salt = os.urandom(SALT_LEN)
                assert salt not in seen_salts
                seen_salts.add(salt)
                datagrams.append(salt + cipher(salt).encrypt(nonce(0), addr(family, echo_port) + data, None))
            for datagram in reversed(datagrams):
                client.send(datagram)
                reply = client.recv(65535)
                salt = reply[:SALT_LEN]
                assert salt not in seen_salts
                seen_salts.add(salt)
                plain = cipher(salt).decrypt(nonce(0), reply[SALT_LEN:], None)
                size = {1: 5, 4: 17, 3: 2 + plain[1]}[plain[0]]
                port, = struct.unpack_from("!H", plain, size)
                assert port == echo_port
                expected = payloads.pop()
                assert plain[size + 2:] == expected, (family, source, len(expected))

            # Truncated, tampered-tag, and replayed datagrams must be dropped without breaking subsequent valid packets.
            client.send(os.urandom(SALT_LEN + 8))
            bad_salt = os.urandom(SALT_LEN)
            tampered = bytearray(bad_salt + cipher(bad_salt).encrypt(nonce(0), addr(family, echo_port) + b"tampered", None))
            tampered[-1] ^= 1
            client.send(tampered)
            client.send(datagrams[0])

            probe_salt = os.urandom(SALT_LEN)
            seen_salts.add(probe_salt)
            probe_payload = b"after-invalid-and-replay"
            client.send(probe_salt + cipher(probe_salt).encrypt(nonce(0), addr(family, echo_port) + probe_payload, None))
            reply = client.recv(65535)
            reply_salt = reply[:SALT_LEN]
            assert reply_salt not in seen_salts
            seen_salts.add(reply_salt)
            plain = cipher(reply_salt).decrypt(nonce(0), reply[SALT_LEN:], None)
            size = {1: 5, 4: 17, 3: 2 + plain[1]}[plain[0]]
            assert plain[size + 2:] == probe_payload, (family, source, plain[size + 2:])


with contextlib.ExitStack() as stack:
    tcp = echo_pair(stack, socketserver.ThreadingTCPServer, Tcp6, TcpEcho)
    udp = echo_pair(stack, socketserver.UDPServer, Udp6, UdpEcho)
    salts = set()
    for family in ("v4", "v6", "domain"):
        tcp_roundtrip(family, tcp)
        tcp_split_address_half_close_and_replay(family, tcp)
        udp_roundtrip(family, udp, salts)
    print(METHOD, "TCP 6 connections + 3 replays, UDP 60 authenticated echoes")
