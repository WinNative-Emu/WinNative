/*
 * The app's end of DirectAudio in a Linux session: opens the AAudio streams a session's
 * DirectAudio driver asks for and moves their PCM through shared memory. See
 * wn_aaudio_protocol.h. Nothing the client writes is trusted with the host's memory: the ring's
 * geometry is kept here, and the positions read from the ring are clamped to it.
 */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/sharedmem.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <limits.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include "wn_aaudio_protocol.h"

#define TAG "DirectAudioHost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define MAX_CONNECTIONS 32
#define MIN_RING_FRAMES 4096u

typedef void (*builder_int_setter)(AAudioStreamBuilder *, int32_t);

struct stream {
    AAudioStream *aaudio;
    /* Set once the stream is open; the error callback may run before that. */
    _Atomic(struct wn_audio_ring *) ring;
    _Atomic int32_t early_error;
    size_t map_size;
    int ring_fd;
    uint32_t frames;
    uint32_t frame_bytes;
    bool input;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_idle = PTHREAD_COND_INITIALIZER;
static pthread_t g_accept_thread;
static int g_listen_fd = -1;
static int g_connections[MAX_CONNECTIONS];
static int g_connection_count;
static bool g_running;
static bool g_allow_capture;

static void wake_client(struct wn_audio_ring *ring) {
    atomic_fetch_add(&ring->wake, 1);
    syscall(SYS_futex, &ring->wake, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

static void copy_out(const struct stream *s, uint8_t *dest, uint32_t pos, uint32_t count) {
    const uint8_t *data = WN_AUDIO_RING_DATA(atomic_load(&s->ring));
    uint32_t at = pos & (s->frames - 1);
    uint32_t first = count < s->frames - at ? count : s->frames - at;
    memcpy(dest, data + (size_t)at * s->frame_bytes, (size_t)first * s->frame_bytes);
    memcpy(dest + (size_t)first * s->frame_bytes, data, (size_t)(count - first) * s->frame_bytes);
}

static void copy_in(const struct stream *s, const uint8_t *src, uint32_t pos, uint32_t count) {
    uint8_t *data = WN_AUDIO_RING_DATA(atomic_load(&s->ring));
    uint32_t at = pos & (s->frames - 1);
    uint32_t first = count < s->frames - at ? count : s->frames - at;
    memcpy(data + (size_t)at * s->frame_bytes, src, (size_t)first * s->frame_bytes);
    memcpy(data, src + (size_t)first * s->frame_bytes, (size_t)(count - first) * s->frame_bytes);
}

static aaudio_data_callback_result_t on_data(AAudioStream *aaudio, void *user, void *audio, int32_t num_frames) {
    struct stream *s = user;
    struct wn_audio_ring *ring = atomic_load(&s->ring);
    uint32_t wanted = (uint32_t)num_frames;
    uint32_t write_pos = atomic_load(&ring->write_pos);
    uint32_t read_pos = atomic_load(&ring->read_pos);
    uint32_t held = write_pos - read_pos;
    (void)aaudio;
    if (held > s->frames) held = s->input ? s->frames : 0;

    if (s->input) {
        uint32_t room = s->frames - held;
        uint32_t count = wanted < room ? wanted : room;
        copy_in(s, audio, write_pos, count);
        atomic_store(&ring->write_pos, write_pos + count);
        if (count < wanted) atomic_fetch_add(&ring->xruns, 1);
    } else {
        uint32_t count = wanted < held ? wanted : held;
        copy_out(s, audio, read_pos, count);
        memset((uint8_t *)audio + (size_t)count * s->frame_bytes, 0, (size_t)(wanted - count) * s->frame_bytes);
        atomic_store(&ring->read_pos, read_pos + count);
        if (count < wanted) atomic_fetch_add(&ring->xruns, 1);
    }
    if (wanted > atomic_load(&ring->callback_frames)) atomic_store(&ring->callback_frames, wanted);
    wake_client(ring);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void on_error(AAudioStream *aaudio, void *user, aaudio_result_t error) {
    struct stream *s = user;
    (void)aaudio;
    LOGW("stream error %d (%s)", error, AAudio_convertResultToText(error));
    atomic_store(&s->early_error, error);
    struct wn_audio_ring *ring = atomic_load(&s->ring);
    if (!ring) return;
    atomic_store(&ring->error, error);
    wake_client(ring);
}

static void close_stream(struct stream *s) {
    if (s->aaudio) {
        AAudioStream_requestStop(s->aaudio);
        AAudioStream_close(s->aaudio);
        s->aaudio = NULL;
    }
    struct wn_audio_ring *ring = atomic_exchange(&s->ring, NULL);
    if (ring) munmap(ring, s->map_size);
    if (s->ring_fd >= 0) {
        close(s->ring_fd);
        s->ring_fd = -1;
    }
}

static uint32_t frame_bytes(aaudio_format_t format, int32_t channels) {
    uint32_t sample = format == AAUDIO_FORMAT_PCM_I16 ? 2 : format == AAUDIO_FORMAT_PCM_I24_PACKED ? 3 : 4;
    return sample * (uint32_t)channels;
}

static aaudio_result_t open_stream(struct stream *s, const struct wn_audio_request *request, struct wn_audio_reply *reply) {
    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t result;

    if (s->aaudio) return AAUDIO_ERROR_INVALID_STATE;
    s->input = request->direction == AAUDIO_DIRECTION_INPUT;
    if (s->input && !g_allow_capture) return AAUDIO_ERROR_UNAVAILABLE;

    result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;
    AAudioStreamBuilder_setDirection(builder, request->direction);
    if (request->sample_rate > 0) AAudioStreamBuilder_setSampleRate(builder, request->sample_rate);
    if (request->channels > 0) AAudioStreamBuilder_setChannelCount(builder, request->channels);
    if (request->format > 0) AAudioStreamBuilder_setFormat(builder, request->format);
    if (request->performance_mode > 0) AAudioStreamBuilder_setPerformanceMode(builder, request->performance_mode);
    AAudioStreamBuilder_setSharingMode(builder, request->sharing_mode);
    if (request->buffer_capacity > 0) AAudioStreamBuilder_setBufferCapacityInFrames(builder, request->buffer_capacity);
    /* Both arrived with Android 9, above what the app still installs on. */
    builder_int_setter set_usage = dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage");
    builder_int_setter set_input_preset = dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setInputPreset");
    if (set_usage && request->usage > 0) set_usage(builder, request->usage);
    if (set_input_preset && s->input && request->input_preset > 0) set_input_preset(builder, request->input_preset);
    AAudioStreamBuilder_setDataCallback(builder, on_data, s);
    AAudioStreamBuilder_setErrorCallback(builder, on_error, s);
    result = AAudioStreamBuilder_openStream(builder, &s->aaudio);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        s->aaudio = NULL;
        return result;
    }

    reply->sample_rate = AAudioStream_getSampleRate(s->aaudio);
    reply->channels = AAudioStream_getChannelCount(s->aaudio);
    reply->format = AAudioStream_getFormat(s->aaudio);
    reply->performance_mode = AAudioStream_getPerformanceMode(s->aaudio);
    reply->sharing_mode = AAudioStream_getSharingMode(s->aaudio);
    reply->frames_per_burst = AAudioStream_getFramesPerBurst(s->aaudio);
    reply->buffer_capacity = AAudioStream_getBufferCapacityInFrames(s->aaudio);
    reply->buffer_size = AAudioStream_getBufferSizeInFrames(s->aaudio);
    if (reply->channels <= 0 || reply->channels > 16 || reply->frames_per_burst <= 0) {
        close_stream(s);
        return AAUDIO_ERROR_INTERNAL;
    }

    uint32_t frames = MIN_RING_FRAMES;
    while (frames < (uint32_t)reply->frames_per_burst * 8 && frames < (1u << 20)) frames <<= 1;
    s->frames = frames;
    s->frame_bytes = frame_bytes(reply->format, reply->channels);
    s->map_size = sizeof(struct wn_audio_ring) + (size_t)frames * s->frame_bytes;
    s->ring_fd = ASharedMemory_create("wn-directaudio", s->map_size);
    if (s->ring_fd < 0) {
        close_stream(s);
        return AAUDIO_ERROR_NO_MEMORY;
    }
    void *map = mmap(NULL, s->map_size, PROT_READ | PROT_WRITE, MAP_SHARED, s->ring_fd, 0);
    if (map == MAP_FAILED) {
        close_stream(s);
        return AAUDIO_ERROR_NO_MEMORY;
    }
    struct wn_audio_ring *ring = map;
    memset(ring, 0, s->map_size);
    ring->frames = frames;
    ring->frame_bytes = s->frame_bytes;
    atomic_store(&s->ring, ring);
    atomic_store(&ring->error, atomic_load(&s->early_error));
    LOGI("%s open: %d Hz, %d ch, format %d, burst %d, mode %d", s->input ? "capture" : "render",
         reply->sample_rate, reply->channels, reply->format, reply->frames_per_burst, reply->performance_mode);
    return AAUDIO_OK;
}

static bool send_reply(int fd, const struct wn_audio_reply *reply, int pass_fd) {
    struct iovec iov = { .iov_base = (void *)reply, .iov_len = sizeof(*reply) };
    union {
        char buffer[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1 };
    if (pass_fd >= 0) {
        memset(&control, 0, sizeof(control));
        message.msg_control = control.buffer;
        message.msg_controllen = sizeof(control.buffer);
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &pass_fd, sizeof(int));
    }
    ssize_t sent;
    do {
        sent = sendmsg(fd, &message, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == (ssize_t)sizeof(*reply);
}

static bool read_request(int fd, struct wn_audio_request *request) {
    size_t got = 0;
    while (got < sizeof(*request)) {
        ssize_t n = recv(fd, (char *)request + got, sizeof(*request) - got, 0);
        if (n == 0 || (n < 0 && errno != EINTR)) return false;
        if (n > 0) got += (size_t)n;
    }
    return request->magic == WN_AUDIO_MAGIC;
}

static void forget_connection(int fd) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_connection_count; i++) {
        if (g_connections[i] != fd) continue;
        g_connections[i] = g_connections[--g_connection_count];
        break;
    }
    /* Closed under the lock, so nativeStop never shuts down a descriptor number that has
     * already been handed to something else. */
    close(fd);
    pthread_cond_broadcast(&g_idle);
    pthread_mutex_unlock(&g_lock);
}

static void *serve(void *arg) {
    int fd = (int)(intptr_t)arg;
    struct stream s = { .ring_fd = -1 };
    struct wn_audio_request request;

    while (read_request(fd, &request)) {
        struct wn_audio_reply reply = { .result = AAUDIO_ERROR_INVALID_STATE };
        int pass_fd = -1;
        bool closing = false;
        switch (request.op) {
        case WN_AUDIO_OPEN:
            reply.result = open_stream(&s, &request, &reply);
            if (reply.result == AAUDIO_OK) pass_fd = s.ring_fd;
            break;
        case WN_AUDIO_START:
            if (s.aaudio) reply.result = AAudioStream_requestStart(s.aaudio);
            break;
        case WN_AUDIO_STOP:
            if (s.aaudio) reply.result = AAudioStream_requestStop(s.aaudio);
            break;
        case WN_AUDIO_SET_BUFFER_SIZE:
            if (s.aaudio) {
                aaudio_result_t size = AAudioStream_setBufferSizeInFrames(s.aaudio, request.value);
                reply.result = size < 0 ? size : AAUDIO_OK;
                reply.buffer_size = size < 0 ? AAudioStream_getBufferSizeInFrames(s.aaudio) : size;
            }
            break;
        case WN_AUDIO_CLOSE:
            close_stream(&s);
            reply.result = AAUDIO_OK;
            closing = true;
            break;
        default:
            reply.result = AAUDIO_ERROR_ILLEGAL_ARGUMENT;
            break;
        }
        if (!send_reply(fd, &reply, pass_fd) || closing) break;
    }
    close_stream(&s);
    forget_connection(fd);
    return NULL;
}

static void *accept_loop(void *arg) {
    int listen_fd = (int)(intptr_t)arg;
    for (;;) {
        int fd = accept4(listen_fd, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        pthread_mutex_lock(&g_lock);
        bool taken = g_running && g_connection_count < MAX_CONNECTIONS;
        if (taken) g_connections[g_connection_count++] = fd;
        pthread_mutex_unlock(&g_lock);
        if (!taken) {
            close(fd);
            continue;
        }
        pthread_t thread;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        int failed = pthread_create(&thread, &attr, serve, (void *)(intptr_t)fd);
        pthread_attr_destroy(&attr);
        if (failed) forget_connection(fd);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_audio_directaudio_DirectAudioHost_nativeStart(
        JNIEnv *env, jclass clazz, jstring socket_path, jboolean allow_capture) {
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, socket_path, NULL);
    if (!path) return JNI_FALSE;
    bool fits = strlen(path) < sizeof(address.sun_path);
    if (fits) strcpy(address.sun_path, path);
    (*env)->ReleaseStringUTFChars(env, socket_path, path);
    if (!fits) return JNI_FALSE;

    pthread_mutex_lock(&g_lock);
    if (g_running) {
        pthread_mutex_unlock(&g_lock);
        return JNI_TRUE;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    unlink(address.sun_path);
    if (fd < 0 || bind(fd, (struct sockaddr *)&address, sizeof(address)) < 0 || listen(fd, 8) < 0 ||
        pthread_create(&g_accept_thread, NULL, accept_loop, (void *)(intptr_t)fd) != 0) {
        LOGW("could not listen on %s: %s", address.sun_path, strerror(errno));
        if (fd >= 0) close(fd);
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }
    g_listen_fd = fd;
    g_allow_capture = allow_capture;
    g_running = true;
    pthread_mutex_unlock(&g_lock);
    LOGI("listening on %s (capture %s)", address.sun_path, allow_capture ? "allowed" : "off");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_audio_directaudio_DirectAudioHost_nativeStop(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    if (!g_running) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    g_running = false;
    int listen_fd = g_listen_fd;
    g_listen_fd = -1;
    shutdown(listen_fd, SHUT_RDWR);
    pthread_mutex_unlock(&g_lock);
    pthread_join(g_accept_thread, NULL);
    close(listen_fd);

    /* Each connection's thread closes its own stream and descriptor once its read fails. */
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_connection_count; i++) shutdown(g_connections[i], SHUT_RDWR);
    while (g_connection_count > 0) pthread_cond_wait(&g_idle, &g_lock);
    pthread_mutex_unlock(&g_lock);
    LOGI("stopped");
}
