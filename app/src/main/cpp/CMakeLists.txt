# Native PTY bridge for the Helper runtime. forkpty/waitpid/ioctl/kill all live in
# bionic libc, so no extra link libraries are needed. arm64-v8a only.
cmake_minimum_required(VERSION 3.22.1)
project(nujanpty C)

add_library(nujanpty SHARED pty.c)
# Phones may use 16 KB memory pages, and a library whose segments are aligned for 4 KB does not load on them.
target_link_options(nujanpty PRIVATE "-Wl,-z,max-page-size=16384")
