# Writes loader-info.c: how far the loader's pokedata_workaround stub sits from its _start, which
# tracee/mem.c needs on arm64 (termux generates it with readelf | awk; this does the same portably).
execute_process(COMMAND "${READELF}" -sW "${LOADER}"
                OUTPUT_VARIABLE symbols
                RESULT_VARIABLE status)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "could not read the symbols of ${LOADER}")
endif()
string(REGEX MATCH "[ \t]([0-9a-fA-F]+)[ \t]+[0-9]+[ \t]+[A-Z]+[ \t]+[A-Z]+[ \t]+[A-Z]+[ \t]+[0-9A-Z]+[ \t]+pokedata_workaround\n" workaround "${symbols}")
set(workaround "${CMAKE_MATCH_1}")
string(REGEX MATCH "[ \t]([0-9a-fA-F]+)[ \t]+[0-9]+[ \t]+[A-Z]+[ \t]+[A-Z]+[ \t]+[A-Z]+[ \t]+[0-9A-Z]+[ \t]+_start\n" start "${symbols}")
set(start "${CMAKE_MATCH_1}")
if(workaround STREQUAL "" OR start STREQUAL "")
  message(FATAL_ERROR "pokedata_workaround or _start missing from ${LOADER}")
endif()
math(EXPR offset "0x${workaround} - 0x${start}")
file(WRITE "${OUTPUT}.tmp" "#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=${offset};\n")
file(RENAME "${OUTPUT}.tmp" "${OUTPUT}")
