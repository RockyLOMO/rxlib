"""Ship only the selected host's credentials, using its pinned SSH host key."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import socket
import time

import paramiko


def connect(target):
    host, port = target["host"], target["port"]
    algorithm, data = target["hostKey"].split()
    key = paramiko.hostkeys.HostKeyEntry.from_line(f"pinned {algorithm} {data}").key
    ssh = paramiko.SSHClient()
    ssh.get_host_keys().add(host if port == 22 else f"[{host}]:{port}", algorithm, key)
    ssh.connect(host, port=port, username=target["user"], password=target["password"],
                allow_agent=False, look_for_keys=False, timeout=20, banner_timeout=30)
    ssh.get_transport().set_keepalive(20)
    return ssh


def command(ssh, cmd):
    _, out, err = ssh.exec_command(cmd)
    # The remote publisher sends only short status lines. Private diagnostics stay on host.
    print(out.read().decode(), end="", flush=True)
    if out.channel.recv_exit_status():
        raise RuntimeError("Remote deployment command failed; inspect the private release log on host")


def reachable(host, port):
    for _ in range(12):
        try:
            with socket.create_connection((host, port), timeout=5):
                return True
        except OSError:
            time.sleep(3)
    return False


def main():
    role = os.environ["ROLE"]
    mode = os.environ["MODE"]
    fallback = os.environ["FALLBACK"]
    if role not in ("client", "server") or mode not in ("replace", "coexist") or fallback not in ("0", "1"):
        raise ValueError("Invalid deployment inputs")
    bundle = json.loads(os.environ.pop("RSS_DEPLOY"))
    target = bundle[role]
    directory = "rss" if role == "client" else "rss-svr"
    root = "/home/" + directory
    release = f".release-{int(os.environ['GITHUB_RUN_ID'])}-{int(os.environ['GITHUB_RUN_ATTEMPT'])}"
    stage = root + "/" + release
    jar, = [p for p in Path("rxlib/target").glob("*.jar")
            if not p.name.endswith(("-sources.jar", "-javadoc.jar", "-tests.jar"))]
    with jar.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    secrets = {"tcp-key": bundle["tcpKey"], "udp-key": bundle["udpKey"]}
    if any(len(base64.b64decode(v, validate=True)) != 32 for v in secrets.values()):
        raise ValueError("RSS transport keys must be 32 bytes")
    if role == "client":
        if bundle.get("tlsCa"):
            secrets["ca.pem"] = bundle["tlsCa"]
    else:
        secrets["shadow-user"] = bundle["shadowUser"]
        if bundle.get("tlsCert") or bundle.get("tlsKey"):
            secrets.update({"cert.pem": bundle["tlsCert"], "key.pem": bundle["tlsKey"]})
    with connect(target) as ssh:
        with ssh.open_sftp() as sftp:
            sftp.mkdir(stage, mode=0o700)
            sftp.chdir(stage)
            sftp.mkdir("keys", mode=0o700)
            for name, value in secrets.items():
                sftp.putfo(io.BytesIO(value.encode()), "keys/" + name)
                sftp.chmod("keys/" + name, 0o600)
            for name in ("start.sh", "rollback.sh"):
                sftp.put(f"deploy/{directory}/{name}", name)
            for name in ("rss-remote.sh", "rss-java21.sh"):
                sftp.put("deploy/" + name, name)
            if role == "server":
                sftp.put("deploy/rss-hops.sh", "rss-hops.sh")
            sftp.put(str(jar), "app.jar")
        args = shlex.join([stage + "/rss-remote.sh", role, mode, fallback, digest])
        print("Release uploaded. Checking package and remote prerequisites.", flush=True)
        command(ssh, "bash " + args)
        if not reachable(target.get("healthHost", target["host"]), 6899 if role == "client" else 9900):
            if mode == "replace":
                command(ssh, "bash " + args + " recover")
            raise RuntimeError("Public TCP health check failed")
        print("Public TCP listener verified")


if __name__ == "__main__":
    main()
