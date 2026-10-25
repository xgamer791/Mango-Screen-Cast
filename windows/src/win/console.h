// Output for the command-line mode. MangoCast.exe is a windowed program (so
// double-clicking it shows no console), so it writes to the handles it was
// given (pipes, files) or, failing that, to the console it was started from.
#pragma once

#include <string>

namespace mango::console {

void setup();
void out(const std::string& utf8);
void err(const std::string& utf8);

}  // namespace mango::console
