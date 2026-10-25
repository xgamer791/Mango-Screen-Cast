# Mango Cast for Windows: MangoCast.exe, one self-contained program (static
# C runtime, nothing to install). Build with Visual Studio 2022 or newer:
#   cmake -S windows -B build -A x64
#   cmake --build build --config Release
# The portable parts (protocol, Annex B, audio conversion, rate control) and
# their unit tests also build elsewhere, e.g. with g++ on Linux.
cmake_minimum_required(VERSION 3.21)
project(MangoCast VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)  # the Windows SDK's C++/WinRT wants C++20 coroutines
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# Static C runtime: MangoCast.exe runs without any redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

if(MSVC)
  set(MANGO_WARNINGS /W4 /permissive- /utf-8 /EHsc /Zc:__cplusplus)
else()
  set(MANGO_WARNINGS -Wall -Wextra)
endif()

add_library(mango_core STATIC
  src/core/annexb.cpp
  src/core/audio_convert.cpp
  src/core/json.cpp
  src/core/options.cpp
  src/core/protocol.cpp
  src/core/stream_math.cpp)
target_include_directories(mango_core PUBLIC src)
target_compile_options(mango_core PRIVATE ${MANGO_WARNINGS})

enable_testing()
add_executable(mango_unit_tests tests/unit_tests.cpp)
target_link_libraries(mango_unit_tests PRIVATE mango_core)
target_compile_options(mango_unit_tests PRIVATE ${MANGO_WARNINGS})
add_test(NAME unit COMMAND mango_unit_tests)

if(WIN32)
  enable_language(RC)
  add_executable(MangoCast WIN32
    src/win/audio.cpp
    src/win/capture.cpp
    src/win/console.cpp
    src/win/convert.cpp
    src/win/d3d.cpp
    src/win/discovery.cpp
    src/win/encoder.cpp
    src/win/log.cpp
    src/win/main.cpp
    src/win/monitors.cpp
    src/win/net.cpp
    src/win/session.cpp
    src/win/ui.cpp
    src/win/util.cpp
    res/app.rc
    res/MangoCast.manifest)
  target_include_directories(MangoCast PRIVATE src src/win)
  target_compile_definitions(MangoCast PRIVATE
    UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00 WINVER=0x0A00
    MANGO_VERSION="${PROJECT_VERSION}"
    MANGO_VERSION_COMMA=${PROJECT_VERSION_MAJOR},${PROJECT_VERSION_MINOR},${PROJECT_VERSION_PATCH},0)
  target_compile_options(MangoCast PRIVATE $<$<COMPILE_LANGUAGE:CXX>:${MANGO_WARNINGS} /bigobj /MP>)
  target_link_libraries(MangoCast PRIVATE mango_core
    d3d11 dxgi d3dcompiler mfplat mfuuid ole32 oleaut32 uuid ws2_32 dnsapi comctl32 shell32 user32 gdi32
    advapi32 windowsapp delayimp)
  # Media Foundation is missing on Windows N editions: load it on demand, so
  # MangoCast.exe can start and say so instead of failing to load.
  target_link_options(MangoCast PRIVATE /DELAYLOAD:mfplat.dll)
endif()
