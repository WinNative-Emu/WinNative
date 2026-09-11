#include <jni.h>
#include <android/log.h>
#include <mutex>

#define SDL_MAIN_HANDLED
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#define TAG "SteamCtrlBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr int kMaxPads = 4;
constexpr Uint16 kValveVendorId = 0x28DE;

constexpr int I_ID = 0;
constexpr int I_BUTTONS = 1;
constexpr int I_STRIDE = 2;

constexpr int F_LX = 0, F_LY = 1, F_RX = 2, F_RY = 3;
constexpr int F_LT = 4, F_RT = 5;
constexpr int F_RPAD_DOWN = 6, F_RPAD_X = 7, F_RPAD_Y = 8;
constexpr int F_LPAD_DOWN = 9, F_LPAD_X = 10, F_LPAD_Y = 11;
constexpr int F_STRIDE = 12;

enum : int {
    B_A = 0, B_B, B_X, B_Y, B_LB, B_RB, B_BACK, B_START, B_LSTICK, B_RSTICK, B_GUIDE,
    B_DPAD_UP, B_DPAD_DOWN, B_DPAD_LEFT, B_DPAD_RIGHT,
    B_QAM, B_R4, B_L4, B_R5, B_L5, B_RPAD_CLICK, B_LPAD_CLICK,
};

struct ButtonMap { SDL_GamepadButton sdl; int bit; };
constexpr ButtonMap kButtons[] = {
    { SDL_GAMEPAD_BUTTON_SOUTH, B_A },
    { SDL_GAMEPAD_BUTTON_EAST, B_B },
    { SDL_GAMEPAD_BUTTON_WEST, B_X },
    { SDL_GAMEPAD_BUTTON_NORTH, B_Y },
    { SDL_GAMEPAD_BUTTON_LEFT_SHOULDER, B_LB },
    { SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER, B_RB },
    { SDL_GAMEPAD_BUTTON_BACK, B_BACK },
    { SDL_GAMEPAD_BUTTON_START, B_START },
    { SDL_GAMEPAD_BUTTON_LEFT_STICK, B_LSTICK },
    { SDL_GAMEPAD_BUTTON_RIGHT_STICK, B_RSTICK },
    { SDL_GAMEPAD_BUTTON_GUIDE, B_GUIDE },
    { SDL_GAMEPAD_BUTTON_DPAD_UP, B_DPAD_UP },
    { SDL_GAMEPAD_BUTTON_DPAD_DOWN, B_DPAD_DOWN },
    { SDL_GAMEPAD_BUTTON_DPAD_LEFT, B_DPAD_LEFT },
    { SDL_GAMEPAD_BUTTON_DPAD_RIGHT, B_DPAD_RIGHT },
    { SDL_GAMEPAD_BUTTON_MISC1, B_QAM },
    { SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1, B_R4 },
    { SDL_GAMEPAD_BUTTON_LEFT_PADDLE1, B_L4 },
    { SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2, B_R5 },
    { SDL_GAMEPAD_BUTTON_LEFT_PADDLE2, B_L5 },
    { SDL_GAMEPAD_BUTTON_MISC2, B_RPAD_CLICK },
    { SDL_GAMEPAD_BUTTON_TOUCHPAD, B_LPAD_CLICK },
};

struct Pad {
    SDL_JoystickID id;
    SDL_Gamepad *gamepad;
};

std::mutex g_lock;
Pad g_pads[kMaxPads];
int g_numPads = 0;
bool g_initialized = false;

bool isValveHidapiPad(SDL_JoystickID id) {
    if (SDL_GetGamepadVendorForID(id) != kValveVendorId) return false;
    return SDL_GetGamepadGUIDForID(id).data[14] == 'h';
}
float stickAxis(SDL_Gamepad *gamepad, SDL_GamepadAxis axis) {
    float v = SDL_GetGamepadAxis(gamepad, axis) / 32767.0f;
    return v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
}
float triggerAxis(SDL_Gamepad *gamepad, SDL_GamepadAxis axis) {
    float v = SDL_GetGamepadAxis(gamepad, axis) / 32767.0f;
    return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}
void readTouchpad(SDL_Gamepad *gamepad, int touchpad, float *out) {
    bool down = false;
    float x = 0.0f, y = 0.0f, pressure = 0.0f;
    if (touchpad < SDL_GetNumGamepadTouchpads(gamepad)
            && SDL_GetGamepadTouchpadFinger(gamepad, touchpad, 0, &down, &x, &y, &pressure)) {
        out[0] = down ? 1.0f : 0.0f;
        out[1] = x;
        out[2] = y;
    } else {
        out[0] = out[1] = out[2] = 0.0f;
    }
}
void syncPadsLocked() {
    int count = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&count);

    for (int i = 0; i < g_numPads;) {
        bool present = false;
        for (int j = 0; j < count; j++) {
            if (ids[j] == g_pads[i].id) { present = true; break; }
        }
        if (present && SDL_GamepadConnected(g_pads[i].gamepad)) {
            i++;
            continue;
        }
        LOGI("Steam controller %d disconnected", (int)g_pads[i].id);
        SDL_CloseGamepad(g_pads[i].gamepad);
        g_pads[i] = g_pads[--g_numPads];
    }

    for (int j = 0; j < count && g_numPads < kMaxPads; j++) {
        SDL_JoystickID id = ids[j];
        bool known = false;
        for (int i = 0; i < g_numPads; i++) {
            if (g_pads[i].id == id) { known = true; break; }
        }
        if (known || !isValveHidapiPad(id)) continue;
        SDL_Gamepad *gamepad = SDL_OpenGamepad(id);
        if (!gamepad) {
            LOGW("SDL_OpenGamepad(%d) failed: %s", (int)id, SDL_GetError());
            continue;
        }
        g_pads[g_numPads++] = { id, gamepad };
        LOGI("Steam controller %d connected: %s (pid %04x, touchpads %d, path %s)", (int)id,
             SDL_GetGamepadNameForID(id), SDL_GetGamepadProductForID(id),
             SDL_GetNumGamepadTouchpads(gamepad), SDL_GetGamepadPathForID(id));
    }

    SDL_free(ids);
}
Pad *findPadLocked(SDL_JoystickID id) {
    for (int i = 0; i < g_numPads; i++) {
        if (g_pads[i].id == id) return &g_pads[i];
    }
    return nullptr;
}
}
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_input_controls_SteamControllerBackend_nativeInit(JNIEnv *, jclass, jboolean bluetooth) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_initialized) return JNI_TRUE;

    SDL_SetMainReady();
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI, "0");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_STEAM, bluetooth ? "1" : "0");
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    SDL_SetHint(SDL_HINT_TV_REMOTE_AS_JOYSTICK, "0");

    if (!SDL_Init(SDL_INIT_GAMEPAD)) {
        LOGW("SDL_Init(GAMEPAD) failed: %s", SDL_GetError());
        return JNI_FALSE;
    }
    if (!bluetooth) SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_STEAM, "1");

    SDL_SetJoystickEventsEnabled(false);
    SDL_SetGamepadEventsEnabled(false);

    g_initialized = true;
    int v = SDL_GetVersion();
    LOGI("SDL %d.%d.%d up: HIDAPI Steam drivers only (bluetooth %s)", SDL_VERSIONNUM_MAJOR(v),
         SDL_VERSIONNUM_MINOR(v), SDL_VERSIONNUM_MICRO(v), bluetooth ? "on" : "off");
    return JNI_TRUE;
}
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_input_controls_SteamControllerBackend_nativePoll(JNIEnv *env, jclass, jintArray ints, jfloatArray floats) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_initialized) return -1;

    SDL_UpdateGamepads();
    SDL_FlushEvents(SDL_EVENT_FIRST, SDL_EVENT_LAST);
    syncPadsLocked();

    jint iv[kMaxPads * I_STRIDE] = {};
    jfloat fv[kMaxPads * F_STRIDE] = {};
    for (int p = 0; p < g_numPads; p++) {
        SDL_Gamepad *gamepad = g_pads[p].gamepad;
        jint *pi = iv + p * I_STRIDE;
        jfloat *pf = fv + p * F_STRIDE;

        int buttons = 0;
        for (const ButtonMap &m : kButtons) {
            if (SDL_GetGamepadButton(gamepad, m.sdl)) buttons |= 1 << m.bit;
        }
        pi[I_ID] = (jint)g_pads[p].id;
        pi[I_BUTTONS] = buttons;

        pf[F_LX] = stickAxis(gamepad, SDL_GAMEPAD_AXIS_LEFTX);
        pf[F_LY] = stickAxis(gamepad, SDL_GAMEPAD_AXIS_LEFTY);
        pf[F_RX] = stickAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHTX);
        pf[F_RY] = stickAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHTY);
        pf[F_LT] = triggerAxis(gamepad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER);
        pf[F_RT] = triggerAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER);
        readTouchpad(gamepad, 1, pf + F_RPAD_DOWN);
        readTouchpad(gamepad, 0, pf + F_LPAD_DOWN);
    }

    if (g_numPads > 0) {
        env->SetIntArrayRegion(ints, 0, g_numPads * I_STRIDE, iv);
        env->SetFloatArrayRegion(floats, 0, g_numPads * F_STRIDE, fv);
    }
    return g_numPads;
}
JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_input_controls_SteamControllerBackend_nativeGetName(JNIEnv *env, jclass, jint id) {
    std::lock_guard<std::mutex> guard(g_lock);
    const char *name = g_initialized ? SDL_GetGamepadNameForID((SDL_JoystickID)id) : nullptr;
    return env->NewStringUTF(name ? name : "Steam Controller");
}
JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_input_controls_SteamControllerBackend_nativeGetPath(JNIEnv *env, jclass, jint id) {
    std::lock_guard<std::mutex> guard(g_lock);
    const char *path = g_initialized ? SDL_GetGamepadPathForID((SDL_JoystickID)id) : nullptr;
    return path ? env->NewStringUTF(path) : nullptr;
}
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_input_controls_SteamControllerBackend_nativeRumble(JNIEnv *, jclass, jint id, jint low, jint high, jint durationMs) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_initialized) return;
    Pad *pad = findPadLocked((SDL_JoystickID)id);
    if (!pad) return;
    auto clamp16 = [](jint v) -> Uint16 { return (Uint16)(v < 0 ? 0 : (v > 0xFFFF ? 0xFFFF : v)); };
    SDL_RumbleGamepad(pad->gamepad, clamp16(low), clamp16(high), (Uint32)(durationMs < 0 ? 0 : durationMs));
}
JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_input_controls_SteamControllerBackend_nativeShutdown(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_initialized) return;
    for (int i = 0; i < g_numPads; i++) SDL_CloseGamepad(g_pads[i].gamepad);
    g_numPads = 0;
    SDL_Quit();
    g_initialized = false;
    LOGI("SDL shut down");
}
}