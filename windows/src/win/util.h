// Small Windows helpers: text conversion, error text, clocks, the computer's
// name and Windows version.
#pragma once

#include <windows.h>

#include <cstdint>
#include <string>

namespace mango {

std::wstring widen(const std::string& utf8);
std::string narrow(const std::wstring& wide);
std::string narrow(const wchar_t* wide);

// "0x887A0004: The specified device interface ... (message)" style text.
std::string hresultText(long hr);
std::string win32ErrorText(unsigned long err);

// The QueryPerformanceCounter clock in microseconds: the clock Windows
// Graphics Capture and WASAPI stamp frames and packets with.
int64_t qpcNowUs();
int64_t qpcToUs(int64_t qpcTicks);
// Milliseconds on a monotonic clock, for timeouts.
double nowMs();

std::string computerName();
uint32_t windowsBuild();
std::string windowsName();  // "Windows 11", "Windows 10", "Windows Server 2025"...

// COM for this thread, for as long as it lives.
class ComScope {
public:
    explicit ComScope(bool multithreaded = true);
    ~ComScope();
    ComScope(const ComScope&) = delete;
    ComScope& operator=(const ComScope&) = delete;
    bool ok() const { return ok_; }

private:
    bool ok_ = false;
};

// A short sleep that's actually short (high-resolution waitable timer).
class PreciseSleeper {
public:
    PreciseSleeper();
    ~PreciseSleeper();
    PreciseSleeper(const PreciseSleeper&) = delete;
    PreciseSleeper& operator=(const PreciseSleeper&) = delete;
    void sleepUs(int64_t us);

private:
    HANDLE timer_ = nullptr;
    bool highRes_ = false;
};

}  // namespace mango
