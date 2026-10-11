#!/usr/bin/env bash
set -euo pipefail

# Requires an explicit adb serial. Optional libraries let the same tests check
# the exact libfakeinput.so and libwaudio.so extracted from an assembled APK.
serial=${1:?Usage: test-fakeinput.sh ADB_SERIAL [LIBFAKEINPUT_SO] [LIBWAUDIO_SO]}
root=$(cd "$(dirname "$0")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}
ndk_dir=${ANDROID_NDK_HOME:-"$sdk_dir/ndk/27.3.13750724"}
compiler_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/winnative-input-XXXXXX")
device_dir="/data/local/tmp/winnative-input-$$"
cleanup() {
  adb -s "$serial" shell rm -rf "$device_dir" >/dev/null 2>&1 || true
  rm -rf "$build_dir"
}
trap cleanup EXIT
cpp=("$compiler_dir/aarch64-linux-android26-clang++" -std=c++17 -O2 -g
     -D_FORTIFY_SOURCE=2 -Wall -Wextra -static-libstdc++)
if [[ -n ${2:-} ]]; then
  cp "$2" "$build_dir/libfakeinput.so"
else
  "${cpp[@]}" -shared -fPIC "$root/app/src/main/cpp/winlator/fakeinput.cpp" -ldl \
    -o "$build_dir/libfakeinput.so"
fi
if [[ -n ${3:-} ]]; then
  cp "$3" "$build_dir/libwaudio.so"
else
  "$compiler_dir/aarch64-linux-android26-clang" -O2 -shared -fPIC \
    "$root/app/src/main/cpp/wnaudiohook/wn_aaudio_shim.c" -ldl -llog \
    -o "$build_dir/libwaudio.so"
fi
for test in fakeinput_test directaudio_input_test; do
  "${cpp[@]}" "$root/app/src/test/cpp/$test.cpp" -ldl -o "$build_dir/$test"
done

# Compile the real Java writer and its real JNI fence, without app/UI startup.
mkdir "$build_dir/classes" "$build_dir/dex"
android_jar="$sdk_dir/platforms/android-35/android.jar"
javac -source 17 -target 17 -cp "$android_jar" -d "$build_dir/classes" \
  "$root/app/src/main/runtime/input/controls/FakeInputWriter.java" \
  "$root/app/src/main/runtime/input/controls/GamepadState.java" \
  "$root/app/src/test/standalone/FakeInputWriterTest.java"
mapfile -t classes < <(rg --files "$build_dir/classes" -g '*.class')
"$sdk_dir/build-tools/35.0.0/d8" --lib "$android_jar" --output "$build_dir/dex" \
  --min-api 26 "${classes[@]}"
"$compiler_dir/aarch64-linux-android26-clang" -O2 -fPIC -c \
  "$root/app/src/main/cpp/winlator/ring_fence.c" -o "$build_dir/fence.o"
"${cpp[@]}" -shared -fPIC "$root/app/src/test/cpp/fakeinput_writer_test.cpp" \
  "$build_dir/fence.o" -ldl -o "$build_dir/libwinlator.so"
cp "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
  "$build_dir/libc++_shared.so"

adb -s "$serial" shell mkdir -p "$device_dir/writer"
adb -s "$serial" push "$build_dir/libfakeinput.so" "$build_dir/libwaudio.so" \
  "$build_dir/libc++_shared.so" "$build_dir/libwinlator.so" "$build_dir/dex/classes.dex" \
  "$build_dir/fakeinput_test" "$build_dir/directaudio_input_test" "$device_dir/" >/dev/null
adb -s "$serial" shell chmod 755 "$device_dir/fakeinput_test" "$device_dir/directaudio_input_test"
tests=(open-deltas partial-resync busy-snapshot overflow deltas ioctl-state ioctl-bounds busy-ioctl close-reader
       concurrent-readers close-poll ppoll-mask mixed-poll poll-interrupt generation
       rumble-backpressure blocking-passthrough)
for test in "${tests[@]}"; do
  adb -s "$serial" shell "TMPDIR=$device_dir LD_LIBRARY_PATH=$device_dir $device_dir/fakeinput_test $device_dir/libfakeinput.so $test"
done
adb -s "$serial" shell "LD_LIBRARY_PATH=$device_dir CLASSPATH=$device_dir/classes.dex app_process -Djava.library.path=$device_dir $device_dir FakeInputWriterTest $device_dir/libfakeinput.so $device_dir/writer"
adb -s "$serial" shell "LD_LIBRARY_PATH=$device_dir LD_PRELOAD=$device_dir/libfakeinput.so $device_dir/directaudio_input_test $device_dir/libwaudio.so"
