/*
 * AAudio for the DirectAudio driver in a Linux session. Android's libaaudio cannot be loaded
 * into a glibc process, so the app opens each stream on the driver's behalf and the PCM moves
 * through a ring in shared memory; see app/src/main/cpp/wnaudiohook/wn_aaudio_protocol.h.
 *
 * AAudio calls a stream's data callback from a thread of its own, once a burst. Here a thread
 * per stream does: it keeps an output ring a little ahead of the app's real callback, which
 * wakes it each time it has taken a burst, and hands an input ring's frames on as they arrive.
 */
#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <android/log.h>
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "wn_aaudio_protocol.h"

#define SOCKET_ENV "WN_DIRECTAUDIO_SOCKET"
#define LOG_ENV "BANNER_AUDIO_DIRECT_LOG"
/* How long the thread sleeps when nothing wakes it: a stopped stream, or a host gone quiet. */
#define IDLE_WAIT_NS 20000000L
/* An output ring is kept this many bursts ahead of the host, over its largest callback. */
#define LEAD_BURSTS 2

struct AAudioStreamBuilderStruct {
    struct wn_audio_request request;
    AAudioStream_dataCallback on_data;
    AAudioStream_errorCallback on_error;
    void *data_user;
    void *error_user;
};

struct AAudioStreamStruct {
    int fd;
    struct wn_audio_ring *ring;
    size_t map_size;
    uint32_t frames;
    uint32_t frame_bytes;
    bool input;
    struct wn_audio_reply granted;
    _Atomic int32_t buffer_size;
    AAudioStream_dataCallback on_data;
    AAudioStream_errorCallback on_error;
    void *data_user;
    void *error_user;
    pthread_mutex_t call_lock; /* one request and its reply at a time */
    pthread_t thread;
    _Atomic bool started;
    _Atomic bool flush;       /* a stop leaves frames behind that a later start must not play */
    _Atomic bool closing;
    _Atomic bool self_closed; /* closed from its own callback: the thread frees the stream */
    uint8_t *burst;
};

int android_get_device_api_level(void) {
    /* The driver only asks whether usage and input presets exist (28); the app applies them
     * by the device's real level. */
    return 28;
}

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    static int enabled = -1;
    if (enabled < 0) {
        const char *value = getenv(LOG_ENV);
        enabled = value && *value && *value != '0';
    }
    if (!enabled && prio < ANDROID_LOG_WARN) return 0;
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "%s: ", tag);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
    va_end(args);
    return 1;
}

static bool call(AAudioStream *stream, const struct wn_audio_request *request, struct wn_audio_reply *reply, int *passed_fd) {
    union {
        char buffer[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    struct iovec iov = { .iov_base = reply, .iov_len = sizeof(*reply) };
    struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.buffer, .msg_controllen = sizeof(control.buffer) };
    bool ok = false;

    pthread_mutex_lock(&stream->call_lock);
    ssize_t n;
    do {
        n = send(stream->fd, request, sizeof(*request), MSG_NOSIGNAL);
    } while (n < 0 && errno == EINTR);
    if (n == (ssize_t)sizeof(*request)) {
        do {
            n = recvmsg(stream->fd, &message, MSG_WAITALL | MSG_CMSG_CLOEXEC);
        } while (n < 0 && errno == EINTR);
        ok = n == (ssize_t)sizeof(*reply);
    }
    pthread_mutex_unlock(&stream->call_lock);
    if (!ok) return false;

    if (passed_fd) {
        *passed_fd = -1;
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        if (header && header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS)
            memcpy(passed_fd, CMSG_DATA(header), sizeof(int));
    }
    return true;
}

static aaudio_result_t simple_call(AAudioStream *stream, uint32_t op, int32_t value, struct wn_audio_reply *reply) {
    struct wn_audio_request request = { .magic = WN_AUDIO_MAGIC, .op = op, .value = value };
    struct wn_audio_reply local;
    if (!reply) reply = &local;
    if (!call(stream, &request, reply, NULL)) return AAUDIO_ERROR_DISCONNECTED;
    return reply->result;
}

static void wait_for_host(struct wn_audio_ring *ring, uint32_t seen) {
    struct timespec timeout = { .tv_sec = 0, .tv_nsec = IDLE_WAIT_NS };
    syscall(SYS_futex, &ring->wake, FUTEX_WAIT, seen, &timeout, NULL, 0);
}

static void ring_write(AAudioStream *stream, uint32_t pos, uint32_t count) {
    uint8_t *data = WN_AUDIO_RING_DATA(stream->ring);
    uint32_t at = pos & (stream->frames - 1);
    uint32_t first = count < stream->frames - at ? count : stream->frames - at;
    memcpy(data + (size_t)at * stream->frame_bytes, stream->burst, (size_t)first * stream->frame_bytes);
    memcpy(data, stream->burst + (size_t)first * stream->frame_bytes, (size_t)(count - first) * stream->frame_bytes);
}

static void ring_read(AAudioStream *stream, uint32_t pos, uint32_t count) {
    const uint8_t *data = WN_AUDIO_RING_DATA(stream->ring);
    uint32_t at = pos & (stream->frames - 1);
    uint32_t first = count < stream->frames - at ? count : stream->frames - at;
    memcpy(stream->burst, data + (size_t)at * stream->frame_bytes, (size_t)first * stream->frame_bytes);
    memcpy(stream->burst + (size_t)first * stream->frame_bytes, data, (size_t)(count - first) * stream->frame_bytes);
}

/* Returns false once the driver's callback asks for the stream to stop. */
static bool pump(AAudioStream *stream) {
    struct wn_audio_ring *ring = stream->ring;
    uint32_t burst = (uint32_t)stream->granted.frames_per_burst;

    if (stream->input) {
        for (;;) {
            uint32_t read_pos = atomic_load(&ring->read_pos);
            uint32_t held = atomic_load(&ring->write_pos) - read_pos;
            if (held > stream->frames) held = stream->frames;
            if (held == 0) return true;
            uint32_t count = held < burst ? held : burst;
            ring_read(stream, read_pos, count);
            atomic_store(&ring->read_pos, read_pos + count);
            if (stream->on_data(stream, stream->data_user, stream->burst, (int32_t)count) != AAUDIO_CALLBACK_RESULT_CONTINUE)
                return false;
        }
    }

    uint32_t lead = atomic_load(&ring->callback_frames);
    if (lead < burst) lead = burst;
    lead += burst * LEAD_BURSTS;
    if (lead > stream->frames) lead = stream->frames;
    for (;;) {
        uint32_t write_pos = atomic_load(&ring->write_pos);
        uint32_t held = write_pos - atomic_load(&ring->read_pos);
        if (held + burst > lead) return true;
        if (stream->on_data(stream, stream->data_user, stream->burst, (int32_t)burst) != AAUDIO_CALLBACK_RESULT_CONTINUE)
            return false;
        ring_write(stream, write_pos, burst);
        atomic_store(&ring->write_pos, write_pos + burst);
    }
}

static void release(AAudioStream *stream) {
    if (stream->ring) munmap(stream->ring, stream->map_size);
    if (stream->fd >= 0) close(stream->fd);
    pthread_mutex_destroy(&stream->call_lock);
    free(stream->burst);
    free(stream);
}

static void *stream_thread(void *arg) {
    AAudioStream *stream = arg;
    struct wn_audio_ring *ring = stream->ring;
    bool failed = false;

    setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), -16);
    while (!atomic_load(&stream->closing)) {
        uint32_t seen = atomic_load(&ring->wake);
        int32_t error = atomic_load(&ring->error);
        /* Only this thread moves the position it empties the ring with. */
        if (atomic_exchange(&stream->flush, false)) {
            if (stream->input) atomic_store(&ring->read_pos, atomic_load(&ring->write_pos));
            else atomic_store(&ring->write_pos, atomic_load(&ring->read_pos));
        }
        if (error && !failed) {
            /* As with AAudio, the stream is dead from here and the driver closes it. */
            failed = true;
            if (stream->on_error) stream->on_error(stream, stream->error_user, error);
            continue;
        }
        if (!failed && atomic_load(&stream->started) && !pump(stream)) atomic_store(&stream->started, false);
        wait_for_host(ring, seen);
    }
    if (atomic_load(&stream->self_closed)) release(stream);
    return NULL;
}

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **builder) {
    if (!builder) return AAUDIO_ERROR_NULL;
    AAudioStreamBuilder *created = calloc(1, sizeof(*created));
    if (!created) return AAUDIO_ERROR_NO_MEMORY;
    created->request.magic = WN_AUDIO_MAGIC;
    created->request.op = WN_AUDIO_OPEN;
    created->request.direction = AAUDIO_DIRECTION_OUTPUT;
    created->request.sharing_mode = AAUDIO_SHARING_MODE_SHARED;
    *builder = created;
    return AAUDIO_OK;
}

void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *builder, int32_t sampleRate) { builder->request.sample_rate = sampleRate; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *builder, int32_t channelCount) { builder->request.channels = channelCount; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *builder, aaudio_format_t format) { builder->request.format = format; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *builder, aaudio_sharing_mode_t sharingMode) { builder->request.sharing_mode = sharingMode; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *builder, aaudio_direction_t direction) { builder->request.direction = direction; }
void AAudioStreamBuilder_setBufferCapacityInFrames(AAudioStreamBuilder *builder, int32_t numFrames) { builder->request.buffer_capacity = numFrames; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *builder, aaudio_performance_mode_t mode) { builder->request.performance_mode = mode; }
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *builder, aaudio_usage_t usage) { builder->request.usage = usage; }
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder *builder, aaudio_input_preset_t inputPreset) { builder->request.input_preset = inputPreset; }

void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *builder, AAudioStream_dataCallback callback, void *userData) {
    builder->on_data = callback;
    builder->data_user = userData;
}

void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *builder, AAudioStream_errorCallback callback, void *userData) {
    builder->on_error = callback;
    builder->error_user = userData;
}

aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *builder) {
    free(builder);
    return AAUDIO_OK;
}

aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *builder, AAudioStream **out) {
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    const char *path = getenv(SOCKET_ENV);
    int ring_fd = -1;

    if (!builder || !out) return AAUDIO_ERROR_NULL;
    /* The driver only streams through callbacks; AAudio's blocking read and write are not here. */
    if (!builder->on_data) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    if (!path || strlen(path) >= sizeof(address.sun_path)) return AAUDIO_ERROR_UNAVAILABLE;
    strcpy(address.sun_path, path);

    AAudioStream *stream = calloc(1, sizeof(*stream));
    if (!stream) return AAUDIO_ERROR_NO_MEMORY;
    pthread_mutex_init(&stream->call_lock, NULL);
    stream->input = builder->request.direction == AAUDIO_DIRECTION_INPUT;
    stream->on_data = builder->on_data;
    stream->on_error = builder->on_error;
    stream->data_user = builder->data_user;
    stream->error_user = builder->error_user;
    stream->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (stream->fd < 0 || connect(stream->fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        release(stream);
        return AAUDIO_ERROR_UNAVAILABLE;
    }

    aaudio_result_t result = AAUDIO_ERROR_DISCONNECTED;
    if (call(stream, &builder->request, &stream->granted, &ring_fd)) result = stream->granted.result;
    if (result == AAUDIO_OK && ring_fd < 0) result = AAUDIO_ERROR_INTERNAL;
    if (result != AAUDIO_OK) {
        if (ring_fd >= 0) close(ring_fd);
        release(stream);
        return result;
    }

    struct wn_audio_ring header;
    bool mapped = pread(ring_fd, &header, sizeof(header), 0) == (ssize_t)sizeof(header) &&
                  header.frames >= (uint32_t)stream->granted.frames_per_burst && header.frame_bytes > 0 &&
                  (header.frames & (header.frames - 1)) == 0;
    if (mapped) {
        stream->frames = header.frames;
        stream->frame_bytes = header.frame_bytes;
        stream->map_size = sizeof(header) + (size_t)header.frames * header.frame_bytes;
        void *map = mmap(NULL, stream->map_size, PROT_READ | PROT_WRITE, MAP_SHARED, ring_fd, 0);
        mapped = map != MAP_FAILED;
        if (mapped) stream->ring = map;
    }
    close(ring_fd);
    if (mapped) stream->burst = malloc((size_t)stream->granted.frames_per_burst * stream->frame_bytes);
    if (!mapped || !stream->burst) {
        release(stream);
        return AAUDIO_ERROR_NO_MEMORY;
    }
    atomic_store(&stream->buffer_size, stream->granted.buffer_size);
    if (pthread_create(&stream->thread, NULL, stream_thread, stream) != 0) {
        release(stream);
        return AAUDIO_ERROR_NO_MEMORY;
    }
    *out = stream;
    return AAUDIO_OK;
}

aaudio_result_t AAudioStream_requestStart(AAudioStream *stream) {
    if (!stream) return AAUDIO_ERROR_INVALID_HANDLE;
    aaudio_result_t result = simple_call(stream, WN_AUDIO_START, 0, NULL);
    if (result == AAUDIO_OK) {
        atomic_store(&stream->started, true);
        syscall(SYS_futex, &stream->ring->wake, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
    }
    return result;
}

aaudio_result_t AAudioStream_requestStop(AAudioStream *stream) {
    if (!stream) return AAUDIO_ERROR_INVALID_HANDLE;
    atomic_store(&stream->started, false);
    aaudio_result_t result = simple_call(stream, WN_AUDIO_STOP, 0, NULL);
    atomic_store(&stream->flush, true);
    return result;
}

aaudio_result_t AAudioStream_close(AAudioStream *stream) {
    if (!stream) return AAUDIO_ERROR_INVALID_HANDLE;
    atomic_store(&stream->started, false);
    simple_call(stream, WN_AUDIO_CLOSE, 0, NULL);
    /* AAudio forbids closing a stream from its own callback; a driver that does so anyway
     * gets a thread that ends by itself rather than one that waits for itself. */
    if (pthread_equal(pthread_self(), stream->thread)) {
        atomic_store(&stream->self_closed, true);
        atomic_store(&stream->closing, true);
        pthread_detach(stream->thread);
        return AAUDIO_OK;
    }
    atomic_store(&stream->closing, true);
    syscall(SYS_futex, &stream->ring->wake, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
    pthread_join(stream->thread, NULL);
    release(stream);
    return AAUDIO_OK;
}

aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *stream, int32_t numFrames) {
    struct wn_audio_reply reply;
    if (!stream) return AAUDIO_ERROR_INVALID_HANDLE;
    aaudio_result_t result = simple_call(stream, WN_AUDIO_SET_BUFFER_SIZE, numFrames, &reply);
    if (result != AAUDIO_OK) return result;
    atomic_store(&stream->buffer_size, reply.buffer_size);
    return reply.buffer_size;
}

int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *stream) { return atomic_load(&stream->buffer_size); }
int32_t AAudioStream_getFramesPerBurst(AAudioStream *stream) { return stream->granted.frames_per_burst; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *stream) { return stream->granted.buffer_capacity; }
int32_t AAudioStream_getXRunCount(AAudioStream *stream) { return atomic_load(&stream->ring->xruns); }
int32_t AAudioStream_getSampleRate(AAudioStream *stream) { return stream->granted.sample_rate; }
int32_t AAudioStream_getChannelCount(AAudioStream *stream) { return stream->granted.channels; }
aaudio_format_t AAudioStream_getFormat(AAudioStream *stream) { return stream->granted.format; }
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *stream) { return stream->granted.sharing_mode; }
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *stream) { return stream->granted.performance_mode; }
