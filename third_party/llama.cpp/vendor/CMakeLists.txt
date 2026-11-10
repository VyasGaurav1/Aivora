# mtmd needs these even when common is not built
add_subdirectory(hash)
add_subdirectory(miniaudio)
add_subdirectory(nlohmann)
add_subdirectory(sheredom)
add_subdirectory(stb)

# AIVORA vendors only the offline runtime. The HTTP dependency is omitted.
if (LLAMA_BUILD_COMMON)
    message(FATAL_ERROR "AIVORA's llama.cpp subset does not include the common HTTP client")
endif()
