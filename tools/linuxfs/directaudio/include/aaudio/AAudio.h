/*
 * The part of Android's AAudio API the DirectAudio driver uses, for a glibc build. The values
 * are AAudio's own: wn_aaudio_client.c hands them to the app, which passes them to the real
 * library unchanged.
 */
#ifndef WN_AAUDIO_H
#define WN_AAUDIO_H

#include <stdint.h>

typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

typedef int32_t aaudio_result_t;
typedef int32_t aaudio_direction_t;
typedef int32_t aaudio_format_t;
typedef int32_t aaudio_sharing_mode_t;
typedef int32_t aaudio_performance_mode_t;
typedef int32_t aaudio_usage_t;
typedef int32_t aaudio_input_preset_t;
typedef int32_t aaudio_data_callback_result_t;

enum { AAUDIO_DIRECTION_OUTPUT, AAUDIO_DIRECTION_INPUT };
enum { AAUDIO_FORMAT_UNSPECIFIED, AAUDIO_FORMAT_PCM_I16, AAUDIO_FORMAT_PCM_FLOAT, AAUDIO_FORMAT_PCM_I24_PACKED, AAUDIO_FORMAT_PCM_I32 };
enum { AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_SHARING_MODE_SHARED };
enum { AAUDIO_PERFORMANCE_MODE_NONE = 10, AAUDIO_PERFORMANCE_MODE_POWER_SAVING, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY };
enum { AAUDIO_USAGE_GAME = 14 };
enum { AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION = 7 };
enum { AAUDIO_CALLBACK_RESULT_CONTINUE, AAUDIO_CALLBACK_RESULT_STOP };
enum {
    AAUDIO_OK = 0,
    AAUDIO_ERROR_DISCONNECTED = -899,
    AAUDIO_ERROR_ILLEGAL_ARGUMENT = -898,
    AAUDIO_ERROR_INTERNAL = -896,
    AAUDIO_ERROR_INVALID_STATE = -895,
    AAUDIO_ERROR_INVALID_HANDLE = -892,
    AAUDIO_ERROR_UNAVAILABLE = -889,
    AAUDIO_ERROR_NO_MEMORY = -887,
    AAUDIO_ERROR_NULL = -886,
    AAUDIO_ERROR_TIMEOUT = -885,
};

typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames);
typedef void (*AAudioStream_errorCallback)(AAudioStream *stream, void *userData, aaudio_result_t error);

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **builder);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *builder, int32_t sampleRate);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *builder, int32_t channelCount);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *builder, aaudio_format_t format);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *builder, aaudio_sharing_mode_t sharingMode);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *builder, aaudio_direction_t direction);
void AAudioStreamBuilder_setBufferCapacityInFrames(AAudioStreamBuilder *builder, int32_t numFrames);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *builder, aaudio_performance_mode_t mode);
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *builder, aaudio_usage_t usage);
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder *builder, aaudio_input_preset_t inputPreset);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *builder, AAudioStream_dataCallback callback, void *userData);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *builder, AAudioStream_errorCallback callback, void *userData);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *builder, AAudioStream **stream);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *builder);
aaudio_result_t AAudioStream_close(AAudioStream *stream);
aaudio_result_t AAudioStream_requestStart(AAudioStream *stream);
aaudio_result_t AAudioStream_requestStop(AAudioStream *stream);
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *stream, int32_t numFrames);
int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *stream);
int32_t AAudioStream_getFramesPerBurst(AAudioStream *stream);
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *stream);
int32_t AAudioStream_getXRunCount(AAudioStream *stream);
int32_t AAudioStream_getSampleRate(AAudioStream *stream);
int32_t AAudioStream_getChannelCount(AAudioStream *stream);
aaudio_format_t AAudioStream_getFormat(AAudioStream *stream);
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *stream);
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *stream);

#endif
