// The window: receivers found on the network, an address box, which screen,
// sound on or off, Connect / Stop and a status line.
#pragma once

#include <windows.h>

#include "core/options.h"

namespace mango {

int runWindow(HINSTANCE instance, const Options& options, int show);

}  // namespace mango
