#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default"))) extern "C"

static constexpr uint16_t GAMEPAD_VENDOR_ID_DEFAULT = 0x045E; // Microsoft
static constexpr uint16_t GAMEPAD_PRODUCT_ID_DEFAULT = 0x028E; // Xbox 360 Controller
static constexpr uint16_t GAMEPAD_VERSION = 0x0110;
static constexpr uint32_t GAMEPAD_IDENTITY_MAX_SLOTS = 4; 
static constexpr const char *GAMEPAD_NAME_TEMPLATE = "Xbox 360 Controller (%d)";
static constexpr const char *GAMEPAD_PHYS_TEMPLATE = "usb-fakeinput/input%d";
static constexpr const char *GAMEPAD_UNIQ_TEMPLATE = "0000000000%02d";
static constexpr uint8_t GAMEPAD_AXIS_COUNT = 8;
static constexpr uint8_t GAMEPAD_BUTTON_COUNT = 11;
static constexpr uint32_t FAKE_INPUT_RING_MAGIC = 0x46494252;
static constexpr uint32_t FAKE_INPUT_RING_VERSION = 2;
static constexpr uint32_t FAKE_INPUT_EVENT_SIZE = sizeof(struct input_event);
static constexpr uint32_t FAKE_INPUT_RING_CAPACITY = 4096;
static constexpr unsigned int FAKE_INPUT_MAJOR = 13;
static constexpr unsigned int FAKE_INPUT_EVENT_MINOR_BASE = 64;
static constexpr unsigned int FAKE_INPUT_JS_MINOR_BASE = 0;

struct FakeInputRingHeader {
  uint32_t magic;             // 0
  uint32_t version;           // 4
  uint32_t event_size;        // 8
  uint32_t capacity;          // 12
  uint64_t write_seq;         // 16
  uint64_t generation;        // 24
  // Authoritative absolute-state snapshot, published by the writer under a
  // seqlock (odd snapshot_seq = write in progress). The reader replays it as a
  // full keyframe whenever the delta stream could have desynced (open, ring
  // overflow) so dropped events can recover without periodic duplicate input.
  uint64_t snapshot_seq;      // 32
  uint32_t snapshot_buttons;  // 40  bit i -> kSnapshotButtons[i] pressed
  int16_t snapshot_axes[8];   // 44  values in kSnapshotAxisCodes order
  uint8_t reserved[4];        // 60
};

static_assert(sizeof(FakeInputRingHeader) == 64,
              "fake input ring header must stay ABI-stable");

static constexpr size_t FAKE_INPUT_RING_HEADER_SIZE =
    sizeof(FakeInputRingHeader);
static constexpr size_t FAKE_INPUT_RING_SIZE =
    FAKE_INPUT_RING_HEADER_SIZE +
    (FAKE_INPUT_RING_CAPACITY * FAKE_INPUT_EVENT_SIZE);

// Per-slot real-controller identity, published by the host when the user opts
// into "Force External Gamepad Identity" so games with dedicated non-Xbox
// controller support (e.g. PlayStation) can recognize the real pad instead
// of the spoofed Xbox 360 Controller identity.
struct GamepadIdentity {
  uint16_t vendor;
  uint16_t product;
  std::string name;
};

struct FakeController {
  char *event = nullptr;
  int slot = -1;
  FakeInputRingHeader *ring = nullptr;
  uint64_t read_seq = 0;
  uint64_t generation = 0;
  size_t mapping_size = 0;
  // Pending keyframe (full absolute-state baseline) currently streaming to the
  // guest. The axis/button values are captured from the snapshot when the
  // keyframe starts so the frame stays consistent across multi-read delivery.
  size_t keyframe_remaining = 0;
  int32_t keyframe_axes[8] = {0, 0, 0, 0, 0, 0, 0, 0};
  uint32_t keyframe_buttons = 0;
  GamepadIdentity identity;
};

struct NeutralEventSpec {
  uint16_t type;
  uint16_t code;
};

// Event template for a full keyframe: every button, every axis/hat, then a
// SYN_REPORT, in this fixed order. The value carried by each event is filled
// from the authoritative snapshot (see keyframe_value); an all-zero snapshot
// yields the neutral baseline. Replayed on open and ring overflow so dropped
// events cannot leave a guest stuck.
static const NeutralEventSpec kNeutralEvents[] = {
    {EV_KEY, BTN_A},      {EV_KEY, BTN_B},      {EV_KEY, BTN_X},
    {EV_KEY, BTN_Y},      {EV_KEY, BTN_TL},     {EV_KEY, BTN_TR},
    {EV_KEY, BTN_SELECT}, {EV_KEY, BTN_START},  {EV_KEY, BTN_MODE},
    {EV_KEY, BTN_THUMBL}, {EV_KEY, BTN_THUMBR}, {EV_ABS, ABS_X},
    {EV_ABS, ABS_Y},      {EV_ABS, ABS_RX},     {EV_ABS, ABS_RY},
    {EV_ABS, ABS_GAS},    {EV_ABS, ABS_BRAKE},  {EV_ABS, ABS_HAT0X},
    {EV_ABS, ABS_HAT0Y},  {EV_SYN, SYN_REPORT},
};
static constexpr size_t kNeutralEventCount =
    sizeof(kNeutralEvents) / sizeof(kNeutralEvents[0]);

// Axis layout of FakeInputRingHeader::snapshot_axes (mirrors the Java writer).
static const uint16_t kSnapshotAxisCodes[8] = {
    ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y};
// Bit i of FakeInputRingHeader::snapshot_buttons maps to this button code.
static const uint16_t kSnapshotButtons[10] = {
    BTN_A,  BTN_B,      BTN_X,     BTN_Y,      BTN_TL,
    BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR};

static std::unordered_map<int, FakeController> controller_map;
static std::unordered_map<int, std::string> ring_paths;
static bool ring_paths_loaded = false;
static bool initialized = false;
static const char *hook_dir = nullptr;
static const char *udev_data_dir = nullptr;
static bool vibration_enabled = true;
volatile sig_atomic_t stop_flag = 0;

static int (*my_open)(const char *, int, ...) = nullptr;
static int (*my_openat)(int, const char *, int, ...) = nullptr;
static int (*my_stat)(const char *, struct stat *) = nullptr;
static int (*my_fstat)(int fd, struct stat *buf) = nullptr;
static int (*my_access)(const char *, int) = nullptr;
static int (*my_faccessat)(int, const char *, int, int) = nullptr;
static int (*my_scandir)(const char *, struct dirent ***,
                         int (*)(const struct dirent *),
                         int (*)(const struct dirent **,
                                 const struct dirent **));
static int (*my_inotify_add_watch)(int, const char *, uint32_t);
static int (*my_close)(int);
static int (*my_poll)(struct pollfd *, nfds_t, int) = nullptr;
static int (*my_ppoll)(struct pollfd *, nfds_t, const struct timespec *,
                       const sigset_t *) = nullptr;
static int (*my_select)(int, fd_set *, fd_set *, fd_set *,
                        struct timeval *) = nullptr;
static ssize_t (*my_write)(int, const void *, size_t) = nullptr;

static std::unordered_map<int, struct ff_effect> ff_effects;
static int next_ff_id = 0;

namespace Logger {
int log_enabled;

void init() {
  log_enabled = getenv("FAKE_EVDEV_LOG") && atoi(getenv("FAKE_EVDEV_LOG"));
}

void log(const char *message, ...) {
  if (!log_enabled)
    return;

  va_list args;
  va_start(args, message);
  vfprintf(stderr, message, args);
  va_end(args);

  std::cerr.flush();
}
} // namespace Logger

void handle_sigint(int sig) {
  (void)sig;
  stop_flag = 1;
}

void setup_signal_handler() {
  if (!initialized) {
    signal(SIGINT, handle_sigint);
    initialized = true;
  }
}

__attribute__((constructor)) static void library_init() {
  if (!hook_dir)
    hook_dir = getenv("FAKE_EVDEV_DIR")
                   ? getenv("FAKE_EVDEV_DIR")
                   : "/data/data/com.termux/files/home/fake-input";
  udev_data_dir = getenv("FAKE_UDEV_DATA_DIR");
  vibration_enabled =
      getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));

  Logger::init();
}

__attribute__((visibility("hidden"))) static void
send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  if (!vibration_enabled)
    return;

  int sock = socket(AF_UNIX, SOCK_STREAM, 0);
  if (sock < 0)
    return;

  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  const char *name = "winlator_vibration";
  memcpy(addr.sun_path + 1, name, strlen(name));
  socklen_t addrlen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);

  if (connect(sock, reinterpret_cast<struct sockaddr *>(&addr), addrlen) < 0) {
    syscall(SYS_close, sock);
    return;
  }

  uint16_t data[4];
  data[0] = static_cast<uint16_t>(strong);
  data[1] = static_cast<uint16_t>(weak);
  data[2] = duration_ms;
  data[3] = slot;
  send(sock, data, sizeof(data), 0);
  syscall(SYS_close, sock);
}

__attribute__((visibility("hidden"))) static void
check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type != EV_FF)
    return;

  int id = ev->code;
  if (ev->value > 0) {
    auto it = ff_effects.find(id);
    if (it == ff_effects.end())
      return;

    uint16_t duration = it->second.replay.length;
    if (it->second.type == FF_RUMBLE) {
      send_vibration(it->second.u.rumble.strong_magnitude,
                     it->second.u.rumble.weak_magnitude, duration, slot);
    } else if (it->second.type == FF_PERIODIC) {
      send_vibration(it->second.u.periodic.magnitude,
                     it->second.u.periodic.magnitude, duration, slot);
    }
  } else {
    send_vibration(0, 0, 0, slot);
  }
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_path(const char *pathname) {
  const char *event = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", hook_dir, event) < 0)
    fake_path = nullptr;
  return fake_path;
}

__attribute__((visibility("hidden"))) static bool path_exists(const char *path) {
  return path && faccessat(AT_FDCWD, path, F_OK, 0) == 0;
}

__attribute__((visibility("hidden"))) static bool
is_fake_input_node_path(const char *pathname) {
  return pathname && (!strncmp(pathname, "/dev/input/event", 16) ||
                      !strncmp(pathname, "/dev/input/js", 13));
}

__attribute__((visibility("hidden"))) static bool
is_fake_udev_data_path(const char *pathname) {
  return pathname && udev_data_dir && *udev_data_dir &&
         !strncmp(pathname, "/run/udev/data/c13:", 19);
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_udev_data_path(const char *pathname) {
  const char *name = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", udev_data_dir, name) < 0)
    fake_path = nullptr;
  return fake_path;
}

__attribute__((visibility("hidden"))) const char *
get_event(const char *pathname) {
  const char *event = strrchr(pathname, '/') + 1;
  return event;
}

__attribute__((visibility("hidden"))) int get_event_number(const char *event) {
  if (!event)
    return -1;

  const char *digits = event;
  while (*digits && (*digits < '0' || *digits > '9'))
    digits++;

  return *digits ? atoi(digits) : -1;
}

__attribute__((visibility("hidden"))) static dev_t
get_fake_input_rdev(const char *event) {
  int event_number = get_event_number(event);
  if (event_number < 0)
    return makedev(FAKE_INPUT_MAJOR, 0);

  if (!strncmp(event, "event", 5))
    return makedev(FAKE_INPUT_MAJOR, FAKE_INPUT_EVENT_MINOR_BASE + event_number);
  if (!strncmp(event, "js", 2))
    return makedev(FAKE_INPUT_MAJOR, FAKE_INPUT_JS_MINOR_BASE + event_number);

  return makedev(FAKE_INPUT_MAJOR, event_number);
}

__attribute__((visibility("hidden"))) static void load_ring_paths() {
  if (ring_paths_loaded)
    return;

  ring_paths_loaded = true;
  const char *spec = getenv("FAKE_EVDEV_MEMFD_PATHS");
  if (!spec || !*spec)
    return;

  char *copy = strdup(spec);
  if (!copy)
    return;

  char *saveptr = nullptr;
  for (char *token = strtok_r(copy, ";", &saveptr); token;
       token = strtok_r(nullptr, ";", &saveptr)) {
    char *equals = strchr(token, '=');
    if (!equals)
      continue;
    *equals = '\0';
    int slot = atoi(token);
    const char *path = equals + 1;
    if (slot >= 0 && *path)
      ring_paths[slot] = path;
  }

  free(copy);
}

__attribute__((visibility("hidden"))) static const char *
get_ring_path_for_slot(int slot) {
  load_ring_paths();
  auto it = ring_paths.find(slot);
  return it == ring_paths.end() ? nullptr : it->second.c_str();
}

// Reads the slot's identity straight out of its udev entry
// ("<udev_data_dir>/c13:<minor>"), which the host keeps up to date as pads are
// bound and released.
//
// Returns false when the entry is missing or incomplete, which leaves the caller
// on the compiled-in Xbox 360 default.
__attribute__((visibility("hidden"))) static bool
read_gamepad_identity(int slot, GamepadIdentity *out) {
  if (!udev_data_dir || !*udev_data_dir || !my_open)
    return false;
  if (slot < 0 || slot >= GAMEPAD_IDENTITY_MAX_SLOTS)
    return false;

  char path[PATH_MAX];
  snprintf(path, PATH_MAX, "%s/c13:%u", udev_data_dir, FAKE_INPUT_EVENT_MINOR_BASE + slot);

  int fd = my_open(path, O_RDONLY);
  if (fd < 0)
    return false;

  // A udev entry for one of our pads is a few hundred bytes; read it whole so a
  // truncated tail can never hide the NAME line.
  char buffer[2048];
  ssize_t length = syscall(SYS_read, fd, buffer, sizeof(buffer) - 1);
  syscall(SYS_close, fd);
  if (length <= 0)
    return false;
  buffer[length] = '\0';

  GamepadIdentity parsed;
  parsed.vendor = 0;
  parsed.product = 0;
  bool have_vendor = false;
  bool have_product = false;
  char *saveptr = nullptr;
  for (char *line = strtok_r(buffer, "\n", &saveptr); line;
       line = strtok_r(nullptr, "\n", &saveptr)) {
    if (!strncmp(line, "E:ID_VENDOR_ID=", 15)) {
      parsed.vendor = static_cast<uint16_t>(strtoul(line + 15, nullptr, 16));
      have_vendor = true;
    } else if (!strncmp(line, "E:ID_MODEL_ID=", 14)) {
      parsed.product = static_cast<uint16_t>(strtoul(line + 14, nullptr, 16));
      have_product = true;
    } else if (!strncmp(line, "E:NAME=", 7)) {
      // udev quotes the value; the name itself never contains a quote because
      // the host strips them before publishing.
      const char *value = line + 7;
      size_t value_length = strlen(value);
      if (value_length >= 2 && value[0] == '"' && value[value_length - 1] == '"')
        parsed.name.assign(value + 1, value_length - 2);
      else
        parsed.name.assign(value);
    }
  }

  if (!have_vendor || !have_product || parsed.name.empty())
    return false;

  *out = parsed;
  return true;
}

__attribute__((visibility("hidden"))) static uint64_t
ring_write_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->write_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint64_t
ring_generation(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->generation, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static bool
ring_header_is_valid(const FakeInputRingHeader *ring) {
  return ring && ring->magic == FAKE_INPUT_RING_MAGIC &&
         ring->version == FAKE_INPUT_RING_VERSION &&
         ring->event_size == FAKE_INPUT_EVENT_SIZE &&
         ring->capacity == FAKE_INPUT_RING_CAPACITY;
}

struct SnapshotState {
  uint32_t buttons = 0;
  int32_t axes[8] = {0, 0, 0, 0, 0, 0, 0, 0};
};

static long long monotonic_ms();

// Read the authoritative absolute-state snapshot using the writer's seqlock.
// Retries on a torn read (snapshot_seq odd or changed mid-read); after a few
// failed attempts returns the neutral baseline rather than spinning. This
// mirrors the publication model already used for write_seq.
__attribute__((visibility("hidden"))) static SnapshotState
read_snapshot(const FakeInputRingHeader *ring) {
  SnapshotState out;
  for (int attempt = 0; attempt < 8; attempt++) {
    uint64_t s1 = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_ACQUIRE);
    if (s1 & 1ULL)
      continue; // a write is in progress
    uint32_t buttons = ring->snapshot_buttons;
    int16_t axes[8];
    for (int i = 0; i < 8; i++)
      axes[i] = ring->snapshot_axes[i];
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    uint64_t s2 = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED);
    if (s1 == s2) {
      out.buttons = buttons;
      for (int i = 0; i < 8; i++)
        out.axes[i] = axes[i]; // sign-extend to int32 for the event value
      return out;
    }
  }
  return out;
}

// Capture the current absolute state into the controller so it can be streamed
// as a keyframe independently of the ring. Idempotent w.r.t. an in-flight
// keyframe: callers guard on keyframe_remaining == 0 so a partially delivered
// frame is never restarted mid-stream.
__attribute__((visibility("hidden"))) static void
capture_keyframe(FakeController &fake, const char *reason, int fd) {
  SnapshotState snap = read_snapshot(fake.ring);
  fake.keyframe_buttons = snap.buttons;
  for (int i = 0; i < 8; i++)
    fake.keyframe_axes[i] = snap.axes[i];
  fake.keyframe_remaining = kNeutralEventCount;
  Logger::log("Fake input keyframe reason=%s fd=%d slot=%d read_seq=%llu "
              "write_seq=%llu buttons=0x%03x axes=[%d,%d,%d,%d,%d,%d,%d,%d]\n",
              reason ? reason : "unknown", fd, fake.slot,
              static_cast<unsigned long long>(fake.read_seq),
              static_cast<unsigned long long>(ring_write_seq(fake.ring)),
              fake.keyframe_buttons, fake.keyframe_axes[0],
              fake.keyframe_axes[1], fake.keyframe_axes[2],
              fake.keyframe_axes[3], fake.keyframe_axes[4],
              fake.keyframe_axes[5], fake.keyframe_axes[6],
              fake.keyframe_axes[7]);
}

// Resolve the value a keyframe event should carry from the captured snapshot.
__attribute__((visibility("hidden"))) static int32_t
keyframe_value(const FakeController &fake, uint16_t type, uint16_t code) {
  if (type == EV_KEY) {
    for (int i = 0; i < 10; i++)
      if (kSnapshotButtons[i] == code)
        return (fake.keyframe_buttons >> i) & 1u;
    return 0; // e.g. BTN_MODE, which the writer never presses
  }
  if (type == EV_ABS) {
    for (int i = 0; i < 8; i++)
      if (kSnapshotAxisCodes[i] == code)
        return fake.keyframe_axes[i];
  }
  return 0; // SYN / unknown
}

__attribute__((visibility("hidden"))) static int
open_fake_input_ring(const char *event, int flags) {
  int slot = get_event_number(event);
  const char *ring_path = get_ring_path_for_slot(slot);
  if (!ring_path) {
    errno = ENODEV;
    return -1;
  }

  if (!my_open)
    *(void **)&my_open = dlsym(RTLD_NEXT, "open");

  int fd = my_open(ring_path, O_RDWR | (flags & O_NONBLOCK));
  if (fd < 0)
    return -1;

  void *mapping = mmap(nullptr, FAKE_INPUT_RING_SIZE, PROT_READ, MAP_SHARED, fd, 0);
  if (mapping == MAP_FAILED) {
    int saved_errno = errno;
    syscall(SYS_close, fd);
    errno = saved_errno;
    return -1;
  }

  FakeInputRingHeader *ring = reinterpret_cast<FakeInputRingHeader *>(mapping);
  if (!ring_header_is_valid(ring)) {
    munmap(mapping, FAKE_INPUT_RING_SIZE);
    syscall(SYS_close, fd);
    errno = ENODEV;
    return -1;
  }

  FakeController controller = {};
  controller.event = strdup(event);
  controller.slot = slot;
  controller.ring = ring;
  controller.mapping_size = FAKE_INPUT_RING_SIZE;
  controller.read_seq = ring_write_seq(ring);
  controller.generation = ring_generation(ring);

  if (!read_gamepad_identity(slot, &controller.identity)) {
    char buf[32];
    snprintf(buf, sizeof(buf), GAMEPAD_NAME_TEMPLATE, slot);
    controller.identity.name = std::string(buf);
    controller.identity.vendor = static_cast<uint16_t>(GAMEPAD_VENDOR_ID_DEFAULT);
    controller.identity.product = static_cast<uint16_t>(GAMEPAD_PRODUCT_ID_DEFAULT);
  }

  // Emit the current absolute state as the first frame so a guest that opens
  // mid-hold (or reopens after a slot hand-off) starts already in sync.
  capture_keyframe(controller, "open", fd);
  controller_map[fd] = controller;

  Logger::log("Adding ring-backed controller, fd %d event %s slot %d\n", fd,
              event, slot);
  return fd;
}

__attribute__((visibility("hidden"))) static void
copy_slot_ioctl_string(int op, void *argp, const char *format, int event_number) {
  size_t size = _IOC_SIZE(op);
  if (!argp || size == 0)
    return;

  snprintf(static_cast<char *>(argp), size, format, event_number);
}

__attribute__((visibility("hidden"))) static void
copy_ioctl_string(int op, void *argp, const char *value) {
  size_t size = _IOC_SIZE(op);
  if (!argp || size == 0)
    return;

  snprintf(static_cast<char *>(argp), size, "%s", value);
}

__attribute__((visibility("hidden"))) static bool is_fake_input_fd(int fd) {
  return controller_map.find(fd) != controller_map.end();
}

__attribute__((visibility("hidden"))) static bool fake_fd_is_stale(int fd) {
  auto controller = controller_map.find(fd);
  return controller != controller_map.end() &&
         ring_generation(controller->second.ring) != controller->second.generation;
}

__attribute__((visibility("hidden"))) static bool
fake_fd_has_unread_data(int fd) {
  auto controller = controller_map.find(fd);
  if (controller == controller_map.end())
    return false;

  FakeController &fake = controller->second;
  if (ring_generation(fake.ring) != fake.generation)
    return false;
  uint64_t write_seq = ring_write_seq(fake.ring);
  if (write_seq < fake.read_seq)
    fake.read_seq = write_seq;
  if (write_seq - fake.read_seq > FAKE_INPUT_RING_CAPACITY) {
    fake.read_seq = write_seq - FAKE_INPUT_RING_CAPACITY;
    if (fake.keyframe_remaining == 0) {
      capture_keyframe(fake, "overflow", fd);
    }
  }
  // A pending keyframe counts as readable so poll/blocking reads wake to finish
  // flushing it even after the ring itself has drained.
  return fake.keyframe_remaining > 0 || write_seq > fake.read_seq;
}

__attribute__((visibility("hidden"))) static long long
timespec_to_ms(const struct timespec *timeout) {
  if (!timeout)
    return -1;
  return static_cast<long long>(timeout->tv_sec) * 1000LL +
         timeout->tv_nsec / 1000000LL;
}

__attribute__((visibility("hidden"))) static long long
timeval_to_ms(const struct timeval *timeout) {
  if (!timeout)
    return -1;
  return static_cast<long long>(timeout->tv_sec) * 1000LL +
         timeout->tv_usec / 1000LL;
}

__attribute__((visibility("hidden"))) static long long monotonic_ms() {
  struct timespec now = {};
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<long long>(now.tv_sec) * 1000LL + now.tv_nsec / 1000000LL;
}

EXPORT int open(const char *pathname, int flags, ...) {
  va_list va;
  mode_t mode;
  int fd;
  bool hasMode;

  va_start(va, flags);

  hasMode = flags & O_CREAT;

  if (hasMode) {
    mode = va_arg(va, mode_t);
  }

  va_end(va);

  if (!my_open)
    *(void **)&my_open = dlsym(RTLD_NEXT, "open");

  char *fake_path = nullptr;
  const char *event = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      if (path_exists(fake_path)) {
        fd = open_fake_input_ring(event, flags);
        if (fd >= 0) {
          free(fake_path);
          return fd;
        }
        int saved_errno = errno;
        free(fake_path);
        errno = saved_errno;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = hook_dir;
    }
  }

  if (hasMode)
    fd = my_open(pathname, flags, mode);
  else
    fd = my_open(pathname, flags);

  if (fake_path)
    free(fake_path);

  return fd;
}

EXPORT int openat(int dirfd, const char *pathname, int flags, ...) {
  va_list va;
  mode_t mode;
  int fd;
  bool hasMode;

  va_start(va, flags);

  hasMode = flags & O_CREAT;

  if (hasMode) {
    mode = va_arg(va, mode_t);
  }

  va_end(va);

  if (!my_openat)
    *(void **)&my_openat = dlsym(RTLD_NEXT, "openat");

  char *fake_path = nullptr;
  const char *event = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      if (path_exists(fake_path)) {
        fd = open_fake_input_ring(event, flags);
        if (fd >= 0) {
          free(fake_path);
          return fd;
        }
        int saved_errno = errno;
        free(fake_path);
        errno = saved_errno;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = hook_dir;
    }
  }

  if (hasMode)
    fd = my_openat(dirfd, pathname, flags, mode);
  else
    fd = my_openat(dirfd, pathname, flags);

  if (fake_path)
    free(fake_path);

  return fd;
}

EXPORT int stat(const char *pathname, struct stat *statbuf) {
  if (!my_stat)
    *(void **)&my_stat = dlsym(RTLD_NEXT, "stat");

  const char *event = nullptr;
  char *fake_path = nullptr;

  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = hook_dir;
    }
  }

  int ret = my_stat(pathname, statbuf);

  if (ret == 0 && event && get_event_number(event) >= 0) {
    statbuf->st_mode = (statbuf->st_mode & ~S_IFMT) | S_IFCHR;
    statbuf->st_rdev = get_fake_input_rdev(event);
  }

  if (fake_path)
    free(fake_path);

  return ret;
}

EXPORT int fstat(int fd, struct stat *buf) {
  if (!my_fstat)
    *(void **)&my_fstat = dlsym(RTLD_NEXT, "fstat");

  int ret = my_fstat(fd, buf);

  auto controller = controller_map.find(fd);
  if (ret == 0 && controller != controller_map.end()) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = get_fake_input_rdev(controller->second.event);
  }

  return ret;
}

EXPORT int access(const char *pathname, int mode) {
  if (!my_access)
    *(void **)&my_access = dlsym(RTLD_NEXT, "access");

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = hook_dir;
    }
  }

  int ret = my_access(pathname, mode);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int faccessat(int dirfd, const char *pathname, int mode, int flags) {
  if (!my_faccessat)
    *(void **)&my_faccessat = dlsym(RTLD_NEXT, "faccessat");

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = hook_dir;
    }
  }

  int ret = my_faccessat(dirfd, pathname, mode, flags);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int scandir(const char *dirp, struct dirent ***namelist,
                   int (*filter)(const struct dirent *),
                   int (*compar)(const struct dirent **,
                                 const struct dirent **)) {
  if (!my_scandir)
    *(void **)&my_scandir = dlsym(RTLD_NEXT, "scandir");

  if (dirp) {
    if (!strcmp(dirp, "/dev/input")) {
      dirp = hook_dir;
    } else if (udev_data_dir && !strcmp(dirp, "/run/udev/data")) {
      dirp = udev_data_dir;
    }
  }

  return my_scandir(dirp, namelist, filter, compar);
}

EXPORT int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
  if (!my_inotify_add_watch)
    *(void **)&my_inotify_add_watch = dlsym(RTLD_NEXT, "inotify_add_watch");

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = hook_dir;
    }
  }

  int ret = my_inotify_add_watch(fd, pathname, mask);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int ioctl(int fd, int op, ...) {
  va_list va;
  void *argp;

  va_start(va, op);
  argp = va_arg(va, void *);
  va_end(va);

  auto controller = controller_map.find(fd);
  if (controller == controller_map.end()) {
    return syscall(SYS_ioctl, fd, op, argp);
  }

  int type = (op >> 8 & 0xFF);
  int number = (op >> 0 & 0xFF);
  const char *event = controller->second.event ? controller->second.event : "event0";
  int event_number = controller->second.slot;

  if (type == 0x45 && number == 0x1) {
    Logger::log("Hooking ioctl EVIOCGVERSION for event %s\n", event);
    int version = 65536;
    memcpy(argp, (void *)&version, sizeof(int));
    return 0;
  } else if (type == 0x45 && number == 0x2) {
    Logger::log("Hooking ioctl EVIOCGID for event %s\n", event);
    struct input_id id;
    memset(&id, 0, sizeof(id));
    id.bustype = 0x03;
    id.vendor = controller->second.identity.vendor;
    id.product = controller->second.identity.product;
    id.version = GAMEPAD_VERSION;
    memcpy(argp, (void *)&id, sizeof(id));
    return 0;
  } else if (type == 0x45 && number == 0x6) {
    Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    copy_ioctl_string(op, argp, controller->second.identity.name.c_str());
    return 0;
  } else if (type == 0x45 && number == 0x7) {
    Logger::log("Hooking ioctl EVIOCGPHYS for event %s\n", event);
    copy_slot_ioctl_string(op, argp, GAMEPAD_PHYS_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x8) {
    Logger::log("Hooking ioctl EVIOCGUNIQ for event %s\n", event);
    copy_slot_ioctl_string(op, argp, GAMEPAD_UNIQ_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x9) {
    Logger::log("Hooking ioctl EVIOCGPROP for event %s\n", event);
    return 0;
  } else if (type == 0x45 && number == 0x18) {
    Logger::log("Hooking ioctl EVIOCGKEY(len) for event %s\n", event);
    char bitmask[KEY_MAX / 8] = {0};
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x20) {
    Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
    char bitmask[EV_MAX / 8] = {0};
    bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
    bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
    bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x21) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
    char bitmask[KEY_MAX / 8] = {0};
    const int xbox_buttons[] = {BTN_A,    BTN_B,      BTN_X,      BTN_Y,
                                BTN_TL,   BTN_TR,     BTN_SELECT, BTN_START,
                                BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (int button : xbox_buttons)
      bitmask[button / 8] |= (1 << (button % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x22) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    char bitmask[REL_MAX / 8] = {0};
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x23) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    char bitmask[ABS_MAX / 8] = {0};
    bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    bitmask[ABS_GAS / 8] |= (1 << (ABS_GAS % 8));
    bitmask[ABS_BRAKE / 8] |= (1 << (ABS_BRAKE % 8));
    bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x35) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
    char bitmask[FF_MAX / 8] = {0};
    bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
    bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x80) {
    struct ff_effect *effect = static_cast<struct ff_effect *>(argp);
    if (effect->id == -1)
      effect->id = next_ff_id++;
    ff_effects[effect->id] = *effect;

    uint16_t duration = effect->replay.length;
    uint16_t slot = static_cast<uint16_t>(event_number);
    if (effect->type == FF_RUMBLE) {
      send_vibration(effect->u.rumble.strong_magnitude,
                     effect->u.rumble.weak_magnitude, duration, slot);
    } else if (effect->type == FF_PERIODIC) {
      send_vibration(effect->u.periodic.magnitude, effect->u.periodic.magnitude,
                     duration, slot);
    }
    return 0;
  } else if (type == 0x45 && number == 0x81) {
    int id = (intptr_t)argp;
    ff_effects.erase(id);
    return 0;
  } else if (type == 0x45 && number == 0x84) {
    int max_effects = 16;
    memcpy(argp, &max_effects, sizeof(int));
    return 0;
  } else if (type == 0x45 && number >= 0x40 && number <= 0x51) {
    Logger::log("Hooking ioctl EVIOCGABS(ABS) for event %s\n", event);
    struct input_absinfo abs_info;
    memset(&abs_info, 0, sizeof(abs_info));
    if (number >= 0x40 && number <= 0x44) {
      abs_info.value = 0;
      abs_info.minimum = -32768;
      abs_info.maximum = 32767;
    } else if (number >= 0x49 && number <= 0x4A) {
      abs_info.value = 0;
      abs_info.minimum = 0;
      abs_info.maximum = 255;
    } else if (number >= 0x50 && number <= 0x51) {
      abs_info.value = 0;
      abs_info.minimum = -1;
      abs_info.maximum = 1;
    }
    memcpy(argp, (void *)&abs_info, sizeof(abs_info));
    return 0;
  } else if (type == 0x45 && number == 0x90) {
    Logger::log("Hooking ioctl EVIOCGRAB for event %s\n", event);
    return 0;
  } else if (type == 0x6A && number == 0x1) {
    Logger::log("Hooking ioctl JSIOCGVERSION for event %s\n", event);
    int version = JS_VERSION;
    memcpy(argp, (void *)&version, sizeof(version));
    return 0;
  } else if (type == 0x6A && number == 0x11) {
    Logger::log("Hooking ioctl JSIOCGAXES for event %s\n", event);
    uint8_t axes = GAMEPAD_AXIS_COUNT;
    memcpy(argp, (void *)&axes, sizeof(axes));
    return 0;
  } else if (type == 0x6A && number == 0x12) {
    Logger::log("Hooking ioctl JSIOCGBUTTONS for event %s\n", event);
    uint8_t buttons = GAMEPAD_BUTTON_COUNT;
    memcpy(argp, (void *)&buttons, sizeof(buttons));
    return 0;
  } else if (type == 0x6A && number == 0x13) {
    Logger::log("Hooking ioctl JSIOCGNAME(len) for event %s\n", event);
    copy_ioctl_string(op, argp, controller->second.identity.name.c_str());
    return 0;
  } else {
    Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    return syscall(SYS_ioctl, fd, op, argp);
  }
}

EXPORT int close(int fd) {
  if (!my_close)
    *(void **)&my_close = dlsym(RTLD_NEXT, "close");

  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    Logger::log("Removing controller, fd %d event %s\n", controller->first,
                controller->second.event ? controller->second.event : "(unknown)");
    if (controller->second.ring)
      munmap(controller->second.ring, controller->second.mapping_size);
    free(controller->second.event);
    controller_map.erase(fd);
  }

  return my_close(fd);
}

EXPORT ssize_t read(int fd, void *buf, size_t count) {
  auto controller = controller_map.find(fd);

  if (controller != controller_map.end()) {
    FakeController &fake = controller->second;
    if (count < FAKE_INPUT_EVENT_SIZE) {
      errno = EINVAL;
      return -1;
    }

    int flags = fcntl(fd, F_GETFL);
    bool isNonBlock = flags >= 0 && (flags & O_NONBLOCK);

    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    long backoff_ns = 1000 * 1000; // 1ms initial
    while (!fake_fd_has_unread_data(fd)) {
      if (fake_fd_is_stale(fd)) {
        errno = ENODEV;
        return -1;
      }
      if (isNonBlock) {
        errno = EAGAIN;
        return -1;
      }
      setup_signal_handler();
      if (stop_flag) {
        errno = EINTR;
        return -1;
      }
      struct timespec sleep_time = {0, backoff_ns};
      nanosleep(&sleep_time, nullptr);
      if (backoff_ns < 16 * 1000 * 1000)
        backoff_ns *= 2;
    }

    uint64_t write_seq = ring_write_seq(fake.ring);
    if (write_seq - fake.read_seq > FAKE_INPUT_RING_CAPACITY) {
      fake.read_seq = write_seq - FAKE_INPUT_RING_CAPACITY;
      if (fake.keyframe_remaining == 0) {
        capture_keyframe(fake, "overflow", fd);
      }
    }

    uint8_t *out = static_cast<uint8_t *>(buf);
    size_t out_events = 0;
    size_t requested_events = count / FAKE_INPUT_EVENT_SIZE;

    // A keyframe is pending (open / ring overflow). Replay the full
    // absolute baseline — every button and axis at its snapshot value — before
    // any surviving delta events, so a lost button-up / axis-return can't stick.
    // The frame streams across reads of any size: we emit as much as fits and do
    // NOT consume the ring until it is fully delivered, so even a
    // one-event-at-a-time consumer recovers. keyframe_remaining keeps the fd
    // readable (see fake_fd_has_unread_data) so poll wakes us to finish it.
    if (fake.keyframe_remaining > 0) {
      struct timeval now = {};
      gettimeofday(&now, nullptr);
      while (fake.keyframe_remaining > 0 && out_events < requested_events) {
        size_t idx = kNeutralEventCount - fake.keyframe_remaining;
        struct input_event ev;
        memset(&ev, 0, sizeof(ev));
        ev.time = now;
        ev.type = kNeutralEvents[idx].type;
        ev.code = kNeutralEvents[idx].code;
        ev.value = keyframe_value(fake, kNeutralEvents[idx].type,
                                  kNeutralEvents[idx].code);
        memcpy(out + (out_events * FAKE_INPUT_EVENT_SIZE), &ev,
               FAKE_INPUT_EVENT_SIZE);
        out_events++;
        fake.keyframe_remaining--;
      }
      if (fake.keyframe_remaining > 0) {
        // Buffer filled before the baseline finished; deliver the remainder (and
        // only then fresh events) on subsequent reads. out_events >= 1 here.
        return static_cast<ssize_t>(out_events * FAKE_INPUT_EVENT_SIZE);
      }
    }

    size_t available_events =
        static_cast<size_t>(std::min<uint64_t>(write_seq - fake.read_seq,
                                              FAKE_INPUT_RING_CAPACITY));
    size_t events_to_read =
        std::min(requested_events - out_events, available_events);
    const uint8_t *ring_events =
        reinterpret_cast<const uint8_t *>(fake.ring) +
        FAKE_INPUT_RING_HEADER_SIZE;

    for (size_t i = 0; i < events_to_read; i++) {
      size_t event_index =
          static_cast<size_t>((fake.read_seq + i) % FAKE_INPUT_RING_CAPACITY);
      memcpy(out + ((out_events + i) * FAKE_INPUT_EVENT_SIZE),
             ring_events + (event_index * FAKE_INPUT_EVENT_SIZE),
             FAKE_INPUT_EVENT_SIZE);
    }

    fake.read_seq += events_to_read;
    return static_cast<ssize_t>((out_events + events_to_read) *
                                FAKE_INPUT_EVENT_SIZE);
  }
  return syscall(SYS_read, fd, buf, count);
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  if (!my_write)
    *(void **)&my_write = dlsym(RTLD_NEXT, "write");

  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    const struct input_event *ev = nullptr;
    uint16_t slot = static_cast<uint16_t>(controller->second.slot);
    if (count == sizeof(struct input_event)) {
      ev = static_cast<const struct input_event *>(buf);
      check_ff_event(ev, slot);
    }

    return static_cast<ssize_t>(count);
  }
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    uint16_t slot = static_cast<uint16_t>(controller->second.slot);
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
      if (iov[i].iov_len == sizeof(struct input_event)) {
        const struct input_event *ev =
            static_cast<const struct input_event *>(iov[i].iov_base);
        check_ff_event(ev, slot);
      }
      total += static_cast<ssize_t>(iov[i].iov_len);
    }
    return total;
  }
  return syscall(SYS_writev, fd, iov, iovcnt);
}

EXPORT int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
  if (!my_poll)
    *(void **)&my_poll = dlsym(RTLD_NEXT, "poll");

  bool has_fake_fds = false;
  std::vector<struct pollfd> real_fds;
  real_fds.reserve(nfds);

  for (nfds_t i = 0; i < nfds; i++) {
    if (is_fake_input_fd(fds[i].fd)) {
      has_fake_fds = true;
    }
    real_fds.push_back(fds[i]);
    if (is_fake_input_fd(real_fds[i].fd)) {
      real_fds[i].fd = -1;
      real_fds[i].revents = 0;
    }
  }

  if (!has_fake_fds)
    return my_poll ? my_poll(fds, nfds, timeout) : -1;

  const long long deadline_ms = timeout < 0 ? -1 : monotonic_ms() + timeout;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    for (nfds_t i = 0; i < nfds; i++)
      fds[i].revents = 0;

    for (nfds_t i = 0; i < nfds; i++) {
      if (!is_fake_input_fd(fds[i].fd))
        continue;

      short revents = 0;
      if (fake_fd_is_stale(fds[i].fd))
        revents |= POLLHUP;
      if ((fds[i].events & (POLLIN | POLLRDNORM)) &&
          fake_fd_has_unread_data(fds[i].fd))
        revents |= (fds[i].events & (POLLIN | POLLRDNORM));

      fds[i].revents = revents;
      if (revents)
        ready++;
    }

    int real_timeout = ready > 0 ? 0 : [&] {
      if (timeout == 0) return 0;
      int remaining = deadline_ms < 0
                          ? backoff_ms
                          : std::min(backoff_ms, (int)(deadline_ms - monotonic_ms()));
      return std::max(remaining, 0);
    }();

    int real_ready = my_poll ? my_poll(real_fds.data(), nfds, real_timeout) : 0;
    if (real_ready > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!is_fake_input_fd(fds[i].fd)) {
          fds[i].revents = real_fds[i].revents;
          if (fds[i].revents)
            ready++;
        }
      }
    }

    if (ready == 0 && real_timeout > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!is_fake_input_fd(fds[i].fd))
          continue;

        short revents = 0;
        if (fake_fd_is_stale(fds[i].fd))
          revents |= POLLHUP;
        if ((fds[i].events & (POLLIN | POLLRDNORM)) &&
            fake_fd_has_unread_data(fds[i].fd))
          revents |= (fds[i].events & (POLLIN | POLLRDNORM));

        fds[i].revents = revents;
        if (revents)
          ready++;
      }
    }

    if (ready > 0)
      return ready;

    if (timeout == 0)
      return 0;

    if (deadline_ms >= 0 && monotonic_ms() >= deadline_ms)
      return 0;

    if (backoff_ms < 16)
      backoff_ms *= 2;
  }
}

EXPORT int ppoll(struct pollfd *fds, nfds_t nfds,
                 const struct timespec *timeout, const sigset_t *sigmask) {
  if (!my_ppoll)
    *(void **)&my_ppoll = dlsym(RTLD_NEXT, "ppoll");

  if (sigmask)
    return my_ppoll ? my_ppoll(fds, nfds, timeout, sigmask)
                    : syscall(SYS_ppoll, fds, nfds, timeout, sigmask,
                              sizeof(sigset_t));

  return poll(fds, nfds, static_cast<int>(timespec_to_ms(timeout)));
}

EXPORT int select(int nfds, fd_set *readfds, fd_set *writefds,
                  fd_set *exceptfds, struct timeval *timeout) {
  if (!my_select)
    *(void **)&my_select = dlsym(RTLD_NEXT, "select");

  fd_set original_readfds;
  fd_set original_writefds;
  fd_set original_exceptfds;
  fd_set real_readfds;
  fd_set real_writefds;
  fd_set real_exceptfds;
  bool has_fake_fds = false;

  if (readfds) {
    original_readfds = *readfds;
    real_readfds = *readfds;
  } else {
    FD_ZERO(&original_readfds);
    FD_ZERO(&real_readfds);
  }
  if (writefds) {
    original_writefds = *writefds;
    real_writefds = *writefds;
  } else {
    FD_ZERO(&original_writefds);
    FD_ZERO(&real_writefds);
  }
  if (exceptfds) {
    original_exceptfds = *exceptfds;
    real_exceptfds = *exceptfds;
  } else {
    FD_ZERO(&original_exceptfds);
    FD_ZERO(&real_exceptfds);
  }

  for (int fd = 0; fd < nfds; fd++) {
    if (!is_fake_input_fd(fd))
      continue;
    has_fake_fds = true;
    FD_CLR(fd, &real_readfds);
    FD_CLR(fd, &real_writefds);
    FD_CLR(fd, &real_exceptfds);
  }

  if (!has_fake_fds)
    return my_select ? my_select(nfds, readfds, writefds, exceptfds, timeout)
                     : -1;

  const long long timeout_ms = timeval_to_ms(timeout);
  const long long deadline_ms =
      timeout_ms < 0 ? -1 : monotonic_ms() + timeout_ms;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    if (readfds)
      FD_ZERO(readfds);
    if (writefds)
      FD_ZERO(writefds);
    if (exceptfds)
      FD_ZERO(exceptfds);

    for (int fd = 0; fd < nfds; fd++) {
      if (!is_fake_input_fd(fd))
        continue;
      if (readfds && FD_ISSET(fd, &original_readfds) && fake_fd_is_stale(fd)) {
        FD_SET(fd, readfds);
        ready++;
      } else if (readfds && FD_ISSET(fd, &original_readfds) &&
                 fake_fd_has_unread_data(fd)) {
        FD_SET(fd, readfds);
        ready++;
      }
    }

    int wait_ms = ready > 0 ? 0 : [&] {
      if (timeout_ms == 0) return 0;
      int remaining = deadline_ms < 0
                          ? backoff_ms
                          : std::min(backoff_ms, (int)(deadline_ms - monotonic_ms()));
      return std::max(remaining, 0);
    }();
    struct timeval wait_tv = {wait_ms / 1000, (wait_ms % 1000) * 1000};

    fd_set iter_readfds = real_readfds;
    fd_set iter_writefds = real_writefds;
    fd_set iter_exceptfds = real_exceptfds;

    int real_ready =
        my_select
            ? my_select(nfds, readfds ? &iter_readfds : nullptr,
                        writefds ? &iter_writefds : nullptr,
                        exceptfds ? &iter_exceptfds : nullptr, &wait_tv)
            : 0;

    if (real_ready > 0) {
      for (int fd = 0; fd < nfds; fd++) {
        if (readfds && FD_ISSET(fd, &iter_readfds)) {
          FD_SET(fd, readfds);
          ready++;
        }
        if (writefds && FD_ISSET(fd, &iter_writefds)) {
          FD_SET(fd, writefds);
          ready++;
        }
        if (exceptfds && FD_ISSET(fd, &iter_exceptfds)) {
          FD_SET(fd, exceptfds);
          ready++;
        }
      }
    }

    if (ready == 0 && wait_ms > 0) {
      for (int fd = 0; fd < nfds; fd++) {
        if (!is_fake_input_fd(fd))
          continue;
        if (readfds && FD_ISSET(fd, &original_readfds) && fake_fd_is_stale(fd)) {
          FD_SET(fd, readfds);
          ready++;
        } else if (readfds && FD_ISSET(fd, &original_readfds) &&
                   fake_fd_has_unread_data(fd)) {
          FD_SET(fd, readfds);
          ready++;
        }
      }
    }

    if (ready > 0)
      return ready;

    if (timeout_ms == 0)
      return 0;

    if (deadline_ms >= 0 && monotonic_ms() >= deadline_ms)
      return 0;

    if (backoff_ms < 16)
      backoff_ms *= 2;
  }
}
