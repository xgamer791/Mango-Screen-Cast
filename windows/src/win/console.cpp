#include "console.h"

#include <windows.h>

#include <mutex>

#include "util.h"

namespace mango::console {

namespace {
std::mutex g_mutex;
HANDLE g_out = nullptr;
HANDLE g_err = nullptr;
bool g_ready = false;

bool usable(HANDLE h) { return h && h != INVALID_HANDLE_VALUE && GetFileType(h) != FILE_TYPE_UNKNOWN; }

void writeTo(HANDLE h, const std::string& text) {
    if (!usable(h)) return;
    DWORD mode = 0;
    if (GetConsoleMode(h, &mode)) {
        std::wstring w = widen(text);
        DWORD written = 0;
        WriteConsoleW(h, w.data(), static_cast<DWORD>(w.size()), &written, nullptr);
    } else {
        DWORD written = 0;
        WriteFile(h, text.data(), static_cast<DWORD>(text.size()), &written, nullptr);
    }
}
}  // namespace

void setup() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ready) return;
    g_ready = true;
    g_out = GetStdHandle(STD_OUTPUT_HANDLE);
    g_err = GetStdHandle(STD_ERROR_HANDLE);
    if (usable(g_out) && usable(g_err)) return;
    // Started from a console without redirection: write to that console.
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        HANDLE con = CreateFileW(L"CONOUT$", GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                                 OPEN_EXISTING, 0, nullptr);
        if (!usable(g_out)) g_out = con;
        if (!usable(g_err)) g_err = con;
        // The prompt was already printed; start on a fresh line.
        writeTo(g_out, "\n");
    }
}

void out(const std::string& text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    writeTo(g_out, text);
}

void err(const std::string& text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    writeTo(g_err, text);
}

}  // namespace mango::console
