cmake_minimum_required(VERSION 3.22)
project(vvvdroid LANGUAGES CXX)

set(FB_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/firebird/include)
set(FB_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/fbclient/${ANDROID_ABI})

# libfbclient.so comes from the official Firebird Android AAR (extracted into
# app/libs/fbclient/<abi> by scripts/fetch-firebird-android.ps1). At runtime
# the AAR's packaged copy of the same .so is used.
add_library(fbclient SHARED IMPORTED)
set_target_properties(fbclient PROPERTIES IMPORTED_LOCATION
    ${FB_LIB_DIR}/libfbclient.so)

add_library(vvvdroid SHARED vvvdroid_jni.cpp)

target_compile_features(vvvdroid PRIVATE cxx_std_17)
target_include_directories(vvvdroid PRIVATE ${FB_INCLUDE_DIR})
target_link_libraries(vvvdroid fbclient log)
