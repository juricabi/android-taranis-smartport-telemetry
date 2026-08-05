cmake_minimum_required(VERSION 3.22.1)

project(telemetry_map_lines LANGUAGES CXX)

add_library(
    telemetry-map-lines
    SHARED
    moving_lines.cpp
)

target_compile_features(telemetry-map-lines PRIVATE cxx_std_17)
target_include_directories(telemetry-map-lines PRIVATE include)

target_link_libraries(
    telemetry-map-lines
    PRIVATE
    GLESv3
    log
)
