#include <cassert>
#include <cerrno>
#include <cstdlib>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <unistd.h>

struct Reader {
  int fd;
  ssize_t (*read)(int, void *, size_t);
  int (*close)(int);
  int x = 0, y = 0, button = 0, presses = 0, trigger = 0;
  int reported_button = 0;
};

extern "C" JNIEXPORT jlong JNICALL
Java_FakeInputWriterTest_openReader(JNIEnv *env, jclass, jstring library,
                                    jstring directory, jstring rings) {
  const char *path = env->GetStringUTFChars(directory, nullptr);
  setenv("FAKE_EVDEV_DIR", path, 1);
  env->ReleaseStringUTFChars(directory, path);
  path = env->GetStringUTFChars(rings, nullptr);
  setenv("FAKE_EVDEV_MEMFD_PATHS", path, 1);
  env->ReleaseStringUTFChars(rings, path);
  path = env->GetStringUTFChars(library, nullptr);
  void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  env->ReleaseStringUTFChars(library, path);
  assert(handle);
  auto open_input = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(handle, "open"));
  auto *reader = new Reader();
  reader->read = reinterpret_cast<decltype(reader->read)>(dlsym(handle, "read"));
  reader->close = reinterpret_cast<decltype(reader->close)>(dlsym(handle, "close"));
  reader->fd = open_input("/dev/input/event0", O_RDONLY | O_NONBLOCK);
  assert(reader->fd >= 0);
  return reinterpret_cast<jlong>(reader);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_FakeInputWriterTest_readState(JNIEnv *env, jclass, jlong handle) {
  auto &reader = *reinterpret_cast<Reader *>(handle);
  input_event ev{};
  for (int i = 0; i < 10000; ++i) {
    ssize_t count = reader.read(reader.fd, &ev, sizeof(ev));
    if (count < 0) { assert(errno == EAGAIN); break; }
    assert(count == sizeof(ev));
    if (ev.type == EV_ABS) {
      if (ev.code == ABS_X) reader.x = ev.value;
      if (ev.code == ABS_Y) reader.y = ev.value;
      if (ev.code == ABS_GAS) reader.trigger = ev.value;
    } else if (ev.type == EV_KEY && ev.code == BTN_A) {
      reader.button = ev.value;
    } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
      // Both axes are written together. A mixed frame detects torn ring
      // events, snapshot/cursor mismatch, or a lost part of a keyframe.
      assert(reader.x == reader.y);
      if (reader.button && !reader.reported_button) ++reader.presses;
      reader.reported_button = reader.button;
    }
  }
  jint values[] = {reader.x, reader.y, reader.button, reader.presses, reader.trigger};
  jintArray result = env->NewIntArray(5);
  env->SetIntArrayRegion(result, 0, 5, values);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_FakeInputWriterTest_closeReader(JNIEnv *, jclass, jlong handle) {
  auto *reader = reinterpret_cast<Reader *>(handle);
  reader->close(reader->fd);
  delete reader;
}
