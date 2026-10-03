cmake_minimum_required(VERSION 3.22.1)

project(groundtrack_map_lines LANGUAGES CXX)

add_library(
    groundtrack-map-lines
    SHARED
    moving_lines.cpp
)

target_compile_features(groundtrack-map-lines PRIVATE cxx_std_17)
target_include_directories(groundtrack-map-lines PRIVATE include)

target_link_libraries(
    groundtrack-map-lines
    PRIVATE
    GLESv3
    log
)
