// MangoCast.exe's command line. Portable C++17.
#pragma once

#include <string>
#include <vector>

namespace mango {

struct Options {
    bool help = false;
    bool version = false;
    bool list = false;      // --list: print receivers found, one JSON line each
    int waitMs = 3000;      // --wait: how long --list looks
    bool monitors = false;  // --monitors: print the screens
    bool diagnose = false;  // --diagnose: what this PC can do
    std::string connect;    // --connect host[:port] (or a receiver's name)
    double seconds = 0;     // --seconds: stream this long, then stop (0: until Ctrl+C)
    std::string codec;      // --codec h264|h265
    bool noAudio = false;   // --no-audio
    int fps = 60;           // --fps
    double bitrateMbps = 0; // --bitrate, in Mbit/s (0: automatic)
    int monitor = 0;        // --monitor N (0: the main screen)
    std::string logFile;    // --log file
    std::string encoder = "auto";  // --encoder auto|hardware|software
    std::string capture = "auto";  // --capture auto|wgc|duplication

    // Command-line mode (no window).
    bool commandLine() const { return help || version || list || monitors || diagnose || !connect.empty(); }
};

// False with a message when the arguments don't make sense.
bool parseOptions(const std::vector<std::string>& args, Options& out, std::string* error);
std::string usage();

}  // namespace mango
