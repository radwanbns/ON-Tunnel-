cmake_minimum_required(VERSION 3.22.1)
project(ontunnel_guard)

add_library(
    ontunnel_guard
    SHARED
    src/main/cpp/ontunnel_guard.cpp
)

find_library(log-lib log)
target_link_libraries(ontunnel_guard ${log-lib})
target_compile_features(ontunnel_guard PRIVATE cxx_std_17)
