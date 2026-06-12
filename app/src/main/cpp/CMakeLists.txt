cmake_minimum_required(VERSION 3.22)
project(androidterm C)

set(GHOSTTY_VT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/ghostty-vt)

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/prebuilt/${ANDROID_ABI}/libghostty-vt.a)

add_library(term SHARED
    pty_jni.c
    terminal_jni.c)

target_include_directories(term PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(term PRIVATE -Wall -Werror)
target_link_libraries(term ghostty-vt log)
