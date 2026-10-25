#include "log.h"

#include <windows.h>
#include <shlobj.h>

#include <cstdarg>
#include <cstdio>
#include <mutex>

#include "console.h"
#include "util.h"

namespace mango::log {

namespace {
std::mutex g_mutex;
HANDLE g_file = INVALID_HANDLE_VALUE;
bool g_stderr = false;
}  // namespace

bool openFile(const std::wstring& path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) CloseHandle(g_file);
    g_file = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, CREATE_ALWAYS,
                         FILE_ATTRIBUTE_NORMAL, nullptr);
    return g_file != INVALID_HANDLE_VALUE;
}

void toStderr(bool on) { g_stderr = on; }

void write(const char* fmt, ...) {
    char msg[2048];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    SYSTEMTIME st;
    GetLocalTime(&st);
    char line[2200];
    int n = std::snprintf(line, sizeof line, "%02d:%02d:%02d.%03d %s\r\n", st.wHour, st.wMinute, st.wSecond,
                          st.wMilliseconds, msg);
    if (n < 0) return;
    if (n > static_cast<int>(sizeof line) - 1) n = static_cast<int>(sizeof line) - 1;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(g_file, line, static_cast<DWORD>(n), &written, nullptr);
    }
    if (g_stderr) console::err(std::string(line, static_cast<size_t>(n - 2)) + "\n");
}

std::wstring defaultPath() {
    PWSTR base = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &base)) && base) {
        dir = std::wstring(base) + L"\\Mango Screen Cast";
        CreateDirectoryW(dir.c_str(), nullptr);
    }
    if (base) CoTaskMemFree(base);
    if (dir.empty()) {
        wchar_t tmp[MAX_PATH];
        DWORD n = GetTempPathW(MAX_PATH, tmp);
        dir = std::wstring(tmp, n);
        if (!dir.empty() && dir.back() == L'\\') dir.pop_back();
    }
    return dir + L"\\MangoCast.log";
}

}  // namespace mango::log
