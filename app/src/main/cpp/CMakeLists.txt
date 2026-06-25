cmake_minimum_required(VERSION 3.22.1)

project(zvec_bridge LANGUAGES CXX)

add_library(zvec_bridge SHARED zvec_bridge.cpp)

target_compile_features(zvec_bridge PRIVATE cxx_std_17)
target_compile_options(zvec_bridge PRIVATE -Wall -Wextra -Werror)

find_library(log-lib log)
target_link_libraries(zvec_bridge PRIVATE ${log-lib})
