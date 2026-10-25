#!/usr/bin/env python3
"""A Mango Cast test receiver (protocol/mango-cast.md, version 1) and the
end-to-end tests of MangoCast.exe that run against it.

The receiver takes one sender, answers HELLO the way a scenario says
(WELCOME with some size / fps / sound, or REFUSE), PINGs it, asks for a
keyframe, can stop reading for a while or go silent, and records every
message. Then the recording is checked against the protocol and decoded
with PyAV.

    python receiver.py --exe build/Release/MangoCast.exe [--sound] [--out DIR] [--only NAME ...]

Needs Python 3.9+, `av` and `numpy` (pip install av numpy).
"""
import argparse
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time  # (time.perf_counter: monotonic() ticks only every 15.6 ms on Windows)

HELLO, WELCOME, REFUSE, VIDEO, KEYFRAME, AUDIO, PING, PONG, BYE = 0x01, 0x02, 0x03, 0x10, 0x11, 0x20, 0x30, 0x31, 0x3F
NAMES = {HELLO: "HELLO", WELCOME: "WELCOME", REFUSE: "REFUSE", VIDEO: "VIDEO", KEYFRAME: "KEYFRAME",
         AUDIO: "AUDIO", PING: "PING", PONG: "PONG", BYE: "BYE"}
MAX_PAYLOAD = 16 * 1024 * 1024
PARAMETER_SETS = {"h264": [7, 8], "h265": [32, 33, 34]}
AUD = {"h264": 9, "h265": 35}


# --- Annex B --------------------------------------------------------------

def split_annexb(data):
    """NAL units of an Annex B access unit (without start codes)."""
    nals, i, start, n = [], 0, None, len(data)
    while i + 3 <= n:
        if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
            if start is not None:
                end = i
                while end > start and data[end - 1] == 0:
                    end -= 1
                nals.append(data[start:end])
            i += 3
            start = i
        else:
            i += 1
    if start is not None:
        end = n
        while end > start and data[end - 1] == 0:
            end -= 1
        nals.append(data[start:end])
    return [x for x in nals if x]


def nal_type(codec, nal):
    return nal[0] & 0x1F if codec == "h264" else (nal[0] >> 1) & 0x3F


def is_picture(codec, t):
    return 1 <= t <= 5 if codec == "h264" else 0 <= t <= 31


def is_key(codec, t):
    return t == 5 if codec == "h264" else 16 <= t <= 23


class Bits:
    def __init__(self, nal_payload):
        # Drop emulation prevention bytes (00 00 03 -> 00 00).
        out, zeros = bytearray(), 0
        for b in nal_payload:
            if zeros >= 2 and b == 3:
                zeros = 0
                continue
            out.append(b)
            zeros = zeros + 1 if b == 0 else 0
        self.data, self.pos = bytes(out), 0

    def u(self, n):
        v = 0
        for _ in range(n):
            byte = self.data[self.pos >> 3] if (self.pos >> 3) < len(self.data) else 0
            v = (v << 1) | ((byte >> (7 - (self.pos & 7))) & 1)
            self.pos += 1
        return v

    def ue(self):
        zeros = 0
        while self.u(1) == 0:
            zeros += 1
            if zeros > 32:
                raise ValueError("bad exp-Golomb code")
        return (1 << zeros) - 1 + self.u(zeros)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)


def h264_sps(nal):
    """The SPS fields needed to find frame_num in a slice header."""
    b = Bits(nal[1:])
    profile = b.u(8)
    b.u(16)
    b.ue()  # seq_parameter_set_id
    separate_colour_plane = 0
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = b.ue()
        if chroma == 3:
            separate_colour_plane = b.u(1)
        b.ue()
        b.ue()
        b.u(1)
        if b.u(1):  # scaling matrices
            for i in range(12 if chroma == 3 else 8):
                if b.u(1):
                    last = nxt = 8
                    for _ in range(16 if i < 6 else 64):
                        if nxt:
                            nxt = (last + b.se() + 256) % 256
                        last = nxt or last
    return {"log2_max_frame_num": b.ue() + 4, "separate_colour_plane": separate_colour_plane}


def h264_frame_num(nal, sps):
    b = Bits(nal[1:])
    b.ue()  # first_mb_in_slice
    b.ue()  # slice_type
    b.ue()  # pic_parameter_set_id
    if sps["separate_colour_plane"]:
        b.u(2)
    return b.u(sps["log2_max_frame_num"])


# --- The receiver ----------------------------------------------------------

def frame(msg_type, payload=b""):
    return struct.pack(">BI", msg_type, len(payload)) + payload


class Receiver:
    """Takes one sender on a local port and follows a scenario:

    welcome:      the WELCOME to send (dict), or None with refuse set
    refuse:       a REFUSE payload (dict) instead
    keyframe_at:  seconds after WELCOME to send KEYFRAME
    ping_every:   seconds between our PINGs
    silent_after: seconds after WELCOME after which we send nothing at all
    bye_at:       seconds after WELCOME to say BYE and close
    pause:        (start, seconds): stop reading for a while
    rcvbuf:       socket receive buffer size
    unknown_at:   seconds after WELCOME to send a message type nobody knows
    run_at:       (seconds, command): run a command then (e.g. change the screen's resolution)
    """

    def __init__(self, scenario, host="127.0.0.1", port=0):
        self.sc = scenario
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if scenario.get("rcvbuf"):
            self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, scenario["rcvbuf"])
        self.listener.bind((host, port))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.messages = []       # (arrival time, type, payload)
        self.problems = []
        self.hello = None
        self.welcome_time = None
        self.before_welcome = []  # messages that came before WELCOME (only HELLO may)
        self.pings_sent = {}     # payload -> time sent
        self.pongs = {}          # payload -> time answered
        self.keyframe_sent = None
        self.run_result = None
        self.closed_by_sender = False
        self.connected = threading.Event()
        self.done = threading.Event()
        self.sock = None
        self.send_lock = threading.Lock()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def problem(self, text):
        self.problems.append(text)

    def send(self, msg_type, payload=b""):
        with self.send_lock:
            try:
                self.sock.sendall(frame(msg_type, payload))
                return True
            except OSError:
                return False

    def _recv_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError()
            buf += chunk
        return bytes(buf)

    def _read(self):
        header = self._recv_exact(5)
        msg_type, length = struct.unpack(">BI", header)
        if length > MAX_PAYLOAD:
            raise ValueError(f"message of {length} bytes is over 16 MiB")
        return msg_type, self._recv_exact(length) if length else b""

    def _run(self):
        try:
            self.listener.settimeout(60)
            self.sock, _ = self.listener.accept()
            self.connected.set()
            self.sock.settimeout(15)
            t_connect = time.perf_counter()
            msg_type, payload = self._read()
            if msg_type != HELLO:
                self.problem(f"first message was {NAMES.get(msg_type, hex(msg_type))}, not HELLO")
                return
            if time.perf_counter() - t_connect > 5:
                self.problem("HELLO took more than 5 s")
            self.hello = json.loads(payload.decode("utf-8"))
            self.messages.append((time.perf_counter(), msg_type, payload))
            # Nothing may come before WELCOME: wait a moment and look.
            self.sock.settimeout(0.3)
            try:
                while True:
                    early_type, early = self._read()
                    self.before_welcome.append(early_type)
            except socket.timeout:
                pass
            self.sock.settimeout(15)
            if self.sc.get("refuse") is not None:
                self.send(REFUSE, json.dumps(self.sc["refuse"]).encode())
                time.sleep(0.2)
                return
            welcome = dict(self.sc["welcome"])
            if "codec" not in welcome:
                ours = welcome.pop("decodes", ["h265", "h264"])
                welcome["codec"] = next((c for c in self.hello.get("codecs", []) if c in ours), None)
            self.codec = welcome["codec"]
            self.welcome = welcome
            self.send(WELCOME, json.dumps(welcome).encode())
            self.welcome_time = time.perf_counter()
            threading.Thread(target=self._talk, daemon=True).start()
            self._listen()
        except Exception as e:  # noqa: BLE001 - every failure is a finding
            if not isinstance(e, EOFError):
                self.problem(f"receiver: {type(e).__name__}: {e}")
        finally:
            self.done.set()
            try:
                if self.sock:
                    self.sock.close()
            except OSError:
                pass
            self.listener.close()

    def _talk(self):
        """Our side of the conversation: PINGs, KEYFRAME, an unknown message, BYE."""
        sc, t0 = self.sc, self.welcome_time
        next_ping = t0 + sc.get("ping_every", 1.0)
        keyframe_done = unknown_done = run_done = False
        while not self.done.is_set():
            now = time.perf_counter() - t0
            if sc.get("silent_after") is not None and now >= sc["silent_after"]:
                time.sleep(0.05)
                continue
            if sc.get("bye_at") is not None and now >= sc["bye_at"]:
                self.send(BYE, b"switching to AirPlay")
                time.sleep(0.1)
                try:
                    self.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                return
            if sc.get("keyframe_at") is not None and not keyframe_done and now >= sc["keyframe_at"]:
                keyframe_done = True
                self.keyframe_sent = time.perf_counter()
                self.send(KEYFRAME)
            if sc.get("run_at") and not run_done and now >= sc["run_at"][0]:
                run_done = True
                threading.Thread(target=self._run_command, args=(sc["run_at"][1],), daemon=True).start()
            if sc.get("unknown_at") is not None and not unknown_done and now >= sc["unknown_at"]:
                unknown_done = True
                self.send(0x5A, b"\x00" * 100)
            if time.perf_counter() >= next_ping:
                payload = struct.pack(">Q", int(time.perf_counter() * 1e6))
                self.pings_sent[payload] = time.perf_counter()
                self.send(PING, payload)
                next_ping += sc.get("ping_every", 1.0)
            time.sleep(0.02)

    def _run_command(self, command):
        proc = subprocess.run(command, capture_output=True, timeout=60)
        self.run_result = (proc.returncode, proc.stdout.decode("utf-8", "replace").strip())

    def _listen(self):
        sc = self.sc
        paused = False
        while True:
            if sc.get("pause") and not paused:
                start, length = sc["pause"]
                if time.perf_counter() - self.welcome_time >= start:
                    paused = True
                    time.sleep(length)  # not reading: the sender's data backs up
            try:
                msg_type, payload = self._read()
            except EOFError:
                self.closed_by_sender = True
                return
            except (ConnectionError, OSError, socket.timeout):
                return
            now = time.perf_counter()
            self.messages.append((now, msg_type, payload))
            if msg_type == PING and sc.get("silent_after") is None:
                self.send(PONG, payload)
            elif msg_type == PING and now - self.welcome_time < sc["silent_after"]:
                self.send(PONG, payload)
            elif msg_type == PONG:
                self.pongs[payload] = now
            elif msg_type == BYE:
                # The sender closes after BYE; see that it does.
                self.sock.settimeout(3)
                try:
                    rest = self.sock.recv(1)
                    if rest:
                        self.problem("the sender sent more after BYE")
                    else:
                        self.closed_by_sender = True
                except (socket.timeout, OSError):
                    self.problem("the sender didn't close the connection after BYE")
                return


# --- Checks ------------------------------------------------------------------

def fit_inside(w, h, max_w, max_h):
    scale = min(1.0, max_w / w if max_w else 1.0, max_h / h if max_h else 1.0)
    fw, fh = int(w * scale + 1e-6), int(h * scale + 1e-6)
    return max(2, min(fw, max_w or fw) & ~1), max(2, min(fh, max_h or fh) & ~1)


def check_hello(rx, findings):
    h = rx.hello
    if h is None:
        rx.problem("no HELLO")
        return
    for key, kind in (("protocol", int), ("name", str), ("os", str), ("codecs", list), ("width", int),
                      ("height", int), ("fps", int), ("audio", bool)):
        if not isinstance(h.get(key), kind):
            rx.problem(f"HELLO {key} is missing or not a {kind.__name__}: {h.get(key)!r}")
    if h.get("protocol") != 1:
        rx.problem("HELLO protocol isn't 1")
    if not set(h.get("codecs", [])) <= {"h264", "h265"} or not h.get("codecs"):
        rx.problem(f"HELLO codecs {h.get('codecs')} aren't h264/h265")
    if rx.before_welcome:
        rx.problem("sent before WELCOME: " + ", ".join(NAMES.get(t, hex(t)) for t in rx.before_welcome))
    findings["hello"] = h


def check_stream(rx, sc, findings, seconds):
    """Everything after WELCOME: framing, video, sound, keepalives, BYE."""
    codec = rx.codec
    t0 = rx.welcome_time
    msgs = [m for m in rx.messages if m[1] != HELLO]
    kinds = {}
    for _, t, _ in msgs:
        kinds[NAMES.get(t, hex(t))] = kinds.get(NAMES.get(t, hex(t)), 0) + 1
    findings["messages"] = kinds
    for _, t, _ in msgs:
        if t not in (VIDEO, AUDIO, PING, PONG, BYE):
            rx.problem(f"the sender sent message type {NAMES.get(t, hex(t))}")
            break

    # Keepalive: something at least every 2 s.
    times = [t0] + [m[0] for m in msgs]
    gap = max((b - a for a, b in zip(times, times[1:])), default=0)
    findings["longest_silence_s"] = round(gap, 2)
    if gap > 2.5 and not sc.get("pause"):
        rx.problem(f"the sender was silent for {gap:.1f} s (at most 2 s allowed)")

    # PONGs for our PINGs.
    end = msgs[-1][0] if msgs else t0
    unanswered = [p for p, sent in rx.pings_sent.items() if p not in rx.pongs and end - sent > 1.0]
    findings["pings"] = {"sent": len(rx.pings_sent), "answered": len(rx.pongs)}
    if unanswered and not sc.get("pause"):
        rx.problem(f"{len(unanswered)} of our PINGs got no PONG")
    for payload, at in rx.pongs.items():
        if payload not in rx.pings_sent:
            rx.problem("a PONG didn't carry a PING's payload")
            break
    findings["sender_pings"] = kinds.get("PING", 0)

    # BYE last, then the sender closes.
    if not msgs or msgs[-1][1] != BYE:
        rx.problem("the stream didn't end with BYE")
    else:
        findings["bye"] = msgs[-1][2].decode("utf-8", "replace")
    if not rx.closed_by_sender:
        rx.problem("the sender didn't close the connection at the end")

    # Video: Annex B, keyframes with parameter sets first, no AUDs.
    video = [(at, struct.unpack(">Q", p[:8])[0], p[8:]) for at, t, p in msgs if t == VIDEO]
    findings["video_messages"] = len(video)
    if not video:
        rx.problem("no VIDEO")
        return None
    keys, last_ts, aud = [], -1, 0
    for i, (at, ts, au) in enumerate(video):
        if not au.startswith(b"\x00\x00\x01") and not au.startswith(b"\x00\x00\x00\x01"):
            rx.problem(f"VIDEO {i} doesn't start with a start code")
            break
        nals = split_annexb(au)
        types = [nal_type(codec, n) for n in nals]
        aud += types.count(AUD[codec])
        if not any(is_picture(codec, t) for t in types):
            rx.problem(f"VIDEO {i} has no picture (NAL types {types})")
        key = any(is_key(codec, t) for t in types)
        if key:
            keys.append(at)
            want = PARAMETER_SETS[codec]
            if types[:len(want)] != want:
                rx.problem(f"keyframe {i} doesn't start with its parameter sets (NAL types {types[:6]})")
        if i == 0 and not key:
            rx.problem(f"the first VIDEO isn't a keyframe (NAL types {types[:6]})")
        if ts < last_ts:
            rx.problem(f"VIDEO timestamps went backwards at {i}")
        last_ts = ts
    if aud:
        rx.problem(f"{aud} access unit delimiters")
    findings["keyframes"] = len(keys)

    # A keyframe soon after our KEYFRAME request.
    if rx.keyframe_sent is not None:
        after = [k for k in keys if k > rx.keyframe_sent]
        if not after:
            rx.problem("no keyframe after the KEYFRAME request")
        else:
            delay = after[0] - rx.keyframe_sent
            findings["keyframe_after_request_s"] = round(delay, 3)
            if delay > 1.0:
                rx.problem(f"the keyframe came {delay:.2f} s after the request (1 s allowed)")

    # H.264: frame_num never skips except at a keyframe, i.e. no picture
    # that others refer to was dropped mid-stream.
    if codec == "h264":
        sps, prev_ref, gaps = None, None, 0
        for i, (_, _, au) in enumerate(video):
            for n in split_annexb(au):
                t = nal_type(codec, n)
                if t == 7:
                    sps = h264_sps(n)
                elif t in (1, 5) and sps:
                    fn = h264_frame_num(n, sps)
                    maximum = 1 << sps["log2_max_frame_num"]
                    if t == 5:
                        prev_ref = fn
                    else:
                        if prev_ref is not None and fn != (prev_ref + 1) % maximum:
                            gaps += 1
                            if gaps <= 3:
                                rx.problem(f"frame_num jumped from {prev_ref} to {fn} at VIDEO {i} "
                                           "without a keyframe (a picture was dropped mid-stream)")
                        if n[0] & 0x60:
                            prev_ref = fn
                    break  # one slice per picture is enough
        findings["frame_num_gaps"] = gaps

    # Sound.
    audio = [(at, struct.unpack(">Q", p[:8])[0], p[8:]) for at, t, p in msgs if t == AUDIO]
    findings["audio_messages"] = len(audio)
    if sc.get("expect_audio"):
        if not audio:
            rx.problem("no AUDIO, though the sender has a sound device and the receiver asked for sound")
        else:
            sizes = {len(pcm) for _, _, pcm in audio}
            frames = sum(len(pcm) // 4 for _, _, pcm in audio)
            findings["audio_chunk_ms"] = sorted({round(s / 4 / 48, 1) for s in sizes})
            findings["audio_seconds"] = round(frames / 48000, 2)
            for s in sizes:
                if s % 4:
                    rx.problem(f"an AUDIO payload of {s} bytes isn't whole stereo 16-bit frames")
                if not 5 * 192 <= s <= 40 * 192:
                    rx.problem(f"an AUDIO payload holds {s / 192:.1f} ms (10 to 20 ms is best)")
            if frames / 48000 < seconds * 0.6:
                rx.problem(f"only {frames / 48000:.1f} s of sound in {seconds} s")
            stamps = [ts for _, ts, _ in audio]
            if any(b < a for a, b in zip(stamps, stamps[1:])):
                rx.problem("AUDIO timestamps went backwards")
            try:
                import numpy as np
                pcm = np.frombuffer(b"".join(p for _, _, p in audio), dtype="<i2").astype(np.float64)
                findings["audio_rms"] = round(float(np.sqrt(np.mean(pcm ** 2))), 1)
                if sc.get("expect_tone") and findings["audio_rms"] < 50:
                    rx.problem("the sound is silent, though a tone was playing")
            except ImportError:
                pass
    elif audio:
        rx.problem(f"{len(audio)} AUDIO messages, though no sound was asked for")
    return video


def decode(codec, video, findings, rx, sc, seconds, out_path=None):
    import av
    import numpy as np

    if out_path:
        with open(out_path, "wb") as f:
            for _, _, au in video:
                f.write(au)
    ctx = av.CodecContext.create("h264" if codec == "h264" else "hevc", "r")
    stats, errors = [], []

    def take(frames):
        for fr in frames:
            planes = [np.frombuffer(bytes(p), dtype=np.uint8) for p in fr.planes[:3]]
            stats.append((fr.width, fr.height, *(float(p.mean()) for p in planes)))

    capture = getattr(av.logging, "Capture", None)
    logs = []
    try:
        if capture:
            with capture() as logs:
                for _, _, au in video:
                    take(ctx.decode(av.Packet(au)))
                take(ctx.decode(None))
        else:
            for _, _, au in video:
                take(ctx.decode(av.Packet(au)))
            take(ctx.decode(None))
    except av.error.FFmpegError as e:
        rx.problem(f"decoding failed: {e}")
    errors = [m for m in logs if m[0] <= av.logging.ERROR]
    findings["decoded_frames"] = len(stats)
    findings["decode_errors"] = len(errors)
    for e in errors[:3]:
        rx.problem(f"decoder error: {e[2].strip()}")
    if not stats:
        rx.problem("nothing decoded")
        return
    sizes = sorted({(w, h) for w, h, *_ in stats})
    findings["sizes"] = [f"{w}x{h}" for w, h in sizes]
    hello = rx.hello
    for w, h in sizes:
        if w % 2 or h % 2:
            rx.problem(f"picture size {w}x{h} isn't even")
        if rx.welcome.get("maxWidth") and (w > rx.welcome["maxWidth"] or h > rx.welcome["maxHeight"]):
            rx.problem(f"picture size {w}x{h} is over the receiver's {rx.welcome['maxWidth']}x{rx.welcome['maxHeight']}")
    if sc.get("expect_resize") and len(sizes) < 2:
        rx.problem(f"the screen changed size but the pictures didn't ({findings['sizes']})")
    if sc.get("expect_size"):
        want = fit_inside(hello["width"], hello["height"], rx.welcome["maxWidth"], rx.welcome["maxHeight"])
        if sizes != [want]:
            rx.problem(f"pictures are {findings['sizes']}, expected {want[0]}x{want[1]}")
    fps = rx.welcome["fps"]
    findings["fps"] = round(len(stats) / seconds, 1)
    lo, hi = sc.get("min_fps", fps * 0.5), fps * 1.1 + 2
    if not lo <= len(stats) / seconds <= hi:
        rx.problem(f"{len(stats)} pictures in {seconds} s ({len(stats) / seconds:.1f} fps); "
                   f"expected {lo:.0f} to {hi:.0f} fps")
    # The picture changes (the test pattern cycles through colours).
    distinct = len({(round(y), round(u), round(v)) for _, _, y, u, v in stats})
    u_range = max(s[3] for s in stats) - min(s[3] for s in stats)
    v_range = max(s[4] for s in stats) - min(s[4] for s in stats)
    findings["distinct_pictures"] = distinct
    findings["chroma_range"] = round(max(u_range, v_range), 1)
    if sc.get("expect_motion", True) and (distinct < 5 or max(u_range, v_range) < 8):
        rx.problem(f"the picture hardly changes ({distinct} distinct, chroma range {max(u_range, v_range):.1f})")


# --- Scenarios ------------------------------------------------------------------

AUDIO_ON = {"format": "pcm_s16le", "rate": 48000, "channels": 2}
DISPLAY_PS1 = os.path.join(os.path.dirname(os.path.abspath(__file__)), "display.ps1")


def scenarios(sound):
    return [
        dict(name="stream", seconds=8, keyframe_at=3.0, unknown_at=1.5,
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=3840, maxHeight=2160, fps=30,
                          audio=AUDIO_ON, extra={"ignored": True}),
             expect_audio=sound, expect_tone=sound, min_fps=18,
             about="WELCOME 4K 30 fps with sound; KEYFRAME at 3 s; an unknown message type at 1.5 s"),
        dict(name="fps60", seconds=5,
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=1920, maxHeight=1080, fps=60, audio=AUDIO_ON),
             expect_audio=sound, min_fps=15,
             about="WELCOME 60 fps: never more than 60 pictures a second"),
        dict(name="small", seconds=4,
             welcome=dict(protocol=1, name="Small TV", maxWidth=640, maxHeight=360, fps=30, audio=AUDIO_ON),
             expect_audio=sound, expect_size=True, min_fps=15,
             about="WELCOME 640x360: the sender scales the picture down, keeping its shape"),
        dict(name="audio-null", seconds=4,
             welcome=dict(protocol=1, name="Muted TV", maxWidth=1920, maxHeight=1080, fps=30, audio=None),
             expect_audio=False, min_fps=15,
             about="WELCOME audio null: no AUDIO at all"),
        dict(name="no-audio-flag", seconds=4, args=["--no-audio"],
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=1920, maxHeight=1080, fps=30, audio=AUDIO_ON),
             expect_audio=False, hello_audio=False, min_fps=15,
             about="--no-audio: HELLO says audio false and no AUDIO comes, whatever WELCOME says"),
        dict(name="backpressure", seconds=10, pause=(3.0, 3.0), rcvbuf=32 * 1024,
             welcome=dict(protocol=1, name="Slow TV", maxWidth=1920, maxHeight=1080, fps=30, audio=AUDIO_ON),
             expect_audio=sound, min_fps=8, expect_congestion=True,
             about="the receiver stops reading for 3 s: the sender drops pictures until a keyframe, "
                   "lowers its bitrate, and the stream stays decodable"),
        dict(name="textures", seconds=4, env={"MANGO_TEST_TEXTURES": "1"},
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=800, maxHeight=600, fps=30, audio=None),
             expect_audio=False, expect_size=True, min_fps=12,
             about="the hardware encoders' path: NV12 textures drawn on the GPU, handed to the encoder as "
                   "Direct3D surfaces (scaled to 800x600)"),
        dict(name="duplication", seconds=5, args=["--capture", "duplication"],
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=1920, maxHeight=1080, fps=30, audio=AUDIO_ON),
             expect_audio=sound, min_fps=12,
             about="--capture duplication: DXGI Desktop Duplication instead of Windows.Graphics.Capture"),
        dict(name="resize", seconds=8, keyframe_at=6.0,
             run_at=(3.0, ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", DISPLAY_PS1,
                           "-Width", "1280", "-Height", "720"]),
             cleanup=["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", DISPLAY_PS1,
                      "-Width", "1024", "-Height", "768"],
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=1920, maxHeight=1080, fps=30, audio=None),
             expect_audio=False, expect_resize=True, min_fps=10,
             about="the screen goes from 1024x768 to 1280x720 mid-stream: a keyframe with new parameter sets"),
        dict(name="window", window=True, seconds=4,
             welcome=dict(protocol=1, name="Window Test TV", maxWidth=1920, maxHeight=1080, fps=30,
                          audio=AUDIO_ON),
             expect_audio=sound, min_fps=12,
             about="the window: type an address, Connect, watch the status line, Stop, close"),
        dict(name="refuse", refuse={"reason": "busy", "message": "An iPhone is mirroring to this TV."},
             expect_exit=3, expect_text="An iPhone is mirroring to this TV.",
             about="REFUSE busy: the sender exits with code 3 and says why"),
        dict(name="receiver-bye", bye_at=2.0, seconds=30,
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=1920, maxHeight=1080, fps=30, audio=None),
             expect_exit=5, expect_text="switching to AirPlay", max_runtime=6,
             about="the receiver says BYE: the sender stops at once (exit code 5)"),
        dict(name="receiver-gone", silent_after=2.0, seconds=30,
             welcome=dict(protocol=1, name="Test Receiver", maxWidth=1920, maxHeight=1080, fps=30, audio=None),
             expect_exit=4, expect_text="stopped answering", min_runtime=9, max_runtime=16,
             about="the receiver goes silent: after 8 s the sender gives up (exit code 4)"),
    ]


def run_scenario(exe, sc, out_dir):
    findings = {}
    rx = Receiver(sc)
    log_path = os.path.join(out_dir, sc["name"] + ".log")
    args = [exe, "--connect", f"127.0.0.1:{rx.port}", "--log", log_path] + sc.get("args", [])
    if sc.get("seconds"):
        args += ["--seconds", str(sc["seconds"])]
    env = dict(os.environ, **sc.get("env", {}))
    started = time.perf_counter()
    try:
        proc = subprocess.run(args, capture_output=True, timeout=90, env=env)
    except subprocess.TimeoutExpired:
        rx.problem("the sender didn't finish within 90 s")
        return findings, rx.problems, ""
    finally:
        if sc.get("cleanup"):
            subprocess.run(sc["cleanup"], capture_output=True, timeout=60)
    runtime = time.perf_counter() - started
    rx.done.wait(10)
    if sc.get("run_at"):
        findings["command"] = rx.run_result
        if not rx.run_result or rx.run_result[0] != 0:
            rx.problem(f"the scenario's command failed: {rx.run_result}")
    stderr = proc.stderr.decode("utf-8", "replace")
    findings["exit_code"] = proc.returncode
    findings["runtime_s"] = round(runtime, 1)
    expect_exit = sc.get("expect_exit", 0)
    if proc.returncode != expect_exit:
        rx.problem(f"exit code {proc.returncode}, expected {expect_exit}")
    if sc.get("expect_text") and sc["expect_text"] not in stderr:
        rx.problem(f"the sender didn't say {sc['expect_text']!r}")
    if sc.get("max_runtime") and runtime > sc["max_runtime"]:
        rx.problem(f"took {runtime:.1f} s (at most {sc['max_runtime']} s expected)")
    if sc.get("min_runtime") and runtime < sc["min_runtime"]:
        rx.problem(f"took only {runtime:.1f} s (at least {sc['min_runtime']} s expected)")
    check_hello(rx, findings)
    if rx.hello is not None and "hello_audio" in sc and rx.hello.get("audio") != sc["hello_audio"]:
        rx.problem(f"HELLO audio is {rx.hello.get('audio')}, expected {sc['hello_audio']}")
    if expect_exit == 0 and rx.welcome_time is not None:
        video = check_stream(rx, sc, findings, sc["seconds"])
        if video:
            decode(rx.codec, video, findings, rx, sc, sc["seconds"],
                   os.path.join(out_dir, sc["name"] + (".h264" if rx.codec == "h264" else ".h265")))
    log = ""
    if os.path.exists(log_path):
        with open(log_path, encoding="utf-8", errors="replace") as f:
            log = f.read()
    if sc.get("expect_congestion"):
        lowered = [line for line in log.splitlines() if "Bitrate now" in line]
        findings["bitrate_changes"] = [line.split("Bitrate now ")[1] for line in lowered][:4]
        if not lowered:
            rx.problem("the sender never lowered its bitrate")
    return findings, rx.problems, stderr + ("\n--- log ---\n" + log if rx.problems else "")


# The window's control IDs (src/win/ui.cpp).
ID_ADDRESS, ID_CONNECT, ID_STOP, ID_STATUS = 102, 105, 106, 107


def run_window_scenario(exe, sc, out_dir):
    """MangoCast.exe without options: type an address, Connect, Stop, close
    the window, all through window messages, as a person would with clicks."""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.FindWindowW.restype = wintypes.HWND
    user32.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
    user32.GetDlgItem.restype = wintypes.HWND
    user32.GetDlgItem.argtypes = [wintypes.HWND, ctypes.c_int]
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    user32.SendMessageTimeoutW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, ctypes.c_void_p,
                                           wintypes.UINT, wintypes.UINT, ctypes.POINTER(ctypes.c_size_t)]
    WM_SETTEXT, WM_GETTEXT, WM_COMMAND, WM_CLOSE = 0x000C, 0x000D, 0x0111, 0x0010

    def text_of(hwnd):
        buf = ctypes.create_unicode_buffer(512)
        res = ctypes.c_size_t()
        user32.SendMessageTimeoutW(hwnd, WM_GETTEXT, 512, ctypes.cast(buf, ctypes.c_void_p), 2, 2000,
                                   ctypes.byref(res))
        return buf.value

    def wait_for(cond, seconds):
        until = time.perf_counter() + seconds
        while time.perf_counter() < until:
            if cond():
                return True
            time.sleep(0.1)
        return False

    findings = {}
    rx = Receiver(sc)
    log_path = os.path.join(out_dir, sc["name"] + ".log")
    proc = subprocess.Popen([exe, "--log", log_path])
    hwnd = None
    try:
        if not wait_for(lambda: user32.FindWindowW("MangoCastWindow", "Mango Cast"), 15):
            rx.problem("the window didn't open")
            return findings, rx.problems, ""
        hwnd = user32.FindWindowW("MangoCastWindow", "Mango Cast")
        status = user32.GetDlgItem(hwnd, ID_STATUS)
        findings["status_at_start"] = text_of(status)
        address = ctypes.create_unicode_buffer(f"127.0.0.1:{rx.port}")
        res = ctypes.c_size_t()
        user32.SendMessageTimeoutW(user32.GetDlgItem(hwnd, ID_ADDRESS), WM_SETTEXT, 0,
                                   ctypes.cast(address, ctypes.c_void_p), 2, 2000, ctypes.byref(res))
        user32.PostMessageW(hwnd, WM_COMMAND, ID_CONNECT, 0)
        if not wait_for(lambda: text_of(status).startswith("Streaming to"), 20):
            rx.problem(f"the status never said it was streaming (it says {text_of(status)!r})")
        findings["status_streaming"] = text_of(status)
        time.sleep(sc["seconds"])
        user32.PostMessageW(hwnd, WM_COMMAND, ID_STOP, 0)
        rx.done.wait(10)
        if not wait_for(lambda: text_of(status).startswith("Stopped"), 10):
            rx.problem(f"after Stop the status says {text_of(status)!r}")
        findings["status_stopped"] = text_of(status)
        user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
        try:
            findings["exit_code"] = proc.wait(15)
            if proc.returncode != 0:
                rx.problem(f"the window closed with exit code {proc.returncode}")
        except subprocess.TimeoutExpired:
            rx.problem("the program didn't end when its window was closed")
    finally:
        if proc.poll() is None:
            proc.kill()
    check_hello(rx, findings)
    if rx.welcome_time is not None:
        end = rx.messages[-1][0] if rx.messages else rx.welcome_time
        seconds = max(1.0, end - rx.welcome_time)
        video = check_stream(rx, sc, findings, seconds)
        if video:
            decode(rx.codec, video, findings, rx, sc, seconds, os.path.join(out_dir, sc["name"] + ".h264"))
        if findings.get("bye") != "stopped":
            rx.problem(f"BYE said {findings.get('bye')!r}, expected 'stopped'")
    log = ""
    if rx.problems and os.path.exists(log_path):
        with open(log_path, encoding="utf-8", errors="replace") as f:
            log = "--- log ---\n" + f.read()
    return findings, rx.problems, log


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exe", required=True)
    ap.add_argument("--sound", action="store_true", help="the PC has a sound device (and a tone playing)")
    ap.add_argument("--out", default="e2e-out")
    ap.add_argument("--only", nargs="*")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    failed = []
    summary = {}
    for sc in scenarios(a.sound):
        if a.only and sc["name"] not in a.only:
            continue
        print(f"\n=== {sc['name']}: {sc['about']}", flush=True)
        run = run_window_scenario if sc.get("window") else run_scenario
        findings, problems, output = run(os.path.abspath(a.exe), sc, a.out)
        summary[sc["name"]] = {"findings": findings, "problems": problems}
        for k, v in findings.items():
            if k != "hello":
                print(f"    {k}: {v}")
        if "hello" in findings:
            print(f"    HELLO: {json.dumps(findings['hello'])}")
        if problems:
            failed.append(sc["name"])
            print("  FAIL")
            for p in problems:
                print(f"    - {p}")
            print("  sender output:\n    " + "\n    ".join(output.strip().splitlines()[-60:]))
        else:
            print("  PASS")
        sys.stdout.flush()
    with open(os.path.join(a.out, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print(f"\n{len(summary) - len(failed)} of {len(summary)} scenarios passed" +
          (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
