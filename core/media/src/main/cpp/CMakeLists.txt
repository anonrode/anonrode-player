cmake_minimum_required(VERSION 3.22.1)
project("anonsync")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-O3 -fvisibility=hidden -ffast-math)

add_library(anonsync SHARED
    anonsync_jni.cpp
    biquad_filter.cpp
    silencedetect.cpp
    correlator.cpp
)

find_library(log-lib log)

target_link_libraries(anonsync
    ${log-lib}
)
