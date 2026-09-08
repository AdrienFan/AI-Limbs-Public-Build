cmake_minimum_required(VERSION 3.18.1)

project("ubuntu_plugin_pty")

add_library(ubuntu_plugin_pty SHARED pty.c)

target_link_libraries(ubuntu_plugin_pty log) 