// The log: a file (--log, or the app's own in %LOCALAPPDATA%), and stderr
// when run from a command line.
#pragma once

#include <string>

namespace mango::log {

bool openFile(const std::wstring& path);
void toStderr(bool on);
void write(const char* fmt, ...)
#if defined(__GNUC__)
    __attribute__((format(printf, 1, 2)))
#endif
    ;
std::wstring defaultPath();  // %LOCALAPPDATA%\Mango Screen Cast\MangoCast.log

}  // namespace mango::log

#define LOG(...) ::mango::log::write(__VA_ARGS__)
