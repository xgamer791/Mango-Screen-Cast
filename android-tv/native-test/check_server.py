#!/usr/bin/env python3
"""Checks what the TV's AirPlay server tells Apple devices.

Starts server_harness (see CMakeLists.txt), reads the service records it
registers, and fetches /info the way a Mac or iPhone does before mirroring.

    python3 check_server.py BUILD_DIR
"""
import os
import plistlib
import socket
import subprocess
import sys
import tempfile

HEVC_BIT = 1 << 42


def fetch_info(port):
    with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
        s.sendall(b"GET /info RTSP/1.0\r\nCSeq: 1\r\nX-Apple-ProtocolVersion: 1\r\n"
                  b"User-Agent: AirPlay/750.4\r\nContent-Length: 0\r\n\r\n")
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = s.recv(4096)
            if not chunk:
                raise RuntimeError("connection closed before headers")
            data += chunk
        head, body = data.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n")[0]
        assert status == b"RTSP/1.0 200 OK", status
        length = next(int(line.split(b":")[1]) for line in head.split(b"\r\n")
                      if line.lower().startswith(b"content-length"))
        while len(body) < length:
            body += s.recv(65536)
        return plistlib.loads(body)


def run_case(harness, width, height, hevc):
    with tempfile.TemporaryDirectory() as tmp:
        keyfile = os.path.join(tmp, "airplay.key")
        proc = subprocess.Popen([harness, keyfile, "3", str(width), str(height), str(int(hevc))],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        services, txt = {}, {}
        port = None
        for line in proc.stdout:
            parts = line.rstrip("\n").split(" ", 3)
            if parts[0] == "REGISTER":
                services[parts[1]] = parts[3]
            elif parts[0] == "TXT":
                key, _, value = parts[2].partition("=")
                txt.setdefault(parts[1], {})[key] = value
            elif parts[0] == "PORT":
                port = int(parts[1])
                break
        assert port and port > 0, f"server failed to start ({port})"
        info = fetch_info(port)
        proc.wait(timeout=10)
        assert os.path.exists(keyfile), "pairing key was not saved"

    assert services.get("_airplay._tcp") == "Mango Test TV", services
    assert services.get("_raop._tcp") == "021122334455@Mango Test TV", services
    for record in ("_airplay._tcp", "_raop._tcp"):
        assert txt[record].get("pw") == "false", f"{record} must not ask for a password"
    features = txt["_airplay._tcp"]["features"]
    lo, hi = (int(x, 16) for x in features.split(","))
    advertised = lo | hi << 32
    assert bool(advertised & HEVC_BIT) == hevc, features
    assert info["features"] == advertised, (info["features"], advertised)

    display = info["displays"][0]
    assert (display["width"], display["height"]) == (width, height), display
    assert display["maxFPS"] == 60, display
    assert info["deviceID"] == "02:11:22:33:44:55", info["deviceID"]
    print(f"ok: {width}x{height} @ {display['maxFPS']} fps, HEVC={hevc}, features={features}")


def main():
    harness = os.path.join(sys.argv[1] if len(sys.argv) > 1 else "build", "server_harness")
    run_case(harness, 3840, 2160, True)
    run_case(harness, 1920, 1080, False)


if __name__ == "__main__":
    main()
