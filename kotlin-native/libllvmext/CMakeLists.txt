cmake_minimum_required(VERSION 3.20)
project(libllvmext)

set(CMAKE_CXX_STANDARD 17)

function(find_llvm_dir)
    message(STATUS "Loading LLVM dependency location from Gradle...")
    # TODO: Check on Windows.
    execute_process(
            COMMAND ../../gradlew printLlvmDir -q
            WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
            OUTPUT_VARIABLE llvm_dir
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
    set(LLVM_DIR ${llvm_dir} PARENT_SCOPE)
endfunction(find_llvm_dir)

if (NOT DEFINED LLVM_DIR)
    find_llvm_dir()
endif ()
if (LLVM_DIR STREQUAL "")
    message(FATAL_ERROR "LLVM_DIR is not set!")
else()
    message(STATUS "LLVM location: ${LLVM_DIR}")
endif()
list(APPEND CMAKE_PREFIX_PATH "${LLVM_DIR}/lib/cmake")
find_package(LLVM REQUIRED CONFIG)

message(STATUS "Found LLVM ${LLVM_PACKAGE_VERSION}")
message(STATUS "Using LLVMConfig.cmake in: ${LLVM_DIR}")

if (CMAKE_SYSTEM_NAME MATCHES Darwin)
    add_compile_definitions(KONAN_MACOS=1)
elseif (CMAKE_SYSTEM_NAME MATCHES Linux)
    add_compile_definitions(KONAN_LINUX=1)
elseif (CMAKE_SYSTEM_NAME MATCHES Windows)
    add_compile_definitions(KONAN_WINDOWS=1)
endif ()

set(SRC_DIR ${CMAKE_SOURCE_DIR}/src)
include_directories(${LLVM_INCLUDE_DIRS})
include_directories(${SRC_DIR}/main/include)

add_library(libllvmext
        ${SRC_DIR}/main/cpp/ArcFrameElision.cpp
        ${SRC_DIR}/main/cpp/ArcReturnUpdateCoalescing.cpp
        ${SRC_DIR}/main/cpp/CoverageMappingC.cpp
        ${SRC_DIR}/main/cpp/CAPIExtensions.cpp)

option(KONAN_LLVMEXT_BUILD_TESTS "Build libllvmext's standalone unit tests" OFF)
if (KONAN_LLVMEXT_BUILD_TESTS)
    enable_testing()
    add_executable(ArcReturnUpdateCoalescingTest
            ${SRC_DIR}/test/cpp/ArcReturnUpdateCoalescingTest.cpp)
    llvm_map_components_to_libnames(LLVM_TEST_LIBS core asmparser support)
    if (CMAKE_SYSTEM_NAME MATCHES Linux)
        # The prebuilt LLVM package names zlib as `z`, but minimal Linux builders
        # may expose only the versioned runtime library. Preserve every other
        # transitive LLVMSupport dependency while resolving zlib to an actual file.
        get_target_property(LLVM_SUPPORT_LIBS LLVMSupport INTERFACE_LINK_LIBRARIES)
        list(REMOVE_ITEM LLVM_SUPPORT_LIBS z)
        find_file(LLVM_ZLIB_RUNTIME NAMES libz.so.1
                PATHS /lib/${CMAKE_LIBRARY_ARCHITECTURE} /usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}
                NO_DEFAULT_PATH REQUIRED)
        list(APPEND LLVM_SUPPORT_LIBS ${LLVM_ZLIB_RUNTIME})
        set_property(TARGET LLVMSupport PROPERTY INTERFACE_LINK_LIBRARIES ${LLVM_SUPPORT_LIBS})
    endif ()
    target_link_libraries(ArcReturnUpdateCoalescingTest PRIVATE libllvmext ${LLVM_TEST_LIBS})
    add_test(NAME ArcReturnUpdateCoalescingTest COMMAND ArcReturnUpdateCoalescingTest)
endif ()
