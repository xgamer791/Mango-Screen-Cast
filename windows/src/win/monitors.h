// The screens to choose from: the main one first, then the others from left
// to right, numbered 1, 2, 3...
#pragma once

#include <windows.h>

#include <string>
#include <vector>

namespace mango {

struct MonitorInfo {
    int number = 0;  // 1-based, as --monitor and the Screen menu use
    HMONITOR handle = nullptr;
    RECT rect{};     // physical pixels (the app is per-monitor DPI aware)
    bool primary = false;
    std::wstring device;  // \\.\DISPLAY1
    int width() const { return rect.right - rect.left; }
    int height() const { return rect.bottom - rect.top; }
    std::string label() const;  // "1: 2560 x 1440 (main)"
};

std::vector<MonitorInfo> listMonitors();

}  // namespace mango
