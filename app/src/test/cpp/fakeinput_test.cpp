// Standalone Android regression tests; see tools/test-fakeinput.sh.
#include <algorithm>
#include <atomic>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <vector>

struct Ring {
  uint32_t magic, version, event_size, capacity;
  uint64_t write_seq, generation, snapshot_seq;
  uint32_t buttons;
  int16_t axes[8];
  uint32_t resync;
  input_event events[4096];
};
static_assert(offsetof(Ring, events) == 64);

struct Fixture {
  Ring *ring;
  int fd;
  std::string directory;
  int (*open_input)(const char *, int, ...);
  ssize_t (*read_input)(int, void *, size_t);
  ssize_t (*write_input)(int, const void *, size_t);
  int (*close_input)(int);
  int (*poll_input)(pollfd *, nfds_t, int);
  int (*ppoll_input)(pollfd *, nfds_t, const timespec *, const sigset_t *);
  int (*ioctl_input)(int, int, ...);

  explicit Fixture(const char *library) {
    const char *temporary = getenv("TMPDIR");
    std::string path = std::string(temporary ? temporary : "/data/local/tmp") + "/fakeinput-test-XXXXXX";
    assert(mkdtemp(path.data()));
    directory = path;
    int storage = open((directory + "/ring0").c_str(), O_CREAT | O_RDWR, 0600);
    assert(storage >= 0 && ftruncate(storage, sizeof(Ring)) == 0);
    ring = static_cast<Ring *>(mmap(nullptr, sizeof(Ring), PROT_READ | PROT_WRITE,
                                    MAP_SHARED, storage, 0));
    assert(ring != MAP_FAILED);
    close(storage);
    ring->magic = 0x46494252;
    ring->version = 2;
    ring->event_size = sizeof(input_event);
    ring->capacity = 4096;
    close(open((directory + "/event0").c_str(), O_CREAT | O_RDWR, 0600));
    setenv("FAKE_EVDEV_DIR", directory.c_str(), 1);
    setenv("FAKE_EVDEV_MEMFD_PATHS", ("0=" + directory + "/ring0").c_str(), 1);
    setenv("FAKE_EVDEV_VIBRATION", "1", 1);
    void *handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!handle) fprintf(stderr, "%s\n", dlerror());
    assert(handle);
#define HOOK(name) name##_input = reinterpret_cast<decltype(name##_input)>(dlsym(handle, #name)); assert(name##_input)
    HOOK(open); HOOK(read); HOOK(write); HOOK(close); HOOK(poll); HOOK(ppoll); HOOK(ioctl);
#undef HOOK
    fd = open_input("/dev/input/event0", O_RDWR | O_NONBLOCK);
    assert(fd >= 0);
  }

  ~Fixture() {
    if (fd >= 0) close_input(fd);
    munmap(ring, sizeof(Ring));
    unlink((directory + "/ring0").c_str());
    unlink((directory + "/event0").c_str());
    rmdir(directory.c_str());
  }

  void begin() {
    __atomic_add_fetch(&ring->snapshot_seq, 1, __ATOMIC_SEQ_CST);
  }
  void end() {
    __atomic_add_fetch(&ring->snapshot_seq, 1, __ATOMIC_RELEASE);
  }
  void event(uint16_t type, uint16_t code, int value) {
    input_event ev{};
    ev.type = type; ev.code = code; ev.value = value;
    ring->events[ring->write_seq++ % 4096] = ev;
  }
  void axis(int value) {
    begin();
    ring->axes[0] = value;
    event(EV_ABS, ABS_X, value);
    event(EV_SYN, SYN_REPORT, 0);
    end();
  }
  std::vector<input_event> drain(size_t batch = 1) {
    std::vector<input_event> result, buffer(batch);
    for (int i = 0; i < 20000; ++i) {
      ssize_t size = read_input(fd, buffer.data(), buffer.size() * sizeof(input_event));
      if (size < 0) {
        assert(errno == EAGAIN);
        return result;
      }
      assert(size > 0 && size % sizeof(input_event) == 0);
      result.insert(result.end(), buffer.begin(), buffer.begin() + size / sizeof(input_event));
    }
    assert(false && "reader never drained");
    return result;
  }
};

static int last_axis(const std::vector<input_event> &events) {
  int result = -99999;
  for (auto &event : events)
    if (event.type == EV_ABS && event.code == ABS_X) result = event.value;
  return result;
}

int main(int argc, char **argv) {
  assert(argc == 3);
  alarm(10);
  Fixture f(argv[1]);
  std::string test = argv[2];
  if (test == "open-deltas") {
    f.axis(20000); f.axis(0);
    auto events = f.drain();
    assert(last_axis(events) == 0);
    assert(std::any_of(events.begin(), events.end(), [](const input_event &ev) {
      return ev.type == EV_ABS && ev.code == ABS_X && ev.value == 20000;
    }));
  } else if (test == "partial-resync") {
    f.drain();
    f.begin(); f.ring->axes[0] = 20000; ++f.ring->resync; f.end();
    input_event ev{};
    assert(f.read_input(f.fd, &ev, sizeof(ev)) == sizeof(ev));
    f.begin(); f.ring->axes[0] = 0; ++f.ring->resync; f.end();
    pollfd pfd{f.fd, POLLIN, 0};
    assert(f.poll_input(&pfd, 1, 0) == 1);
    assert(last_axis(f.drain()) == 0);
  } else if (test == "busy-snapshot") {
    f.drain();
    f.begin();
    f.ring->axes[0] = 20000; ++f.ring->resync;
    input_event ev{};
    assert(f.read_input(f.fd, &ev, sizeof(ev)) == -1 && errno == EAGAIN);
    f.end();
    assert(last_axis(f.drain()) == 20000);
  } else if (test == "overflow") {
    f.drain();
    for (int i = 0; i < 3000; ++i) f.axis(i + 1);
    f.axis(0);
    auto events = f.drain();
    assert(last_axis(events) == 0);
    for (auto &event : events)
      assert(event.type != EV_ABS || event.code != ABS_X || event.value == 0);
  } else if (test == "deltas") {
    f.drain();
    f.axis(20000); f.axis(0);
    auto events = f.drain();
    assert(events.size() == 4 && events[0].value == 20000 && events[2].value == 0);
    assert(f.drain().empty());
  } else if (test == "ioctl-state") {
    f.axis(12345);
    f.begin(); f.ring->buttons = 1; f.end();
    input_absinfo info{};
    assert(f.ioctl_input(f.fd, EVIOCGABS(ABS_X), &info) >= 0);
    assert(info.value == 12345 && info.minimum == -32768 && info.maximum == 32767);
    unsigned char keys[(KEY_MAX + 8) / 8]{};
    assert(f.ioctl_input(f.fd, EVIOCGKEY(sizeof(keys)), keys) >= 0);
    assert(keys[BTN_A / 8] & (1 << (BTN_A % 8)));
  } else if (test == "ioctl-bounds") {
    unsigned char buffer[128];
    memset(buffer, 0xa5, sizeof(buffer));
    assert(f.ioctl_input(f.fd, EVIOCGBIT(EV_KEY, 1), buffer) >= 0);
    for (size_t i = 1; i < sizeof(buffer); ++i) assert(buffer[i] == 0xa5);
  } else if (test == "busy-ioctl") {
    f.drain();
    f.begin();
    f.ring->axes[0] = 23456;
    std::atomic<bool> started{false};
    std::thread query([&] {
      input_absinfo info{};
      started = true;
      assert(f.ioctl_input(f.fd, EVIOCGABS(ABS_X), &info) == 0);
      assert(info.value == 23456);
    });
    while (!started) std::this_thread::yield();
    usleep(30000);
    int version = 0;
    assert(f.ioctl_input(f.fd, EVIOCGVERSION, &version) == 0);
    f.end();
    query.join();
  } else if (test == "close-reader") {
    f.drain();
    assert(fcntl(f.fd, F_SETFL, 0) == 0);
    std::atomic<bool> started{false};
    std::thread reader([&] {
      input_event ev{};
      started = true;
      assert(f.read_input(f.fd, &ev, sizeof(ev)) == -1 && errno == EBADF);
    });
    while (!started) std::this_thread::yield();
    usleep(30000);
    f.close_input(f.fd);
    reader.join();
    f.fd = -1;
  } else if (test == "concurrent-readers") {
    f.drain();
    std::atomic<bool> done{false};
    auto read_loop = [&] {
      input_event ev{};
      while (!done) {
        auto size = f.read_input(f.fd, &ev, sizeof(ev));
        assert(size == sizeof(ev) || (size == -1 && errno == EAGAIN));
        if (size > 0) assert(ev.type == EV_ABS || ev.type == EV_KEY || ev.type == EV_SYN);
      }
    };
    std::thread first(read_loop), second(read_loop);
    for (int i = 0; i < 50000; ++i) f.axis(i % 30000);
    done = true;
    first.join(); second.join();
    f.axis(0);
    assert(last_axis(f.drain()) == 0);
  } else if (test == "close-poll") {
    f.drain();
    std::atomic<bool> started{false};
    std::thread waiter([&] {
      pollfd pfd{f.fd, POLLIN, 0};
      started = true;
      assert(f.poll_input(&pfd, 1, -1) == 1 && pfd.revents == POLLNVAL);
    });
    while (!started) std::this_thread::yield();
    usleep(30000);
    f.close_input(f.fd);
    waiter.join();
    f.fd = -1;
  } else if (test == "ppoll-mask") {
    f.drain();
    sigset_t mask;
    sigemptyset(&mask);
    timespec timeout{};
    pollfd pfd{f.fd, POLLIN, 0};
    assert(f.ppoll_input(&pfd, 1, &timeout, &mask) == 0 && pfd.revents == 0);
    f.axis(100);
    assert(f.ppoll_input(&pfd, 1, &timeout, &mask) == 1 && pfd.revents == POLLIN);
    assert(last_axis(f.drain()) == 100);
  } else if (test == "mixed-poll") {
    f.drain();
    int pipefd[2];
    assert(pipe(pipefd) == 0);
    assert(write(pipefd[1], "x", 1) == 1);
    pollfd pfds[] = {{f.fd, POLLIN, 0}, {pipefd[0], POLLIN, 0}};
    assert(f.poll_input(pfds, 2, 100) == 1);
    assert(pfds[0].revents == 0 && pfds[1].revents == POLLIN);
    close(pipefd[0]); close(pipefd[1]);
  } else if (test == "poll-interrupt") {
    f.drain();
    struct sigaction action{};
    action.sa_handler = [](int) {};
    sigemptyset(&action.sa_mask);
    assert(sigaction(SIGUSR1, &action, nullptr) == 0);
    std::atomic<bool> started{false};
    std::thread waiter([&] {
      pollfd pfd{f.fd, POLLIN, 0};
      started = true;
      assert(f.poll_input(&pfd, 1, -1) == -1 && errno == EINTR);
    });
    while (!started) std::this_thread::yield();
    usleep(30000);
    pthread_kill(waiter.native_handle(), SIGUSR1);
    waiter.join();
  } else if (test == "generation") {
    f.drain();
    f.begin(); ++f.ring->generation; f.end();
    pollfd pfd{f.fd, POLLIN, 0};
    assert(f.poll_input(&pfd, 1, 0) == 1 && pfd.revents == POLLHUP);
    input_event ev{};
    assert(f.read_input(f.fd, &ev, sizeof(ev)) == -1 && errno == ENODEV);
    f.close_input(f.fd);
    f.fd = f.open_input("/dev/input/event0", O_RDWR | O_NONBLOCK);
    assert(f.fd >= 0);
    assert(last_axis(f.drain()) == 0);
  } else if (test == "rumble-backpressure") {
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    const char *name = "winlator_vibration";
    memcpy(addr.sun_path + 1, name, strlen(name));
    socklen_t len = offsetof(sockaddr_un, sun_path) + 1 + strlen(name);
    assert(bind(server, reinterpret_cast<sockaddr *>(&addr), len) == 0);
    assert(listen(server, 0) == 0);
    int client = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    assert(connect(client, reinterpret_cast<sockaddr *>(&addr), len) == 0);
    ff_effect effect{};
    effect.id = -1; effect.type = FF_RUMBLE;
    effect.u.rumble.strong_magnitude = 100;
    assert(f.ioctl_input(f.fd, EVIOCSFF, &effect) == 0);
    f.axis(0);
    assert(last_axis(f.drain()) == 0);
    close(client); close(server);
  } else if (test == "blocking-passthrough") {
    int pipefd[2];
    assert(pipe(pipefd) == 0);
    std::vector<char> bytes(1024 * 1024);
    std::thread writer([&] { assert(f.write_input(pipefd[1], bytes.data(), bytes.size()) > 0); });
    usleep(30000);
    int version = 0;
    assert(f.ioctl_input(f.fd, EVIOCGVERSION, &version) == 0);
    size_t received = 0;
    while (received < bytes.size()) {
      ssize_t n = read(pipefd[0], bytes.data(), bytes.size());
      assert(n > 0); received += n;
    }
    writer.join(); close(pipefd[0]); close(pipefd[1]);
  } else {
    assert(false && "unknown test");
  }
  printf("PASS %s\n", test.c_str());
}
