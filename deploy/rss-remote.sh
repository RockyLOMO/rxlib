#!/usr/bin/env bash
set -euo pipefail
umask 077
stage=$(cd "$(dirname "$0")" && pwd)
cd "$stage/.."
role=$1 mode=$2 fallback=$3 digest=$4
log="$stage/deploy.log"
exec 2>>"$log"
# Serialize this host's file switch with other RSS Actions runs.
exec 9>.rss-publish.lock
flock -w 60 9
run_private() { "$@" >>"$log" 2>&1 9>&-; }
recover() {
    if [[ -f "$stage/previous-conf.yml" ]]; then
        cp -p "$stage/previous-conf.yml" "$stage/restore-conf.yml"
        mv "$stage/restore-conf.yml" conf.yml
    fi
    if [[ -s "$stage/previous-link" ]]; then
        ln -s "$(cat "$stage/previous-link")" "$stage/restore-link"
        mv -Tf "$stage/restore-link" transport
    elif [[ -L transport && $(readlink transport) == "$stage/keys" ]]; then
        unlink transport
    fi
    for file in start.sh rollback.sh; do
        cp -p "$stage/previous-$file" "$file"
    done
    if [[ "$mode" == replace && -f app.jar && $(stat -c '%d:%i' app.jar) == "$(cat "$stage/new-inode")" ]]; then
        run_private bash rollback.sh
    fi
    echo 'Previous configuration restored; checked whether JAR rollback was required.'
}
if [[ ${5:-} == recover ]]; then recover; exit; fi
printf '%s  %s\n' "$digest" "$stage/app.jar" | sha256sum --check --status
[[ ! -e transport || -L transport ]] || { echo 'transport exists and is not a release link'; exit 1; }
run_private bash "$stage/rss-java21.sh"
if [[ $role == server ]]; then
    run_private bash "$stage/rss-hops.sh" || { echo "UDP preflight failed. Inspect $log"; exit 1; }
    openssl x509 -in "$stage/keys/cert.pem" -noout -checkend 86400 >/dev/null
    cmp <(openssl x509 -in "$stage/keys/cert.pem" -pubkey -noout) <(openssl pkey -in "$stage/keys/key.pem" -pubout)
else
    openssl x509 -in "$stage/keys/ca.pem" -checkend 86400 -noout >/dev/null
fi
for file in start.sh rollback.sh; do cp -p "$file" "$stage/previous-$file"; done
readlink transport >"$stage/previous-link" || true
# Record inode identity without keeping a second 90 MB JAR after deployment.
new_inode=$(stat -c '%d:%i' "$stage/app.jar")
printf '%s\n' "$new_inode" > "$stage/new-inode"
trap 'rc=$?; line=$LINENO; trap - ERR; echo "Publish failed at line $line (status $rc). Inspect $log"; recover; exit "$rc"' ERR
if [[ $role == client ]]; then
    # Migrate the existing 6898 opt-in once; preserve the operator's YAML layout.
    if grep -q '^[[:space:]]*adaptiveTcp:' conf.yml; then
        cp -p conf.yml "$stage/previous-conf.yml"
        run_private python3 - "$stage" <<'PY'
from pathlib import Path
import re, sys, yaml
conf = Path("conf.yml")
text = conf.read_text()
def entry(s):
    users = yaml.safe_load(s)["shadowUsers"]
    user, = [v for v in users if v["ssPort"] == 6898]
    return user
before = entry(text)
if before.get("adaptiveTcp") is True and "transport" not in before:
    edited, n = re.subn(r"(?m)^(\s*- ssPort: 6898[ \t]*\n)( +)adaptiveTcp: true[ \t]*$", r"\1\2transport: {profile: daily}", text)
    assert n == 1
    expected = dict(before, transport={"profile": "daily"})
    del expected["adaptiveTcp"]
    assert entry(edited) == expected
    tmp = Path(sys.argv[1]) / "next-conf.yml"
    tmp.write_text(edited)
    tmp.chmod(conf.stat().st_mode & 0o777)
    tmp.replace(conf)
PY
    fi
fi
ln -s "$stage/keys" "$stage/new-link"
mv -Tf "$stage/new-link" transport
for file in start.sh rollback.sh; do chmod 755 "$stage/$file"; mv -f "$stage/$file" "$file"; done
mv -f "$stage/app.jar" app.jar.publish
if [[ $role == server ]]; then
    run_private bash start.sh publish
    # NAT alone does not prove the new process bound the primary socket.
    for i in {1..15}; do
        sockets=$(ss -Hlunp 'sport = :20000')
        [[ $sockets == *'"java"'* ]] && break
        sleep 1
    done
    [[ $sockets == *'"java"'* ]]
    nft -j list map inet rxrss_hop_20000_20127 hops >"$stage/hops.json"
    python3 - "$stage/hops.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    mapping, = [v["map"] for v in json.load(f)["nftables"] if "map" in v]
assert dict(mapping["elem"]) == {p: p-1 for p in range(20001, 20128, 2)}
PY
    echo 'Server primary UDP socket and all 64 nft port pairs verified.'
else
    export REUSEPORT_MIGRATION_FALLBACK=$fallback
    run_private bash start.sh publish "$mode"
fi
[[ $(stat -c '%d:%i' app.jar) == "$new_inode" ]]
echo 'Published verified JAR.'
