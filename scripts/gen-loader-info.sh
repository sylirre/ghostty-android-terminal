#!/bin/sh
# Generates loader-info.c for PRoot's arm64 PTRACE_POKEDATA workaround:
# tracee/mem.c needs the offset of the loader's pokedata_workaround stub
# from its entry point. Replaces the GNUmakefile rule
# `readelf -s loader | awk -f loader/loader-info.awk` (whose awk needs
# gawk's strtonum; this script only needs POSIX sh + awk).
#
# Usage: gen-loader-info.sh <readelf> <loader-elf> <output.c>
set -eu

readelf=$1
loader=$2
output=$3

sym_value() {
    "$readelf" -s "$loader" | awk -v name="$1" '$NF == name { print $2; exit }'
}

pokedata=$(sym_value pokedata_workaround)
start=$(sym_value _start)
if [ -z "$pokedata" ] || [ -z "$start" ]; then
    echo "$0: pokedata_workaround/_start not found in $loader" >&2
    exit 1
fi

printf '#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround = %d;\n' \
    "$((0x$pokedata - 0x$start))" > "$output"
