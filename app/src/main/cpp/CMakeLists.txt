cmake_minimum_required(VERSION 3.18)
project(doremi_synth LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(
    fluidsynth
    URL https://github.com/FluidSynth/fluidsynth/archive/refs/tags/v2.6.2.tar.gz
    URL_HASH SHA256=ca376b42a3c1b186a4c8c248931789a455ac94a89b777394969d678010c2f91f
)

function(set_fluid_option name value)
    set(${name} "${value}" CACHE BOOL "FluidSynth ${name}" FORCE)
endfunction()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build FluidSynth as a static library" FORCE)
set_fluid_option(enable-floats ON)
set_fluid_option(enable-threads ON)
set_fluid_option(enable-alsa OFF)
set_fluid_option(enable-aufile OFF)
set_fluid_option(enable-coreaudio OFF)
set_fluid_option(enable-coremidi OFF)
set_fluid_option(enable-coverage OFF)
set_fluid_option(enable-dart OFF)
set_fluid_option(enable-dbus OFF)
set_fluid_option(enable-dsound OFF)
set_fluid_option(enable-fpe-check OFF)
set_fluid_option(enable-framework OFF)
set_fluid_option(enable-ipv6 OFF)
set_fluid_option(enable-jack OFF)
set_fluid_option(enable-kai OFF)
set_fluid_option(enable-ladspa OFF)
set_fluid_option(enable-libsndfile OFF)
set_fluid_option(enable-midishare OFF)
set_fluid_option(enable-native-dls OFF)
set_fluid_option(enable-network OFF)
set_fluid_option(enable-oboe OFF)
set_fluid_option(enable-openmp OFF)
set_fluid_option(enable-opensles OFF)
set_fluid_option(enable-oss OFF)
set_fluid_option(enable-pipewire OFF)
set_fluid_option(enable-portaudio OFF)
set_fluid_option(enable-profiling OFF)
set_fluid_option(enable-pulseaudio OFF)
set_fluid_option(enable-readline OFF)
set_fluid_option(enable-sdl3 OFF)
set_fluid_option(enable-signalsmith OFF)
set_fluid_option(enable-systemd OFF)
set_fluid_option(enable-trap-on-fpe OFF)
set_fluid_option(enable-ubsan OFF)
set_fluid_option(enable-wasapi OFF)
set_fluid_option(enable-waveout OFF)
set_fluid_option(enable-winmidi OFF)

FetchContent_GetProperties(fluidsynth)
if(NOT fluidsynth_POPULATED)
    FetchContent_Populate(fluidsynth)
endif()
if(EXISTS "${fluidsynth_SOURCE_DIR}/CMakeLists.txt")
    set(FLUIDSYNTH_SRC "${fluidsynth_SOURCE_DIR}")
elseif(EXISTS "${fluidsynth_SOURCE_DIR}/fluidsynth-2.6.2/CMakeLists.txt")
    set(FLUIDSYNTH_SRC "${fluidsynth_SOURCE_DIR}/fluidsynth-2.6.2")
else()
    message(FATAL_ERROR "FluidSynth sources not found in ${fluidsynth_SOURCE_DIR}")
endif()

# FluidSynth 2.6 decodes SF3 only when libsndfile has Vorbis. This build keeps
# libsndfile off and patches the loader to decode SF3 samples with stb_vorbis.
find_package(Python3 COMPONENTS Interpreter REQUIRED)
execute_process(
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/patch_sf3.py" "${FLUIDSYNTH_SRC}"
    RESULT_VARIABLE DOREMI_SF3_PATCH
)
if(NOT DOREMI_SF3_PATCH EQUAL 0)
    message(FATAL_ERROR "Failed to enable SF3 decoding in FluidSynth")
endif()

if(NOT TARGET libfluidsynth)
    add_subdirectory("${FLUIDSYNTH_SRC}" "${fluidsynth_BINARY_DIR}" EXCLUDE_FROM_ALL)
endif()

# The decoder has to live inside FluidSynth. Its own test binaries link that
# static library and would not see a symbol defined only in doremi_synth.
set_source_files_properties(doremi_sf3.c PROPERTIES
    COMPILE_OPTIONS "-Wno-unused-function;-Wno-unused-variable;-Wno-unused-parameter;-Wno-sign-compare"
)
get_target_property(DOREMI_FLUID_SOURCES libfluidsynth-OBJ SOURCES)
if(NOT DOREMI_FLUID_SOURCES MATCHES "doremi_sf3.c")
    target_sources(libfluidsynth-OBJ PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/doremi_sf3.c")
endif()

find_package(oboe REQUIRED CONFIG)

add_library(doremi_synth SHARED
    SynthEngine.cpp
    synth_jni.cpp
)

target_compile_features(doremi_synth PRIVATE cxx_std_17)
target_link_libraries(doremi_synth
    libfluidsynth
    oboe::oboe
    log
    android
    m
)
