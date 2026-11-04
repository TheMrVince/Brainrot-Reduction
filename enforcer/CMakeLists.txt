cmake_minimum_required(VERSION 3.20)
project(brainrot_enforcer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_OBJCXX_STANDARD 20)
set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
    json
    URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
    URL_HASH SHA256=d6c65aca6b1ed68e7a182f4757257b107ae403032760ed6ef121c9d55e81757d
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(json)

# Portable logic: no OS headers allowed in here.
add_library(enforcer_core STATIC src/matcher.cpp src/message_handler.cpp)
target_include_directories(enforcer_core PUBLIC include)
target_link_libraries(enforcer_core PRIVATE nlohmann_json::nlohmann_json)

add_executable(enforcer src/main.cpp)

# One IPC transport and one platform adapter per OS.
if(UNIX)
    add_library(enforcer_ipc STATIC src/ipc_server_posix.cpp)
    target_link_libraries(enforcer_ipc PUBLIC enforcer_core)
    target_link_libraries(enforcer PRIVATE enforcer_ipc)
else()
    message(FATAL_ERROR "No IPC transport for this OS yet (Windows arrives in phase C6)")
endif()

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

add_executable(message_handler_test tests/message_handler_test.cpp)
target_link_libraries(message_handler_test PRIVATE enforcer_core nlohmann_json::nlohmann_json)
add_test(NAME message_handler COMMAND message_handler_test)

add_executable(ipc_server_test tests/ipc_server_test.cpp)
target_link_libraries(ipc_server_test PRIVATE enforcer_ipc nlohmann_json::nlohmann_json)
add_test(NAME ipc_server COMMAND ipc_server_test)
