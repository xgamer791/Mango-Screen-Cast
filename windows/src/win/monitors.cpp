#include "monitors.h"

#include <algorithm>

#include "util.h"

namespace mango {

std::string MonitorInfo::label() const {
    std::string s = std::to_string(number) + ": " + std::to_string(width()) + " x " + std::to_string(height());
    if (primary) s += " (main)";
    return s;
}

static BOOL CALLBACK onMonitor(HMONITOR mon, HDC, LPRECT, LPARAM param) {
    auto* list = reinterpret_cast<std::vector<MonitorInfo>*>(param);
    MONITORINFOEXW mi{};
    mi.cbSize = sizeof(mi);
    if (!GetMonitorInfoW(mon, &mi)) return TRUE;
    MonitorInfo m;
    m.handle = mon;
    m.rect = mi.rcMonitor;
    m.primary = (mi.dwFlags & MONITORINFOF_PRIMARY) != 0;
    m.device = mi.szDevice;
    list->push_back(m);
    return TRUE;
}

std::vector<MonitorInfo> listMonitors() {
    std::vector<MonitorInfo> list;
    EnumDisplayMonitors(nullptr, nullptr, onMonitor, reinterpret_cast<LPARAM>(&list));
    std::stable_sort(list.begin(), list.end(), [](const MonitorInfo& a, const MonitorInfo& b) {
        if (a.primary != b.primary) return a.primary;
        if (a.rect.left != b.rect.left) return a.rect.left < b.rect.left;
        return a.rect.top < b.rect.top;
    });
    for (size_t i = 0; i < list.size(); i++) list[i].number = static_cast<int>(i) + 1;
    return list;
}

}  // namespace mango
