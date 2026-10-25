#include "util.h"

#include <objbase.h>

#include <chrono>
#include <cstdio>

namespace mango {

std::wstring widen(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

std::string narrow(const wchar_t* w) { return w ? narrow(std::wstring(w)) : std::string(); }

static std::string systemMessage(unsigned long code) {
    wchar_t* buf = nullptr;
    DWORD n = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                             nullptr, code, 0, reinterpret_cast<wchar_t*>(&buf), 0, nullptr);
    std::string text;
    if (n && buf) {
        text = narrow(buf);
        while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ' || text.back() == '.'))
            text.pop_back();
    }
    if (buf) LocalFree(buf);
    return text;
}

std::string hresultText(long hr) {
    char code[16];
    std::snprintf(code, sizeof code, "0x%08lX", static_cast<unsigned long>(hr));
    std::string text = systemMessage(static_cast<unsigned long>(hr));
    return text.empty() ? std::string(code) : std::string(code) + " (" + text + ")";
}

std::string win32ErrorText(unsigned long err) {
    std::string text = systemMessage(err);
    std::string s = "error " + std::to_string(err);
    return text.empty() ? s : s + " (" + text + ")";
}

static int64_t qpcFrequency() {
    static const int64_t f = [] {
        LARGE_INTEGER li;
        QueryPerformanceFrequency(&li);
        return li.QuadPart;
    }();
    return f;
}

int64_t qpcToUs(int64_t q) {
    const int64_t f = qpcFrequency();
    return q / f * 1000000 + q % f * 1000000 / f;
}

int64_t qpcNowUs() {
    LARGE_INTEGER li;
    QueryPerformanceCounter(&li);
    return qpcToUs(li.QuadPart);
}

double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

std::string computerName() {
    wchar_t buf[256];
    DWORD n = 256;
    if (GetComputerNameExW(ComputerNamePhysicalDnsHostname, buf, &n) && n) return narrow(std::wstring(buf, n));
    n = 256;
    if (GetComputerNameW(buf, &n)) return narrow(std::wstring(buf, n));
    return "Windows PC";
}

static RTL_OSVERSIONINFOEXW osVersion() {
    RTL_OSVERSIONINFOEXW vi{};
    vi.dwOSVersionInfoSize = sizeof(vi);
    using Fn = LONG(WINAPI*)(PRTL_OSVERSIONINFOW);
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    auto fn = ntdll ? reinterpret_cast<Fn>(reinterpret_cast<void*>(GetProcAddress(ntdll, "RtlGetVersion"))) : nullptr;
    if (fn) fn(reinterpret_cast<PRTL_OSVERSIONINFOW>(&vi));
    return vi;
}

uint32_t windowsBuild() { return osVersion().dwBuildNumber; }

std::string windowsName() {
    RTL_OSVERSIONINFOEXW vi = osVersion();
    if (vi.wProductType != VER_NT_WORKSTATION) {
        if (vi.dwBuildNumber >= 26100) return "Windows Server 2025";
        if (vi.dwBuildNumber >= 20348) return "Windows Server 2022";
        return "Windows Server";
    }
    if (vi.dwMajorVersion == 10 && vi.dwBuildNumber >= 22000) return "Windows 11";
    if (vi.dwMajorVersion == 10) return "Windows 10";
    return "Windows " + std::to_string(vi.dwMajorVersion) + "." + std::to_string(vi.dwMinorVersion);
}

ComScope::ComScope(bool multithreaded) {
    HRESULT hr = CoInitializeEx(nullptr, multithreaded ? COINIT_MULTITHREADED : COINIT_APARTMENTTHREADED);
    ok_ = SUCCEEDED(hr);  // S_FALSE (already initialized) needs the matching uninitialize too
}

ComScope::~ComScope() {
    if (ok_) CoUninitialize();
}

PreciseSleeper::PreciseSleeper() {
#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x00000002
#endif
    timer_ = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    highRes_ = timer_ != nullptr;
    if (!timer_) timer_ = CreateWaitableTimerExW(nullptr, nullptr, 0, TIMER_ALL_ACCESS);
}

PreciseSleeper::~PreciseSleeper() {
    if (timer_) CloseHandle(timer_);
}

void PreciseSleeper::sleepUs(int64_t us) {
    if (us <= 0) return;
    if (!timer_) {
        Sleep(static_cast<DWORD>((us + 999) / 1000));
        return;
    }
    LARGE_INTEGER due;
    due.QuadPart = -us * 10;  // relative, 100 ns units
    if (SetWaitableTimer(timer_, &due, 0, nullptr, nullptr, FALSE)) WaitForSingleObject(timer_, INFINITE);
    else Sleep(static_cast<DWORD>((us + 999) / 1000));
}

}  // namespace mango
