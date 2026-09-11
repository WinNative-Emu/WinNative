#include <jni.h>
#include <stdint.h>
#include <string.h>

static void fex_stats_memory_barrier(void) {
#if defined(__aarch64__)
    __asm__ volatile("dmb ish" ::: "memory");
#else
    __sync_synchronize();
#endif
}

// FEX-Emu accumulates JIT/signal time in the shm stats as CNTVCT_EL0 cycles, so
// the HUD needs the CNTFRQ_EL0 frequency to turn cycle deltas into load
// percentages.
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_runtime_display_ui_FexStats_nativeCycleCounterFrequency(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if defined(__aarch64__)
    uint64_t freq = 0;
    __asm__ volatile("mrs %0, CNTFRQ_EL0" : "=r"(freq));
    return (jlong)freq;
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_ui_FexStats_nativeMemoryBarrier(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    fex_stats_memory_barrier();
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_ui_FexStats_nativeCopyThreadStats(
    JNIEnv *env, jclass clazz, jobject buffer, jint base, jint stats_size, jlongArray dest) {
    (void)clazz;

    if (buffer == NULL || dest == NULL || base < 0 || stats_size < 0 || stats_size > 112) {
        return JNI_FALSE;
    }

    const jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    uint8_t *buffer_base = (*env)->GetDirectBufferAddress(env, buffer);
    if (buffer_base == NULL || capacity < 0 || (jlong)base + stats_size > capacity) {
        return JNI_FALSE;
    }

    if ((*env)->GetArrayLength(env, dest) < 8) {
        return JNI_FALSE;
    }

    jlong *values = (*env)->GetLongArrayElements(env, dest, NULL);
    if (values == NULL) {
        return JNI_FALSE;
    }

    // FEX publishes this structure in 16-byte atomic chunks. Keep the same
    // snapshot rule as MangoHud instead of reading counters independently.
    const size_t copy_size = ((size_t)stats_size / 16U) * 16U;
    if (copy_size != 0) {
        uint8_t snapshot[112] __attribute__((aligned(16)));
        const uint8_t *source = buffer_base + base;

        fex_stats_memory_barrier();
#if defined(__aarch64__) || defined(__x86_64__) || defined(__i386__)
        if ((((uintptr_t)source | (uintptr_t)snapshot) & 15U) != 0) {
            memcpy(snapshot, source, copy_size);
        } else {
            typedef unsigned __int128 copy_type;
            const copy_type *source128 = (const copy_type *)source;
            copy_type *snapshot128 = (copy_type *)snapshot;
            for (size_t i = 0; i < copy_size / sizeof(copy_type); ++i) {
                snapshot128[i] = source128[i];
            }
        }
#else
        memcpy(snapshot, source, copy_size);
#endif

        if (copy_size >= 16) memcpy(&values[0], snapshot + 8, sizeof(uint64_t));
        if (copy_size >= 32) {
            memcpy(&values[1], snapshot + 16, sizeof(uint64_t));
            memcpy(&values[2], snapshot + 24, sizeof(uint64_t));
        }
        if (copy_size >= 48) {
            memcpy(&values[3], snapshot + 32, sizeof(uint64_t));
            memcpy(&values[4], snapshot + 40, sizeof(uint64_t));
        }
        if (copy_size >= 64) memcpy(&values[5], snapshot + 48, sizeof(uint64_t));
        if (copy_size >= 96) {
            memcpy(&values[6], snapshot + 80, sizeof(uint64_t));
            memcpy(&values[7], snapshot + 88, sizeof(uint64_t));
        }
    }

    (*env)->ReleaseLongArrayElements(env, dest, values, 0);
    return JNI_TRUE;
}
