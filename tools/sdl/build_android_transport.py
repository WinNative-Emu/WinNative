#!/usr/bin/env python3
"""Build an isolated SDL3 for Steam Controller input on arm64 Android."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
VERSION = "3.4.16"
PATCHED = VERSION + "-winnative.2"
REPO = ROOT / "vendor/maven/org/libsdl/android/SDL3"
SOURCE_SHA = "c2ee715e42ec520c4d11fd8d249ef1d2b2baf4ad31148b72b3b000276c0b3633"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    args = parser.parse_args()
    if not args.sdk:
        parser.error("set ANDROID_HOME or pass --sdk")
    with tempfile.TemporaryDirectory(prefix="sdl-transport-") as directory:
        work = Path(directory)
        archive = args.source_archive or work / "sdl.tar.gz"
        if not args.source_archive:
            urllib.request.urlretrieve(
                f"https://github.com/libsdl-org/SDL/archive/refs/tags/release-{VERSION}.tar.gz",
                archive,
            )
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SOURCE_SHA:
            raise ValueError("SDL source checksum mismatch")
        with tarfile.open(archive) as source:
            build_env = dict(os.environ, SOURCE_DATE_EPOCH=str(int(source.getmembers()[0].mtime)))
            source.extractall(work, filter="data")
        source = work / f"SDL-release-{VERSION}"
        subprocess.run(
            ["patch", "-p1", "-i", str(ROOT / "tools/sdl/steam-bluetooth.patch")],
            cwd=source, check=True,
        )
        for path in list((source / "src").rglob("*")) + list((source / "android-project/app/src/main/java").rglob("*.java")):
            if not path.is_file() or path.suffix not in {".c", ".h", ".cpp", ".java"}:
                continue
            text = path.read_text()
            replaced = text.replace("org/libsdl/app", "org/winnative/steam").replace("org.libsdl.app", "org.winnative.steam").replace("org_libsdl_app", "org_winnative_steam")
            if path.name == "SDLActivity.java":
                replaced = replaced.replace('"SDL3"', '"SDL3steam"')
            if replaced != text:
                path.write_text(replaced)
        with (source / "CMakeLists.txt").open("a") as cmake:
            cmake.write('\nset_target_properties(SDL3-shared PROPERTIES OUTPUT_NAME SDL3steam)\n')
        ndk = args.sdk / "ndk/27.3.13750724"
        build = work / "build"
        cmake = shutil.which("cmake") or str(args.sdk / "cmake/3.22.1/bin/cmake")
        subprocess.run([cmake, "-S", str(source), "-B", str(build), "-G", "Ninja",
            f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
            "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
            "-DCMAKE_BUILD_TYPE=Release", "-DSDL_SHARED=ON", "-DSDL_STATIC=OFF",
            f"-DCMAKE_C_FLAGS=-ffile-prefix-map={work}=/sdl-build",
            f"-DCMAKE_CXX_FLAGS=-ffile-prefix-map={work}=/sdl-build",
            "-DSDL_TEST_LIBRARY=OFF", "-DSDL_TESTS=OFF", "-DSDL_EXAMPLES=OFF",
            "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"], check=True, env=build_env)
        subprocess.run([cmake, "--build", str(build), "--target", "SDL3-shared", "--parallel", "8"], check=True, env=build_env)
        upstream = REPO / VERSION / f"SDL3-{VERSION}.aar"
        expected = (upstream.with_suffix(".aar.sha256")).read_text().split()[0]
        if hashlib.sha256(upstream.read_bytes()).hexdigest() != expected:
            raise ValueError("SDL AAR checksum mismatch")
        with zipfile.ZipFile(upstream) as aar:
            jar = work / "classes.jar"
            jar.write_bytes(aar.read("classes.jar"))
            classes = work / "classes"
            classes.mkdir()
            subprocess.run([
                "javac", "--release", "8", "-cp",
                os.pathsep.join([str(args.sdk / "platforms/android-35/android.jar"), str(jar)]),
                "-d", str(classes),
                *map(str, sorted((source / "android-project/app/src/main/java").rglob("*.java"))),
            ], check=True)
            replacement = io.BytesIO()
            with zipfile.ZipFile(replacement, "w") as result:
                for compiled in sorted(classes.rglob("*.class")):
                    entry = zipfile.ZipInfo(compiled.relative_to(classes).as_posix(), (2026, 1, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    result.writestr(entry, compiled.read_bytes())
            output = REPO / PATCHED / f"SDL3-{PATCHED}.aar"
            output.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(output, "w") as result:
                for entry in aar.infolist():
                    if "/libs/android." in entry.filename and "/android.arm64-v8a/" not in entry.filename:
                        continue
                    data = aar.read(entry)
                    if entry.filename == "classes.jar":
                        data = replacement.getvalue()
                    elif entry.filename.endswith("/libSDL3.so"):
                        entry.filename = entry.filename.replace("libSDL3.so", "libSDL3steam.so")
                        data = (build / "libSDL3steam.so").read_bytes()
                    elif entry.filename == "prefab/modules/SDL3-shared/module.json":
                        data = json.dumps({"export_libraries": [":Headers"], "library_name": "libSDL3steam"}).encode()
                    elif entry.filename.endswith("/abi.json"):
                        metadata = json.loads(data)
                        metadata.update(api=26, ndk=27, stl="none")
                        data = json.dumps(metadata).encode()
                    result.writestr(entry, data)
        output.with_suffix(".aar.sha256").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "\n")
        pom = (REPO / VERSION / f"SDL3-{VERSION}.pom").read_text()
        pom = re.sub(r"<!--.*?-->\n", "", pom, flags=re.S)
        pom = pom.replace("official release AAR, unmodified", "isolated Steam Controller build for WinNative")
        output.with_suffix(".pom").write_text(pom.replace(f"<version>{VERSION}</version>", f"<version>{PATCHED}</version>"))
        print(output)


if __name__ == "__main__":
    main()
