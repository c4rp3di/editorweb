cmake_minimum_required(VERSION 3.22.1)
project(exec-shim C)

add_library(exec-shim SHARED exec_shim.c)
find_library(log-lib log)
target_link_libraries(exec-shim ${log-lib})
