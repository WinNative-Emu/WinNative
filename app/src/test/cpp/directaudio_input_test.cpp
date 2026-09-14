#include <aaudio/AAudio.h>
#include <atomic>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

// Run under LD_PRELOAD=libfakeinput.so. Loading the production libwaudio.so
// exercises DirectAudio's namespace bridge and Binder thread pool as well.
int main(int argc, char **argv) {
  assert(argc == 2);
  alarm(15);
  void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!library) fprintf(stderr, "%s\n", dlerror());
  assert(library);
#define AUDIO(name) auto name = reinterpret_cast<decltype(&::name)>(dlsym(library, #name)); assert(name)
  AUDIO(AAudio_createStreamBuilder);
  AUDIO(AAudioStreamBuilder_setDirection);
  AUDIO(AAudioStreamBuilder_setFormat);
  AUDIO(AAudioStreamBuilder_setChannelCount);
  AUDIO(AAudioStreamBuilder_setDataCallback);
  AUDIO(AAudioStreamBuilder_openStream);
  AUDIO(AAudioStreamBuilder_delete);
  AUDIO(AAudioStream_requestStart);
  AUDIO(AAudioStream_requestStop);
  AUDIO(AAudioStream_close);
#undef AUDIO
  for (int i = 0; i < 5; ++i) {
    std::atomic<int> callbacks{0};
    AAudioStreamBuilder *builder = nullptr;
    assert(AAudio_createStreamBuilder(&builder) == AAUDIO_OK);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setDataCallback(builder,
        [](AAudioStream *, void *user, void *audio, int32_t frames) -> aaudio_data_callback_result_t {
          memset(audio, 0, frames * 2 * sizeof(float));
          ++*static_cast<std::atomic<int> *>(user);
          return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }, &callbacks);
    AAudioStream *stream = nullptr;
    assert(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK);
    AAudioStreamBuilder_delete(builder);
    assert(AAudioStream_requestStart(stream) == AAUDIO_OK);
    for (int wait = 0; callbacks < 3 && wait < 100; ++wait) usleep(10000);
    assert(callbacks >= 3);
    // The AAudio binder workers are now waiting in blocking ioctls. Another
    // ioctl must still pass through fakeinput without inheriting their wait.
    int fd = open("/dev/null", O_RDONLY);
    int value = 0;
    ioctl(fd, FIONREAD, &value);
    close(fd);
    assert(AAudioStream_requestStop(stream) == AAUDIO_OK);
    assert(AAudioStream_close(stream) == AAUDIO_OK);
  }
  puts("PASS DirectAudio bridge: five streams delivered callbacks under fakeinput preload");
}
