cmake_minimum_required(VERSION 3.22.1)

project("saydo_native")

add_library(
    saydo_native
    SHARED
    saydo_native.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    saydo_native
    ${log-lib}
)
