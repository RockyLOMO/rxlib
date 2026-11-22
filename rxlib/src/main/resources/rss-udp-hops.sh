# Invoked by RSS on Linux. The caller validates the dedicated range as pairs.
set -eu
first=$1
last=$2
table="rxrss_hop_${first}_${last}"
# Only redirect traffic addressed to this host. This also works on kernels without nft_fib.
v4=$(ip -o -4 addr show | awk '{split($4,a,"/"); print a[1]}' | paste -sd, -)
v6=$(ip -o -6 addr show | awk '{split($4,a,"/"); print a[1]}' | paste -sd, -)
[ -n "$v4$v6" ] || exit 1
for proc in /proc/net/udp /proc/net/udp6; do
    [ -r "$proc" ] || continue
    for hex in $(awk 'NR>1 {split($2,a,":"); print a[2]}' "$proc"); do
        port=$((0x$hex))
        if [ "$port" -ge "$first" ] && [ "$port" -le "$last" ]; then
            echo "RSS UDP range overlaps active port $port" >&2
            exit 1
        fi
    done
done
# Create atomically. An existing table is never flushed or taken over.
{
    echo "create table inet $table"
    echo "add chain inet $table ingress { type nat hook prerouting priority dstnat; policy accept; }"
    echo "add chain inet $table localout { type nat hook output priority dstnat; policy accept; }"
    awk -v t="$table" -v f="$first" -v l="$last" 'BEGIN {
        printf "add map inet %s hops { type inet_service : inet_service; elements = { ", t
        for (p = f; p < l; p += 2) printf "%s%d : %d", p == f ? "" : ", ", p + 1, p
        print " }; }"
    }'
    if [ -n "$v4" ]; then echo "add rule inet $table ingress ip daddr { $v4 } meta l4proto udp redirect to :udp dport map @hops"; fi
    if [ -n "$v6" ]; then echo "add rule inet $table ingress ip6 daddr { $v6 } meta l4proto udp redirect to :udp dport map @hops"; fi
    echo "add rule inet $table localout oifname lo meta l4proto udp redirect to :udp dport map @hops"
} | nft -f -
trap 'nft delete table inet "$table"' EXIT
trap 'exit 0' HUP TERM INT
echo ready
read -r unused || :
