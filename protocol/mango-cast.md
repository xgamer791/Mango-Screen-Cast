# Mango Cast protocol, version 1

How a computer that can't send AirPlay (Windows) puts its screen and sound on
a Mango Screen Cast receiver (the TV app, and later the iPad app). Mango
writes both ends, so it's kept simple: one TCP connection on the local
network, the screen as H.264 or HEVC, the sound as plain PCM.

AirPlay keeps working exactly as before; this is a second way in.

## Finding receivers

A receiver announces itself with DNS-SD (mDNS) as `_mangocast._tcp`, under
its name (the TV name in its settings), on the port it listens on. Its TXT
record:

| Key | Value |
|---|---|
| `v` | Protocol version: `1` |
| `id` | The receiver's id (stable across restarts) |
| `model` | The device, e.g. `NVIDIA SHIELD Android TV` |
| `w`, `h` | The largest picture it takes, in pixels, e.g. `3840`, `2160` |
| `fps` | The most frames per second it takes, e.g. `60` |
| `codecs` | What it decodes, in order of preference: `h265,h264` or `h264` |
| `audio` | `1` when it plays sound, `0` when sound is off |

Receivers listen on port **7270** when it's free, so a sender can also be
pointed at an address by hand; the port in the announcement is the one to use.

## Messages

Every message, both ways, is:

| Bytes | |
|---|---|
| 1 | Type |
| 4 | Payload length, big-endian, at most 16 MiB |
| n | Payload |

Numbers in payloads are big-endian. A receiver of a message type it doesn't
know skips its payload and carries on.

| Type | Name | From | Payload |
|---|---|---|---|
| `0x01` | HELLO | sender | JSON (below) |
| `0x02` | WELCOME | receiver | JSON (below) |
| `0x03` | REFUSE | receiver | JSON `{"reason", "message"}`; the receiver then closes |
| `0x10` | VIDEO | sender | 8-byte timestamp (µs), then one access unit |
| `0x11` | KEYFRAME | receiver | empty: send a keyframe |
| `0x20` | AUDIO | sender | 8-byte timestamp (µs), then PCM samples |
| `0x30` | PING | either | 8-byte timestamp (µs) |
| `0x31` | PONG | either | the PING's payload, unchanged |
| `0x3F` | BYE | either | optional UTF-8 reason; the sender of BYE then closes |

JSON is UTF-8. Fields not listed here are ignored, so either side can add some
without breaking the other.

### Starting

1. The sender connects and sends **HELLO** within 5 seconds:

   ```json
   {"protocol": 1, "name": "DESKTOP-4F2K", "os": "Windows 11",
    "codecs": ["h265", "h264"], "width": 2560, "height": 1440, "fps": 60, "audio": true}
   ```

   `codecs` is what the sender can encode, in its order of preference;
   `width` and `height` are its screen's; `fps` the most it will send.

2. The receiver answers **WELCOME** or **REFUSE**:

   ```json
   {"protocol": 1, "name": "Living Room", "codec": "h265",
    "maxWidth": 3840, "maxHeight": 2160, "fps": 60,
    "audio": {"format": "pcm_s16le", "rate": 48000, "channels": 2}}
   ```

   `codec` is the first of the sender's codecs the receiver decodes. With
   `h264`, `maxWidth` × `maxHeight` is at most 1920 × 1080 (the receiver's
   shape, smaller), as Apple devices send H.264. `audio` is `null` when the
   receiver's sound is off; the sender then sends none.

   REFUSE reasons: `busy` (another device is mirroring), `protocol` (no
   shared version or codec), `off` (Windows connections are turned off on the
   receiver). `message` says so in words a person can read.

3. The sender scales its picture to fit inside `maxWidth` × `maxHeight`,
   keeping its shape, both sides rounded down to even numbers, and sends media
   at up to `fps` frames per second. Nothing is sent before WELCOME.

### Video

Each VIDEO message is one access unit (one picture) in **Annex B** form: NAL
units with start codes, as `ffmpeg -f h264` writes them.

- The first VIDEO message is a keyframe (H.264 IDR; HEVC IRAP), and **every
  keyframe starts with its parameter sets** (HEVC: VPS, SPS, PPS; H.264: SPS,
  PPS) in the same message, before the picture.
- No access unit delimiters (H.264 NAL type 9, HEVC type 35). SEI is allowed
  after the parameter sets.
- The picture size may change (the sender's screen did); the next keyframe
  carries the new parameter sets.
- TCP never loses data, so keyframes are only needed at the start, when the
  size changes, and when the receiver sends **KEYFRAME** (its decoder
  restarted). A sender may still send one every few seconds.

The timestamp is the sender's clock when it captured the picture. The
receiver shows each picture as soon as it's decoded, so it's only for
statistics.

A sender keeps the delay down when the network can't keep up: when its
unsent data is more than about 3 frames' worth, it drops pictures until it
can send a keyframe again, and it lowers its bitrate.

### Sound

Each AUDIO message is interleaved signed 16-bit little-endian PCM, at the rate
and channels WELCOME gave (48 kHz stereo), a whole number of sample frames,
best 10 to 20 ms of them. The receiver plays it as it comes, with very little
buffering.

### Staying connected

Each side sends something at least every 2 seconds (a PING when nothing else
is going), and answers a PING with a PONG. A side that hears nothing for
8 seconds closes the connection. Either side ends cleanly with BYE.

One sender at a time: while one is connected, or while an Apple device is
mirroring over AirPlay, a receiver REFUSEs others with `busy`. If an Apple
device starts mirroring while a sender is connected, the receiver ends that
sender's connection with BYE.
