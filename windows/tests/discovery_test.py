#!/usr/bin/env python3
"""Discovery test: announces a Mango receiver (_mangocast._tcp) with
python-zeroconf, checks that `MangoCast.exe --list` finds it with its TXT
values, then connects to it by name.

    python discovery_test.py --exe build/Release/MangoCast.exe

Needs `zeroconf` (pip install zeroconf) and receiver.py next to it.
"""
import argparse
import json
import os
import socket
import subprocess
import sys

from zeroconf import IPVersion, ServiceInfo, Zeroconf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import receiver  # noqa: E402

NAME = "CI Test Receiver"
TXT = {"v": "1", "id": "ci-4f2a9c", "model": "GitHub Actions runner", "w": "1920", "h": "1080", "fps": "60",
       "codecs": "h265,h264", "audio": "1"}


def local_ipv4():
    """This machine's LAN address (where the announcement says to connect)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", required=True)
    a = ap.parse_args()
    exe = os.path.abspath(a.exe)
    problems = []

    # A real receiver behind the announcement, for the connect-by-name part.
    rx = receiver.Receiver(dict(welcome=dict(protocol=1, name=NAME, maxWidth=1280, maxHeight=720, fps=30,
                                             audio=None)), host="0.0.0.0")
    ip = local_ipv4()
    print(f"Announcing {NAME!r} at {ip}:{rx.port} with TXT {TXT}")
    zc = Zeroconf(ip_version=IPVersion.V4Only)
    info = ServiceInfo("_mangocast._tcp.local.", f"{NAME}._mangocast._tcp.local.",
                       addresses=[socket.inet_aton(ip)], port=rx.port, properties=TXT, server="mango-ci-test.local.")
    zc.register_service(info)
    try:
        found = None
        for attempt, wait in enumerate((4000, 8000)):
            proc = subprocess.run([exe, "--list", "--wait", str(wait)], capture_output=True, timeout=60)
            out = proc.stdout.decode("utf-8", "replace")
            print(f"--list --wait {wait} (exit {proc.returncode}):\n{out.strip() or '(nothing)'}")
            if proc.returncode != 0:
                problems.append(f"--list exited with {proc.returncode}: {proc.stderr.decode(errors='replace')}")
            for line in out.splitlines():
                try:
                    item = json.loads(line)
                except ValueError:
                    problems.append(f"--list printed a line that isn't JSON: {line!r}")
                    continue
                if item.get("name") == NAME:
                    found = item
            if found:
                break
        if not found:
            problems.append(f"--list didn't find {NAME!r}")
        else:
            want = {"port": rx.port, "id": TXT["id"], "model": TXT["model"], "w": 1920, "h": 1080, "fps": 60,
                    "codecs": ["h265", "h264"], "audio": True, "v": 1, "txt": TXT}
            for key, value in want.items():
                if found.get(key) != value:
                    problems.append(f"--list gave {key} = {found.get(key)!r}, announced {value!r}")
            if found.get("address") not in (ip, "127.0.0.1"):
                problems.append(f"--list gave address {found.get('address')!r}, announced {ip}")

            # Connect by name: MangoCast finds the address itself.
            proc = subprocess.run([exe, "--connect", NAME, "--seconds", "3", "--no-audio"], capture_output=True,
                                  timeout=60)
            rx.done.wait(10)
            print(f"--connect {NAME!r} --seconds 3: exit {proc.returncode}")
            if proc.returncode != 0:
                problems.append(f"connecting by name failed (exit {proc.returncode}): "
                                f"{proc.stderr.decode(errors='replace')[-2000:]}")
            if rx.hello is None:
                problems.append("connecting by name: the receiver got no HELLO")
            else:
                videos = sum(1 for m in rx.messages if m[1] == receiver.VIDEO)
                print(f"  HELLO {json.dumps(rx.hello)}; {videos} VIDEO messages")
                if not videos:
                    problems.append("connecting by name: no VIDEO")
            problems += [f"receiver: {p}" for p in rx.problems]
    finally:
        zc.unregister_service(info)
        zc.close()
    if problems:
        print("FAIL")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
