cmake_minimum_required(VERSION 3.22.1)
project(cutvideo LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CUTVIDEO_SOURCES
    native_engine.cpp
    video_processor.cpp
    filter_engine.cpp
    audio_processor.cpp
    timeline_renderer.cpp
    jni_bridge.cpp)

add_library(cutvideo SHARED ${CUTVIDEO_SOURCES})

target_include_directories(cutvideo PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(cutvideo PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fno-finite-math-only
    -O2)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(cutvideo PRIVATE ${log-lib} ${android-lib} m)
