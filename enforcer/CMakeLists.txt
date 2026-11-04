cmake_minimum_required(VERSION 3.20)
project(brainrot_enforcer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_OBJCXX_STANDARD 20)
set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)

# Portable logic: no OS headers allowed in here.
add_library(enforcer_core STATIC src/matcher.cpp)
target_include_directories(enforcer_core PUBLIC include)

add_executable(enforcer src/main.cpp)
target_link_libraries(enforcer PRIVATE enforcer_core)

if(APPLE)
    enable_language(OBJCXX)
    target_sources(enforcer PRIVATE src/platform_macos.mm)
    set_source_files_properties(src/platform_macos.mm PROPERTIES COMPILE_FLAGS "-fobjc-arc")
    target_link_libraries(enforcer PRIVATE "-framework AppKit" "-framework Foundation")
else()
    message(FATAL_ERROR "No platform adapter for this OS yet (Windows arrives in phase C6)")
endif()

enable_testing()
add_executable(matcher_test tests/matcher_test.cpp)
target_link_libraries(matcher_test PRIVATE enforcer_core)
add_test(NAME matcher COMMAND matcher_test)
