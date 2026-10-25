# Mango Cast for Windows

Puts a Windows PC's screen and sound on the TV running Mango Screen Cast, or
on the iPad running Mango Cast. Windows can't send AirPlay, so `MangoCast.exe`
speaks Mango's own protocol ([Mango Cast](../protocol/mango-cast.md)) to them
instead: the screen as H.264 or HEVC, the sound as plain PCM, over the local
network. Below, "the TV" means either.

It's one program, `MangoCast.exe`: no installer, nothing else to install.

## Requirements

| | |
|---|---|
| **PC** | Windows 10 version 1903 or newer, or Windows 11, 64-bit. |
| **Graphics** | Any graphics card or built-in graphics with Direct3D 11. A hardware video encoder (NVIDIA NVENC, Intel Quick Sync, AMD VCE/VCN) is used when there is one; without one, Windows' own H.264 encoder runs on the CPU, at 1080p at most. |
| **HEVC** | Sent when the PC has a hardware HEVC encoder and the TV decodes HEVC, up to 4K; otherwise H.264, as big as the TV's H.264 decoder plays (1080p on many TVs; an iPad's whole screen). |
| **TV or iPad** | Mango Screen Cast on the TV, or Mango Cast on the iPad, open, with Windows connections on (in its settings). |
| **Network** | The PC and the TV on the same network. Use Ethernet or 5 GHz / 6 GHz Wi-Fi for 1440p and 4K. |

Windows "N" editions lack Media Foundation: add the **Media Feature Pack**
(Settings > Apps > Optional features) first.

## Install

Download `MangoCast.exe` from the
[`windows-latest` pre-release](https://github.com/xgamer791/Mango-Screen-Cast/releases/tag/windows-latest) and run
it. There's nothing to install; keep it wherever you like.

It isn't signed, so the first time Windows may show **"Windows protected your
PC"**: choose **More info**, then **Run anyway**. (If it was downloaded with a
browser that marks files from the internet, you can also right-click it >
Properties > **Unblock**.)

## Use

1. Open **Mango Screen Cast** on the TV, or **Mango Cast** on the iPad.
2. Start `MangoCast.exe`. The TV shows up under *Mango receivers on your
   network* (with its model). If it doesn't, type its address in *Or an
   address* (shown on the TV; `192.168.1.20` or `192.168.1.20:7270`).
3. Pick the **Screen** to send (when the PC has more than one) and whether to
   **Send sound**, then **Connect** (or double-click the TV).
4. The status line says what's being sent, e.g. *Streaming to Living Room —
   2560×1440 HEVC 60 fps, with sound*. **Stop** ends it.

It remembers the last TV, address, screen and sound choice. Closing the window
stops streaming. The tray icon shows the status and has *Stop*.

What's sent:

- **Picture**: the whole screen with the mouse pointer, scaled to fit the TV
  (keeping its shape), at up to 60 frames a second. When nothing on screen
  moves, the last picture is sent again ten times a second, so the TV stays
  sharp and current. If the screen's resolution changes, the stream follows.
- **Bitrate**: about 16 Mbit/s for 1080p60, 25 for 1440p60, 40 for 4K60
  (`--bitrate` to choose). When the network can't keep up, it drops pictures
  until it can send a keyframe, lowers the bitrate, and raises it again once
  things are calm, so the delay stays low.
- **Sound**: whatever the PC is playing on its default playback device, at
  48 kHz stereo. It keeps playing on the PC too. It follows the default
  device if you switch (headphones, HDMI).

## Command line

Without options `MangoCast.exe` opens its window. With them it works from a
command line:

| | |
|---|---|
| `--list [--wait MS]` | Look for receivers (3 s by default) and print each as one JSON line: name, address, port, id, model, size, fps, codecs, sound, and the raw TXT record. |
| `--connect HOST[:PORT]` | Stream to a receiver, by address (port 7270 by default) or by its name as `--list` shows it. Runs until Ctrl+C unless `--seconds` is given. |
| `--seconds N` | Stream for N seconds, then say BYE and exit. |
| `--monitor N` | Which screen: 1 is the main one, then left to right (`--monitors` lists them). |
| `--codec h264\|h265` | Send this codec only (default: the best one both sides can do). |
| `--fps N` | At most N frames a second (default 60; the TV may ask for fewer). |
| `--bitrate MBPS` | Video bitrate in Mbit/s (default: from the picture size). |
| `--no-audio` | Picture only. |
| `--encoder auto\|hardware\|software` | Which kind of video encoder (default auto: hardware first). |
| `--capture auto\|wgc\|duplication` | How to capture the screen (default auto: Windows.Graphics.Capture, else DXGI Desktop Duplication). |
| `--log FILE` | Write a log (works with the window too). |
| `--monitors` | Print the screens as JSON lines. |
| `--diagnose` | What this PC can capture and encode, and its sound device. |
| `--help`, `--version` | |

Exit codes: **0** done, **1** error, **2** bad options, **3** the receiver said
no (the reason is printed), **4** couldn't connect or lost the connection,
**5** the receiver ended the session.

`MangoCast.exe` is a windowed program, so a command prompt doesn't wait for
it. To wait and see the output:

```powershell
.\MangoCast.exe --list | Out-Host
.\MangoCast.exe --connect "Living Room" --seconds 60 | Out-Host; $LASTEXITCODE
```

```bat
start /wait MangoCast.exe --list
```

## Troubleshooting

- **The TV isn't listed.** Check the PC and the TV are on the same network
  (not a guest network, and no VPN on the PC). Windows finds receivers with
  mDNS; if the network's profile is *Public*, try setting it to *Private*
  (Settings > Network & internet). You can always type the TV's address
  instead.
- **"Couldn't connect … no answer".** Mango Screen Cast must be open on the
  TV, with Windows connections on.
- **"… said no: … (busy)".** Something else (an iPhone, iPad or Mac over
  AirPlay, or another PC) is already showing its screen on the TV.
- **A yellow frame round the screen (Windows 10).** That's Windows marking a
  screen being captured; it isn't sent to the TV. To avoid it, start with
  `MangoCast.exe --capture duplication`.
- **Stutters or blurry pictures.** Wi-Fi is usually the limit: use Ethernet
  or 5 GHz, or send less with `--fps 30` or a lower `--bitrate`. The log
  shows when the bitrate was lowered.
- **No sound on the TV.** Sound comes from the PC's *default* playback device:
  pick the one you're actually using (Settings > System > Sound). Check *Send
  sound* is ticked and the TV's sound is on. Muting some sound drivers also
  silences what's sent; turn the volume down instead.
- **A black or frozen picture, or errors about the encoder.** Try
  `--encoder software` (rules out the graphics driver's encoder) or
  `--codec h264`, and update the graphics driver.
- **Logs.** The window writes `%LOCALAPPDATA%\Mango Screen Cast\MangoCast.log`;
  `--log FILE` writes one anywhere. `MangoCast.exe --diagnose | Out-Host`
  shows the encoders, capture and sound device Windows offers.

## Build

Visual Studio 2022 or newer with *Desktop development with C++* (MSVC, the
Windows 10/11 SDK and CMake):

```bat
cmake -S windows -B build -A x64
cmake --build build --config Release
```

The program is `build\Release\MangoCast.exe`, linked with the static C runtime
so it runs anywhere without a redistributable.

The source: `src/core/` is plain C++ (protocol messages, Annex B fixing,
sound conversion, sizes, bitrates and congestion control) and builds anywhere;
`src/win/` is the Windows part: `capture` (Windows.Graphics.Capture),
`duplication` (Desktop Duplication), `convert` (BGRA to NV12 with two pixel
shaders), `encoder` (Media Foundation), `audio` (WASAPI loopback), `net`
(the connection), `discovery` (DNS-SD), `session` (all of it together) and
`ui` (the window).

## Tests

CI ([`windows.yml`](../.github/workflows/windows.yml)) builds on GitHub's
Windows runner and runs:

- `tests/unit_tests.cpp` (CTest): the portable parts.
- `tests/receiver.py`: a test receiver in Python that plays the TV's side of
  the protocol in several scenarios (sizes, frame rates, sound on and off,
  KEYFRAME, REFUSE, BYE, a receiver that goes silent or stops reading, a
  screen that changes size, the window driven like a person would) and
  checks everything `MangoCast.exe` sends, then decodes the video with PyAV.
- `tests/discovery_test.py`: announces a receiver with python-zeroconf and
  checks `--list` finds it, then connects to it by name.

To run them on a Windows PC: `pip install av numpy zeroconf`, start
`tests\moving.ps1` (something moving on screen), then
`python tests\receiver.py --exe build\Release\MangoCast.exe [--sound]`.
