cmake_minimum_required(VERSION 3.18.1)

project("system_environment_ubuntu_pty")

add_library(system_environment_ubuntu_pty SHARED pty.c)

target_link_libraries(system_environment_ubuntu_pty log)
