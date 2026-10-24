# Mango Screen Cast

Turns an Android TV, Google TV or Fire TV into an **AirPlay screen-mirroring
receiver**. Macs, iPhones and iPads find the TV in their built-in **Screen
Mirroring** menu. You don't install anything on them and there's no code to
type in.

- **Mac:** mirror the screen, or choose **Use As Separate Display** to use the
  TV as a second monitor. The TV offers its own resolution (up to 4K) at
  60 fps, so the Mac renders for the panel instead of 1080p.
- **iPhone and iPad:** they send the sharpest picture they're able to. Devices
  that can send HEVC go above 1080p.
- **Sound** plays on the TV.

## Requirements

| | |
|---|---|
| **TV** | Android TV / Google TV (Android 7 or newer), or Fire TV. |
| **4K** | A TV whose hardware HEVC decoder handles 4K at 60 fps. Most 4K Android TVs and streaming sticks have one. The app checks and falls back to 1440p or 1080p. |
| **Sender** | Any Mac, iPhone or iPad with Screen Mirroring. Above 1080p needs one that sends HEVC (Apple silicon Macs, recent iPhones and iPads). |
| **Network** | The TV and the Apple device on the same network. For 4K, use Ethernet or 5 GHz / 6 GHz Wi-Fi. |

Samsung (Tizen), LG (webOS) and Roku TVs can't run Android apps. Many of them
already have AirPlay 2 built in.

## Install

**Download:** open the latest run of the
[Android TV app workflow](../../actions/workflows/android.yml) and download the
`MangoScreenCast-apk` artifact. Then sideload `MangoScreenCast.apk` onto the
TV, either with `adb install MangoScreenCast.apk` or with an app such as
*Downloader* (Fire TV, Google TV).

**Build it yourself:**

```sh
git clone --recursive https://github.com/xgamer791/Mango-Screen-Cast
cd Mango-Screen-Cast/android-tv
./gradlew assembleRelease   # needs Android SDK 35, NDK 27.2, CMake 3.22
# -> app/build/outputs/apk/release/app-release.apk
```

## Use

1. Open **Mango Screen Cast** on the TV. It shows *Ready to mirror* and the
   name to look for.
2. On a **Mac**: Control Center → Screen Mirroring → your TV. To extend the
   desktop instead of mirroring it, choose **Use As Separate Display**.
3. On an **iPhone or iPad**: Control Center → Screen Mirroring → your TV.
4. To stop: stop mirroring on the Apple device, or press **Back twice** on the
   TV remote. Press **OK** while mirroring to show live stats (resolution,
   fps, decode time).

**Settings** (on the TV's home screen):

| Setting | What it does |
|---|---|
| TV name | The name Apple devices show. |
| Resolution | Auto uses the TV panel's resolution, up to 4K. Pick a lower one on slow Wi-Fi. |
| Frame pacing | *Balanced* (default) shows each frame right away, one per screen refresh. *Fastest* shows frames the moment they're decoded. *Smoothest* holds one frame back to absorb Wi-Fi jitter. |
| Sound | Play the device's audio on the TV. |
| Show stats | Overlay while mirroring. |
| Stay visible after closing the app | Keeps the TV in Screen Mirroring lists in the background. To let mirroring open the player on its own, Android also needs *Display over other apps* permission (the app asks for it). |

## What makes it fast

The app removes as much delay as the TV side can:

- **Hardware decoding** straight onto a video overlay surface, with each chip
  vendor's low-latency decoder modes switched on (Android 11 low-latency,
  Qualcomm, MediaTek, Realtek, HiSilicon).
- **Game mode.** The player asks the TV for ALLM / minimal post-processing.
  TV picture processing can otherwise add a lot of delay.
- **60 Hz output.** If the TV is set to 50 Hz or 24 Hz, the player switches it
  to a 60 Hz native-resolution mode so 60 fps plays evenly.
- **Frame pacing** that never shows two frames in one refresh, without adding
  a buffer by default.
- A **low-latency Wi-Fi lock** while mirroring, so the TV's Wi-Fi doesn't drop
  into power save.
- **No restart on screen changes.** If the player goes to the background, the
  decoder keeps running off-screen, so the picture comes back instantly
  without waiting for a keyframe.

## Limitations

- **AirPlay's own latency.** Apple devices encode and buffer the stream
  before sending it, and a receiver can't change that. This app keeps the
  TV's share of the delay as small as it can, but AirPlay is not a
  gaming-grade link.
- **Not yet tested on real TVs and Apple devices.** It builds, its unit tests
  pass, and CI checks what the AirPlay server advertises. Please report what
  you see on your hardware.
- **Music-only AirPlay** (sending just audio from Apple Music) uses Apple
  Lossless, which this app doesn't decode yet. Mirroring audio works.
- **AirPlay "video" mode** (an app handing the TV a video link) isn't offered,
  so apps fall back to mirroring the screen.
- Only one device mirrors at a time. A new one takes over the screen.

## How it works

```
android-tv/
  app/src/main/cpp/        JNI bridge, server start-up, dns_sd → NsdManager shim
  app/src/main/java/...    Kotlin app
    airplay/               AirPlayReceiver (server lifecycle, status), ServiceAdvertiser
    media/                 VideoDecoder (MediaCodec + pacing), AudioPlayer, NAL parsing
    display/               works out the resolution and codecs to offer
    ui/                    Home, Mirror (player), Settings
  third_party/uxplay       AirPlay protocol (git submodule)
  third_party/libplist     Apple property lists (git submodule)
  native-test/             host build of the AirPlay core + checks of what it advertises
```

The AirPlay protocol comes from [UxPlay](https://github.com/FDH2/UxPlay), an
actively maintained open-source AirPlay mirroring server. It handles pairing,
FairPlay, decryption and the RTP streams. It's built for Android with the NDK
against Google's prebuilt OpenSSL. Its desktop renderers (GStreamer) are left
out: decoded video and audio go to Android's MediaCodec instead.

The service records that make the TV show up in Screen Mirroring are built by
UxPlay and published through Android's NsdManager. The TV offers
`width × height @ 60 fps` from its panel and decoders, and adds HEVC when it
can decode it. That's what lets Apple devices send more than 1080p.

**Tests:** `./gradlew testDebugUnitTest` runs the Kotlin unit tests.
`android-tv/native-test` builds the AirPlay core on Linux. It checks the
service records and the `/info` reply Apple devices read (display size,
60 fps, HEVC flag, no password). CI runs both and builds the APKs.

## License

The app links UxPlay, which is GPL-3.0, so if you distribute the app it must
be under GPL-3.0. libplist is LGPL-2.1. OpenSSL 1.1.1 is under the OpenSSL
license. AirPlay is a trademark of Apple Inc. This project isn't affiliated
with Apple.
