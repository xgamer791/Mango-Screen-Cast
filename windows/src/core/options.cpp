#include "options.h"

#include <cstdlib>

namespace mango {

namespace {

bool number(const std::string& text, double lo, double hi, double& out) {
    if (text.empty()) return false;
    char* end = nullptr;
    double v = std::strtod(text.c_str(), &end);
    if (!end || *end != '\0' || !(v >= lo && v <= hi)) return false;
    out = v;
    return true;
}

}  // namespace

bool parseOptions(const std::vector<std::string>& args, Options& o, std::string* error) {
    o = Options();
    auto fail = [&](const std::string& why) {
        if (error) *error = why;
        return false;
    };
    for (size_t i = 0; i < args.size(); i++) {
        std::string arg = args[i];
        std::string value;
        bool hasValue = false;
        size_t eq = arg.find('=');
        if (arg.rfind("--", 0) == 0 && eq != std::string::npos) {
            value = arg.substr(eq + 1);
            arg = arg.substr(0, eq);
            hasValue = true;
        }
        auto take = [&](std::string& out) {
            if (hasValue) {
                out = value;
                return true;
            }
            if (i + 1 >= args.size()) return false;
            out = args[++i];
            return true;
        };
        std::string v;
        double d = 0;
        if (arg == "--help" || arg == "-h" || arg == "/?") {
            o.help = true;
        } else if (arg == "--version") {
            o.version = true;
        } else if (arg == "--list") {
            o.list = true;
        } else if (arg == "--monitors") {
            o.monitors = true;
        } else if (arg == "--diagnose") {
            o.diagnose = true;
        } else if (arg == "--no-audio") {
            o.noAudio = true;
        } else if (arg == "--wait") {
            if (!take(v) || !number(v, 0, 600000, d)) return fail("--wait needs a time in milliseconds");
            o.waitMs = static_cast<int>(d);
        } else if (arg == "--connect") {
            if (!take(v) || v.empty()) return fail("--connect needs an address: host or host:port");
            o.connect = v;
        } else if (arg == "--seconds") {
            if (!take(v) || !number(v, 0.001, 1e7, d)) return fail("--seconds needs a number of seconds");
            o.seconds = d;
        } else if (arg == "--codec") {
            if (!take(v) || (v != "h264" && v != "h265" && v != "hevc")) return fail("--codec is h264 or h265");
            o.codec = v == "hevc" ? "h265" : v;
        } else if (arg == "--fps") {
            if (!take(v) || !number(v, 1, 240, d)) return fail("--fps is a number from 1 to 240");
            o.fps = static_cast<int>(d);
        } else if (arg == "--bitrate") {
            if (!take(v) || !number(v, 0.1, 500, d)) return fail("--bitrate is in Mbit/s, from 0.1 to 500");
            o.bitrateMbps = d;
        } else if (arg == "--monitor") {
            if (!take(v) || !number(v, 1, 64, d) || d != static_cast<int>(d))
                return fail("--monitor is a screen number: 1, 2, ... (see --monitors)");
            o.monitor = static_cast<int>(d);
        } else if (arg == "--log") {
            if (!take(v) || v.empty()) return fail("--log needs a file name");
            o.logFile = v;
        } else if (arg == "--encoder") {
            if (!take(v) || (v != "auto" && v != "hardware" && v != "software"))
                return fail("--encoder is auto, hardware or software");
            o.encoder = v;
        } else {
            return fail("Unknown option: " + args[i]);
        }
        if (hasValue && (arg == "--help" || arg == "--version" || arg == "--list" || arg == "--monitors" ||
                         arg == "--diagnose" || arg == "--no-audio"))
            return fail(arg + " doesn't take a value");
    }
    if (o.seconds > 0 && o.connect.empty()) return fail("--seconds goes with --connect");
    return true;
}

std::string usage() {
    return "Mango Cast for Windows: puts this PC's screen and sound on a Mango Screen Cast TV.\n"
           "\n"
           "Run it without options for the window. From a command line:\n"
           "\n"
           "  MangoCast.exe --list [--wait MS]       Receivers on the network, one JSON line each\n"
           "  MangoCast.exe --connect HOST[:PORT]    Stream to a receiver (an address, or its name)\n"
           "  MangoCast.exe --monitors               The screens, as numbers for --monitor\n"
           "  MangoCast.exe --diagnose               What this PC can capture and encode\n"
           "\n"
           "Options for --connect:\n"
           "  --seconds N         Stream for N seconds, then stop (default: until Ctrl+C)\n"
           "  --monitor N         Which screen (default: the main one)\n"
           "  --codec h264|h265   Send this codec (default: the best both sides can do)\n"
           "  --fps N             At most N frames per second (default 60)\n"
           "  --bitrate MBPS      Video bitrate in Mbit/s (default: from the picture size)\n"
           "  --no-audio          Picture only\n"
           "  --encoder auto|hardware|software   Which video encoder (default auto)\n"
           "  --log FILE          Write a log\n"
           "\n"
           "Exit codes: 0 done, 1 error, 2 bad options, 3 the receiver said no,\n"
           "4 couldn't connect or lost the connection, 5 the receiver ended the session.\n";
}

}  // namespace mango
