#!/usr/bin/env bash
# Run as the deployment identity that will launch the JVM, before replacing it.
set -euo pipefail
command -v ip >/dev/null
nft list tables >/dev/null
nft --check -f - <<'NFT'
add table inet rxrss_preflight
add chain inet rxrss_preflight ingress { type nat hook prerouting priority dstnat; }
delete table inet rxrss_preflight
NFT
# Preserve all existing reservations when writing the merged setting at runtime and boot.
file=/etc/sysctl.d/99-zz-rss-hop-ports.conf
python3 - "$file" <<'PY'
import os, pathlib, sys, tempfile
kernel = pathlib.Path("/proc/sys/net/ipv4/ip_local_reserved_ports")
ports = set(range(20000, 20128))
for chunk in kernel.read_text().strip().split(","):
    if chunk:
        bounds = list(map(int, chunk.split("-")))
        ports.update(range(bounds[0], bounds[-1]+1))
runs = []
for p in sorted(ports):
    if runs and runs[-1][1]+1 == p: runs[-1][1] = p
    else: runs.append([p,p])
value = ",".join(str(a) if a == b else f"{a}-{b}" for a,b in runs)
path = pathlib.Path(sys.argv[1])
text = "net.ipv4.ip_local_reserved_ports = " + value + "\n"
if not path.exists() or path.read_text() != text:
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as f:
        f.write(text)
    os.chmod(f.name, 0o644)
    os.replace(f.name, path)
if kernel.read_text().strip() != value: kernel.write_text(value+"\n")
PY
if command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
    dev=$(ip route show default | awk 'NR==1 {for(i=1;i<NF;i++) if ($i=="dev") print $(i+1)}')
    zone=$(firewall-cmd --get-zone-of-interface="$dev") || zone=$(firewall-cmd --get-default-zone)
    for scope in runtime permanent; do
        args=(--zone="$zone")
        if [[ $scope == permanent ]]; then args+=(--permanent); fi
        firewall-cmd "${args[@]}" --query-port=20000-20127/udp >/dev/null ||
            firewall-cmd "${args[@]}" --add-port=20000-20127/udp
    done
fi
