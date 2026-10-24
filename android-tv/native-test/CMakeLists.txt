# Builds the app's AirPlay core (UxPlay + Android dns_sd shim + mango_server.c)
# for the host, with a small harness, so CI can check what the TV advertises
# without an Android device. See check_server.py.
cmake_minimum_required(VERSION 3.16)
project(mango_native_test C)

set(APP ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
set(UXPLAY ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/uxplay/lib)
set(PLIST ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libplist)

file(GLOB PLIST_SOURCES ${PLIST}/src/*.c)
list(APPEND PLIST_SOURCES ${PLIST}/libcnary/node.c ${PLIST}/libcnary/node_list.c)
add_library(plist STATIC ${PLIST_SOURCES})
target_include_directories(plist PUBLIC ${PLIST}/include
    PRIVATE ${PLIST}/src ${PLIST}/libcnary/include ${APP}/plist_config)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H PUBLIC LIBPLIST_STATIC)

file(GLOB UXPLAY_SOURCES ${UXPLAY}/*.c ${UXPLAY}/playfair/*.c ${UXPLAY}/llhttp/*.c)
add_library(airplay STATIC ${UXPLAY_SOURCES} ${UXPLAY}/dns_sd/dns_sd.c
    ${APP}/android_dnssd/dns_sd_android.c ${APP}/mango_server.c)
target_include_directories(airplay PUBLIC ${UXPLAY} ${APP}/android_dnssd ${APP}
    PRIVATE ${UXPLAY}/playfair ${UXPLAY}/llhttp)
target_compile_definitions(airplay PRIVATE STANDALONE TARGET_POSIX _LINUX _REENTRANT NOHOLD
    PLIST_210 PLIST_230 PUBLIC OPENSSL_API_COMPAT=0x10101000L)
find_package(OpenSSL REQUIRED)
find_package(Threads REQUIRED)
target_link_libraries(airplay PUBLIC plist OpenSSL::Crypto Threads::Threads m)

add_executable(server_harness server_harness.c)
target_link_libraries(server_harness airplay)
