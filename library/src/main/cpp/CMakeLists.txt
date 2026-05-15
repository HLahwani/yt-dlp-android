cmake_minimum_required(VERSION 3.22.1)
project("ytdlpdroid")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fvisibility=hidden")

add_library(
    quickjs_bridge
    SHARED
    quickjs/quickjs.c
    quickjs/quickjs-libc.c
    quickjs/libregexp.c
    quickjs/libunicode.c
    quickjs/cutils.c
    quickjs/dtoa.c
    quickjs_bridge.cpp
)

target_include_directories(quickjs_bridge PRIVATE quickjs/)

target_compile_definitions(quickjs_bridge PRIVATE
    CONFIG_VERSION="2025-09-13"
)

find_library(log-lib log)
target_link_libraries(quickjs_bridge ${log-lib})
