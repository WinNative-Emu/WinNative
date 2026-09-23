/*
 * What a Linux session's AAudio client (tools/linuxfs/directaudio/wn_aaudio_client.c) and the
 * app's AAudio host (wn_aaudio_host.c) say to each other. A glibc process cannot load Android's
 * libaaudio, so the DirectAudio driver built for Valve's Proton calls a client that has the app
 * open the stream for it. One unix socket connection is one stream: requests and replies go over
 * it, and the PCM goes through a ring in shared memory whose descriptor comes with the reply to
 * WN_AUDIO_OPEN, so no sample crosses the socket.
 */
#ifndef WN_AAUDIO_PROTOCOL_H
#define WN_AAUDIO_PROTOCOL_H

#include <stdatomic.h>
#include <stdint.h>

#define WN_AUDIO_MAGIC 0x31414e57u /* "WNA1" */

enum {
    WN_AUDIO_OPEN = 1,
    WN_AUDIO_START,
    WN_AUDIO_STOP,
    WN_AUDIO_SET_BUFFER_SIZE, /* value = frames; the reply's buffer_size is what was granted */
    WN_AUDIO_CLOSE,
};

/* The stream fields are AAudio's own values and only read for WN_AUDIO_OPEN; 0 leaves a field
 * at AAudio's default, as an unset builder field does. */
struct wn_audio_request {
    uint32_t magic;
    uint32_t op;
    int32_t direction;
    int32_t sample_rate;
    int32_t channels;
    int32_t format;
    int32_t performance_mode;
    int32_t sharing_mode;
    int32_t usage;
    int32_t input_preset;
    int32_t buffer_capacity;
    int32_t value;
};

/* result is an aaudio_result_t. The rest describes the stream AAudio granted and is only
 * filled in for WN_AUDIO_OPEN and WN_AUDIO_SET_BUFFER_SIZE. */
struct wn_audio_reply {
    int32_t result;
    int32_t sample_rate;
    int32_t channels;
    int32_t format;
    int32_t performance_mode;
    int32_t sharing_mode;
    int32_t frames_per_burst;
    int32_t buffer_capacity;
    int32_t buffer_size;
};

/*
 * The ring, at the start of the shared mapping. Positions count frames and run freely; the
 * distance between them is what is held. An output stream is written by the client and read by
 * the host's data callback, an input stream the other way round. The host bumps `wake` and wakes
 * its futex after every callback, which is what paces the client's thread.
 */
struct wn_audio_ring {
    _Atomic uint32_t write_pos;
    _Atomic uint32_t read_pos;
    _Atomic uint32_t wake;
    _Atomic int32_t error;           /* an aaudio_result_t once the stream has failed */
    _Atomic int32_t xruns;
    _Atomic uint32_t callback_frames; /* the most frames one host callback has asked for */
    uint32_t frames;                 /* capacity, a power of two */
    uint32_t frame_bytes;
    uint32_t reserved[8];
};

#define WN_AUDIO_RING_DATA(ring) ((uint8_t *)(ring) + sizeof(struct wn_audio_ring))

#endif
