cmake_minimum_required(VERSION 3.22.1)
project(mango_airplay C)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(UXPLAY ${THIRD_PARTY}/uxplay/lib)
set(PLIST ${THIRD_PARTY}/libplist)

if(NOT EXISTS ${UXPLAY}/raop.c OR NOT EXISTS ${PLIST}/src/plist.c)
    message(FATAL_ERROR "Missing third_party sources. Run: git submodule update --init")
endif()

set(CMAKE_C_STANDARD 11)
add_compile_options(-O2 -fvisibility=hidden -Wno-unused-parameter -Wno-unused-function
                    -Wno-unused-variable -Wno-deprecated-declarations)

# --- libplist (LGPL-2.1): binary plists used throughout AirPlay ------------
add_library(plist STATIC
    ${PLIST}/src/base64.c
    ${PLIST}/src/bplist.c
    ${PLIST}/src/bytearray.c
    ${PLIST}/src/common.c
    ${PLIST}/src/hashtable.c
    ${PLIST}/src/jplist.c
    ${PLIST}/src/jsmn.c
    ${PLIST}/src/oplist.c
    ${PLIST}/src/out-default.c
    ${PLIST}/src/out-limd.c
    ${PLIST}/src/out-plutil.c
    ${PLIST}/src/plist.c
    ${PLIST}/src/ptrarray.c
    ${PLIST}/src/time64.c
    ${PLIST}/src/xplist.c
    ${PLIST}/libcnary/node.c
    ${PLIST}/libcnary/node_list.c)
target_include_directories(plist
    PUBLIC ${PLIST}/include
    PRIVATE ${PLIST}/src ${PLIST}/libcnary/include ${CMAKE_CURRENT_SOURCE_DIR}/plist_config)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H LIBPLIST_STATIC)
target_compile_definitions(plist PUBLIC LIBPLIST_STATIC)

# --- UxPlay AirPlay server core (GPL-3.0) ----------------------------------
# Everything in lib/ except the GStreamer renderers and the desktop mDNS
# backends; service registration goes through android_dnssd instead.
file(GLOB UXPLAY_SOURCES ${UXPLAY}/*.c ${UXPLAY}/playfair/*.c ${UXPLAY}/llhttp/*.c)
add_library(uxplay STATIC
    ${UXPLAY_SOURCES}
    ${UXPLAY}/dns_sd/dns_sd.c
    android_dnssd/dns_sd_android.c)
target_include_directories(uxplay
    PUBLIC ${UXPLAY} android_dnssd
    PRIVATE ${UXPLAY}/playfair ${UXPLAY}/llhttp)
target_compile_definitions(uxplay PRIVATE
    STANDALONE TARGET_POSIX _LINUX _REENTRANT NOHOLD PLIST_210 PLIST_230
    __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS)
target_compile_definitions(uxplay PUBLIC OPENSSL_API_COMPAT=0x10101000L)

find_package(openssl REQUIRED CONFIG)
target_link_libraries(uxplay PUBLIC plist openssl::crypto)

# --- JNI bridge ------------------------------------------------------------
add_library(mango_airplay SHARED airplay_jni.c mango_server.c)
target_link_libraries(mango_airplay PRIVATE uxplay log)
