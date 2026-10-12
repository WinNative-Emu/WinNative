#include <algorithm>
#include <cerrno>
#include <climits>
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
#include <pthread.h>
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

// glibc declares the request as unsigned long; bionic as int. The same source also serves the
// Linux runtime's preload (tools/linuxfs), where Steam and SDL run against glibc.
#ifdef __GLIBC__
typedef unsigned long ioctl_request_t;
#else
typedef int ioctl_request_t;
#endif

static constexpr uint16_t GAMEPAD_VENDOR_ID_DEFAULT = 0x045E; // Microsoft
static constexpr uint16_t GAMEPAD_PRODUCT_ID_DEFAULT = 0x028E; // Xbox 360 Controller
static constexpr uint16_t GAMEPAD_VERSION = 0x0110;
static constexpr uint32_t GAMEPAD_IDENTITY_MAX_SLOTS = 4;
static constexpr const char *GAMEPAD_NAME_TEMPLATE = "Xbox 360 Controller (%d)";
// The gamepad Steam Input presents to a game, and the name SDL reads its slot from.
static constexpr uint16_t STEAM_VIRTUAL_VENDOR_ID = 0x28de;
static constexpr uint16_t STEAM_VIRTUAL_PRODUCT_ID = 0x11ff;
static constexpr const char *STEAM_VIRTUAL_NAME_TEMPLATE = "Microsoft X-Box 360 pad %d";
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
  uint32_t resync_seq;        // 60
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
  uint32_t resync_seq = 0;
  size_t mapping_size = 0;
  bool closed = false;
  bool needs_keyframe = true;

  ~FakeController() {
    if (ring) munmap(ring, mapping_size);
    free(event);
  }
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
static const uint16_t kSnapshotButtons[] = {
    BTN_A,  BTN_B,      BTN_X,     BTN_Y,      BTN_TL,    BTN_TR,
    BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR, BTN_MODE};
static const int kSnapshotButtonCount =
    sizeof(kSnapshotButtons) / sizeof(kSnapshotButtons[0]);

static std::unordered_map<int, std::shared_ptr<FakeController>> controller_map;
static std::unordered_map<int, std::string> ring_paths;
static std::recursive_mutex controller_mutex;
static bool ring_paths_loaded = false;
static const char *hook_dir = nullptr;
static const char *udev_data_dir = nullptr;
static bool vibration_enabled = true;

static std::unordered_map<int, struct ff_effect> ff_effects;
static int next_ff_id = 0;

// fork() carries over only the calling thread, so a lock another thread was holding at that
// instant stays held in the child by a thread that is not there to release it. Every hook below
// takes controller_mutex, and the Steam client forks while its other threads are inside them, so
// the child blocked on its first open() and never reached exec: the spawn never completed, the
// client's main loop stalled past its own watchdog and it tore the session down.
//
// Taking the lock before the fork is what makes the copy consistent - no thread is part way
// through the tables it guards. The parent then unlocks it. The child cannot: a recursive mutex
// records the owning thread, the child's one thread has a new id, and unlocking one it does not
// own is refused, which would leave the lock held for good. It gets a fresh mutex instead, which
// is sound precisely because the fork was taken with the lock held.
static void controller_fork_prepare() { controller_mutex.lock(); }
static void controller_fork_parent() { controller_mutex.unlock(); }

static void controller_fork_child() {
  pthread_mutexattr_t attr;

  pthread_mutexattr_init(&attr);
  pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
  pthread_mutex_init(controller_mutex.native_handle(), &attr);
  pthread_mutexattr_destroy(&attr);
}

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

static void export_sdl_mappings();

__attribute__((constructor)) static void library_init() {
  if (!hook_dir)
    hook_dir = getenv("FAKE_EVDEV_DIR")
                   ? getenv("FAKE_EVDEV_DIR")
                   : "/data/data/com.termux/files/home/fake-input";
  udev_data_dir = getenv("FAKE_UDEV_DATA_DIR");
  vibration_enabled =
      getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));

  pthread_atfork(controller_fork_prepare, controller_fork_parent,
                 controller_fork_child);

  Logger::init();
  export_sdl_mappings();
}

__attribute__((visibility("hidden"))) static void
send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  if (!vibration_enabled)
    return;

  // Rumble is best effort. A full Android listener backlog must never park
  // winebus (or every input hook through controller_mutex), and a closing
  // listener must not terminate the guest with SIGPIPE.
  int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
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
  send(sock, data, sizeof(data), MSG_DONTWAIT | MSG_NOSIGNAL);
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
  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  if (ring_paths_loaded)
    return;

  const char *spec = getenv("FAKE_EVDEV_MEMFD_PATHS");
  if (!spec || !*spec) {
    ring_paths_loaded = true;
    return;
  }

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
  ring_paths_loaded = true;
}

__attribute__((visibility("hidden"))) static std::string
get_ring_path_for_slot(int slot) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  load_ring_paths();
  auto it = ring_paths.find(slot);
  return it == ring_paths.end() ? std::string() : it->second;
}

// Reads the slot's identity straight out of its udev entry
// ("<udev_data_dir>/c13:<minor>"), which the host keeps up to date as pads are
// bound and released.
//
// Returns false when the entry is missing or incomplete, which leaves the caller
// on the compiled-in Xbox 360 default.
__attribute__((visibility("hidden"))) static bool
read_gamepad_identity(int slot, GamepadIdentity *out) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  if (!udev_data_dir || !*udev_data_dir || !my_open)
    return false;
  if (slot < 0 || slot >= static_cast<int>(GAMEPAD_IDENTITY_MAX_SLOTS))
    return false;

  char path[PATH_MAX];
  snprintf(path, PATH_MAX, "%s/c13:%u", udev_data_dir, FAKE_INPUT_EVENT_MINOR_BASE + slot);

  int fd = my_open(path, O_RDONLY | O_CLOEXEC);
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

// The identity a slot reports: the real pad's when the host published it, else the Xbox 360 default.
__attribute__((visibility("hidden"))) static GamepadIdentity slot_identity(int slot) {
  GamepadIdentity identity;
  if (!read_gamepad_identity(slot, &identity)) {
    char buf[32];
    snprintf(buf, sizeof(buf), GAMEPAD_NAME_TEMPLATE, slot);
    identity.name = buf;
    identity.vendor = GAMEPAD_VENDOR_ID_DEFAULT;
    identity.product = GAMEPAD_PRODUCT_ID_DEFAULT;
  }
  return identity;
}

__attribute__((visibility("hidden"))) static uint64_t
ring_write_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->write_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint64_t
ring_generation(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->generation, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint32_t
ring_resync_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->resync_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static bool
ring_header_is_valid(const FakeInputRingHeader *ring) {
  return ring && ring->magic == FAKE_INPUT_RING_MAGIC &&
         ring->version == FAKE_INPUT_RING_VERSION &&
         ring->event_size == FAKE_INPUT_EVENT_SIZE &&
         ring->capacity == FAKE_INPUT_RING_CAPACITY;
}

struct SnapshotState {
  uint64_t sequence = 0;
  uint64_t write_seq = 0;
  uint64_t generation = 0;
  uint32_t resync_seq = 0;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
};

static long long monotonic_ms();

// The writer protects the events, cursor and snapshot in one publication.
// A busy writer is retried later; inventing a neutral state would lose holds.
__attribute__((visibility("hidden"))) static bool
read_snapshot(const FakeInputRingHeader *ring, SnapshotState &out) {
  for (int attempt = 0; attempt < 8; attempt++) {
    uint64_t sequence = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_ACQUIRE);
    if (sequence & 1ULL) continue;
    out.sequence = sequence;
    out.write_seq = ring_write_seq(ring);
    out.generation = ring_generation(ring);
    out.resync_seq = ring_resync_seq(ring);
    out.buttons = __atomic_load_n(&ring->snapshot_buttons, __ATOMIC_RELAXED);
    for (int i = 0; i < 8; i++)
      out.axes[i] = __atomic_load_n(&ring->snapshot_axes[i], __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (sequence == __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED))
      return true;
  }
  return false;
}

// This baseline supersedes all deltas through its cursor. Replaying older
// deltas after it can reassert a control that the snapshot already released.
__attribute__((visibility("hidden"))) static void
capture_keyframe(FakeController &fake, const SnapshotState &snap) {
  fake.keyframe_buttons = snap.buttons;
  for (int i = 0; i < 8; i++) fake.keyframe_axes[i] = snap.axes[i];
  fake.keyframe_remaining = kNeutralEventCount;
  fake.read_seq = snap.write_seq;
  fake.resync_seq = snap.resync_seq;
  fake.needs_keyframe = false;
}

// Resolve the value a keyframe event should carry from the captured snapshot.
__attribute__((visibility("hidden"))) static int32_t
keyframe_value(const FakeController &fake, uint16_t type, uint16_t code) {
  if (type == EV_KEY) {
    for (int i = 0; i < kSnapshotButtonCount; i++)
      if (kSnapshotButtons[i] == code)
        return (fake.keyframe_buttons >> i) & 1u;
    return 0;
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
  std::string ring_path = get_ring_path_for_slot(slot);
  if (ring_path.empty()) {
    Logger::log("No input ring is configured for %s (slot %d)\n", event, slot);
    errno = ENODEV;
    return -1;
  }

  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));

  int fd = my_open(ring_path.c_str(), O_RDWR | (flags & (O_NONBLOCK | O_CLOEXEC)));
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
    Logger::log("Input ring %s has no valid header\n", ring_path.c_str());
    munmap(mapping, FAKE_INPUT_RING_SIZE);
    syscall(SYS_close, fd);
    errno = ENODEV;
    return -1;
  }

  auto controller = std::make_shared<FakeController>();
  controller->event = strdup(event);
  controller->slot = slot;
  controller->ring = ring;
  controller->mapping_size = FAKE_INPUT_RING_SIZE;
  controller->generation = ring_generation(ring);

  controller->identity = slot_identity(slot);

  // Establish the cursor at open so taps arriving before the first read stay
  // queued. If publication is busy, read() will finish establishing it later.
  SnapshotState snap;
  if (read_snapshot(ring, snap) && snap.generation == controller->generation)
    capture_keyframe(*controller, snap);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex);
    controller_map[fd] = controller;
  }

  Logger::log("Adding ring-backed controller, fd %d event %s slot %d\n", fd,
              event, slot);
  return fd;
}

// Under the Steam client a pad it manages is hidden from the game: the client lists it in
// SDL_GAMECONTROLLER_IGNORE_DEVICES, its overlay refuses the game's open() of the node, and the
// game is meant to see Steam Input's virtual gamepad instead. That one is a uinput device, which
// does not exist here, so the game was left with no controller at all. With
// FAKE_EVDEV_STEAM_VIRTUAL set the pad carries the virtual gamepad's identity for every process
// but the client's own, which must keep seeing the pad as the physical one it reads.
__attribute__((visibility("hidden"))) static bool presents_steam_virtual() {
  static int known;
  if (known == 0) {
    const char *enabled = getenv("FAKE_EVDEV_STEAM_VIRTUAL");
    char exe[PATH_MAX];
    ssize_t length = enabled && atoi(enabled) ? readlink("/proc/self/exe", exe, sizeof(exe) - 1) : -1;
    bool client = false;
    if (length > 0) {
      exe[length] = '\0';
      const char *name = strrchr(exe, '/');
      client = !strncmp(name ? name + 1 : exe, "steam", 5);
    }
    known = length > 0 && !client ? 1 : -1;
  }
  return known == 1;
}

// The ring carries the triggers as ABS_BRAKE (left) and ABS_GAS (right); the pad presents them as
// ABS_Z and ABS_RZ, where an Xbox 360 pad has them, whatever identity it carries. Readers place a
// gamepad's axes by position: SDL's mapping for a known vendor and product (winebus's SDL bus in a
// container), and Proton's evdev bus, which takes any pad with six axes and ten buttons for one.
// With the triggers last the right stick landed on the left trigger, and a released right trigger
// read as the right stick held up. Wine's XInput reads the triggers from the Z and Rz usages, which
// are ABS_Z and ABS_RZ on its usage-based path too.
__attribute__((visibility("hidden"))) static uint16_t presented_abs_code(uint16_t code) {
  if (code == ABS_BRAKE) return ABS_Z;
  if (code == ABS_GAS) return ABS_RZ;
  return code;
}

__attribute__((visibility("hidden"))) static uint16_t ring_abs_code(uint16_t code) {
  if (code == ABS_Z) return ABS_BRAKE;
  if (code == ABS_RZ) return ABS_GAS;
  return code;
}

// SDL's mapping of the layout this pad presents, whatever its identity. SDL numbers buttons by key
// code (BTN_A, B, X, Y, TL, TR, SELECT, START, MODE, THUMBL, THUMBR) and axes by ABS code with the
// hat apart (X, Y, Z, RX, RY, RZ).
static constexpr const char *SDL_PAD_MAPPING =
    "a:b0,b:b1,x:b2,y:b3,leftshoulder:b4,rightshoulder:b5,back:b6,start:b7,guide:b8,"
    "leftstick:b9,rightstick:b10,leftx:a0,lefty:a1,lefttrigger:a2,rightx:a3,righty:a4,"
    "righttrigger:a5,dpup:h0.1,dpright:h0.2,dpdown:h0.4,dpleft:h0.8,";

// SDL maps a pad it knows by vendor, product and version with its own database, which describes
// that physical pad's kernel layout, not this one: 054c:05c4 gets a hid-sony entry with thirteen
// buttons, so a DualShock identity scrambled every button. Each slot's identity is given the
// mapping above as an exact match, which SDL chooses over any built-in entry. SDL reads
// SDL_GAMECONTROLLERCONFIG once, as its game controller subsystem starts, so it is set here, as the
// library loads; a mapping already in the variable comes after these and wins. Runs as a
// constructor: no static object of this file is used, the controller tables may not exist yet.
static void export_sdl_mappings() {
  const char *rings = getenv("FAKE_EVDEV_MEMFD_PATHS");
  if (!rings || !*rings)
    return;
  // Inherited from the process that started this one, which exported the same lines.
  const char *existing = getenv("SDL_GAMECONTROLLERCONFIG");
  std::string mappings;
  for (const char *token = rings; *token;) {
    int slot = atoi(token);
    const char *equals = strchr(token, '=');
    const char *next = strchr(token, ';');
    token = next ? next + 1 : token + strlen(token);
    if (!equals || (next && equals > next) || slot < 0 ||
        slot >= static_cast<int>(GAMEPAD_IDENTITY_MAX_SLOTS))
      continue;

    GamepadIdentity identity;
    if (presents_steam_virtual()) {
      identity.vendor = STEAM_VIRTUAL_VENDOR_ID;
      identity.product = STEAM_VIRTUAL_PRODUCT_ID;
      identity.name = "Microsoft X-Box 360 pad";
    } else if (!read_gamepad_identity(slot, &identity)) {
      identity.vendor = GAMEPAD_VENDOR_ID_DEFAULT;
      identity.product = GAMEPAD_PRODUCT_ID_DEFAULT;
      identity.name = "Xbox 360 Controller";
    }
    // The GUID of a USB evdev pad: bus, CRC (left 0 to match any name), vendor, product, version.
    char guid[33];
    snprintf(guid, sizeof(guid), "03000000%02x%02x0000%02x%02x0000%02x%02x0000",
             identity.vendor & 0xff, identity.vendor >> 8, identity.product & 0xff,
             identity.product >> 8, GAMEPAD_VERSION & 0xff, GAMEPAD_VERSION >> 8);
    for (char &c : identity.name)
      if (c == ',') c = ' ';
    std::string line = std::string(guid) + "," + identity.name + "," + SDL_PAD_MAPPING;
    if (mappings.find(guid) != std::string::npos || (existing && strstr(existing, line.c_str())))
      continue;
    mappings.append(line).append("\n");
  }
  if (mappings.empty())
    return;
  if (existing && *existing)
    mappings.append(existing);
  else
    mappings.pop_back();
  setenv("SDL_GAMECONTROLLERCONFIG", mappings.c_str(), 1);
  Logger::log("SDL mappings for the presented pads:\n%s\n", mappings.c_str());
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

// The name EVIOCGNAME and JSIOCGNAME report: Steam Input's virtual pad to a game under the
// Steam client, otherwise the slot's identity (the real pad's, or the Xbox 360 default).
__attribute__((visibility("hidden"))) static void
copy_presented_name(int op, void *argp, const FakeController &controller) {
  if (presents_steam_virtual())
    copy_slot_ioctl_string(op, argp, STEAM_VIRTUAL_NAME_TEMPLATE, controller.slot);
  else
    copy_ioctl_string(op, argp, controller.identity.name.c_str());
}

__attribute__((visibility("hidden"))) static bool is_fake_input_fd(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  return controller_map.find(fd) != controller_map.end();
}

__attribute__((visibility("hidden"))) static bool fake_fd_is_stale(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  return controller != controller_map.end() &&
         ring_generation(controller->second->ring) != controller->second->generation;
}

// Caller holds controller_mutex.
static bool fake_has_unread_data(const FakeController &fake) {
  if (ring_generation(fake.ring) != fake.generation)
    return false;
  // Readiness never consumes resync requests or changes the read cursor.
  // Do not spin a nonblocking guest on a producer's unfinished publication.
  if (fake.keyframe_remaining > 0) return true;
  uint64_t sequence = __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_ACQUIRE);
  if (sequence & 1ULL) return false;
  bool ready = fake.needs_keyframe ||
               ring_resync_seq(fake.ring) != fake.resync_seq ||
               ring_write_seq(fake.ring) != fake.read_seq;
  __atomic_thread_fence(__ATOMIC_ACQUIRE);
  return ready && sequence == __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED);
}

static bool fake_fd_has_unread_data(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  auto it = controller_map.find(fd);
  return it != controller_map.end() && fake_has_unread_data(*it->second);
}

static short fake_poll_revents(const std::shared_ptr<FakeController> &fake, short events) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  if (fake->closed) return POLLNVAL;
  if (ring_generation(fake->ring) != fake->generation) return POLLHUP;
  short ready = events & (POLLOUT | POLLWRNORM);
  if ((events & (POLLIN | POLLRDNORM)) && fake_has_unread_data(*fake))
    ready |= events & (POLLIN | POLLRDNORM);
  return ready;
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

  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));

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

  static auto my_openat = reinterpret_cast<int (*)(int, const char *, int, ...)>(dlsym(RTLD_NEXT, "openat"));

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
  static auto my_stat = reinterpret_cast<decltype(&::stat)>(dlsym(RTLD_NEXT, "stat"));

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
  static auto my_fstat = reinterpret_cast<decltype(&::fstat)>(dlsym(RTLD_NEXT, "fstat"));

  int ret = my_fstat(fd, buf);

  std::lock_guard<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  if (ret == 0 && controller != controller_map.end()) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = get_fake_input_rdev(controller->second->event);
  }

  return ret;
}

EXPORT int access(const char *pathname, int mode) {
  static auto my_access = reinterpret_cast<decltype(&::access)>(dlsym(RTLD_NEXT, "access"));

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
  static auto my_faccessat = reinterpret_cast<decltype(&::faccessat)>(dlsym(RTLD_NEXT, "faccessat"));

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
  static auto my_scandir = reinterpret_cast<decltype(&::scandir)>(dlsym(RTLD_NEXT, "scandir"));

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
  static auto my_inotify_add_watch = reinterpret_cast<decltype(&::inotify_add_watch)>(dlsym(RTLD_NEXT, "inotify_add_watch"));

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

template <size_t N>
static int copy_ioctl_bits(int op, void *destination, const unsigned char (&bits)[N]) {
  size_t size = std::min<size_t>(_IOC_SIZE(op), N);
  if (size) memcpy(destination, bits, size);
  return static_cast<int>(size);
}

static bool wait_snapshot(std::shared_ptr<FakeController> fake,
                          std::unique_lock<std::recursive_mutex> &guard,
                          SnapshotState &snap) {
  for (;;) {
    if (fake->closed) {
      errno = EBADF;
      return false;
    }
    if (ring_generation(fake->ring) != fake->generation) {
      errno = ENODEV;
      return false;
    }
    if (read_snapshot(fake->ring, snap)) return true;
    // Enumeration queries must not fail merely because Android is publishing
    // an input frame. Other hooks (including Binder) can proceed while we wait.
    guard.unlock();
    struct timespec wait = {0, 1000000};
    int result = nanosleep(&wait, nullptr);
    int saved_errno = errno;
    guard.lock();
    if (result < 0) {
      errno = saved_errno;
      return false;
    }
  }
}

EXPORT int ioctl(int fd, ioctl_request_t op, ...) {
  va_list va;
  void *argp;

  va_start(va, op);
  argp = va_arg(va, void *);
  va_end(va);

  // The lock must not be held across the passthrough: binder's transport is a
  // blocking ioctl(BINDER_WRITE_READ), so a parked binder pool thread would own
  // controller_mutex for as long as it waits and deadlock every other ioctl.
  std::unique_lock<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  if (controller == controller_map.end()) {
    guard.unlock();
    return syscall(SYS_ioctl, fd, op, argp);
  }

  int type = (op >> 8 & 0xFF);
  int number = (op >> 0 & 0xFF);
  const char *event = controller->second->event ? controller->second->event : "event0";
  int event_number = controller->second->slot;

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
    if (presents_steam_virtual()) {
      id.vendor = STEAM_VIRTUAL_VENDOR_ID;
      id.product = STEAM_VIRTUAL_PRODUCT_ID;
    } else {
      id.vendor = controller->second->identity.vendor;
      id.product = controller->second->identity.product;
    }
    id.version = GAMEPAD_VERSION;
    memcpy(argp, (void *)&id, sizeof(id));
    return 0;
  } else if (type == 0x45 && number == 0x6) {
    Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    copy_presented_name(op, argp, *controller->second);
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
    unsigned char bitmask[(INPUT_PROP_MAX + 8) / 8] = {};
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x18) {
    SnapshotState snap;
    if (!wait_snapshot(controller->second, guard, snap)) return -1;
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {};
    for (int i = 0; i < kSnapshotButtonCount; ++i) {
      if (snap.buttons & (1u << i))
        bitmask[kSnapshotButtons[i] / 8] |= 1u << (kSnapshotButtons[i] % 8);
    }
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x20) {
    Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
    unsigned char bitmask[(EV_MAX + 8) / 8] = {};
    bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
    bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
    bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x21) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {};
    const int xbox_buttons[] = {BTN_A,    BTN_B,      BTN_X,      BTN_Y,
                                BTN_TL,   BTN_TR,     BTN_SELECT, BTN_START,
                                BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (int button : xbox_buttons)
      bitmask[button / 8] |= (1 << (button % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x22) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    unsigned char bitmask[(REL_MAX + 8) / 8] = {};
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x23) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    unsigned char bitmask[(ABS_MAX + 8) / 8] = {};
    bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    for (uint16_t trigger : {ABS_GAS, ABS_BRAKE}) {
      uint16_t code = presented_abs_code(trigger);
      bitmask[code / 8] |= (1 << (code % 8));
    }
    bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x35) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
    unsigned char bitmask[(FF_MAX + 8) / 8] = {};
    bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
    bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
    return copy_ioctl_bits(op, argp, bitmask);
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
    uint16_t code = ring_abs_code(number - 0x40);
    if (code == ABS_GAS || code == ABS_BRAKE) {
      abs_info.value = 0;
      abs_info.minimum = 0;
      abs_info.maximum = 255;
    } else if (code <= ABS_RY) {
      abs_info.value = 0;
      abs_info.minimum = -32768;
      abs_info.maximum = 32767;
    } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
      abs_info.value = 0;
      abs_info.minimum = -1;
      abs_info.maximum = 1;
    }
    SnapshotState snap;
    if (!wait_snapshot(controller->second, guard, snap)) return -1;
    for (int i = 0; i < 8; ++i)
      if (kSnapshotAxisCodes[i] == code) abs_info.value = snap.axes[i];
    memcpy(argp, &abs_info, std::min<size_t>(_IOC_SIZE(op), sizeof(abs_info)));
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
    copy_presented_name(op, argp, *controller->second);
    return 0;
  } else {
    Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    guard.unlock();
    return syscall(SYS_ioctl, fd, op, argp);
  }
}

EXPORT int close(int fd) {
  static auto my_close = reinterpret_cast<decltype(&::close)>(dlsym(RTLD_NEXT, "close"));

  std::unique_lock<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    Logger::log("Removing controller, fd %d event %s\n", controller->first,
                controller->second->event ? controller->second->event : "(unknown)");
    controller->second->closed = true;
    controller_map.erase(fd);
  }
  guard.unlock();

  return my_close(fd);
}

// Nothing wakes a reader when the app publishes, so a waiting reader looks at the ring again
// at least this often: it bounds how late a press reaches a game that blocks on the device.
static constexpr int kMaxRingWaitMs = 2;

EXPORT ssize_t read(int fd, void *buf, size_t count) {
  std::unique_lock<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  if (controller == controller_map.end()) {
    guard.unlock();
    return syscall(SYS_read, fd, buf, count);
  }
  // Keep the mapping alive across waits, even if close() removes the fd and
  // another open reuses its number. Every state access still holds the lock.
  auto handle = controller->second;
  FakeController &fake = *handle;
  if (count < FAKE_INPUT_EVENT_SIZE) {
    errno = EINVAL;
    return -1;
  }
  int flags = fcntl(fd, F_GETFL);
  bool nonblock = flags >= 0 && (flags & O_NONBLOCK);
  long backoff_ns = 1000 * 1000;

  for (;;) {
    if (fake.closed) {
      errno = EBADF;
      return -1;
    }
    if (ring_generation(fake.ring) != fake.generation) {
      errno = ENODEV;
      return -1;
    }
    for (int attempt = 0; attempt < 8; ++attempt) {
      size_t requested = count / FAKE_INPUT_EVENT_SIZE;
      if (fake.keyframe_remaining == 0) {
        SnapshotState snap;
        if (!read_snapshot(fake.ring, snap)) break;
        if (snap.generation != fake.generation) {
          errno = ENODEV;
          return -1;
        }
        if (fake.needs_keyframe || snap.resync_seq != fake.resync_seq ||
            snap.write_seq < fake.read_seq ||
            snap.write_seq - fake.read_seq > FAKE_INPUT_RING_CAPACITY) {
          capture_keyframe(fake, snap);
        } else {
          size_t events = std::min<uint64_t>(requested, snap.write_seq - fake.read_seq);
          if (events == 0) break;
          const uint8_t *ring_events = reinterpret_cast<const uint8_t *>(fake.ring) +
                                       FAKE_INPUT_RING_HEADER_SIZE;
          for (size_t i = 0; i < events; ++i) {
            size_t index = (fake.read_seq + i) % FAKE_INPUT_RING_CAPACITY;
            struct input_event ev;
            memcpy(&ev, ring_events + index * FAKE_INPUT_EVENT_SIZE, FAKE_INPUT_EVENT_SIZE);
            if (ev.type == EV_ABS) ev.code = presented_abs_code(ev.code);
            memcpy(static_cast<uint8_t *>(buf) + i * FAKE_INPUT_EVENT_SIZE, &ev,
                   FAKE_INPUT_EVENT_SIZE);
          }
          // A producer can lap the reader while it copies. Discard that copy
          // instead of delivering torn/overwritten events or losing a release.
          __atomic_thread_fence(__ATOMIC_ACQUIRE);
          if (snap.sequence != __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED))
            continue;
          fake.read_seq += events;
          return static_cast<ssize_t>(events * FAKE_INPUT_EVENT_SIZE);
        }
      }

      // Finish a captured frame before handling a newer resync. Its counter is
      // acknowledged only when that newer snapshot is actually captured.
      size_t events = std::min(requested, fake.keyframe_remaining);
      struct timeval now = {};
      gettimeofday(&now, nullptr);
      for (size_t i = 0; i < events; ++i) {
        size_t index = kNeutralEventCount - fake.keyframe_remaining;
        struct input_event ev = {};
        ev.time = now;
        ev.type = kNeutralEvents[index].type;
        ev.code = kNeutralEvents[index].code;
        ev.value = keyframe_value(fake, ev.type, ev.code);
        if (ev.type == EV_ABS) ev.code = presented_abs_code(ev.code);
        memcpy(static_cast<uint8_t *>(buf) + i * FAKE_INPUT_EVENT_SIZE,
               &ev, FAKE_INPUT_EVENT_SIZE);
        --fake.keyframe_remaining;
      }
      return static_cast<ssize_t>(events * FAKE_INPUT_EVENT_SIZE);
    }
    if (nonblock) {
      errno = EAGAIN;
      return -1;
    }
    guard.unlock();
    struct timespec sleep_time = {0, backoff_ns};
    int result = nanosleep(&sleep_time, nullptr);
    guard.lock();
    if (result < 0) return -1;
    if (backoff_ns < kMaxRingWaitMs * 1000 * 1000) backoff_ns *= 2;
  }
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  static auto my_write = reinterpret_cast<decltype(&::write)>(dlsym(RTLD_NEXT, "write"));

  std::unique_lock<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    const struct input_event *ev = nullptr;
    uint16_t slot = static_cast<uint16_t>(controller->second->slot);
    if (count == sizeof(struct input_event)) {
      ev = static_cast<const struct input_event *>(buf);
      check_ff_event(ev, slot);
    }

    return static_cast<ssize_t>(count);
  }
  guard.unlock();
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  std::unique_lock<std::recursive_mutex> guard(controller_mutex);
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    uint16_t slot = static_cast<uint16_t>(controller->second->slot);
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
  guard.unlock();
  return syscall(SYS_writev, fd, iov, iovcnt);
}

static int poll_fake(struct pollfd *fds, nfds_t nfds, int timeout,
                     const sigset_t *sigmask) {
  static auto my_poll = reinterpret_cast<decltype(&::poll)>(dlsym(RTLD_NEXT, "poll"));
  static auto my_ppoll = reinterpret_cast<decltype(&::ppoll)>(dlsym(RTLD_NEXT, "ppoll"));

  bool has_fake_fds = false;
  std::vector<struct pollfd> real_fds;
  real_fds.reserve(nfds);
  std::vector<std::shared_ptr<FakeController>> fake_fds(nfds);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex);
    for (nfds_t i = 0; i < nfds; i++) {
      real_fds.push_back(fds[i]);
      auto it = controller_map.find(fds[i].fd);
      if (it != controller_map.end()) {
        fake_fds[i] = it->second;
        has_fake_fds = true;
        real_fds[i].fd = -1;
        real_fds[i].revents = 0;
      }
    }
  }

  if (!has_fake_fds) {
    if (sigmask) {
      struct timespec wait = {timeout / 1000, (timeout % 1000) * 1000000L};
      return my_ppoll(fds, nfds, timeout < 0 ? nullptr : &wait, sigmask);
    }
    return my_poll(fds, nfds, timeout);
  }

  const long long deadline_ms = timeout < 0 ? -1 : monotonic_ms() + timeout;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    for (nfds_t i = 0; i < nfds; i++)
      fds[i].revents = 0;

    for (nfds_t i = 0; i < nfds; i++) {
      if (!fake_fds[i]) continue;
      short revents = fake_poll_revents(fake_fds[i], fds[i].events);
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

    int real_ready;
    if (sigmask) {
      struct timespec wait = {real_timeout / 1000, (real_timeout % 1000) * 1000000L};
      real_ready = my_ppoll(real_fds.data(), nfds, &wait, sigmask);
    } else {
      real_ready = my_poll(real_fds.data(), nfds, real_timeout);
    }
    if (real_ready < 0) return -1;
    if (real_ready > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!fake_fds[i]) {
          fds[i].revents = real_fds[i].revents;
          if (fds[i].revents)
            ready++;
        }
      }
    }

    if (ready == 0 && real_timeout > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!fake_fds[i]) continue;
        short revents = fake_poll_revents(fake_fds[i], fds[i].events);
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

    if (backoff_ms < kMaxRingWaitMs)
      backoff_ms *= 2;
  }
}

EXPORT int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
  return poll_fake(fds, nfds, timeout, nullptr);
}

EXPORT int ppoll(struct pollfd *fds, nfds_t nfds,
                 const struct timespec *timeout, const sigset_t *sigmask) {
  static auto my_ppoll = reinterpret_cast<decltype(&::ppoll)>(dlsym(RTLD_NEXT, "ppoll"));
  if (timeout && (timeout->tv_sec < 0 || timeout->tv_nsec < 0 || timeout->tv_nsec >= 1000000000L)) {
    errno = EINVAL;
    return -1;
  }
  for (nfds_t i = 0; i < nfds; ++i) {
    if (!is_fake_input_fd(fds[i].fd)) continue;
    if (!sigmask)
      return poll_fake(fds, nfds, static_cast<int>(timespec_to_ms(timeout)), nullptr);
    // Keep signals pending between polling slices. Each kernel ppoll applies
    // the requested mask atomically with its wait, just like a single ppoll.
    sigset_t all_signals, original_mask;
    sigfillset(&all_signals);
    int error = pthread_sigmask(SIG_SETMASK, &all_signals, &original_mask);
    if (error) {
      errno = error;
      return -1;
    }
    int result = poll_fake(fds, nfds, static_cast<int>(timespec_to_ms(timeout)), sigmask);
    int saved_errno = errno;
    pthread_sigmask(SIG_SETMASK, &original_mask, nullptr);
    errno = saved_errno;
    return result;
  }
  return my_ppoll(fds, nfds, timeout, sigmask);
}

EXPORT int select(int nfds, fd_set *readfds, fd_set *writefds,
                  fd_set *exceptfds, struct timeval *timeout) {
  static auto my_select = reinterpret_cast<decltype(&::select)>(dlsym(RTLD_NEXT, "select"));

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

    if (real_ready < 0) return -1;
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

    if (backoff_ms < kMaxRingWaitMs)
      backoff_ms *= 2;
  }
}

#ifdef __GLIBC__
// Steam's binaries were linked against an older glibc and reach the calls above through these
// names instead.
static mode_t open_mode(int flags, va_list va) {
  return (flags & (O_CREAT | O_TMPFILE)) ? va_arg(va, mode_t) : 0;
}

EXPORT int open64(const char *pathname, int flags, ...) {
  va_list va;
  va_start(va, flags);
  mode_t mode = open_mode(flags, va);
  va_end(va);
  return open(pathname, flags, mode);
}

EXPORT int openat64(int dirfd, const char *pathname, int flags, ...) {
  va_list va;
  va_start(va, flags);
  mode_t mode = open_mode(flags, va);
  va_end(va);
  return openat(dirfd, pathname, flags, mode);
}

EXPORT int stat64(const char *pathname, struct stat64 *statbuf) {
  return stat(pathname, reinterpret_cast<struct stat *>(statbuf));
}

EXPORT int fstat64(int fd, struct stat64 *buf) {
  return fstat(fd, reinterpret_cast<struct stat *>(buf));
}

EXPORT int __xstat(int version, const char *pathname, struct stat *statbuf) {
  (void)version;
  return stat(pathname, statbuf);
}

EXPORT int __xstat64(int version, const char *pathname, struct stat64 *statbuf) {
  (void)version;
  return stat(pathname, reinterpret_cast<struct stat *>(statbuf));
}

EXPORT int __fxstat(int version, int fd, struct stat *buf) {
  (void)version;
  return fstat(fd, buf);
}

EXPORT int __fxstat64(int version, int fd, struct stat64 *buf) {
  (void)version;
  return fstat(fd, reinterpret_cast<struct stat *>(buf));
}
#endif
