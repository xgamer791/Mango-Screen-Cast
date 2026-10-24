# Mango Cast Protocol, version 1

This is the protocol the Mango Screen Cast apps use to talk to each other.
A **source** (the Mac or iPhone app) sends its screen and sound. A **receiver**
(the TV app) shows them.

Goals, in order:

1. **Low latency.** No waiting on retransmissions, no frame reordering, no
   buffering we don't need.
2. **Smooth playback on Wi-Fi.** Forward error correction (FEC) hides packet
   loss without a round trip. A lost frame triggers an immediate keyframe
   request.
3. **No setup.** Devices find each other on the local network and connect
   with no passcode or pairing step.

All multi-byte integers are big-endian unless stated otherwise.

---

## 1. Ports

| Port  | Transport | Who listens | Purpose                          |
|-------|-----------|-------------|----------------------------------|
| 47800 | TCP       | source      | control (receiver-initiated)     |
| 47801 | TCP       | receiver    | control (source-initiated)       |
| 47802 | UDP       | receiver    | media (video + audio)            |
| 47809 | UDP       | both        | discovery beacons                |

If a port is taken, an implementation may bind any free port and advertise
that port instead (DNS-SD record, beacon, `CAPS.udpPort`).

## 2. Discovery

Two mechanisms run side by side. DNS-SD finds Apple devices quickly. The
UDP beacon covers TV firmware where multicast DNS is broken or filtered.
Implementations merge results by `id`.

### 2.1 DNS-SD (Bonjour)

| Role     | Service type      | Port              |
|----------|-------------------|-------------------|
| source   | `_mangocast._tcp` | source control    |
| receiver | `_mangotv._tcp`   | receiver control  |

The instance name is the device's display name. TXT keys:

| Key     | Example        | Meaning                              |
|---------|----------------|--------------------------------------|
| `v`     | `1`            | protocol version                     |
| `id`    | UUID string    | stable device id                     |
| `role`  | `source`       | `source` or `receiver`               |
| `os`    | `macos`        | `macos`, `ios`, `android`            |
| `model` | `Mac15,3`      | hardware model, informational        |
| `w`,`h` | `3840`,`2160`  | receiver only: native panel size     |

### 2.2 UDP beacon

A device that is discoverable sends a UTF-8 JSON datagram to
`255.255.255.255:47809` (and to each interface's directed broadcast
address) every 2 seconds:

```json
{"mango":1,"role":"source","id":"6B0D…","name":"Ana's MacBook Pro",
 "os":"macos","model":"Mac15,3","port":47800}
```

Receivers add `"w"` and `"h"`. A device that just started looking sends
the same object with `"probe":true`. Any device that receives a probe
answers right away with its own beacon, sent unicast to the probe's source
address on port 47809. Entries expire 7 seconds after their last beacon.

## 3. Control channel (TCP)

Either side may open the connection. The roles come from the `HELLO`
messages, not from who connected. Both ends set `TCP_NODELAY`.

### 3.1 Framing

```
+-----------------+--------+----------------------+
| length (u32 BE) | type   | payload              |
|                 | (u8)   | (length - 1 bytes)   |
+-----------------+--------+----------------------+
```

`length` counts the type byte plus the payload. Maximum `length` is
16 MiB. A message with an unknown type is ignored.

| Type | Name        | Direction           | Payload          |
|------|-------------|---------------------|------------------|
| 0x01 | `HELLO`     | both                | JSON             |
| 0x02 | `CAPS`      | receiver → source   | JSON             |
| 0x03 | `START`     | source → receiver   | JSON             |
| 0x04 | `STOP`      | both                | JSON             |
| 0x05 | `KEYFRAME`  | receiver → source   | JSON             |
| 0x06 | `STATS`     | receiver → source   | JSON             |
| 0x07 | `PING`      | both                | JSON             |
| 0x08 | `PONG`      | both                | JSON             |
| 0x09 | `MEDIA`     | source → receiver   | binary (§4)      |
| 0x0A | `TRANSPORT` | receiver → source   | JSON             |

### 3.2 Session flow

```
source                                   receiver
  | ---------------- TCP connect (either way) ---------------- |
  | -- HELLO ------------------------->                         |
  |                         <------------------------ HELLO --  |
  |                         <------------------------- CAPS --  |
  | -- START ------------------------->                         |
  | == media over UDP ================>                         |
  |                         <---------- STATS (every second) -  |
  |                         <-------------- KEYFRAME (on loss)  |
  | -- PING / PONG (every second, both ways) ----------------  |
  | -- STOP (or close) -------------->                          |
```

1. As soon as the connection opens, each side sends `HELLO`.
2. The receiver sends `CAPS` right after its `HELLO`.
3. When the source has the receiver's `CAPS`, it picks a mode and sends
   `START`, then starts sending media to the receiver's IP (the TCP peer
   address) on `CAPS.udpPort`.
4. The source may send a new `START` at any time to change the stream
   (resolution, codec, rotation). Each `START` has a new `session` value.
   The receiver drops media packets whose session does not match the
   latest `START`.
5. Either side ends the session with `STOP` or by closing the connection.
   If no message of any kind arrives for 5 seconds, the peer is gone.

A source that is already streaming answers a second receiver with
`STOP {"reason":"busy"}`.

### 3.3 Messages

**HELLO**

```json
{"proto":1,"role":"source","id":"6B0D…","name":"Ana's MacBook Pro",
 "os":"macos","model":"Mac15,3","app":"1.0.0"}
```

**CAPS** — the receiver works out what it can show. The source only
picks from it. This keeps the "what resolution is this TV?" logic in the
TV app, which knows its panel and decoders.

```json
{
  "display": {"width":3840,"height":2160,"refresh":[60,50,30]},
  "modes": [
    {"width":3840,"height":2160,"fps":60,"codec":"hevc"},
    {"width":2560,"height":1440,"fps":60,"codec":"hevc"},
    {"width":1920,"height":1080,"fps":60,"codec":"h264"}
  ],
  "audio": {"codec":"pcm_s16le","rate":48000,"channels":2},
  "udpPort": 47802,
  "maxBitrateKbps": 150000,
  "transports": ["udp","tcp"]
}
```

* `display` is the physical panel (not the UI resolution, which is often
  1080p on 4K Android TVs).
* `modes` is ordered best first. Every entry is a size, frame rate and
  codec that the panel can show **and** a hardware decoder can decode in
  real time. A source picks the first entry it can encode.
* A source may send a smaller picture than the chosen mode (for example a
  portrait phone screen inside a 3840×2160 mode). `START.video.width`
  and `height` must each be ≤ the mode's, and even.
* `audio` is absent if the receiver can't play audio.

**START**

```json
{
  "session": 3405691582,
  "video": {"codec":"hevc","width":3840,"height":2160,"fps":60,
            "bitrateKbps":60000},
  "audio": {"codec":"pcm_s16le","rate":48000,"channels":2,"frameMs":10},
  "transport": "udp",
  "content": "extend"
}
```

* `session`: random non-zero u32. Media packets carry its low 16 bits.
* `video.codec`: `hevc` or `h264`. The bitstream is Annex B (start
  codes). Every keyframe starts with its parameter sets (VPS/SPS/PPS for
  HEVC, SPS/PPS for H.264). No B-frames.
* `audio`: `null` when audio is off.
* `content`: `extend` (TV is a second display), `mirror`, or `phone`.
  Informational.

**STOP** — `{"reason":"user"}`. Reasons: `user`, `busy`, `error`,
`replaced`, `unsupported`. Optional `"message"` for humans.

**KEYFRAME** — `{"reason":"loss"}`. The source must encode the next frame
as a keyframe. Reasons: `start`, `loss`, `decoder`. A receiver should not
send more than one every 100 ms.

**STATS** — sent by the receiver about once a second:

```json
{"intervalMs":1000,"packets":5210,"lostPackets":3,"recoveredPackets":3,
 "frames":60,"lostFrames":0,"droppedFrames":0,
 "decodeMs":4.1,"jitterMs":1.2}
```

* `packets`: media datagrams received in the interval.
* `lostPackets`: shards (data or parity) never received, counted when a
  frame finishes or is given up on.
* `recoveredPackets`: data shards rebuilt by FEC.
* `lostFrames`: frames that could not be rebuilt.
* `droppedFrames`: frames decoded but not shown (pacing catch-up).

**PING / PONG** — `{"t": 123456789}`. `t` is the sender's monotonic clock
in microseconds. The peer echoes it back in a `PONG` so the sender can
measure round-trip time.

**TRANSPORT** — `{"mode":"tcp"}`. If a receiver has had no media
datagram 1.5 seconds after a `START` with video, it asks for TCP
fallback. The source then sends each media packet as a `MEDIA` control
message instead of a datagram. FEC is optional in this mode.

## 4. Media packets

Each UDP datagram (or `MEDIA` payload) is one **shard**: a 32-byte header
followed by `shardSize` bytes. The datagram is at most 1400 bytes, so a
shard holds at most 1368 bytes. That fits any normal LAN or Wi-Fi MTU
with room for VLAN or VPN overhead, so IP never fragments the datagram.

```
offset size field
0      1    magic           0x4D ('M')
1      1    version | type  high nibble: version (1)
                            low nibble: 1 = video, 2 = audio
2      2    session         low 16 bits of START.session
4      4    frame number    increments by 1 per frame, per type (wraps)
8      4    frame length    bytes in the frame, before padding
12     8    timestamp       capture time, µs, source monotonic clock
20     1    flags           0x01 keyframe
                            0x02 frame starts with parameter sets
21     1    block index     0 ≤ index < block count
22     1    block count     ≥ 1
23     1    shard index     0..k-1 data, k..k+m-1 parity
24     1    k               data shards in this block (1..255)
25     1    m               parity shards in this block (0..254), k+m ≤ 255
26     2    shard size      bytes of payload in every shard of this block
28     4    block offset    offset in the frame of this block's first byte
32     …    shard payload   exactly `shard size` bytes
```

### 4.1 Frames, blocks and shards

A **frame** is one encoded video access unit, or one chunk of audio. A
source splits it like this (the receiver only needs the header fields):

```
T  = ceil(L / 1368)              total data shards (L = frame length ≥ 1)
S  = ceil(L / T)                 shard size, same for the whole frame
B  = ceil(T / 128)               block count
k_b = floor(T / B) + (b < T mod B ? 1 : 0)     data shards in block b
offset_b = S × (k_0 + … + k_{b-1})
m_b = 0                                   if FEC is off
      min(255 - k_b, max(1, ceil(k_b × fecPercent / 100)))  otherwise
```

Data shard `i` of block `b` holds frame bytes
`[offset_b + i×S, offset_b + (i+1)×S)`. Bytes past the end of the frame
are zero. The receiver strips them using `frame length`.

Within a frame, a source sends shards in order: block 0 data, block 0
parity, block 1 data, and so on. Sources should pace large frames (spread
packets out over a few milliseconds) rather than bursting them, because
Wi-Fi access points drop bursts.

A receiver can rebuild a block from **any** `k` of its `k + m` shards.
When all data shards arrive, the parity shards are not needed.

### 4.2 Reed–Solomon code

* Field: GF(2⁸), primitive polynomial x⁸ + x⁴ + x³ + x² + 1 (0x11D),
  generator 2.
* `exp(a, n)` = aⁿ in the field, with `exp(a, 0) = 1` for every `a`
  (including 0) and `exp(0, n) = 0` for `n > 0`.
* Build the `n × k` Vandermonde matrix `V[r][c] = exp(r, c)`, `n = k + m`.
* Encoding matrix `E = V × inverse(top k rows of V)`. Its top `k` rows are
  the identity, so data shards go out unchanged.
* Parity shard `j` = Σ over `c` of `E[k + j][c] × data_c`, computed byte
  by byte.
* To decode, take any `k` received shards. Take the rows of `E` for their
  indices, invert that `k × k` matrix, and multiply it by the shards to get
  the data back.

This is the same construction as Backblaze's JavaReedSolomon and
klauspost/reedsolomon. `protocol/test-vectors.json` has reference
outputs that every implementation's tests check against.

### 4.3 Receiving video

* Deliver frames to the decoder in frame-number order.
* Give up on a frame when any frame two or more numbers later has
  started arriving, or 60 ms after its first shard arrived.
* After giving up on a video frame, drop frames until the next keyframe
  and send `KEYFRAME`. If no keyframe arrives within 500 ms, ask again.
* Compare frame numbers with serial-number arithmetic (signed 32-bit
  difference), because they wrap.

### 4.4 Audio

* Format: `pcm_s16le`, 48 kHz, 2 channels, interleaved. A frame is
  `frameMs` (10) milliseconds: 1920 bytes, which goes out as `k = 2`
  shards of 960 bytes plus 1 parity shard.
* PCM on a LAN costs 1.5 Mbit/s, small next to the video, and needs no
  encoding or decoding time.
* Receivers keep a small jitter buffer (about 30–60 ms). They fill a
  missing frame with silence, and drop or repeat a few samples to absorb
  clock drift between the two devices.

## 5. Rate control (recommended)

The source starts at `START.video.bitrateKbps` with 10% FEC and adjusts on
every `STATS` message:

| Condition                                    | Action                              |
|----------------------------------------------|-------------------------------------|
| `lostFrames > 0` or loss > 3%                | bitrate × 0.8, FEC + 10 (max 50%)    |
| loss > 1%                                    | FEC + 5 (max 40%)                   |
| loss < 0.3% for 3 reports in a row           | bitrate × 1.08 (up to target), FEC − 2 (min 10%) |

Loss is `lostPackets / (packets + lostPackets)`. Bitrate never drops
below 20% of the starting value.

## 6. Security

The protocol has no pairing, by design: connecting is a single click.
To limit exposure:

* Sources only listen, advertise and accept connections while the user
  has **Beam** turned on.
* Receivers only accept a session while the TV app is running.
* Both sides only talk to private, link-local and loopback addresses
  (RFC 1918, RFC 4193, 169.254/16, 127/8).

Anyone on the same network can see a beaming screen while Beam is on.
Turn Beam off on networks you don't trust.
