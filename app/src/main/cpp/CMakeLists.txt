cmake_minimum_required(VERSION 3.22.1)
project(easytier_root_tun)


set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(root_tun_jni SHARED root_tun_jni.cpp)
find_library(log-lib log)
target_link_libraries(root_tun_jni ${log-lib})
