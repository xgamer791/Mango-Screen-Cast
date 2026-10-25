// MangoCast.exe: the window when started without options, the command line
// otherwise (see --help).
#include <winsock2.h>
#include <windows.h>
#include <shellapi.h>

#include <string>
#include <vector>

#include "capture.h"
#include "console.h"
#include "convert.h"
#include "core/options.h"
#include "d3d.h"
#include "discovery.h"
#include "encoder.h"
#include "log.h"
#include "monitors.h"
#include "net.h"
#include "audio.h"
#include "session.h"
#include "ui.h"
#include "util.h"

using namespace mango;

namespace {

Session* g_session = nullptr;

BOOL WINAPI onConsoleCtrl(DWORD) {
    if (g_session) {
        g_session->stop();
        return TRUE;
    }
    return FALSE;
}

std::string yesNo(bool b) { return b ? "yes" : "no"; }

int diagnose() {
    ComScope com;
    std::string why;
    console::out("Mango Cast for Windows " MANGO_VERSION "\n");
    console::out(windowsName() + " (build " + std::to_string(windowsBuild()) + "), computer " + computerName() + "\n");
    for (const auto& m : listMonitors()) console::out("Screen " + m.label() + "\n");

    D3DDevice d3d;
    if (createD3DDevice(nullptr, false, d3d, &why)) {
        console::out("Graphics: " + d3d.adapterName + (d3d.software ? " (drawn on the CPU)" : "") +
                     "; NV12 drawing: " + yesNo(Nv12Converter::canRenderNv12(d3d.device.Get())) + "\n");
    } else {
        console::out("Graphics: " + why + "\n");
    }
    bool wgc = ScreenCapture::supported(&why);
    console::out("Screen capture (Windows.Graphics.Capture): " + (wgc ? std::string("yes") : "no, " + why) + "\n");

    if (!startMediaFoundation(&why)) {
        console::out("Video encoders: " + why + "\n");
    } else {
        for (Codec codec : {Codec::HEVC, Codec::H264}) {
            for (bool hw : {true, false}) {
                auto list = findEncoders(codec, hw);
                std::string kind = std::string(codec == Codec::HEVC ? "HEVC" : "H.264") + (hw ? " hardware" : " software");
                if (list.empty()) console::out(kind + " encoders: none\n");
                for (const auto& c : list) {
                    std::string err;
                    bool ok = encoderUsable(c, &err);
                    console::out(kind + " encoder: " + c.name + (ok ? " (works)" : " (doesn't work: " + err + ")") + "\n");
                }
            }
        }
        auto codecs = encodableCodecs("auto", "", &why);
        std::string offer;
        for (Codec c : codecs) offer += std::string(offer.empty() ? "" : ", ") + codecName(c);
        console::out("Would offer: " + (offer.empty() ? "nothing (" + why + ")" : offer) + "\n");
    }
    std::string sound;
    console::out("Sound: " + (LoopbackAudio::defaultDevice(&sound) ? sound : std::string("no playback device")) + "\n");
    return 0;
}

int commandLine(const Options& opt) {
    if (!opt.logFile.empty() && !log::openFile(widen(opt.logFile)))
        console::err("MangoCast: couldn't write the log file " + opt.logFile + "\n");
    log::toStderr(true);
    if (opt.help) {
        console::out(usage());
        return 0;
    }
    if (opt.version) {
        console::out("Mango Cast for Windows " MANGO_VERSION "\n");
        return 0;
    }
    if (opt.diagnose) return diagnose();
    if (opt.monitors) {
        for (const auto& m : listMonitors()) {
            console::out("{\"number\": " + std::to_string(m.number) + ", \"width\": " + std::to_string(m.width()) +
                         ", \"height\": " + std::to_string(m.height()) + ", \"x\": " + std::to_string(m.rect.left) +
                         ", \"y\": " + std::to_string(m.rect.top) + ", \"primary\": " + (m.primary ? "true" : "false") +
                         "}\n");
        }
        return 0;
    }
    if (opt.list) {
        std::string why;
        if (!startWinsock(&why)) {
            console::err("MangoCast: " + why + "\n");
            return 1;
        }
        auto found = Browser::browseFor(opt.waitMs, &why);
        if (!why.empty()) {
            console::err("MangoCast: " + why + "\n");
            return 1;
        }
        for (const auto& r : found) console::out(r.toJson() + "\n");
        LOG("Found %zu receiver(s)", found.size());
        return 0;
    }
    // --connect
    ComScope com;
    SessionOptions so;
    so.target = opt.connect;
    so.monitor = opt.monitor;
    so.codec = opt.codec;
    so.audio = !opt.noAudio;
    so.fps = opt.fps;
    so.bitrateMbps = opt.bitrateMbps;
    so.encoder = opt.encoder;
    so.seconds = opt.seconds;
    Session session;
    g_session = &session;
    SetConsoleCtrlHandler(onConsoleCtrl, TRUE);
    LOG("Mango Cast for Windows %s on %s", MANGO_VERSION, windowsName().c_str());
    std::string message;
    SessionResult result = session.run(so, nullptr, &message);
    g_session = nullptr;
    if (result != SessionResult::Done) console::err("MangoCast: " + message + "\n");
    return static_cast<int>(result);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::vector<std::string> args;
    for (int i = 1; argv && i < argc; i++) args.push_back(narrow(argv[i]));
    if (argv) LocalFree(argv);

    Options opt;
    std::string error;
    bool ok = parseOptions(args, opt, &error);
    if (!ok || opt.commandLine()) {
        console::setup();
        if (!ok) {
            console::err("MangoCast: " + error + "\n\n" + usage());
            return 2;
        }
        return commandLine(opt);
    }
    return runWindow(instance, opt, show);
}
