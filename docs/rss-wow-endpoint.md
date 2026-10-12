# WoW direct endpoint control API

The RSS Server publishes a selected home RSS Client's authenticated **actual TCP RPC peer IPv4**. It never forwards game traffic and never accepts an IP supplied by an HTTP caller, proxy header or client registration.

## Configuration

Server process environment (do not use CLI arguments or commit real credentials):

```text
RSS_WOW_CLIENT_ID=wow-home
RSS_WOW_WRITER_KEY=<independent random writer secret, at least 32 UTF-8 bytes>
RSS_WOW_QUERY_KEY=<different random read-only secret, at least 32 UTF-8 bytes>
```

Any missing identity/key, short key, or equal writer/query key disables the API (503). Only the selected identity and writer key may register, and the existing RSS `app.rtoken` must also validate. The read-only query key cannot register. The selected client identity is not its public IP and must remain stable across reconnections.

Home `conf.yml` additions (the deployed RSS configuration has these root fields; keep all other fields):

```yaml
wowEndpointClientId: wow-home
wowEndpointWriterKey: '<same independent server writer secret>'
wowEndpointReadyFile: /run/wow-endpoint-ready.json
wowEndpointRpcEndpoint: '202.91.34.9:9901'
ddnsJobSeconds: 15
ddnsLocalCommand: ['python3', '/srv/wow/wow-realm-wan-sync.py']
ddnsLocalCommandTimeoutSeconds: 10
publicIpEndpoints:
  - https://myip.ipip.net/json
  - https://ip.3322.net/
  - https://api.ipify.org/
  - https://checkip.amazonaws.com/
```

Do not replace the rest of the configuration with this snippet. Identity/writer/local-command fields default to empty/off; do not set them on other RSS clients. The writer and command arguments are omitted from JSON diagnostics and toString; changes to them still trigger reload. Retain them in the private YAML rather than round-tripping a redacted diagnostic JSON. Public IP endpoint arrays are cloned on initial load and live reload to the shared `app.net.publicIpEndpoints` setting.

The existing persistent event RPC connection sends an observation on session initialization/reconnection and every 10 seconds. Registration requires stateful event metadata; pool RPC connections cannot replace it. The snapshot expires after 45 seconds without an authenticated observation or immediately when its owning connection disconnects. An older still-connected session cannot replace a newer registration, and failure never falls back to an old IP. DNS and the existing 120-second whitelist job are not used to derive this address.

## Query contract

`GET /api/wow/endpoint`, registered on the existing RSS Server HTTP service, preserving its configured HTTPS/TLS behavior on port 8082. External access goes through `https://f-li.cn:9443/api/wow/endpoint` without changing existing port 443 routing. When the existing server uses the generated localhost self-signed certificate, it changes after JVM restart; the gateway must trust the new leaf or deployment must provide a persistent certificate. Never disable upstream TLS verification to carry bearer credentials.

Header: `Authorization: Bearer <read-only query secret>`.

Successful 200 JSON:

```json
{"ok":true,"ip":"58.209.44.49","auth_port":3724,"world_port":8085,"observed_at":"2026-10-12T08:00:00Z","online":true,"version":"1791792000000","ready":false}
```

`version` is an epoch string that changes when the IP changes; heartbeat observations do not change it. `observed_at` is UTC ISO 8601. `ready:false` is still a successful trusted online observation, allowing the Java DDNS job's configured local hook to update the WAN realm address first.

Missing/wrong read key returns 401. Disabled, disconnected or expired state returns 503 with `{"ok":false,"online":false,"error":"disabled|offline"}` and **no IP**. Non-GET methods return 405. Every API response uses `Cache-Control: no-store`. Local, loopback, CGNAT, link-local, documentation, benchmark, multicast and reserved IPv4 are not published; IPv6 is unsupported in this version. The observed peer must represent the home's direct public connection; an intermediate egress proxy would publish that proxy's IP instead.

## Readiness acknowledgement

The root-owned `/srv/wow/wow-realm-wan-sync.py` local hook runs under the existing Java DDNS job (15-second interval, no separate timer). It consumes `RSS_DDNS_IPV4` from Java, applies that trusted observed IP and atomically writes a public-readable file only after Authserver's realm cache refresh is mature:

```json
{"ip":"58.209.44.49","ready":true,"updated_at":1791792000}
```

`updated_at` must be an integer Unix timestamp in seconds, no more than 30 seconds old and not in the future. The file is read on the RPC worker, capped at 4096 bytes. Missing, malformed, stale or mismatched acknowledgement produces `ready:false`; it cannot change the registered peer IP. Every IP change initially forces `ready:false`; a subsequent observation may acknowledge the matching IP. This code does not modify the realm database or create the readiness file.

## Existing timing and operational checks

DDNS uses the same selected persistent RPC connection first: `getObservedPublicIp(WowEndpointObservation)` authenticates all three credentials, derives this call's real peer IPv4, refreshes the registry and preserves readiness. It never uses a pool socket or returns an old cached peer. If this RPC is unavailable, DDNS falls back to `Sockets.getPublicIp(false)`: ordered independent HTTPS sources must provide two matching public IPv4 observations; domestic agreement stops before overseas fallback. Only the explicit `myip.ipip.net/json` endpoint parses `data.ip`; others require the entire trimmed body to be one IPv4 literal. Error pages, IPv6 and private addresses are rejected. A dedicated client verifies CA and HTTPS hostname, uses an IPv4-only Netty DNS resolver and NO_PROXY; no global network or JVM properties are changed. The parameterless `Sockets.getPublicIp()` delegates to the cached overload and retains its 120-second TTL. Provider traffic keeps its actual pre-existing default route; the previously unused `ddnsApiProxy` field is not interpreted as a new route.

The DDNS worker is non-reentrant. It checks every configured `ddnsJobSeconds`, writes the provider only after initial observation, an IP/configuration change or a prior failed update, and retains successful per-zone updates when another zone fails. Provider replies require HTTP 2xx and JSON `code:200`; records outside configured A names keep their previous values and TTL remains 300. Business result format follows the [official Dynadot REST documentation](https://www.dynadot.com/domain/api-document); production continues to use its existing v1 URL.

Every observation runs a configured local command regardless of provider success. Environment: `RSS_DDNS_IPV4`, `RSS_DDNS_CHANGED`, `RSS_DDNS_PROVIDER_UPDATED`, `RSS_DDNS_PROVIDER_SUCCESS`, `RSS_DDNS_OBSERVED_SUCCESS` (booleans are `1`/`0`). When both RPC and independent HTTPS observation fail, IPv4 is empty and observed-success is 0, allowing the helper to clear readiness. No command arguments or child stdout/stderr are logged. Commands are ProcessBuilder argument arrays without shell concatenation. Timeout defaults to 10 seconds, clamped to 1–60. Java 8 Linux uses `/usr/bin/setsid` and kills only the verified new child process group on timeout; commands must run in the foreground and must not daemonize into unrelated sessions. Java 9+ Windows uses taskkill on the current live child PID; Java 8 Windows refuses execution because it has no safe supported child PID API. The extension does not have its own timer.

- TCP stateful heartbeat timeout: 60 seconds; writer-idle ping interval: 30 seconds. Authenticated observer RPCs every 10 seconds serve as freshness heartbeats even when continuous requests suppress idle pings.
- Disconnect reconnect: first attempt after 1000 ms; failed attempts back off from 100 ms, doubling to a 5000 ms cap. Connect wait default: 4000 ms. These are transport defaults, not a guaranteed end-to-end public-IP detection deadline.
- `rpcAutoWhiteListSeconds`: default 120. `ddnsJobSeconds`: default 0 (disabled unless positive with domains). Neither controls this observer.
- Metrics: snapshot age, 200/401/503 rate, IP/ready transitions, stateful connection/reconnect count, EventLoop latency, heap/direct-buffer memory, and existing RSS queue/backpressure counters. No key or credential payload should appear in logs.

Validation commands (PowerShell, quote dotted Maven properties):

```powershell
mvn -pl rxlib -am '-DskipTests=false' '-Dmaven.test.skip=false' '-Dtest=WowEndpointTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn '-DskipTests=true' package
```

The tests exercise TTL/offline fail-closed behavior, selected identity and key separation, readiness matching, IP epochs, two concurrent sessions, HTTP forwarded-header spoofing, real persistent RPC disconnect/reconnect, pool isolation, and credential-safe text/Fury serialization. Production TLS and WAN game login require deployment verification separately.

The CI server startup script optionally reads `/home/rss-svr/wow-endpoint.env` only when it is a root-owned regular non-symlink file with mode 600. No file preserves the prior startup behavior. Optional `RSS_WOW_HTTP_CERTIFICATE_PATH` supplies a persistent certificate through a quoted Java launcher option without changing existing startup commands or CI triggers. No real credentials are committed in this example.
