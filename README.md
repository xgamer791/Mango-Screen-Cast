# Mango Screen Cast For macOS/iOS

Turns an Android TV, Google TV or Fire TV into an **AirPlay screen-mirroring
receiver**. Macs, iPhones and iPads find the TV in their built-in **Screen
Mirroring** menu. You don't install anything on them and there's no code to
type in.

For Windows PCs there's a separate app, [Mango Screen Cast For Windows](https://github.com/xgamer791/Mango-Screen-Cast-For-Windows), with its own TV app. The two can be installed side by side on the same TV and don't share anything.

There's also an **iPad and iPhone app, Mango Cast**, that does the same on an
iPad: put your Mac's screen on it, or use it as a second display. See
[iPad and iPhone](#ipad-and-iphone).

- **Mac:** mirror the screen, or choose **Use As Separate Display** to use the
  TV as a second monitor. The TV offers 120 fps when its screen and decoder
  can show it (60 otherwise), and 4K (3840 × 2160) with HEVC whenever its
  decoder takes it at that rate, so the Mac renders for the full panel
  instead of 1080p.
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

**Download:** get `MangoScreenCast.apk` from the
[latest release](../../releases/latest) (or, before the first release, from
the `MangoScreenCast-apk` artifact of the latest
[Android TV app workflow](../../actions/workflows/android.yml) run). Then
sideload it from a computer on the same network with
[adb](https://developer.android.com/tools/adb) (turn on *Developer options →
Network debugging* on the TV first):

```sh
adb connect <tv-ip-address>
adb install -r MangoScreenCast.apk
# Lets the player open by itself when mirroring starts while another app is
# on screen (Google TV has no settings screen for this):
adb shell appops set com.mangoscreencast.tv SYSTEM_ALERT_WINDOW allow
# Lets the app install its own updates without asking:
adb shell appops set com.mangoscreencast.tv REQUEST_INSTALL_PACKAGES allow
```

Apps such as *Downloader* also work for the install itself; the two `appops`
lines need adb.

**Updates:** the app checks this repository's latest GitHub Release every few
hours and installs newer versions by itself while it's on screen and nothing
is mirroring (Settings → Updates: *Automatic*, *Ask first* or *Off*). CI
publishes a release for every push to `main` or the development branch once
the `MANGO_KEYSTORE_BASE64` Actions secret holds the signing key (a base64'd
keystore; optional `MANGO_KEYSTORE_PASSWORD`, `MANGO_KEY_ALIAS` and
`MANGO_KEY_PASSWORD` default to `android`, `androiddebugkey`, `android`).
Every release must be signed with the same key as the copy on the TV, or
Android refuses the update.

**Build it yourself:**

```sh
git clone --recursive https://github.com/xgamer791/Mango-Screen-Cast
cd Mango-Screen-Cast/android-tv
./gradlew assembleRelease   # needs Android SDK 35, NDK 27.2, CMake 3.22
# -> app/build/outputs/apk/release/app-release.apk
```

## Use

1. Open **Mango Screen Cast For macOS/iOS** on the TV. It shows *Ready to mirror* and the
   name to look for.
2. On a **Mac**: Control Center → Screen Mirroring → your TV. To extend the
   desktop instead of mirroring it, choose **Use As Separate Display**. No
   code is needed. On macOS 26, Screen Mirroring can be removed from Control
   Center; add it back with Control Center → **Edit Controls**, or show it in
   the menu bar under System Settings → **Menu Bar**. Without it, use System
   Settings → Displays → **Add Display** and pick the TV.
3. On an **iPhone or iPad**: Control Center → Screen Mirroring → your TV.
4. To stop: stop mirroring on the Apple device, or press **Back twice** on the
   TV remote. Press **OK** while mirroring to show live stats (resolution,
   fps, decode time).

**Settings** (on the TV's home screen):

| Setting | What it does |
|---|---|
| TV name | The name Apple devices show. *Mango Screen Cast For macOS/iOS* unless you change it. |
| Resolution | *720p* (1280 × 720, default), *Auto* (the most the TV can decode, up to 4K), *4K*, *2K* (1440p) or *1080p*. |
| Frame rate | The most frames per second Apple devices may send: *120 fps* (default), *60 fps* or *30 fps*. 120 needs a 120 Hz mode on the TV's screen and a decoder rated for it; a TV without them offers 60. With Resolution on Auto the picture shrinks (usually to 1440p) until the decoder is rated for 120; choose *60 fps* to keep 4K on such a TV. Apple devices treat this as an upper limit and may still send 60; *Show stats* shows what arrives. |
| Frame pacing | *Balanced* (default) shows each frame right away, one per screen refresh. *Fastest* shows frames the moment they're decoded. *Smoothest* holds one frame back to absorb Wi-Fi jitter. |
| iPhone in landscape | *Fill the screen* (default) or *Fit whole picture*. iPhones are wider than a TV, so the whole picture leaves black bars above and below; Fill zooms in to cover the TV and cuts off the left and right edges (a 16:9 video then fills the TV exactly). Mac screens and portrait phones are never cropped. While mirroring, press **Up** or **Down** to switch. |
| Sound | Play the device's audio on the TV. |
| Show stats | Overlay while mirroring: resolution, codec, fps, the bitrate the sender is using, decode time. |
| Diagnostics | What the TV detected (panel, decoders), what it offers Apple devices, and the recent AirPlay and decoder log. Include this when reporting a problem. |
| Stay visible after closing the app | Keeps the TV in Screen Mirroring lists while you use other apps, and after the TV restarts. For mirroring to bring the player up by itself, Android also needs *Display over other apps* for the app; on Google TV grant it with the `SYSTEM_ALERT_WINDOW` adb line above. Diagnostics shows whether it's granted. |
| Updates | *Automatic* (default) installs new releases while nothing is mirroring; *Ask first* shows an Update button on the home screen. |

## iPad and iPhone

**Mango Cast**, the app in `ios/`, makes an iPad (or iPhone) an AirPlay
receiver, with the same AirPlay core as the TV app. Your Mac finds it in Screen
Mirroring as *Mango Cast iPad*, and mirrors to it or uses it as a second display
with **Use As Separate Display**. Sound plays on the iPad.

**Resolution fills the screen.** By default the iPad offers exactly its own
screen: its full pixel count (for example 2360 × 1640 on an iPad Air,
2752 × 2064 on a 13-inch iPad Pro), in the orientation it's held, with HEVC. So
a separate display fills the screen edge to edge at full sharpness, in
landscape or portrait. Turn the iPad while nothing is connected and it offers
the new shape to the next Mac. Macs that can't send HEVC (Intel Macs) get the
same shape at about 1080p.

To find a sweet spot (smoother over busy Wi-Fi, or bigger text on a separate
display), Settings → **Resolution** lists smaller sizes: 90%, 80%, 75%, 66%,
60% and 50% of full. **Every size listed has the iPad's exact shape**, so
whatever size you pick, the picture still fills the screen. That's the size
the iPad tells the Mac (or iPhone, or any other AirPlay sender) to send. On the
Mac, leave the AirPlay display's resolution in System Settings → Displays at
its default, which follows what the iPad offers.

**Backing out.** A new size is only tried at first, the way a Mac treats a
display change:

- The next time you mirror, Mango Cast asks **Keep 1888 × 1312?** with a
  20-second countdown. Tap **Keep**, or **Go back**.
- It goes back to the last size that worked by itself if you don't answer, if
  no picture arrives within 15 seconds, if the picture keeps failing, if
  mirroring stops before you keep it, or if the app closes mid-try.
- While a size is being tried, the home screen says so with a **Go back**
  button, and Settings has one too.
- Whenever you're below full size, the home screen has **Back to full
  resolution**, which is always safe.

A *mirrored* Mac keeps its own shape (a MacBook is wider than an iPad), so it
shows with thin bars; tap **Fill** (or double-tap) to zoom it to cover the
screen instead. Windows PCs can't send AirPlay on their own; an AirPlay sender
app for Windows gets the same sizes.

It needs iPadOS or iOS 16 or newer, and the Mac and the iPad on the same
Wi-Fi network.

### Install

Apple only lets you install apps from outside the App Store if they're signed
with your own Apple ID. A free Apple ID works.

**With Xcode on your Mac:**

1. Get the code with its submodules, and open the project:
   ```sh
   git clone --recursive https://github.com/xgamer791/Mango-Screen-Cast
   open Mango-Screen-Cast/ios/MangoScreenCast.xcodeproj
   ```
   Xcode 15 or newer. It downloads OpenSSL by itself the first time.
2. Click the **MangoScreenCast** project, then the **MangoScreenCast** target →
   **Signing & Capabilities**, and pick your Apple ID under **Team** (Add an
   Account… if it isn't there). If Xcode says the bundle identifier isn't
   available, change `com.mangoscreencast.ios` to something of your own, such
   as `com.yourname.mango`.
3. Connect the iPad with a cable, pick it at the top of the Xcode window, and
   press **Run** (⌘R).
4. The first time, the iPad asks for two things: turn on **Developer Mode**
   (Settings → Privacy & Security, then restart), and trust your Apple ID
   under Settings → General → **VPN & Device Management**.

With a free Apple ID, the app stops opening after 7 days. Press Run in Xcode
again to renew it. A paid developer account makes it last a year.

**Without Xcode (and with updates):** use [AltStore](https://altstore.io) or
[SideStore](https://sidestore.io), which install apps signed with your Apple ID.
Add this source in AltStore (Browse → Sources → **+**) or SideStore:

```
https://github.com/xgamer791/Mango-Screen-Cast/releases/download/ios-latest/altstore-source.json
```

then install **Mango Cast** from it. You can also install the IPA from the
[`ios-latest` pre-release](../../releases/tag/ios-latest)
(`MangoScreenCast-unsigned.ipa`) with [Sideloadly](https://sideloadly.io).

### Updates

iOS only lets the App Store update apps by itself, so Mango Cast can't install
its own updates the way the TV app does. Instead:

- Every push to `main` or the development branch builds a new version
  (1.0.*n*) and publishes it to the source above.
- **AltStore and SideStore** show the new version under My Apps; tap
  **Update**. AltStore also renews the app in the background every week (with
  AltServer running on your Mac), so a free Apple ID's 7-day limit never
  stops it opening.
- **The app checks too**, at launch and every few hours while it's open. When a
  newer version is out, the home screen says so, and **Update** opens AltStore
  or SideStore on it. Settings → **Updates** shows the version you have and
  has a button that adds the source to AltStore or SideStore.
- **Built with Xcode?** Get the latest code and press Run again.

For fully automatic updates (no tap), a paid Apple Developer account and
TestFlight are the only way; this repository doesn't set that up.

### Use

1. Open **Mango Cast** on the iPad. The first time, allow **Local Network**
   access. Without it your Mac can't see the iPad.
2. On the Mac: Control Center → **Screen Mirroring** → *Mango Cast iPad*. To
   use the iPad as a second display instead of a copy, choose **Use As
   Separate Display**. Without Screen Mirroring in Control Center, use System
   Settings → Displays → **Add Display**.
3. Tap the iPad's screen while mirroring to show who's mirroring, **Fill** /
   **Fit**, a **Stop** button and live stats. Double-tap to switch Fill / Fit.
   Pinch to zoom in. To tap, scroll and type on the Mac instead, see
   [Control the Mac from the iPad](#control-the-mac-from-the-ipad).

**Leaving the app doesn't disconnect.** Switch to another app or lock the iPad
and your Mac stays connected, and the iPad stays in Screen Mirroring lists;
only closing Mango Cast (swiping it away in the app switcher) disconnects it.
iOS suspends apps in the background unless they play sound, so Mango Cast
keeps its audio output running, silently when there's nothing to play and
mixed with other apps' sound. iOS doesn't let apps decode video in the
background either, so leaving while the Mac mirrors shows its screen in
**Picture in Picture**, and the picture is live the moment you come back.
Close the floating window and the connection still stays; the picture
catches up when you return (or, after a long time away, at the Mac's next
full frame). A phone call or Siri can still pause the app; if that happens,
Mango Cast restarts AirPlay when you come back.

**Settings** (gear button on the home screen):

| Setting | What it does |
|---|---|
| Name | What your Mac shows in Screen Mirroring. *Mango Cast iPad* unless you change it. |
| Resolution | Full (default) or 90% down to 50% of it, all in the iPad's exact shape. New sizes are tried first and go back by themselves unless kept (see above). |
| Fill the screen | Zoom a mirrored picture of another shape to cover the screen, cutting off its edges. Same as Fill / Fit while mirroring. |
| Sound | Play the Mac's audio on the iPad. |
| Keep running in the background | On by default: leaving the app or locking the screen keeps the Mac connected (see above). Off, Mango Cast stops receiving when you leave it. |
| Picture in Picture when you leave | On by default: leaving while a Mac mirrors shows its screen in a floating window. |
| Show stats while mirroring | Resolution, codec, frame rate and bitrate in a corner. |
| Keep the screen on while waiting | On by default. It always stays on while mirroring. |
| Updates | The version you have, Check for updates, and a button that adds the update source to AltStore or SideStore. |
| Mac control | Link with a Mac running Mango Cast Remote (once), unlink Macs, and turn controlling on or off. |
| Diagnostics | What the iPad offers and the recent AirPlay log, with a Share button. |

### Control the Mac from the iPad

AirPlay only carries the picture and sound, one way. To work on the Mac from
the iPad, run **Mango Cast Remote** on the Mac: a small menu bar app that plays
the iPad's taps and typing there. The controls are the same as Holli Bot's
Screen on iOS. There's no trackpad on the picture; the picture is the Mac's
screen:

| On the iPad | On the Mac |
|---|---|
| Tap | Clicks there. Two quick taps double-click, three triple-click. |
| Swipe with one finger | Scrolls where the finger is, up and down or sideways (whichever way it went most). The page follows the finger, and a quick swipe carries on and slows down. |
| Pinch | Zooms the iPad's picture, up to 6×, about the fingers (nothing happens on the Mac). Zoomed in, a finger moves the picture around and a tap still clicks exactly what's under it. The zoom button (**2.5×**, say) shows the whole screen again. |
| Keyboard button | The iPad's keyboard types on the Mac as you type: suggestions, dictation, emoji and other languages included. Return, Backspace and, on a keyboard attached to the iPad, Tab, Esc, the arrows, F1–F12 and shortcuts like ⌘C, ⌘Tab or ⌃C are pressed on the Mac. ⌘V pastes what the iPad copied, as typing. |
| Mouse or trackpad attached to the iPad | The pointer follows it; click, right-click (two-finger click), drag and scroll. |

**Set it up once:**

1. Download `MangoCastRemote-mac.zip` from the
   [`ios-latest` pre-release](../../releases/tag/ios-latest) (the iPad can
   AirDrop you the link: Settings → **Link with a Mac**), open it, and move
   **MangoCastRemote** to Applications. (Or build the **MangoCastRemote**
   scheme in Xcode.) It's signed ad hoc, not by Apple, so the first time,
   Control-click it → **Open**, or allow it under System Settings → Privacy &
   Security → **Open Anyway**.
2. The first time it runs, it asks to be allowed under Privacy & Security →
   **Accessibility** (macOS only lets apps it lists there click and type),
   and adds itself to your login items, so it's always there after that. It
   lives in the menu bar (a pointer icon).
3. On the iPad, tap **Link** (on the mirrored picture, or Settings → **Link
   with a Mac**). A code pops up on the Mac; enter it on the iPad.

That's it: they stay linked until you unlink them (Settings → Unlink on the
iPad, or **Unlink all iPads** in the Mac's menu), even if Mango Cast is
deleted and installed again (with the same Apple ID in AltStore or
SideStore): the link is kept in the iPad's keychain, which iOS keeps. From
then on, while that Mac mirrors to the iPad, the iPad connects to it by
itself and the **Controlling** button shows at the top. Tap it to turn
controlling off (taps then show the controls again, as without it).

Everything goes over your Wi-Fi only, encrypted (TLS with a key only linked
devices hold). The code only works while the Mac shows it, for five minutes,
and it's what keeps another device on the network from linking itself.

**After updating Mango Cast Remote**, macOS may see it as a new app: if taps
stop working and the iPad says to allow it under Accessibility, remove
Mango Cast Remote from the Accessibility list with **−** and allow it again.

**Sidecar:** if your Mac and iPad are signed in to the same Apple ID and both
support it, macOS's built-in Sidecar also puts the Mac's screen on the iPad
(it shows up in the same Screen Mirroring menu), with touch and Apple Pencil
input. Mango Cast works across Apple IDs, on iPads that Sidecar doesn't
support, and from iPhones and other iPads too.

## What makes it fast

The app removes as much delay as the TV side can:

- **The network is never kept waiting.** Each frame is handed off to a
  decoder thread the moment it arrives. Apple devices lower their bitrate
  when a receiver reads slowly, so this keeps the picture at full quality.
- **Hardware decoding** straight onto a video overlay surface, with each chip
  vendor's low-latency decoder modes switched on (Android 11 low-latency,
  Qualcomm, MediaTek, Realtek, HiSilicon).
- **Game mode.** The player asks the TV for ALLM / minimal post-processing.
  TV picture processing can otherwise add a lot of delay.
- **120 Hz output.** For a 120 fps stream the player switches the TV to its
  largest 120 Hz mode. At 60 fps, if the TV is set to 50 Hz or 24 Hz, it
  switches to a 60 Hz native-resolution mode so frames play evenly.
- **Frame pacing** that never shows two frames in one refresh, without adding
  a buffer by default.
- A **low-latency Wi-Fi lock** while mirroring, so the TV's Wi-Fi doesn't drop
  into power save.
- **No restart on screen changes.** If the player goes to the background, the
  decoder keeps running off-screen, so the picture comes back instantly
  without waiting for a keyframe.
- **Clean starts.** The decoder always starts on a keyframe and is sized from
  the stream itself, so rotating an iPhone or changing the Mac's resolution
  doesn't leave a blocky picture.

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
- **iPad app:** it keeps receiving in the background by keeping its audio
  output running, so anything that takes the audio away for good (a long
  phone call) can still pause it until you come back. CI builds it, and its
  tests decode real H.264 and HEVC frames in the simulator.

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
ios/
  MangoScreenCast.xcodeproj   generated by tools/gen_ios_project.py
  MangoCore/               C bridge to the same AirPlay core (built from android-tv/)
  MangoScreenCast/         SwiftUI app
    AirPlay/               Receiver (server lifecycle, status), Bonjour advertising
    Media/                 VideoRenderer (VideoToolbox), AudioPlayer, NAL parsing
    Views/                 Home, Mirror (player), Settings
    Updates/               checks the release feed for newer builds
  MangoScreenCastTests/    unit tests, including decoding real H.264/HEVC frames
  MangoScreenCastUITests/  screenshots of each screen, for checking layouts
tools/make_altstore_source.py   the AltStore / SideStore source CI publishes
```

On the iPad the same UxPlay core is built with Xcode against
[OpenSSL-Package](https://github.com/krzyzanowskim/OpenSSL-Package) (a
prebuilt OpenSSL for Apple platforms). Video goes to an
`AVSampleBufferDisplayLayer`, which decodes it in hardware and shows each frame
as soon as it's ready. The frames since the last keyframe are kept, so if iOS
interrupts the decoder the picture comes back at once. Audio is decoded with
Core Audio's AAC-ELD decoder. Service records are published with Bonjour
(`NetService`).

After adding or removing a source file, run `python3 tools/gen_ios_project.py`
and commit the regenerated project.

The AirPlay protocol comes from [UxPlay](https://github.com/FDH2/UxPlay), an
actively maintained open-source AirPlay mirroring server. It handles pairing,
FairPlay, decryption and the RTP streams. It's built for Android with the NDK
against Google's prebuilt OpenSSL. Its desktop renderers (GStreamer) are left
out: decoded video and audio go to Android's MediaCodec instead.

The service records that make the TV show up in Screen Mirroring are built by
UxPlay and published through Android's NsdManager. The TV offers
`width × height @ 120 fps` (or 60) from its panel and decoders, and adds HEVC
when it can decode it. That's what lets Apple devices send more than 1080p.

**Tests:** `./gradlew testDebugUnitTest` runs the Kotlin unit tests.
`android-tv/native-test` builds the AirPlay core on Linux. It checks the
service records and the `/info` reply Apple devices read (display size,
60 fps, HEVC flag, no password). CI runs both and builds the APKs. For the
iPad app, CI runs its tests on an iPad simulator, builds the unsigned IPA, and
takes screenshots of the home and Settings screens on every iPad simulator (and
an iPhone) in both orientations (the `ios-screenshots` artifact).

## License

The app links UxPlay, which is GPL-3.0, so if you distribute the app it must
be under GPL-3.0. libplist is LGPL-2.1. OpenSSL 1.1.1 is under the OpenSSL
license. AirPlay is a trademark of Apple Inc. This project isn't affiliated
with Apple.
