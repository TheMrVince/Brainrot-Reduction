cmake_minimum_required(VERSION 3.20)
project(brainrot_enforcer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(enforcer src/main.cpp)
target_include_directories(enforcer PRIVATE include)

# Platform adapters are added here as they are written:
#   if(APPLE)  -> src/platform_macos.mm  (language OBJCXX)
#   if(WIN32)  -> src/platform_windows.cpp
