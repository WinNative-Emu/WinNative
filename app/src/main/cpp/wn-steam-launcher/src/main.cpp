
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "clean_shutdown.h"

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <tlhelp32.h>
#include <filesystem>
#include <string>
#include <thread>
#include <vector>

#ifndef LOAD_LIBRARY_SEARCH_SYSTEM32
#define LOAD_LIBRARY_SEARCH_SYSTEM32 0x00000800
#endif
#ifndef LOAD_LIBRARY_SEARCH_DEFAULT_DIRS
#define LOAD_LIBRARY_SEARCH_DEFAULT_DIRS 0x00001000
#endif
#ifndef LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR
#define LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR 0x00000100
#endif
#ifndef LOAD_IGNORE_CODE_AUTHZ_LEVEL
#define LOAD_IGNORE_CODE_AUTHZ_LEVEL 0x00000010
#endif

#ifdef __i386__
#define WN_THISCALL __thiscall
#else
#define WN_THISCALL
#endif

#ifdef __i386__
#define WN_AGENT_ARCH "x86"
#else
#define WN_AGENT_ARCH "x86_64"
#endif

#ifdef __i386__
static const char* const kSteamClientDll = "steamclient.dll";
#else
static const char* const kSteamClientDll = "steamclient64.dll";
#endif

static const int kVtEngine_GetIClientUser   = 8;  // IClientEngine slot 8
static const int kVtUser_LogOn              = 1;  // slot  1: EResult LogOn(uint64 steamID)
static const int kVtUser_BLoggedOn          = 4;  // slot  4: bool BLoggedOn()
static const int kVtUser_GetSteamID         = 10;  // slot 10: CSteamID& GetSteamID(CSteamID& out)
static const int kVtUser_BHasCachedCreds    = 49; // slot 49: bool BHasCachedCredentials(const char*)
static const int kVtUser_SetLoginToken      = 56; // slot 56: EResult SetLoginToken(const char* token, const char* account)
static const int kVtUser_BIsSubscribedApp   = 181; // bool BIsSubscribedApp(AppId_t)
static const int kVtUser_BGameConnectTokensAvailable = 130;
static const int kVtUser_BUpdateAppOwnershipTicket = 69; // bool(AppId_t, bool bOnlyIfStale, bool bIsDepot)
static const int kVtUser_GetAppOwnershipTicketLength = 103; // uint32(AppId_t)

static const int kOwnershipWaitMsDefault = 20000;
static const int kTicketWaitMsDefault = 15000;
static const int kAppInfoWaitMsDefault   = 8000;
static const int kAppInfoWaitMsAfterMiss = 400;

static const int kVtUser_NumGamesRunning          = 131;
static const int kVtUser_GetRunningGameID         = 132;
static const int kVtUser_GetRunningGamePID        = 133;
static const int kVtUtils_SetAppIDForCurrentPipe   = 18;
static const int kVtUtils_GetAppID                = 19;
static const int kVtUser_RequestEncryptedAppTicket = 120;
static const int kVtUser_GetEncryptedAppTicket     = 121;
static const int kVtUser_BIsOtherSessionPlaying   = 220;
static const int kVtUser_BKickOtherPlayingSession = 221;

static const int kEAppUpdateErrorApplicationRunning  = 16;
static const int kEAppUpdateErrorOtherSessionPlaying = 35;
static const int kBlockedAnswerWaitMs = 120000;

static const int kVtEngine_GetIClientAppManager = 43; // IClientEngine slot 43
static const int kVtAppMgr_LaunchApp            = 2;  // IClientAppManager slot 2
static const int kVtAppMgr_ShutdownApp          = 3;
static const int kVtAppMgr_BIsAppUpToDate       = 21;
static const int kVtAppMgr_GetAppInstallState   = 4;  // int  GetAppInstallState(AppId_t)

static const int kVtEngine_GetIClientApps       = 17;  // slot 17: IClientApps*(hUser, hPipe)
static const int kVtApps_RequestAppInfoUpdate   = 7;  // slot 7:  bool(AppId_t* ids, int n)

static const int kVtEngine_GetIClientUtils       = 14;  // slot 14: IClientUtils*(HSteamPipe)
static const int kVtUtils_IsAPICallCompleted     = 22;  // slot 22: bool(apiCall, *pbFailed)
static const int kVtUtils_GetAPICallFailureReason = 23; // slot 23: int(apiCall)  ESteamAPICallFailure
static const int kVtUtils_GetAPICallResult       = 24;  // slot 24: bool(apiCall, pCb, cubCb, iCbExpected, *pbFailed)

static const int kLaunchAppResultCallbackId    = 0x13610B;
static const int kLaunchAppResultSize          = 0x20C;
static const int kLaunchResultErrorOffset      = 0x8;     // int32 EAppUpdateError

typedef void* (*CreateInterfaceFn)(const char* version, int* returnCode);
typedef int   (*Steam_CreateGlobalUser_fn)(int* pipe_out);
typedef bool  (*Steam_BLoggedOn_fn)(int pipe, int user);
typedef bool  (*Steam_BGetCallback_fn)(int pipe, void* cb);
typedef void  (*Steam_FreeLastCallback_fn)(int pipe);
typedef void  (*Breakpad_SteamSetAppID_fn)(unsigned app_id);

static FILE* g_logFile = NULL;

static void open_log(void) {
    if (g_logFile) return;
    g_logFile = fopen("C:\\wn-launcher.log", "w");
    if (g_logFile) setvbuf(g_logFile, NULL, _IONBF, 0);
}

static void log_line(const char* fmt, ...) __attribute__((format(gnu_printf, 1, 2)));

static DWORD g_logT0 = 0;

static void log_line(const char* fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    if (n < 0) n = 0;
    if (n > (int)sizeof(msg) - 1) n = (int)sizeof(msg) - 1;
    msg[n] = '\0';

    if (g_logT0 == 0) g_logT0 = GetTickCount();
    DWORD elapsed = GetTickCount() - g_logT0;

    char buf[1152];
    int m = snprintf(buf, sizeof(buf) - 2, "[%3lu.%03lus] %s",
                     (unsigned long)(elapsed / 1000),
                     (unsigned long)(elapsed % 1000), msg);
    if (m < 0) m = 0;
    if (m > (int)sizeof(buf) - 2) m = (int)sizeof(buf) - 2;
    buf[m] = '\n';
    buf[m + 1] = '\0';

    fputs(buf, stderr);
    OutputDebugStringA(buf);
    if (g_logFile) {
        fputs(buf, g_logFile);
    } else {
        FILE* lf = fopen("C:\\wn-launcher.log", "a");
        if (lf) { fputs(buf, lf); fclose(lf); }
    }
}

// Route clean_shutdown.cpp's [wn-launcher] markers through our single log handle;
// a separate fopen() there gets clobbered by our next write, dropping the markers
// the Android close path keys off.
static void clean_shutdown_log_sink(const char* line) {
    if (line) log_line("%s", line);
}

static uint64_t env_u64(const char* name) {
    const char* v = getenv(name);
    if (!v || !*v) return 0;
    return (uint64_t) _strtoui64(v, NULL, 10);
}

static int b64url_val(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
}

static void log_token_claims(const char* token) {
    if (!token || !*token) { log_line("[wn-launcher] token: (empty)"); return; }
    const char* dot1 = strchr(token, '.');
    if (!dot1) { log_line("[wn-launcher] token: not a JWT (no '.')"); return; }
    const char* dot2 = strchr(dot1 + 1, '.');
    if (!dot2) { log_line("[wn-launcher] token: not a JWT (one '.')"); return; }
    size_t seglen = (size_t)(dot2 - (dot1 + 1));
    if (seglen == 0 || seglen > 2000) {
        log_line("[wn-launcher] token: payload segment size unusable (%zu)", seglen);
        return;
    }
    char out[1536];
    size_t op = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (size_t i = 0; i < seglen && op < sizeof(out) - 1; ++i) {
        unsigned char c = (unsigned char) (dot1 + 1)[i];
        int v = b64url_val(c);
        if (v < 0) continue;
        acc = (acc << 6) | (uint32_t) v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[op++] = (char)((acc >> bits) & 0xFF);
        }
    }
    out[op] = '\0';
    log_line("[wn-launcher] token JWT payload: %s", out);
}

static void seed_active_process_registry(uint32_t our_pid, uint32_t steam_account_id) {
    HKEY h = NULL;
    LONG rc = RegCreateKeyExA(HKEY_CURRENT_USER,
            "Software\\Valve\\Steam\\ActiveProcess",
            0, NULL, REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &h, NULL);
    if (rc != ERROR_SUCCESS) {
        log_line("[wn-launcher] RegCreateKeyEx(ActiveProcess) failed rc=%ld", rc);
        return;
    }
    const char* clientDll   = "C:\\Program Files (x86)\\Steam\\steamclient.dll";
    const char* clientDll64 = "C:\\Program Files (x86)\\Steam\\steamclient64.dll";
    const char* installPath = "C:\\Program Files (x86)\\Steam";
    DWORD universe = 1;  // k_EUniversePublic
    DWORD pid_dw = (DWORD) our_pid;
    DWORD active_user = (DWORD) steam_account_id;
    RegSetValueExA(h, "SteamClientDll",   0, REG_SZ, (const BYTE*) clientDll,   (DWORD) strlen(clientDll)   + 1);
    RegSetValueExA(h, "SteamClientDll64", 0, REG_SZ, (const BYTE*) clientDll64, (DWORD) strlen(clientDll64) + 1);
    RegSetValueExA(h, "Universe",         0, REG_DWORD, (const BYTE*) &universe, sizeof(universe));
    RegSetValueExA(h, "pid",              0, REG_DWORD, (const BYTE*) &pid_dw,   sizeof(pid_dw));
    RegSetValueExA(h, "ActiveUser",       0, REG_DWORD, (const BYTE*) &active_user, sizeof(active_user));
    RegCloseKey(h);

    const char* appIdStr = getenv("WN_STEAM_APPID");
    if (appIdStr && *appIdStr) {
        char keyPath[256];
        snprintf(keyPath, sizeof(keyPath),
                 "Software\\Valve\\Steam\\Apps\\%s", appIdStr);
        HKEY h2 = NULL;
        if (RegCreateKeyExA(HKEY_CURRENT_USER, keyPath, 0, NULL,
                            REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &h2, NULL) == ERROR_SUCCESS) {
            DWORD one = 1;
            DWORD zero = 0;
            RegSetValueExA(h2, "Installed", 0, REG_DWORD, (const BYTE*) &one,  sizeof(one));
            RegSetValueExA(h2, "Running",   0, REG_DWORD, (const BYTE*) &one,  sizeof(one));
            RegSetValueExA(h2, "Updating",  0, REG_DWORD, (const BYTE*) &zero, sizeof(zero));
            RegCloseKey(h2);
        }
    }
    {
        const char* steamFwd  = "c:/program files (x86)/steam";
        const char* steamExe  = "c:/program files (x86)/steam/steam.exe";
        const char* steamBack = "C:\\Program Files (x86)\\Steam";
        HKEY hk = NULL;
        if (RegCreateKeyExA(HKEY_CURRENT_USER, "Software\\Valve\\Steam", 0, NULL,
                REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &hk, NULL) == ERROR_SUCCESS) {
            RegSetValueExA(hk, "SteamPath", 0, REG_SZ,
                           (const BYTE*) steamFwd, (DWORD) strlen(steamFwd) + 1);
            RegSetValueExA(hk, "SteamExe",  0, REG_SZ,
                           (const BYTE*) steamExe, (DWORD) strlen(steamExe) + 1);
            RegCloseKey(hk);
        }
        HKEY hm = NULL;
        if (RegCreateKeyExA(HKEY_LOCAL_MACHINE, "Software\\Valve\\Steam", 0, NULL,
                REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &hm, NULL) == ERROR_SUCCESS) {
            RegSetValueExA(hm, "InstallPath", 0, REG_SZ,
                           (const BYTE*) steamBack, (DWORD) strlen(steamBack) + 1);
            RegSetValueExA(hm, "SteamPath",   0, REG_SZ,
                           (const BYTE*) steamFwd,  (DWORD) strlen(steamFwd) + 1);
            RegCloseKey(hm);
        }
        SetEnvironmentVariableA("SteamPath", steamBack);
    }

    log_line("[wn-launcher] HKCU ActiveProcess + Steam install registry seeded "
             "(pid=%u, activeUser=%u, SteamPath set)",
             our_pid, steam_account_id);
}

static void stage_steam_config(void) {
    const char* cfgDir = "C:\\Program Files (x86)\\Steam\\config";
    CreateDirectoryA(cfgDir, NULL);
    const char* files[2] = {
        "C:\\Program Files (x86)\\Steam\\config\\config.vdf",
        "C:\\Program Files (x86)\\Steam\\config\\local.vdf",
    };
    for (int i = 0; i < 2; ++i) {
        DWORD attr = GetFileAttributesA(files[i]);
        if (attr == INVALID_FILE_ATTRIBUTES) {
            HANDLE h = CreateFileA(files[i], GENERIC_WRITE, 0, NULL,
                                   CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
            if (h != INVALID_HANDLE_VALUE) {
                CloseHandle(h);
                log_line("[wn-launcher] staged empty %s", files[i]);
            }
        }
    }
}

// Escape a free-text value for a VDF/ACF quoted field: double backslashes, then
// escape quotes and newlines. Mirrors the Kotlin escapeString() so the C++ and
// Kotlin manifest paths produce identical, well-formed output.
static std::string vdf_escape(const char* s) {
    std::string out;
    if (!s) return out;
    for (const char* p = s; *p; ++p) {
        switch (*p) {
            case '\\': out += "\\\\"; break;
            case '"':  out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            default:   out += *p; break;
        }
    }
    return out;
}

static bool is_windows_path(const char* s) {
    if (!s || !*s) return false;
    if (s[0] == '\\' && s[1] == '\\') return true;
    bool drive = (s[0] >= 'A' && s[0] <= 'Z') || (s[0] >= 'a' && s[0] <= 'z');
    return drive && s[1] == ':' && (s[2] == '\\' || s[2] == '/');
}

static void stage_app_manifest(uint32_t appId, const char* gameExe) {
    if (appId == 0 || !gameExe) return;
    const char* marker = "\\steamapps\\common\\";
    size_t mlen = strlen(marker);
    const char* hit = NULL;
    for (const char* s = gameExe; *s; ++s) {
        if (_strnicmp(s, marker, mlen) == 0) { hit = s; break; }
    }
    if (!hit) {
        log_line("[wn-launcher] app manifest: game not under steamapps\\common "
                 "— skipping (LaunchApp may report not-installed)");
        return;
    }
    const char* dirStart = hit + mlen;
    const char* dirEnd = strchr(dirStart, '\\');
    if (!dirEnd || dirEnd == dirStart) return;
    char installdir[260];
    size_t n = (size_t)(dirEnd - dirStart);
    if (n >= sizeof(installdir)) return;
    memcpy(installdir, dirStart, n);
    installdir[n] = '\0';

    CreateDirectoryA("C:\\Program Files (x86)\\Steam\\steamapps", NULL);
    char acf[MAX_PATH];
    snprintf(acf, sizeof(acf),
             "C:\\Program Files (x86)\\Steam\\steamapps\\appmanifest_%u.acf",
             appId);
    const char* owner = getenv("WN_STEAM_STEAMID");
    const char* depotsEnv = getenv("WN_STEAM_DEPOTS");
    const char* sharedEnv = getenv("WN_STEAM_SHARED_DEPOTS");
    const char* appName = getenv("WN_STEAM_APP_NAME");
    const char* installScriptsEnv = getenv("WN_STEAM_INSTALL_SCRIPTS");
    const char* language = getenv("WN_STEAM_LANGUAGE");
    const char* buildIdStr = getenv("WN_STEAM_BUILD_ID");
    const char* sizeOnDiskStr = getenv("WN_STEAM_SIZE_ON_DISK");
    const char* bytesToDownloadStr = getenv("WN_STEAM_BYTES_TO_DOWNLOAD");
    const char* bytesToStageStr = getenv("WN_STEAM_BYTES_TO_STAGE");
    if (!appName || !*appName) appName = installdir;
    if (!language || !*language) language = "english";
    unsigned long long buildId = (buildIdStr && *buildIdStr) ? strtoull(buildIdStr, NULL, 10) : 0ULL;
    unsigned long long sizeOnDisk = (sizeOnDiskStr && *sizeOnDiskStr) ? strtoull(sizeOnDiskStr, NULL, 10) : 0ULL;
    unsigned long long bytesToDownload = (bytesToDownloadStr && *bytesToDownloadStr) ? strtoull(bytesToDownloadStr, NULL, 10) : 0ULL;
    unsigned long long bytesToStage = (bytesToStageStr && *bytesToStageStr) ? strtoull(bytesToStageStr, NULL, 10) : 0ULL;
    FILE* f = fopen(acf, "w");
    if (!f) {
        log_line("[wn-launcher] app manifest: fopen(%s) failed", acf);
        return;
    }
    std::string nameEsc = vdf_escape(appName);
    std::string installdirEsc = vdf_escape(installdir);
    std::string languageEsc = vdf_escape(language);
    const bool minimalAcf =
        GetFileAttributesA("C:\\wn-minimal-acf.on") != INVALID_FILE_ATTRIBUTES;
    if (minimalAcf) {
        log_line("[wn-launcher] app manifest: minimal shape (SteamLite parity) — "
                 "no buildid, empty InstalledDepots");
        fprintf(f,
                "\"AppState\"\n"
                "{\n"
                "\t\"appid\"\t\t\"%u\"\n"
                "\t\"universe\"\t\t\"1\"\n"
                "\t\"LauncherPath\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\"\n"
                "\t\"name\"\t\t\"%s\"\n"
                "\t\"StateFlags\"\t\t\"4\"\n"
                "\t\"installdir\"\t\t\"%s\"\n"
                "\t\"LastOwner\"\t\t\"%s\"\n"
                "\t\"InstalledDepots\"\n\t{\n\t}\n"
                "\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"%s\"\n\t}\n"
                "\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"%s\"\n\t}\n"
                "}\n",
                appId, nameEsc.c_str(), installdirEsc.c_str(),
                (owner && *owner) ? owner : "0",
                languageEsc.c_str(), languageEsc.c_str());
        fclose(f);
        log_line("[wn-launcher] app manifest staged (minimal): %s (installdir=\"%s\")",
                 acf, installdirEsc.c_str());
        return;
    }
    fprintf(f,
            "\"AppState\"\n"
            "{\n"
            "\t\"appid\"\t\t\"%u\"\n"
            "\t\"universe\"\t\t\"1\"\n"
            "\t\"LauncherPath\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\"\n"
            "\t\"name\"\t\t\"%s\"\n"
            "\t\"StateFlags\"\t\t\"4\"\n"
            "\t\"installdir\"\t\t\"%s\"\n"
            "\t\"LastUpdated\"\t\t\"%llu\"\n"
            "\t\"LastPlayed\"\t\t\"0\"\n"
            "\t\"SizeOnDisk\"\t\t\"%llu\"\n"
            "\t\"StagingSize\"\t\t\"0\"\n"
            "\t\"buildid\"\t\t\"%llu\"\n"
            "\t\"LastOwner\"\t\t\"%s\"\n"
            "\t\"DownloadType\"\t\t\"1\"\n"
            "\t\"UpdateResult\"\t\t\"0\"\n"
            "\t\"BytesToDownload\"\t\t\"%llu\"\n"
            "\t\"BytesDownloaded\"\t\t\"%llu\"\n"
            "\t\"BytesToStage\"\t\t\"%llu\"\n"
            "\t\"BytesStaged\"\t\t\"%llu\"\n"
            "\t\"TargetBuildID\"\t\t\"%llu\"\n"
            "\t\"AutoUpdateBehavior\"\t\t\"0\"\n"
            "\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"\n"
            "\t\"ScheduledAutoUpdate\"\t\t\"0\"\n",
            appId, nameEsc.c_str(), installdirEsc.c_str(),
            (unsigned long long)time(NULL),
            sizeOnDisk, buildId,
            (owner && *owner) ? owner : "0",
            bytesToDownload, bytesToDownload,
            bytesToStage, bytesToStage, buildId);
    // Write InstalledDepots with depot data from WN_STEAM_DEPOTS env var.
    // Format: depotId:manifestGid:size[:dlcAppId],...
    if (depotsEnv && *depotsEnv) {
        fprintf(f, "\t\"InstalledDepots\"\n\t{\n");
        std::vector<char> buf(strlen(depotsEnv) + 1);
        memcpy(buf.data(), depotsEnv, buf.size());
        char* token = strtok(buf.data(), ",");
        while (token) {
            // Parse depotId:manifestGid:size[:dlcAppId]
            char* colon1 = strchr(token, ':');
            if (!colon1) { token = strtok(NULL, ","); continue; }
            *colon1 = '\0';
            const char* depotIdStr = token;
            char* manifestStart = colon1 + 1;
            char* colon2 = strchr(manifestStart, ':');
            if (!colon2) { token = strtok(NULL, ","); continue; }
            *colon2 = '\0';
            const char* manifestStr = manifestStart;
            char* sizeStart = colon2 + 1;
            char* colon3 = strchr(sizeStart, ':');
            const char* sizeStr, *dlcAppIdStr;
            if (colon3) {
                *colon3 = '\0';
                sizeStr = sizeStart;
                dlcAppIdStr = colon3 + 1;
            } else {
                sizeStr = sizeStart;
                dlcAppIdStr = NULL;
            }
            fprintf(f, "\t\t\"%s\"\n\t\t{\n"
                       "\t\t\t\"manifest\"\t\t\"%s\"\n"
                       "\t\t\t\"size\"\t\t\"%s\"\n",
                    depotIdStr, manifestStr, sizeStr);
            if (dlcAppIdStr && *dlcAppIdStr) {
                fprintf(f, "\t\t\t\"dlcappid\"\t\t\"%s\"\n", dlcAppIdStr);
            }
            fprintf(f, "\t\t}\n");
            token = strtok(NULL, ",");
        }
        fprintf(f, "\t}\n");
    } else {
        fprintf(f, "\t\"InstalledDepots\"\n\t{\n\t}\n");
    }
    // Write InstallScripts from WN_STEAM_INSTALL_SCRIPTS env var.
    // Format: depotId:scriptFilename,...
    if (installScriptsEnv && *installScriptsEnv) {
        fprintf(f, "\t\"InstallScripts\"\n\t{\n");
        std::vector<char> isbuf(strlen(installScriptsEnv) + 1);
        memcpy(isbuf.data(), installScriptsEnv, isbuf.size());
        char* istoken = strtok(isbuf.data(), ",");
        while (istoken) {
            char* iscolon = strchr(istoken, ':');
            if (!iscolon) { istoken = strtok(NULL, ","); continue; }
            *iscolon = '\0';
            std::string scriptEsc = vdf_escape(iscolon + 1);
            fprintf(f, "\t\t\"%s\"\t\t\"%s\"\n", istoken, scriptEsc.c_str());
            istoken = strtok(NULL, ",");
        }
        fprintf(f, "\t}\n");
    }
    // Write SharedDepots from WN_STEAM_SHARED_DEPOTS env var.
    // Format: sourceDepotId:targetAppId,...
    if (sharedEnv && *sharedEnv) {
        fprintf(f, "\t\"SharedDepots\"\n\t{\n");
        std::vector<char> sbuf(strlen(sharedEnv) + 1);
        memcpy(sbuf.data(), sharedEnv, sbuf.size());
        char* stoken = strtok(sbuf.data(), ",");
        while (stoken) {
            char* scolon = strchr(stoken, ':');
            if (!scolon) { stoken = strtok(NULL, ","); continue; }
            *scolon = '\0';
            fprintf(f, "\t\t\"%s\"\t\t\"%s\"\n", stoken, scolon + 1);
            stoken = strtok(NULL, ",");
        }
        fprintf(f, "\t}\n");
    }
    fprintf(f,
            "\t\"UserConfig\"\n"
            "\t{\n"
            "\t\t\"language\"\t\t\"%s\"\n"
            "\t}\n"
            "\t\"MountedConfig\"\n"
            "\t{\n"
            "\t\t\"language\"\t\t\"%s\"\n"
            "\t}\n"
            "}\n",
            languageEsc.c_str(), languageEsc.c_str());
    fclose(f);
    log_line("[wn-launcher] app manifest staged: %s (installdir=\"%s\", "
             "depots=%s shared=%s scripts=%s)",
             acf, installdir,
             depotsEnv && *depotsEnv ? depotsEnv : "(none)",
             sharedEnv && *sharedEnv ? sharedEnv : "(none)",
             installScriptsEnv && *installScriptsEnv ? installScriptsEnv : "(none)");
}

static bool is_exec_ptr(void* p);

static void log_iface_vtable(const char* name, void* iface) {
    if (!iface) { log_line("[wn-launcher] ifacevt: %s = NULL", name); return; }
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery(iface, &mbi, sizeof(mbi)) == 0 || mbi.State != MEM_COMMIT) {
        log_line("[wn-launcher] ifacevt: %s = %p (unreadable)", name, iface); return;
    }
    void** vt = *(void***) iface;
    char mod[MAX_PATH] = {0};
    HMODULE m = NULL;
    if (vt && GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                                 | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                 (LPCSTR) vt, &m)) {
        GetModuleFileNameA(m, mod, sizeof(mod));
    }
    log_line("[wn-launcher] ifacevt: %s iface=%p vtable=%p base=%p vt_rva=0x%tx mod=%s",
             name, iface, (void*) vt, (void*) m,
             (ptrdiff_t) ((char*) vt - (char*) m), mod[0] ? mod : "?");
}

static bool interface_looks_valid(void* iface, int probeSlot) {
    if (!iface) return false;
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery(iface, &mbi, sizeof(mbi)) == 0 || mbi.State != MEM_COMMIT) return false;
    void** vt = *(void***) iface;
    if (!vt) return false;
    if (VirtualQuery(vt, &mbi, sizeof(mbi)) == 0 || mbi.State != MEM_COMMIT) return false;
    return is_exec_ptr(vt[0]) && is_exec_ptr(vt[probeSlot]);
}

static void dump_engine_vtable(void* engine, int count) {
    if (!engine) return;
    void** vt = *(void***) engine;
    MEMORY_BASIC_INFORMATION mbi;
    if (!vt || VirtualQuery(vt, &mbi, sizeof(mbi)) == 0 || mbi.State != MEM_COMMIT) {
        log_line("[wn-launcher] enginevt: vtable pointer %p unreadable", (void*) vt);
        return;
    }
    log_line("[wn-launcher] enginevt: engine=%p vtable=%p ptrsize=%d",
             engine, (void*) vt, (int) sizeof(void*));
    for (int i = 0; i < count; ++i) {
        void* fn = vt[i];
        if (!is_exec_ptr(fn)) continue;
        char mod[MAX_PATH] = {0};
        HMODULE m = NULL;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                               | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               (LPCSTR) fn, &m)) {
            GetModuleFileNameA(m, mod, sizeof(mod));
        }
        const char* base = strrchr(mod, '\\');
        base = base ? base + 1 : mod;
        log_line("[wn-launcher] enginevt: [%d] off=0x%X fn=%p mod=%s",
                 i, (unsigned) (i * sizeof(void*)), fn, base[0] ? base : "?");
    }
}

static bool file_contains_valve_company(const std::filesystem::path& p) {
    static const char kNeedle[] =
        "V\0a\0l\0v\0e\0 \0C\0o\0r\0p\0o\0r\0a\0t\0i\0o\0n\0";
    const size_t needleLen = sizeof(kNeedle) - 1;
    FILE* f = fopen(p.string().c_str(), "rb");
    if (!f) return false;
    std::vector<char> buf(1 << 20);
    size_t carry = 0;
    bool found = false;
    while (!found) {
        size_t n = fread(buf.data() + carry, 1, buf.size() - carry, f);
        if (n == 0) break;
        size_t total = carry + n;
        for (size_t i = 0; i + needleLen <= total; ++i) {
            if (memcmp(buf.data() + i, kNeedle, needleLen) == 0) { found = true; break; }
        }
        carry = (total >= needleLen - 1) ? needleLen - 1 : total;
        memmove(buf.data(), buf.data() + (total - carry), carry);
    }
    fclose(f);
    return found;
}

static void verify_game_steam_api(const char* gameRootDir) {
    if (!gameRootDir || !gameRootDir[0]) return;

    std::vector<std::filesystem::path> apis;
    try {
        for (auto it = std::filesystem::recursive_directory_iterator(
                     gameRootDir, std::filesystem::directory_options::skip_permission_denied);
             it != std::filesystem::recursive_directory_iterator(); ++it) {
            if (!it->is_regular_file()) continue;
            std::string lower = it->path().filename().string();
            for (char& c : lower) c = (char) tolower((unsigned char) c);
            if (lower == "steam_api.dll" || lower == "steam_api64.dll") {
                apis.push_back(it->path());
            }
        }
    } catch (const std::exception& e) {
        log_line("[wn-launcher] steam_api: scan failed (%s)", e.what());
        return;
    }

    int emulated = 0, restored = 0;
    for (const auto& api : apis) {
        std::string name = api.filename().string();
        std::string lower = name;
        for (char& c : lower) c = (char) tolower((unsigned char) c);

        uintmax_t size = 0;
        try { size = std::filesystem::file_size(api); } catch (...) {}
        bool genuine = file_contains_valve_company(api);
        std::filesystem::path backup = api;
        backup += ".orig";
        bool haveBackup = std::filesystem::is_regular_file(backup);
        uintmax_t backupSize = 0;
        if (haveBackup) {
            try { backupSize = std::filesystem::file_size(backup); } catch (...) {}
        }
        log_line("[wn-launcher] steam_api: %s size=%llu valve-signed=%d backup=%s%llu",
                 api.string().c_str(), (unsigned long long) size, genuine ? 1 : 0,
                 haveBackup ? "yes:" : "none:", (unsigned long long) backupSize);

        if (genuine) continue;
        emulated++;
        if (!haveBackup || backupSize == 0) {
            log_line("[wn-launcher] steam_api: WARNING — \"%s\" is NOT the game's genuine "
                     "Valve DLL and there is no .orig backup to restore. The game's "
                     "SteamAPI_Init cannot reach this client, so its online/version "
                     "handshake will fail. Verify the game files.", name.c_str());
            continue;
        }
        if (!CopyFileA(backup.string().c_str(), api.string().c_str(), FALSE)) {
            log_line("[wn-launcher] steam_api: restore of \"%s\" from .orig FAILED (GLE=%lu)",
                     name.c_str(), GetLastError());
            continue;
        }
        restored++;
        log_line("[wn-launcher] steam_api: restored genuine \"%s\" from .orig backup",
                 name.c_str());

        std::filesystem::path stub = api.parent_path() /
            (lower == "steam_api64.dll" ? "steamclient64.dll" : "steamclient.dll");
        std::error_code sec;
        if (std::filesystem::is_regular_file(stub, sec)) {
            uintmax_t stubSize = std::filesystem::file_size(stub, sec);
            if (!sec && stubSize < 200000 && !file_contains_valve_company(stub)) {
                std::filesystem::remove(stub, sec);
                log_line("[wn-launcher] steam_api: removed emulator steamclient stub %s",
                         stub.string().c_str());
            }
        }
    }
    log_line("[wn-launcher] steam_api: %zu checked, %d non-genuine, %d restored",
             apis.size(), emulated, restored);
}

static int env_int_signed(const char* name, int fallback) {
    const char* v = getenv(name);
    if (!v || !*v) return fallback;
    char* end = NULL;
    long n = strtol(v, &end, 0);
    if (end == v) return fallback;
    return (int) n;
}

static int env_int(const char* name, int fallback) {
    const char* v = getenv(name);
    if (!v || !*v) return fallback;
    long n = strtol(v, NULL, 0);
    return (n > 0) ? (int) n : fallback;
}

static const char* const kEAppUpdateErrorNames[] = {
    "No Error", "Unspecified Error", "Paused", "Canceled", "Suspended",
    "No subscription", "No connection", "Connection timeout", "Missing decryption key",
    "Missing configuration", "Disk read failure", "Disk write failure",
    "Not enough disk space", "Corrupt game files", "Waiting for disk",
    "Invalid install path", "Application running", "Dependency failure", "Not installed",
    "Update required", "Still busy", "No connection to content servers",
    "Invalid application configuration", "Invalid content configuration",
    "Manifest unavailable", "Not released", "Region restricted", "Corrupt depot cache",
    "Missing executable", "Invalid platform", "Invalid file system",
    "Corrupt update files", "Download disabled", "Shared library locked",
    "Purchase pending", "Other session playing", "Corrupt download", "Corrupt disk",
    "Missing file permissions", "File locked", "Content unavailable",
    "Requires 64bit operating system", "Missing update files", "Not enough disk quota",
    "Site License locked", "Parental control blocked", "Create process failed",
    "Steam client out of date", "Allowed playtime exceeded", "Corrupt file signature",
    "Missing game files", "Compat tool failed", "Install path removed",
    "Invalid backup path", "Invalid Passcode", "Self updating",
    "Allowed playtime exceeded", "Blocked arguments",
};

static const char* eapp_update_error_name(int e) {
    const int count = (int) (sizeof(kEAppUpdateErrorNames) / sizeof(kEAppUpdateErrorNames[0]));
    if (e < 0 || e >= count) return "unknown";
    return kEAppUpdateErrorNames[e];
}

static const char* const kBlockedRequestPath = "C:\\wn-steam-blocked.txt";
static const char* const kBlockedAnswerPath  = "C:\\wn-steam-blocked-answer.txt";

static void query_running_game_quiet(void* user, int* countOut) {
    if (countOut) *countOut = 0;
    if (!user) return;
    void** vt = *(void***) user;
    void* numP = vt[kVtUser_NumGamesRunning];
    if (!is_exec_ptr(numP)) return;
    typedef int (WN_THISCALL *NumGamesRunningFn2)(void* self);
    int n = ((NumGamesRunningFn2) numP)(user);
    if (countOut) *countOut = n;
}

static uint32_t query_running_game(void* user, uint32_t* pidOut, int* countOut) {
    if (pidOut) *pidOut = 0;
    if (countOut) *countOut = 0;
    if (!user) return 0;
    void** vt = *(void***) user;
    void* numP = vt[kVtUser_NumGamesRunning];
    void* idP  = vt[kVtUser_GetRunningGameID];
    if (!is_exec_ptr(numP) || !is_exec_ptr(idP)) {
        log_line("[wn-launcher] blocked-check: running-game slots not executable");
        return 0;
    }
    typedef int (WN_THISCALL *NumGamesRunningFn)(void* self);
    typedef uint64_t* (WN_THISCALL *GetRunningGameIDFn)(void* self, uint64_t* out, int index);
    int n = ((NumGamesRunningFn) numP)(user);
    if (countOut) *countOut = n;
    if (n <= 0) return 0;
    uint64_t gameId = 0;
    ((GetRunningGameIDFn) idP)(user, &gameId, 0);
    uint32_t appId = (uint32_t) (gameId & 0xFFFFFFull);
    uint32_t pid = 0;
    void* pidP = vt[kVtUser_GetRunningGamePID];
    if (is_exec_ptr(pidP)) {
        typedef int (WN_THISCALL *GetRunningGamePIDFn)(void* self, int index);
        int rc = ((GetRunningGamePIDFn) pidP)(user, 0);
        if (rc > 0) pid = (uint32_t) rc;
    }
    if (pidOut) *pidOut = pid;
    log_line("[wn-launcher] blocked-check: NumGamesRunning=%d gameID=0x%llx appId=%u pid=%u",
             n, (unsigned long long) gameId, appId, pid);
    return appId;
}

static bool query_other_session(void* user, uint32_t* appIdOut) {
    if (appIdOut) *appIdOut = 0;
    if (!user) return false;
    void** vt = *(void***) user;
    void* p = vt[kVtUser_BIsOtherSessionPlaying];
    if (!is_exec_ptr(p)) return false;
    typedef bool (WN_THISCALL *BIsOtherSessionPlayingFn)(void* self, uint32_t* out);
    uint32_t appId = 0;
    bool blocked = ((BIsOtherSessionPlayingFn) p)(user, &appId);
    if (appIdOut) *appIdOut = appId;
    if (blocked) {
        log_line("[wn-launcher] blocked-check: BIsOtherSessionPlaying=1 appId=%u", appId);
    }
    return blocked;
}

static void write_blocked_request(const char* kind, uint32_t blockingAppId,
                                  uint32_t targetAppId, uint32_t pid) {
    DeleteFileA(kBlockedAnswerPath);
    FILE* f = fopen(kBlockedRequestPath, "wb");
    if (f) {
        fprintf(f, "kind=%s\nblocking=%u\ntarget=%u\npid=%u\n",
                kind, blockingAppId, targetAppId, pid);
        fclose(f);
    }
    log_line("[wn-launcher] BLOCKED: kind=%s blocking=%u target=%u pid=%u",
             kind, blockingAppId, targetAppId, pid);
}

static bool wait_blocked_answer(int timeoutMs, Steam_BGetCallback_fn bGetCallback,
                                Steam_FreeLastCallback_fn freeLastCallback, int pipe) {
    const int stepMs = 100;
    for (int waited = 0; waited < timeoutMs; waited += stepMs) {
        if (bGetCallback && freeLastCallback) {
            char cb[64];
            while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
        }
        FILE* f = fopen(kBlockedAnswerPath, "rb");
        if (f) {
            char buf[32] = {0};
            size_t got = fread(buf, 1, sizeof(buf) - 1, f);
            fclose(f);
            buf[got] = '\0';
            for (char* p = buf; *p; ++p) {
                if (*p == '\r' || *p == '\n') { *p = '\0'; break; }
            }
            DeleteFileA(kBlockedAnswerPath);
            DeleteFileA(kBlockedRequestPath);
            log_line("[wn-launcher] BLOCKED: answer=\"%s\"", buf);
            return strcmp(buf, "stop") == 0;
        }
        Sleep(stepMs);
    }
    DeleteFileA(kBlockedRequestPath);
    log_line("[wn-launcher] BLOCKED: no answer within %dms", timeoutMs);
    return false;
}

static bool clear_running_game(void* engine, void* user, void* appMgr, uint32_t blockingAppId,
                               Steam_BGetCallback_fn bGetCallback,
                               Steam_FreeLastCallback_fn freeLastCallback, int pipe) {
    (void) engine;
    if (appMgr && blockingAppId != 0) {
        void** amVt = *(void***) appMgr;
        void* shutdownP = amVt[kVtAppMgr_ShutdownApp];
        if (is_exec_ptr(shutdownP)) {
            typedef void (WN_THISCALL *ShutdownAppFn)(void* self, uint32_t appId, bool bForce);
            ((ShutdownAppFn) shutdownP)(appMgr, blockingAppId, true);
            log_line("[wn-launcher] BLOCKED: IClientAppManager.ShutdownApp(%u, force) dispatched",
                     blockingAppId);
        } else {
            log_line("[wn-launcher] BLOCKED: ShutdownApp slot not executable");
        }
    }
    for (int waited = 0; waited < 15000; waited += 200) {
        if (bGetCallback && freeLastCallback) {
            char cb[64];
            while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
        }
        int count = 0;
        query_running_game(user, NULL, &count);
        if (count <= 0) {
            log_line("[wn-launcher] BLOCKED: running-game registration cleared after %dms", waited);
            return true;
        }
        Sleep(200);
    }
    log_line("[wn-launcher] BLOCKED: running-game registration still set after 15000ms");
    return false;
}

static bool clear_other_session(void* user, Steam_BGetCallback_fn bGetCallback,
                                Steam_FreeLastCallback_fn freeLastCallback, int pipe) {
    if (!user) return false;
    void** vt = *(void***) user;
    void* p = vt[kVtUser_BKickOtherPlayingSession];
    if (!is_exec_ptr(p)) {
        log_line("[wn-launcher] BLOCKED: BKickOtherPlayingSession slot not executable");
        return false;
    }
    typedef bool (WN_THISCALL *BKickOtherPlayingSessionFn)(void* self);
    bool rc = ((BKickOtherPlayingSessionFn) p)(user);
    log_line("[wn-launcher] BLOCKED: BKickOtherPlayingSession() -> %d", rc ? 1 : 0);
    for (int waited = 0; waited < 15000; waited += 200) {
        if (bGetCallback && freeLastCallback) {
            char cb[64];
            while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
        }
        uint32_t blocking = 0;
        if (!query_other_session(user, &blocking)) {
            log_line("[wn-launcher] BLOCKED: other session cleared after %dms", waited);
            return true;
        }
        Sleep(200);
    }
    log_line("[wn-launcher] BLOCKED: other session still playing after 15000ms");
    return false;
}

static bool run_probe_exe(const char* probeExe, const char* logPath,
                          const char* gameRootDir, uint32_t appId) {
    if (GetFileAttributesA(probeExe) == INVALID_FILE_ATTRIBUTES) {
        log_line("[wn-launcher] iface-probe: %s not present", probeExe);
        return false;
    }
    char cmd[2048];
    snprintf(cmd, sizeof(cmd), "\"%s\" \"%s\" %u \"%s\"",
             probeExe, gameRootDir ? gameRootDir : "", (unsigned) appId, logPath);
    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    memset(&pi, 0, sizeof(pi));
    log_line("[wn-launcher] iface-probe: launching %s", cmd);
    if (!CreateProcessA(probeExe, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
        log_line("[wn-launcher] iface-probe: CreateProcess failed GLE=%lu",
                 (unsigned long) GetLastError());
        return false;
    }
    WaitForSingleObject(pi.hProcess, 25000);
    DWORD rc = 0;
    GetExitCodeProcess(pi.hProcess, &rc);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    log_line("[wn-launcher] iface-probe: %s exited rc=%lu", probeExe, (unsigned long) rc);
    FILE* f = fopen(logPath, "r");
    if (!f) {
        log_line("[wn-launcher] iface-probe: no log produced at %s", logPath);
        return true;
    }
    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        size_t n = strlen(line);
        while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = '\0';
        log_line("%s", line);
    }
    fclose(f);
    return true;
}

static bool run_iface_probe(const char* gameRootDir, uint32_t appId) {
    if (GetFileAttributesA("C:\\wn-iface-probe.on") == INVALID_FILE_ATTRIBUTES) return false;
    bool ran = run_probe_exe("C:\\Program Files (x86)\\Steam\\wn-iface-probe.exe",
                             "C:\\wn-iface-probe.log", gameRootDir, appId);
    if (run_probe_exe("C:\\Program Files (x86)\\Steam\\wn-iface-probe32.exe",
                      "C:\\wn-iface-probe32.log", gameRootDir, appId)) {
        ran = true;
    }
    return ran;
}

static void sync_app_ownership(void* engine, int hUser, int pipe, uint32_t appId,
                               Steam_BGetCallback_fn bGetCallback,
                               Steam_FreeLastCallback_fn freeLastCallback) {
    if (!engine || appId == 0) return;
    log_line("[wn-launcher] ownership: entry appId=%u hUser=%d pipe=%d", appId, hUser, pipe);

    void** engine_vt = *(void***) engine;
    typedef void* (WN_THISCALL *GetIClientUserFn)(void* self, int hUser, int hPipe);
    void* getUserP = engine_vt[kVtEngine_GetIClientUser];
    if (!is_exec_ptr(getUserP)) {
        log_line("[wn-launcher] ownership: GetIClientUser slot not executable — skipping license wait");
        return;
    }
    {
        char mod[MAX_PATH] = {0};
        HMODULE m = NULL;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                               | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               (LPCSTR) getUserP, &m)) {
            GetModuleFileNameA(m, mod, sizeof(mod));
        }
        log_line("[wn-launcher] ownership: engine slot %d fn=%p base=%p rva=0x%tx mod=%s",
                 kVtEngine_GetIClientUser, getUserP, (void*) m,
                 (ptrdiff_t) ((char*) getUserP - (char*) m), mod[0] ? mod : "?");
    }
    void* iuser = ((GetIClientUserFn) getUserP)(engine, hUser, pipe);
    log_line("[wn-launcher] ownership: IClientUser=%p (BIsSubscribedApp index %d)",
             iuser, kVtUser_BIsSubscribedApp);
    log_iface_vtable("IClientUser", iuser);
    if (!iuser) {
        log_line("[wn-launcher] ownership: no IClientUser — skipping license wait");
        return;
    }
    if (!interface_looks_valid(iuser, kVtUser_BIsSubscribedApp)) {
        log_line("[wn-launcher] ownership: IClientUser=%p FAILED validation — engine slot %d did "
                 "not return a usable interface for this client build; dumping the engine vtable",
                 iuser, kVtEngine_GetIClientUser);
        dump_engine_vtable(engine, 64);
        return;
    }
    void** user_vt = *(void***) iuser;

    void* subscribedP = user_vt[kVtUser_BIsSubscribedApp];
    if (!is_exec_ptr(subscribedP)) {
        log_line("[wn-launcher] ownership: BIsSubscribedApp(appId=%u) initial -> -1 "
                 "(slot-unavailable) — proceeding without the license wait", appId);
        return;
    }
    typedef bool (WN_THISCALL *BIsSubscribedAppFn)(void* self, uint32_t app);
    BIsSubscribedAppFn isSubscribed = (BIsSubscribedAppFn) subscribedP;

    bool owned = isSubscribed(iuser, appId);
    log_line("[wn-launcher] ownership: BIsSubscribedApp(appId=%u) initial -> %d "
             "(0=no 1=yes)", appId, owned ? 1 : 0);

    const int waitMs = env_int("WN_STEAM_OWNERSHIP_WAIT_MS", kOwnershipWaitMsDefault);
    int waited = 0;
    while (!owned && waited < waitMs) {
        if (bGetCallback && freeLastCallback) {
            char cb[64];
            while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
        }
        Sleep(200);
        waited += 200;
        owned = isSubscribed(iuser, appId);
    }
    log_line("[wn-launcher] ownership: BIsSubscribedApp(appId=%u)=%d after %dms "
             "(license sync %s)", appId, owned ? 1 : 0, waited,
             owned ? "complete" : "INCOMPLETE — appinfo and the game's own entitlement "
                                  "checks may see stale data");

    void* tokensP = user_vt[kVtUser_BGameConnectTokensAvailable];
    if (is_exec_ptr(tokensP)) {
        typedef bool (WN_THISCALL *BGameConnectTokensAvailableFn)(void* self);
        bool tokens = ((BGameConnectTokensAvailableFn) tokensP)(iuser);
        log_line("[wn-launcher] ownership: BGameConnectTokensAvailable -> %d — %s",
                 tokens ? 1 : 0,
                 tokens ? "the client holds game-connect tokens, so the game can "
                          "authenticate to secure (VAC-enabled) servers"
                        : "NO game-connect tokens: joining a secure server will fail "
                          "auth even though the launch itself is secure");
    } else {
        log_line("[wn-launcher] ownership: BGameConnectTokensAvailable slot %d not "
                 "executable — secure-server readiness unknown",
                 kVtUser_BGameConnectTokensAvailable);
    }

    const long ticketSlot =
        env_int("WN_STEAM_OWNERSHIP_SLOT",
                kVtUser_BUpdateAppOwnershipTicket * (int) sizeof(void*));
    void* lengthP = user_vt[kVtUser_GetAppOwnershipTicketLength];
    void* ticketP = (ticketSlot > 0 && (ticketSlot % (long) sizeof(void*)) == 0)
        ? user_vt[ticketSlot / (long) sizeof(void*)] : NULL;
    typedef uint32_t (WN_THISCALL *GetTicketLengthFn)(void* self, uint32_t app);
    typedef bool (WN_THISCALL *UpdateOwnershipTicketFn)(void* self, uint32_t app,
                                                        bool onlyIfStale);

    if (!is_exec_ptr(lengthP)) {
        log_line("[wn-launcher] ownership: GetAppOwnershipTicketLength slot 0x%x not "
                 "executable — cannot verify the app ownership ticket",
                 kVtUser_GetAppOwnershipTicketLength);
        return;
    }
    GetTicketLengthFn ticketLength = (GetTicketLengthFn) lengthP;
    uint32_t before = ticketLength(iuser, appId);
    log_line("[wn-launcher] ownership: app ownership ticket length before = %u", before);
    if (before != 0) return;

    if (!ticketP || !is_exec_ptr(ticketP)) {
        log_line("[wn-launcher] ownership: BUpdateAppOwnershipTicket slot 0x%lx invalid or "
                 "not executable — the game's GetAuthSessionTicket will have no ticket",
                 ticketSlot);
        return;
    }
    bool rc = ((UpdateOwnershipTicketFn) ticketP)(iuser, appId, false);
    log_line("[wn-launcher] ownership: BUpdateAppOwnershipTicket[slot 0x%lx](appId=%u) -> %d",
             ticketSlot, appId, rc ? 1 : 0);

    const int ticketWaitMs = env_int("WN_STEAM_TICKET_WAIT_MS", kTicketWaitMsDefault);
    int ticketWaited = 0;
    uint32_t after = ticketLength(iuser, appId);
    while (after == 0 && ticketWaited < ticketWaitMs) {
        if (bGetCallback && freeLastCallback) {
            char cb[64];
            while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
        }
        Sleep(200);
        ticketWaited += 200;
        after = ticketLength(iuser, appId);
    }
    log_line("[wn-launcher] ownership: app ownership ticket length after = %u (%dms) — %s",
             after, ticketWaited,
             after ? "ticket acquired; GetAuthSessionTicket can now produce a signed ticket"
                   : "STILL EMPTY — the game's GetAuthSessionTicket will fail and its "
                     "online/version handshake with the publisher will be rejected");
}

static void prewarm_encrypted_app_ticket(void* engine, int hUser, int pipe, uint32_t appId,
                                         Steam_BGetCallback_fn bGetCallback,
                                         Steam_FreeLastCallback_fn freeLastCallback) {
    if (!engine || appId == 0) return;
    if (env_int("WN_STEAM_APPTICKET_PREWARM", 0) == 0) {
        return;
    }

    void** engine_vt = *(void***) engine;
    typedef void* (WN_THISCALL *GetIClientUserFn)(void* self, int hUser, int hPipe);
    void* getUserP = engine_vt[kVtEngine_GetIClientUser];
    if (!is_exec_ptr(getUserP)) return;
    void* iuser = ((GetIClientUserFn) getUserP)(engine, hUser, pipe);
    if (!iuser || !interface_looks_valid(iuser, kVtUser_BIsSubscribedApp)) {
        log_line("[wn-launcher] appticket: no usable IClientUser — skipping prewarm");
        return;
    }

    void** user_vt = *(void***) iuser;
    void* reqP = user_vt[kVtUser_RequestEncryptedAppTicket];
    void* getP = user_vt[kVtUser_GetEncryptedAppTicket];
    if (!is_exec_ptr(reqP) || !is_exec_ptr(getP)) {
        log_line("[wn-launcher] appticket: slots %d/%d not executable — cannot prewarm the "
                 "encrypted app ticket the game needs for publisher auth",
                 kVtUser_RequestEncryptedAppTicket, kVtUser_GetEncryptedAppTicket);
        return;
    }

    typedef uint64_t (WN_THISCALL *RequestEncFn)(void* self, void* data, int cbData);
    typedef bool (WN_THISCALL *GetEncFn)(void* self, void* ticket, int cbMax, uint32_t* pcb);

    static unsigned char ticket[4096];
    uint32_t cb = 0;
    memset(ticket, 0, sizeof(ticket));
    bool have = ((GetEncFn) getP)(iuser, ticket, (int) sizeof(ticket), &cb);
    if (have && cb) {
        log_line("[wn-launcher] appticket: already cached (%u bytes) — the game's "
                 "GetEncryptedAppTicket will return immediately", cb);
        return;
    }

    typedef void* (WN_THISCALL *GetIClientUtilsFn)(void* self, int hPipe);
    typedef uint32_t (WN_THISCALL *GetAppIDFn)(void* self);
    typedef void (WN_THISCALL *SetAppIDForCurrentPipeFn)(void* self, uint32_t app, bool track);
    void* utils = NULL;
    uint32_t priorApp = 0;
    bool contextSet = false;
    void* getUtilsP = engine_vt[kVtEngine_GetIClientUtils];
    if (is_exec_ptr(getUtilsP)) utils = ((GetIClientUtilsFn) getUtilsP)(engine, pipe);
    if (utils) {
        void** utils_vt = *(void***) utils;
        void* getAppP = utils_vt[kVtUtils_GetAppID];
        void* setAppP = utils_vt[kVtUtils_SetAppIDForCurrentPipe];
        if (is_exec_ptr(getAppP)) priorApp = ((GetAppIDFn) getAppP)(utils);
        log_line("[wn-launcher] appticket: pipe app context = %u (want %u)", priorApp, appId);
        if (priorApp != appId && is_exec_ptr(setAppP)) {
            ((SetAppIDForCurrentPipeFn) setAppP)(utils, appId, false);
            contextSet = true;
            uint32_t nowApp = is_exec_ptr(getAppP) ? ((GetAppIDFn) getAppP)(utils) : 0;
            log_line("[wn-launcher] appticket: SetAppIDForCurrentPipe(%u, track=false) -> "
                     "context now %u (encrypted app tickets are scoped to the pipe's app)",
                     appId, nowApp);
        }
    } else {
        log_line("[wn-launcher] appticket: no IClientUtils — cannot scope the pipe to appId %u",
                 appId);
    }

    uint64_t call = ((RequestEncFn) reqP)(iuser, NULL, 0);
    log_line("[wn-launcher] appticket: RequestEncryptedAppTicket -> HSteamAPICall=0x%llx",
             (unsigned long long) call);

    const int waitMs = env_int("WN_STEAM_APPTICKET_WAIT_MS", 5000);
    int waited = 0;
    while (waited < waitMs) {
        if (bGetCallback && freeLastCallback) {
            char cbbuf[64];
            while (bGetCallback(pipe, cbbuf)) freeLastCallback(pipe);
        }
        Sleep(100);
        waited += 100;
        cb = 0;
        memset(ticket, 0, sizeof(ticket));
        have = ((GetEncFn) getP)(iuser, ticket, (int) sizeof(ticket), &cb);
        if (have && cb) break;
    }
    log_line("[wn-launcher] appticket: GetEncryptedAppTicket -> rc=%d length=%u (%dms) — %s",
             have ? 1 : 0, cb, waited,
             (have && cb)
                 ? "ticket cached before launch; the game's DNA/publisher init can proceed"
                 : "STILL EMPTY — the game will see an empty encrypted app ticket and skip "
                   "its publisher (Ubisoft/DNA) initialisation entirely");

    if (contextSet && utils) {
        void** utils_vt = *(void***) utils;
        void* setAppP = utils_vt[kVtUtils_SetAppIDForCurrentPipe];
        if (is_exec_ptr(setAppP)) {
            ((SetAppIDForCurrentPipeFn) setAppP)(utils, priorApp, false);
            log_line("[wn-launcher] appticket: pipe app context restored to %u", priorApp);
        }
    }
}

static void log_active_process_registry(const char* when) {
    HKEY h = NULL;
    if (RegOpenKeyExA(HKEY_CURRENT_USER, "Software\\Valve\\Steam\\ActiveProcess",
                      0, KEY_READ, &h) != ERROR_SUCCESS) {
        log_line("[wn-launcher] activeprocess(%s): key missing", when);
        return;
    }
    DWORD pid = 0, activeUser = 0, universe = 0, sz = sizeof(DWORD), type = 0;
    RegQueryValueExA(h, "pid", NULL, &type, (LPBYTE) &pid, &sz);
    sz = sizeof(DWORD);
    RegQueryValueExA(h, "ActiveUser", NULL, &type, (LPBYTE) &activeUser, &sz);
    sz = sizeof(DWORD);
    RegQueryValueExA(h, "Universe", NULL, &type, (LPBYTE) &universe, &sz);
    char dll64[MAX_PATH] = {0};
    sz = sizeof(dll64);
    RegQueryValueExA(h, "SteamClientDll64", NULL, &type, (LPBYTE) dll64, &sz);
    RegCloseKey(h);
    log_line("[wn-launcher] activeprocess(%s): pid=%lu ourPid=%lu ActiveUser=%lu "
             "Universe=%lu SteamClientDll64=%s", when,
             (unsigned long) pid, (unsigned long) GetCurrentProcessId(),
             (unsigned long) activeUser, (unsigned long) universe,
             dll64[0] ? dll64 : "(unset)");
}

static void log_game_process_modules(unsigned long pid) {
    if (pid == 0) {
        log_line("[wn-launcher] gamemodules: no game pid resolved");
        return;
    }
    HANDLE snap = INVALID_HANDLE_VALUE;
    for (int attempt = 0; attempt < 5 && snap == INVALID_HANDLE_VALUE; ++attempt) {
        snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32,
                                        (DWORD) pid);
        if (snap == INVALID_HANDLE_VALUE) Sleep(200);
    }
    if (snap == INVALID_HANDLE_VALUE) {
        DWORD gle = GetLastError();
        log_line("[wn-launcher] gamemodules: snapshot of pid=%lu failed GLE=%lu%s",
                 pid, gle,
                 gle == 299 ? " (ERROR_PARTIAL_COPY — a 32-bit agent cannot enumerate a "
                              "64-bit game's modules; diagnostic only)" : "");
        return;
    }
    MODULEENTRY32 me;
    me.dwSize = sizeof(me);
    int total = 0, steamish = 0;
    std::vector<std::string> allNames;
    if (Module32First(snap, &me)) {
        do {
            total++;
            if (allNames.size() < 40) allNames.emplace_back(me.szModule);
            char lower[MAX_PATH];
            snprintf(lower, sizeof(lower), "%s", me.szModule);
            for (char* p = lower; *p; ++p) *p = (char) tolower((unsigned char) *p);
            if (strstr(lower, "steam") || strstr(lower, "gameoverlay")) {
                steamish++;
                log_line("[wn-launcher] gamemodules: pid=%lu %s <- %s",
                         pid, me.szModule, me.szExePath);
            }
        } while (Module32Next(snap, &me));
    }
    CloseHandle(snap);
    log_line("[wn-launcher] gamemodules: pid=%lu total=%d steam-related=%d",
             pid, total, steamish);
    if (steamish == 0) {
        std::string joined;
        for (const auto& n : allNames) {
            if (!joined.empty()) joined += " ";
            joined += n;
        }
        log_line("[wn-launcher] gamemodules: pid=%lu loaded NO steam module — the game "
                 "never bound to this client. modules: %s", pid, joined.c_str());
    }
}

static void resolve_game_root_dir(const char* gameExe, char* out, size_t outSize) {
    if (!out || outSize == 0) return;
    out[0] = '\0';
    if (!gameExe || !*gameExe) return;

    const char* marker = "\\steamapps\\common\\";
    size_t mlen = strlen(marker);
    for (const char* s = gameExe; *s; ++s) {
        if (_strnicmp(s, marker, mlen) != 0) continue;
        const char* dirStart = s + mlen;
        const char* dirEnd = strchr(dirStart, '\\');
        if (!dirEnd) break;
        size_t len = (size_t)(dirEnd - gameExe);
        if (len == 0 || len >= outSize) return;
        memcpy(out, gameExe, len);
        out[len] = '\0';
        return;
    }

    const char* slash = strrchr(gameExe, '\\');
    if (!slash || slash == gameExe) return;
    size_t len = (size_t)(slash - gameExe);
    if (len >= outSize) return;
    memcpy(out, gameExe, len);
    out[len] = '\0';
}

static const int kLaunchRegisterGraceMs = 20000;

static bool wait_for_game_process(const char* exeName, int maxSeconds, const char* context,
                                  Steam_BGetCallback_fn bGetCallback,
                                  Steam_FreeLastCallback_fn freeLastCallback,
                                  int pipe, void* blockUser) {
    const int kPollMs = 500;
    const int kHeartbeatMs = 5000;
    int elapsed = 0;
    int sinceHeartbeat = 0;
    bool steamRegistered = false;
    while (elapsed < maxSeconds * 1000) {
        if (wn_launcher_count_game_processes() > 0) {
            log_line("[wn-launcher] LaunchApp: \"%s\" is running after %dms (%s)",
                     exeName, elapsed, context);
            return true;
        }
        if (bGetCallback && freeLastCallback) {
            char cb[64];
            while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
        }
        if (blockUser) {
            int running = 0;
            query_running_game_quiet(blockUser, &running);
            if (!steamRegistered) {
                if (running > 0) {
                    steamRegistered = true;
                    log_line("[wn-launcher] LaunchApp: Steam registered the app as running "
                             "after %dms — the launch was accepted, waiting for the process",
                             elapsed);
                } else if (elapsed >= kLaunchRegisterGraceMs) {
                    log_line("[wn-launcher] LaunchApp: Steam still reports 0 running games "
                             "after %dms and \"%s\" has not spawned — the dispatch did not "
                             "take effect; stopping the wait early instead of burning %ds",
                             elapsed, exeName, maxSeconds);
                    return false;
                }
            } else if (running == 0) {
                log_line("[wn-launcher] LaunchApp: Steam de-registered the app after %dms "
                         "and \"%s\" was never seen alive — the game started and exited "
                         "immediately (startup crash); not waiting the remaining %ds",
                         elapsed, exeName, maxSeconds - elapsed / 1000);
                return false;
            }
        }
        Sleep(kPollMs);
        elapsed += kPollMs;
        sinceHeartbeat += kPollMs;
        if (sinceHeartbeat >= kHeartbeatMs) {
            sinceHeartbeat = 0;
            log_line("[wn-launcher] LaunchApp: still waiting for \"%s\" to appear "
                     "(%ds/%ds, steam-registered=%d, %s)",
                     exeName, elapsed / 1000, maxSeconds, steamRegistered ? 1 : 0, context);
        }
    }
    return wn_launcher_count_game_processes() > 0;
}

static bool create_process_game(const char* gameExe, const char* exeName) {
    if (wn_launcher_count_game_processes() > 0) {
        log_line("[wn-launcher] CreateProcess fallback skipped — \"%s\" is already "
                 "running (Steam owns the launch)", exeName);
        return true;
    }

    char cwd[MAX_PATH];
    snprintf(cwd, sizeof(cwd), "%s", gameExe);
    char* slash = strrchr(cwd, '\\');
    if (slash) *slash = '\0'; else cwd[0] = '\0';

    char cmd[MAX_PATH + 8];
    snprintf(cmd, sizeof(cmd), "\"%s\"", gameExe);

    STARTUPINFOA si;
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi;
    memset(&pi, 0, sizeof(pi));

    // Inherit our env (SteamAppId etc.) so the game's SteamAPI_Init attaches to
    // our logged-on steamclient session.
    BOOL ok = CreateProcessA(gameExe, cmd, NULL, NULL, FALSE,
                             0, NULL, cwd[0] ? cwd : NULL, &si, &pi);
    if (!ok) {
        log_line("[wn-launcher] CreateProcess fallback FAILED for \"%s\" (GLE=%lu)",
                 exeName, GetLastError());
        return false;
    }
    log_line("[wn-launcher] game process started pid=%lu via CreateProcess "
             "fallback (\"%s\")", (unsigned long) pi.dwProcessId, exeName);
    if (pi.hThread) CloseHandle(pi.hThread);
    if (pi.hProcess) CloseHandle(pi.hProcess);
    return true;
}

static void dump_loaded_modules(const char* when) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32,
                                           GetCurrentProcessId());
    if (snap == INVALID_HANDLE_VALUE) {
        log_line("[wn-launcher] modules(%s): CreateToolhelp32Snapshot failed GLE=%lu",
                 when, GetLastError());
        return;
    }
    MODULEENTRY32 me;
    me.dwSize = sizeof(me);
    int n = 0;
    if (Module32First(snap, &me)) {
        do {
            log_line("[wn-launcher] modules(%s): base=%p size=0x%lx name=%s path=%s",
                     when, me.modBaseAddr, (unsigned long) me.modBaseSize,
                     me.szModule, me.szExePath);
            n++;
        } while (Module32Next(snap, &me));
    }
    log_line("[wn-launcher] modules(%s): total=%d", when, n);
    CloseHandle(snap);
}

static LONG WINAPI launcher_unhandled_filter(EXCEPTION_POINTERS* info) {
    if (!info || !info->ExceptionRecord) return EXCEPTION_EXECUTE_HANDLER;
    const EXCEPTION_RECORD* er = info->ExceptionRecord;
    void* ip = er->ExceptionAddress;

    char modName[MAX_PATH] = {0};
    HMODULE faultMod = NULL;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                           | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCSTR)ip, &faultMod)) {
        GetModuleFileNameA(faultMod, modName, sizeof(modName));
    }

    char bytes[3 * 16 + 1] = {0};
    {
        MEMORY_BASIC_INFORMATION mbi;
        if (VirtualQuery(ip, &mbi, sizeof(mbi)) && mbi.State == MEM_COMMIT) {
            const unsigned char* p = (const unsigned char*)ip;
            int hp = 0;
            for (int i = 0; i < 16 && hp + 3 < (int)sizeof(bytes); ++i) {
                hp += snprintf(bytes + hp, sizeof(bytes) - hp, "%02x ", p[i]);
            }
        }
    }

    log_line("[wn-launcher] UEF: tid=%lu pid=%lu exc=0x%lx at %p mod='%s' bytes=%s",
             (unsigned long) GetCurrentThreadId(),
             (unsigned long) GetCurrentProcessId(),
             er->ExceptionCode, ip, modName[0] ? modName : "(unknown)", bytes);
    if (er->ExceptionCode == EXCEPTION_ACCESS_VIOLATION && er->NumberParameters >= 2) {
        const char* op = (er->ExceptionInformation[0] == 0) ? "read"
                       : (er->ExceptionInformation[0] == 1) ? "write"
                       : (er->ExceptionInformation[0] == 8) ? "DEP" : "?";
        log_line("[wn-launcher] UEF: AV %s fault_addr=0x%llx",
                 op, (unsigned long long) er->ExceptionInformation[1]);
    }

    {
        MEMORY_BASIC_INFORMATION mbi;
        if (VirtualQuery(ip, &mbi, sizeof(mbi))) {
            log_line("[wn-launcher] UEF: page base=%p size=0x%llx state=0x%lx "
                     "protect=0x%lx alloc_protect=0x%lx type=0x%lx",
                     mbi.BaseAddress, (unsigned long long) mbi.RegionSize,
                     mbi.State, mbi.Protect, mbi.AllocationProtect, mbi.Type);
        }
    }

    if (info->ContextRecord) {
        const CONTEXT* c = info->ContextRecord;
#ifdef __i386__
        log_line("[wn-launcher] UEF: ctx Eip=%lx Esp=%lx Ebp=%lx",
                 (unsigned long) c->Eip, (unsigned long) c->Esp,
                 (unsigned long) c->Ebp);
        log_line("[wn-launcher] UEF: ctx Eax=%lx Ecx=%lx Edx=%lx Ebx=%lx",
                 (unsigned long) c->Eax, (unsigned long) c->Ecx,
                 (unsigned long) c->Edx, (unsigned long) c->Ebx);
        log_line("[wn-launcher] UEF: ctx Esi=%lx Edi=%lx",
                 (unsigned long) c->Esi, (unsigned long) c->Edi);
        const uintptr_t* sp = (const uintptr_t*) c->Esp;
#else
        log_line("[wn-launcher] UEF: ctx Rip=%llx Rsp=%llx Rbp=%llx",
                 (unsigned long long) c->Rip,
                 (unsigned long long) c->Rsp,
                 (unsigned long long) c->Rbp);
        log_line("[wn-launcher] UEF: ctx Rax=%llx Rcx=%llx Rdx=%llx Rbx=%llx",
                 (unsigned long long) c->Rax, (unsigned long long) c->Rcx,
                 (unsigned long long) c->Rdx, (unsigned long long) c->Rbx);
        log_line("[wn-launcher] UEF: ctx Rsi=%llx Rdi=%llx R8=%llx R9=%llx",
                 (unsigned long long) c->Rsi, (unsigned long long) c->Rdi,
                 (unsigned long long) c->R8,  (unsigned long long) c->R9);
        const uintptr_t* sp = (const uintptr_t*) c->Rsp;
#endif
        MEMORY_BASIC_INFORMATION smbi;
        if (sp && VirtualQuery((LPCVOID) sp, &smbi, sizeof(smbi))
            && smbi.State == MEM_COMMIT) {
            char chain[256]; int p = 0;
            for (int i = 0; i < 8; ++i) {
                p += snprintf(chain + p, sizeof(chain) - p, "%llx ",
                              (unsigned long long) sp[i]);
            }
            log_line("[wn-launcher] UEF: stack[0..7]=%s", chain);
        }
    }

    dump_loaded_modules("UEF");
    return EXCEPTION_EXECUTE_HANDLER;
}

static void log_steam_named_pipes(const char* when) {
    WIN32_FIND_DATAA fd;
    HANDLE h = FindFirstFileA("\\\\.\\pipe\\*", &fd);
    if (h == INVALID_HANDLE_VALUE) {
        log_line("[wn-launcher] pipes(%s): enumeration unavailable GLE=%lu",
                 when, GetLastError());
        return;
    }
    int total = 0, steamish = 0;
    do {
        total++;
        char lower[MAX_PATH];
        snprintf(lower, sizeof(lower), "%s", fd.cFileName);
        for (char* p = lower; *p; ++p) *p = (char) tolower((unsigned char) *p);
        if (strstr(lower, "steam") || strstr(lower, "valve")) {
            steamish++;
            log_line("[wn-launcher] pipes(%s): %s", when, fd.cFileName);
        }
    } while (FindNextFileA(h, &fd));
    FindClose(h);
    log_line("[wn-launcher] pipes(%s): total=%d steam-related=%d", when, total, steamish);
}

static bool start_steam_client_service(void) {
    const char* kSvcName = "Steam Client Service";
    static const char* const kSvcExeCandidates[] = {
        "C:\\Program Files (x86)\\Steam\\bin\\steamservice.exe",
        "C:\\Program Files (x86)\\Common Files\\Steam\\steamservice.exe",
    };
    const char* kSvcExe = NULL;
    for (const char* cand : kSvcExeCandidates) {
        DWORD a = GetFileAttributesA(cand);
        if (a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY)) { kSvcExe = cand; break; }
    }
    if (!kSvcExe) {
        log_line("[wn-launcher] steamservice: binary not present at %s or %s — "
                 "LaunchApp's IPC queue will have no peer; will use "
                 "CreateProcess fallback", kSvcExeCandidates[0], kSvcExeCandidates[1]);
        return false;
    }
    char svcBinPathBuf[MAX_PATH + 32];
    snprintf(svcBinPathBuf, sizeof(svcBinPathBuf), "\"%s\" /RunAsService", kSvcExe);
    const char* kSvcBinPath = svcBinPathBuf;
    log_line("[wn-launcher] steamservice: found %s", kSvcExe);

    SC_HANDLE scm = OpenSCManagerA(NULL, NULL, SC_MANAGER_ALL_ACCESS);
    if (!scm) {
        log_line("[wn-launcher] steamservice: OpenSCManager failed GLE=%lu",
                 GetLastError());
        return false;
    }

    SC_HANDLE svc = OpenServiceA(scm, kSvcName, SERVICE_ALL_ACCESS);
    if (!svc) {
        DWORD err = GetLastError();
        if (err == ERROR_SERVICE_DOES_NOT_EXIST) {
            log_line("[wn-launcher] steamservice: service missing — "
                     "installing as \"%s\"", kSvcName);
            svc = CreateServiceA(
                scm, kSvcName, kSvcName,
                SERVICE_ALL_ACCESS,
                SERVICE_WIN32_OWN_PROCESS,
                SERVICE_DEMAND_START,
                SERVICE_ERROR_NORMAL,
                kSvcBinPath,
                NULL, NULL, NULL, NULL, NULL);
            if (!svc) {
                log_line("[wn-launcher] steamservice: CreateService failed GLE=%lu",
                         GetLastError());
                CloseServiceHandle(scm);
                return false;
            }
            log_line("[wn-launcher] steamservice: service installed");
        } else {
            log_line("[wn-launcher] steamservice: OpenService failed GLE=%lu", err);
            CloseServiceHandle(scm);
            return false;
        }
    }

    {
        BYTE cfgBuf[8192];
        DWORD needed = 0;
        LPQUERY_SERVICE_CONFIGA cfg = (LPQUERY_SERVICE_CONFIGA) cfgBuf;
        if (QueryServiceConfigA(svc, cfg, sizeof(cfgBuf), &needed)
            && cfg->lpBinaryPathName
            && _stricmp(cfg->lpBinaryPathName, kSvcBinPath) != 0) {
            log_line("[wn-launcher] steamservice: registered path \"%s\" differs from "
                     "\"%s\" — reconfiguring", cfg->lpBinaryPathName, kSvcBinPath);
            SERVICE_STATUS st;
            memset(&st, 0, sizeof(st));
            QueryServiceStatus(svc, &st);
            if (st.dwCurrentState != SERVICE_STOPPED) {
                ControlService(svc, SERVICE_CONTROL_STOP, &st);
                for (int i = 0; i < 25; ++i) {
                    if (!QueryServiceStatus(svc, &st)) break;
                    if (st.dwCurrentState == SERVICE_STOPPED) break;
                    Sleep(200);
                }
            }
            if (!ChangeServiceConfigA(svc, SERVICE_NO_CHANGE, SERVICE_NO_CHANGE,
                                      SERVICE_NO_CHANGE, kSvcBinPath, NULL, NULL,
                                      NULL, NULL, NULL, NULL)) {
                log_line("[wn-launcher] steamservice: ChangeServiceConfig failed GLE=%lu",
                         GetLastError());
            }
        }
    }

    SERVICE_STATUS status;
    memset(&status, 0, sizeof(status));
    QueryServiceStatus(svc, &status);
    log_line("[wn-launcher] steamservice: pre-start state=%lu", status.dwCurrentState);

    if (status.dwCurrentState != SERVICE_RUNNING) {
        if (!StartServiceA(svc, 0, NULL)) {
            DWORD err = GetLastError();
            if (err != ERROR_SERVICE_ALREADY_RUNNING) {
                log_line("[wn-launcher] steamservice: StartService failed GLE=%lu", err);
                memset(&status, 0, sizeof(status));
                QueryServiceStatus(svc, &status);
            }
        }
        int waited = 0;
        while (waited < 30000) {
            if (!QueryServiceStatus(svc, &status)) break;
            if (status.dwCurrentState == SERVICE_RUNNING ||
                status.dwCurrentState == SERVICE_STOPPED) break;
            Sleep(200);
            waited += 200;
        }
        log_line("[wn-launcher] steamservice: post-start state=%lu after %dms",
                 status.dwCurrentState, waited);
        if (status.dwCurrentState != SERVICE_RUNNING
            && _stricmp(kSvcExe, kSvcExeCandidates[0]) == 0) {
            DWORD altAttr = GetFileAttributesA(kSvcExeCandidates[1]);
            if (altAttr != INVALID_FILE_ATTRIBUTES && !(altAttr & FILE_ATTRIBUTE_DIRECTORY)) {
                char altBin[MAX_PATH + 32];
                snprintf(altBin, sizeof(altBin), "\"%s\" /RunAsService", kSvcExeCandidates[1]);
                log_line("[wn-launcher] steamservice: canonical path did not start — "
                         "reconfiguring to %s and retrying", kSvcExeCandidates[1]);
                if (ChangeServiceConfigA(svc, SERVICE_NO_CHANGE, SERVICE_NO_CHANGE,
                                         SERVICE_NO_CHANGE, altBin, NULL, NULL,
                                         NULL, NULL, NULL, NULL)
                    && (StartServiceA(svc, 0, NULL)
                        || GetLastError() == ERROR_SERVICE_ALREADY_RUNNING)) {
                    int w2 = 0;
                    while (w2 < 30000) {
                        if (!QueryServiceStatus(svc, &status)) break;
                        if (status.dwCurrentState == SERVICE_RUNNING ||
                            status.dwCurrentState == SERVICE_STOPPED) break;
                        Sleep(200);
                        w2 += 200;
                    }
                    log_line("[wn-launcher] steamservice: fallback post-start state=%lu "
                             "after %dms", status.dwCurrentState, w2);
                } else {
                    log_line("[wn-launcher] steamservice: fallback start failed GLE=%lu",
                             GetLastError());
                }
            }
        }
        if (status.dwCurrentState != SERVICE_RUNNING) {
            if (DeleteService(svc)) {
                log_line("[wn-launcher] steamservice: service is wedged — deregistered so the "
                         "next launch recreates it cleanly");
            } else {
                log_line("[wn-launcher] steamservice: DeleteService failed GLE=%lu",
                         GetLastError());
            }
        }
    }

    bool running = (status.dwCurrentState == SERVICE_RUNNING);
    CloseServiceHandle(svc);
    CloseServiceHandle(scm);
    log_steam_named_pipes("post-service-start");
    return running;
}

static bool is_exec_ptr(void* p) {
    if (!p) return false;
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery(p, &mbi, sizeof(mbi)) == 0) return false;
    if (mbi.State != MEM_COMMIT) return false;
    DWORD x = mbi.Protect & 0xFF;
    return x == PAGE_EXECUTE || x == PAGE_EXECUTE_READ ||
           x == PAGE_EXECUTE_READWRITE || x == PAGE_EXECUTE_WRITECOPY;
}

static const char* kRedistsMarkerPath = "C:\\wn-installed-redists.txt";

enum class RedistInstallResult {
    SKIPPED = 0,
    INSTALLED = 1,
    FAILED = 2,
    TIMED_OUT = 3,
};

static bool is_known_redist_installer(const std::filesystem::path& p) {
    if (!std::filesystem::is_regular_file(p)) return false;
    std::string name = p.filename().string();
    std::string ext  = p.extension().string();
    for (char& c : name) c = (char) std::tolower((unsigned char) c);
    for (char& c : ext)  c = (char) std::tolower((unsigned char) c);
    if (ext != ".exe" && ext != ".msi") return false;
    return name.find("vcredist") != std::string::npos ||
           name.find("vc_redist") != std::string::npos ||
           name.find("dxsetup") != std::string::npos ||
           name.find("directx") != std::string::npos ||
           name.find("physx") != std::string::npos ||
           name.find("oalinst") != std::string::npos ||
           name.find("openal") != std::string::npos ||
           name.find("dotnet") != std::string::npos ||
           name.find("ndp") != std::string::npos ||
           name.find("xna") != std::string::npos ||
           name.find("ue4prereq") != std::string::npos ||
           name.find("prereq") != std::string::npos ||
           name.find("redist") != std::string::npos;
}

static bool is_redisty_dir_name(const std::string& nameRaw) {
    std::string n = nameRaw;
    for (char& c : n) c = (char) tolower((unsigned char) c);
    static const char* const kNeedles[] = {
        "redist", "redists", "_redist", "redistributables", "installer",
        "installers", "support", "prereq", "prereqs", "commonredist",
    };
    for (const char* needle : kNeedles) {
        if (n.find(needle) != std::string::npos) return true;
    }
    return false;
}

static void collect_redist_installers_at(const std::filesystem::path& dir,
                                         std::vector<std::filesystem::path>& out,
                                         int depth, bool insideRedist) {
    if (depth > 5) return;
    std::error_code ec;
    std::filesystem::directory_iterator it(dir,
            std::filesystem::directory_options::skip_permission_denied, ec);
    if (ec) return;
    for (const auto& e : it) {
        std::error_code isDirEc;
        if (e.is_directory(isDirEc)) {
            bool redisty = insideRedist || is_redisty_dir_name(e.path().filename().string());
            if (depth == 0 || redisty) {
                collect_redist_installers_at(e.path(), out, depth + 1, redisty);
            }
            continue;
        }
        if (is_known_redist_installer(e.path())) out.push_back(e.path());
    }
}

static std::vector<std::filesystem::path> collect_redist_installers(const std::filesystem::path& root) {
    std::vector<std::filesystem::path> out;
    if (root.empty()) return out;
    collect_redist_installers_at(root, out, 0, false);
    return out;
}

static bool marker_has_path(const std::string& line) {
    DWORD attr = GetFileAttributesA(line.c_str());
    return attr != INVALID_FILE_ATTRIBUTES;
}

static bool load_installed_redists(std::vector<std::string>& lines) {
    FILE* f = fopen(kRedistsMarkerPath, "r");
    if (!f) return false;
    char buf[MAX_PATH * 4];
    while (fgets(buf, sizeof(buf), f)) {
        size_t n = strlen(buf);
        while (n && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = '\0';
        if (n) lines.emplace_back(buf);
    }
    fclose(f);
    return true;
}

static bool save_installed_redists(const std::vector<std::string>& lines) {
    FILE* f = fopen(kRedistsMarkerPath, "w");
    if (!f) return false;
    for (const auto& line : lines) fprintf(f, "%s\n", line.c_str());
    fclose(f);
    return true;
}

static bool marker_contains(const std::vector<std::string>& lines, const std::string& path) {
    for (const auto& line : lines) {
        if (_stricmp(line.c_str(), path.c_str()) == 0) return true;
    }
    return false;
}

static std::string redist_silent_args(const std::filesystem::path& installer) {
    std::string name = installer.filename().string();
    std::string ext  = installer.extension().string();
    for (char& c : name) c = (char) std::tolower((unsigned char) c);
    for (char& c : ext)  c = (char) std::tolower((unsigned char) c);
    if (ext == ".msi") return " /qn /norestart";
    if (name.find("dxsetup") != std::string::npos) return " /silent";
    if (name.find("ue4prereq") != std::string::npos) return " /quiet /norestart";
    if (name.find("physx") != std::string::npos) return " /quiet /norestart";
    return " /quiet /norestart";
}

static RedistInstallResult run_redist_installer(const std::filesystem::path& installer,
                                                DWORD* outExitCode) {
    std::string cmd = "\"" + installer.string() + "\"" + redist_silent_args(installer);
    std::vector<char> cmdVec(cmd.begin(), cmd.end());
    cmdVec.push_back('\0');

    STARTUPINFOA si = {};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION pi = {};
    std::string cwdStr = installer.parent_path().string();
    if (!CreateProcessA(
            installer.string().c_str(),
            cmdVec.data(),
            nullptr, nullptr, FALSE,
            CREATE_NO_WINDOW,
            nullptr,
            cwdStr.empty() ? nullptr : cwdStr.c_str(),
            &si, &pi)) {
        log_line("[wn-launcher] redist install: CreateProcess failed for %s "
                  "(GLE=%lu)",
                  installer.string().c_str(), GetLastError());
        if (outExitCode) *outExitCode = 0xFFFFFFFFu;
        return RedistInstallResult::FAILED;
    }

    constexpr DWORD kPerInstallerTimeoutMs = 90 * 1000;
    DWORD waitResult = WaitForSingleObject(pi.hProcess, kPerInstallerTimeoutMs);
    DWORD exitCode = ~0u;
    bool timedOut = false;
    if (waitResult == WAIT_OBJECT_0) {
        GetExitCodeProcess(pi.hProcess, &exitCode);
    } else {
        log_line("[wn-launcher] redist install: %s — 90s timeout (silent "
                 "installer hung?)",
                 installer.filename().string().c_str());
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, 5000);
        timedOut = true;
        exitCode = 0xFFFFFFFEu;
    }
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    if (outExitCode) *outExitCode = exitCode;
    if (timedOut) return RedistInstallResult::TIMED_OUT;
    return (exitCode == 0 || exitCode == 3010) ? RedistInstallResult::INSTALLED
                                               : RedistInstallResult::FAILED;
}

struct VdfNode {
    std::string value;
    bool isBlock = false;
    std::vector<std::pair<std::string, VdfNode>> children;

    const VdfNode* find(const char* key) const {
        for (const auto& kv : children) {
            if (_stricmp(kv.first.c_str(), key) == 0) return &kv.second;
        }
        return NULL;
    }
};

static void vdf_skip_ws(const std::string& s, size_t& i) {
    while (i < s.size()) {
        if (isspace((unsigned char) s[i])) { ++i; continue; }
        if (s[i] == '/' && i + 1 < s.size() && s[i + 1] == '/') {
            while (i < s.size() && s[i] != '\n') ++i;
            continue;
        }
        break;
    }
}

static bool vdf_read_token(const std::string& s, size_t& i, std::string& out) {
    vdf_skip_ws(s, i);
    if (i >= s.size()) return false;
    if (s[i] == '{' || s[i] == '}') { out.assign(1, s[i]); ++i; return true; }
    if (s[i] != '"') return false;
    ++i;
    out.clear();
    while (i < s.size() && s[i] != '"') {
        if (s[i] == '\\' && i + 1 < s.size()) {
            char c = s[i + 1];
            out.push_back(c == 'n' ? '\n' : c == 't' ? '\t' : c);
            i += 2;
            continue;
        }
        out.push_back(s[i]);
        ++i;
    }
    if (i < s.size()) ++i;
    return true;
}

static bool vdf_parse_block(const std::string& s, size_t& i, VdfNode& out) {
    out.isBlock = true;
    for (;;) {
        std::string key;
        size_t save = i;
        if (!vdf_read_token(s, i, key)) return true;
        if (key == "}") return true;
        if (key == "{") { i = save; return false; }

        std::string tok;
        if (!vdf_read_token(s, i, tok)) return true;
        VdfNode child;
        if (tok == "{") {
            if (!vdf_parse_block(s, i, child)) return false;
        } else {
            child.value = tok;
        }
        out.children.emplace_back(key, child);
    }
}

static bool vdf_parse_file(const std::filesystem::path& path, VdfNode& root) {
    FILE* f = fopen(path.string().c_str(), "rb");
    if (!f) return false;
    std::string text;
    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) text.append(buf, n);
    fclose(f);
    size_t i = 0;
    root = VdfNode();
    root.isBlock = true;
    for (;;) {
        std::string key;
        if (!vdf_read_token(text, i, key)) break;
        if (key == "{" || key == "}") continue;
        std::string tok;
        if (!vdf_read_token(text, i, tok)) break;
        VdfNode child;
        if (tok == "{") {
            if (!vdf_parse_block(text, i, child)) break;
        } else {
            child.value = tok;
        }
        root.children.emplace_back(key, child);
    }
    return !root.children.empty();
}

static bool split_hasrunkey(const std::string& full, HKEY& hive, std::string& subKey) {
    size_t slash = full.find('\\');
    if (slash == std::string::npos) return false;
    std::string hiveName = full.substr(0, slash);
    subKey = full.substr(slash + 1);
    if (_stricmp(hiveName.c_str(), "HKEY_LOCAL_MACHINE") == 0 || _stricmp(hiveName.c_str(), "HKLM") == 0) {
        hive = HKEY_LOCAL_MACHINE;
        return true;
    }
    if (_stricmp(hiveName.c_str(), "HKEY_CURRENT_USER") == 0 || _stricmp(hiveName.c_str(), "HKCU") == 0) {
        hive = HKEY_CURRENT_USER;
        return true;
    }
    return false;
}

static bool hasrunkey_is_set(const std::string& full, const std::string& entryName) {
    HKEY hive;
    std::string sub;
    if (!split_hasrunkey(full, hive, sub)) return false;
    const REGSAM views[] = { KEY_WOW64_64KEY, KEY_WOW64_32KEY };
    for (REGSAM view : views) {
        HKEY h;
        if (RegOpenKeyExA(hive, sub.c_str(), 0, KEY_READ | view, &h) != ERROR_SUCCESS) continue;
        LONG rc = RegQueryValueExA(h, entryName.c_str(), NULL, NULL, NULL, NULL);
        RegCloseKey(h);
        if (rc == ERROR_SUCCESS) return true;
    }
    return false;
}

static void hasrunkey_mark(const std::string& full, const std::string& entryName) {
    HKEY hive;
    std::string sub;
    if (!split_hasrunkey(full, hive, sub)) return;
    const REGSAM views[] = { KEY_WOW64_64KEY, KEY_WOW64_32KEY };
    for (REGSAM view : views) {
        HKEY h;
        DWORD disp = 0;
        if (RegCreateKeyExA(hive, sub.c_str(), 0, NULL, 0,
                            KEY_WRITE | view, NULL, &h, &disp) != ERROR_SUCCESS) continue;
        DWORD one = 1;
        RegSetValueExA(h, entryName.c_str(), 0, REG_DWORD,
                       (const BYTE*) &one, sizeof(one));
        RegCloseKey(h);
    }
}

typedef BOOL (WINAPI *Wow64DisableFsRedirFn)(PVOID*);
typedef BOOL (WINAPI *Wow64RevertFsRedirFn)(PVOID);

static bool file_exists_plain(const std::string& path) {
    DWORD attrs = GetFileAttributesA(path.c_str());
    return attrs != INVALID_FILE_ATTRIBUTES && !(attrs & FILE_ATTRIBUTE_DIRECTORY);
}

static bool file_exists_unredirected(const std::string& path) {
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    Wow64DisableFsRedirFn disable = k32
        ? (Wow64DisableFsRedirFn) GetProcAddress(k32, "Wow64DisableWow64FsRedirection") : NULL;
    Wow64RevertFsRedirFn revert = k32
        ? (Wow64RevertFsRedirFn) GetProcAddress(k32, "Wow64RevertWow64FsRedirection") : NULL;
    PVOID old = NULL;
    bool disabled = disable && revert && disable(&old);
    bool exists = file_exists_plain(path);
    if (disabled) revert(old);
    return exists;
}

static int vc_redist_year(const std::string& text) {
    std::string t = text;
    for (char& c : t) c = (char) tolower((unsigned char) c);
    size_t at = t.find("vcredist");
    if (at == std::string::npos) at = t.find("vc_redist");
    if (at == std::string::npos) return 0;
    for (size_t i = at; i + 4 <= t.size(); ++i) {
        if (t[i] == '2' && t[i + 1] == '0' &&
            isdigit((unsigned char) t[i + 2]) && isdigit((unsigned char) t[i + 3])) {
            int year = atoi(t.substr(i, 4).c_str());
            if (year >= 2005 && year <= 2035) return year;
        }
    }
    return 0;
}

static bool vc_runtime_present(int year, const std::string& archHint, std::string& whereOut) {
    std::vector<const char*> dlls;
    if (year >= 2015)      dlls = { "msvcp140.dll", "vcruntime140.dll" };
    else if (year == 2013) dlls = { "msvcr120.dll", "msvcp120.dll" };
    else if (year == 2012) dlls = { "msvcr110.dll", "msvcp110.dll" };
    else if (year == 2010) dlls = { "msvcr100.dll", "msvcp100.dll" };
    else if (year == 2008) dlls = { "msvcr90.dll", "msvcp90.dll" };
    else if (year == 2005) dlls = { "msvcr80.dll", "msvcp80.dll" };
    else return false;

    std::string hint = archHint;
    for (char& c : hint) c = (char) tolower((unsigned char) c);
    const bool want32 = hint.find("x86") != std::string::npos &&
                        hint.find("x64") == std::string::npos &&
                        hint.find("amd64") == std::string::npos &&
                        hint.find("arm64") == std::string::npos;

    char winDir[MAX_PATH];
    if (!GetWindowsDirectoryA(winDir, MAX_PATH)) return false;
    std::string system32 = std::string(winDir) + "\\system32";

    if (want32) {
        char wow[MAX_PATH];
        wow[0] = 0;
        if (GetSystemWow64DirectoryA(wow, MAX_PATH) && wow[0]) {
            bool all = true;
            for (const char* dll : dlls) {
                if (!file_exists_plain(std::string(wow) + "\\" + dll)) { all = false; break; }
            }
            if (all) { whereOut = wow; return true; }
            return false;
        }
        bool all = true;
        for (const char* dll : dlls) {
            if (!file_exists_plain(system32 + "\\" + dll)) { all = false; break; }
        }
        if (all) { whereOut = system32; return true; }
        return false;
    }

    bool all = true;
    for (const char* dll : dlls) {
        if (!file_exists_unredirected(system32 + "\\" + dll)) { all = false; break; }
    }
    if (all) { whereOut = system32; return true; }
    std::string sysnative = std::string(winDir) + "\\sysnative";
    all = true;
    for (const char* dll : dlls) {
        if (!file_exists_plain(sysnative + "\\" + dll)) { all = false; break; }
    }
    if (all) { whereOut = sysnative; return true; }
    return false;
}

static bool is_vc_redist_installer_name(const std::string& nameRaw) {
    std::string n = nameRaw;
    for (char& c : n) c = (char) tolower((unsigned char) c);
    return n.find("vcredist") != std::string::npos || n.find("vc_redist") != std::string::npos;
}

static std::string expand_installdir(const std::string& in, const std::string& installDir) {
    std::string out;
    const char* token = "%INSTALLDIR%";
    size_t tokenLen = strlen(token);
    size_t pos = 0;
    for (;;) {
        size_t at = std::string::npos;
        for (size_t i = pos; i + tokenLen <= in.size(); ++i) {
            if (_strnicmp(in.c_str() + i, token, (int) tokenLen) == 0) { at = i; break; }
        }
        if (at == std::string::npos) { out.append(in, pos, std::string::npos); break; }
        out.append(in, pos, at - pos);
        out.append(installDir);
        pos = at + tokenLen;
    }
    return out;
}

static bool install_script_os_ok(const VdfNode& entry) {
    const VdfNode* req = entry.find("Requirement_OS");
    if (!req) return true;
    const VdfNode* win64 = req->find("Is64BitWindows");
    if (win64 && win64->value == "0") return false;
    return true;
}

static RedistInstallResult run_process_line(const std::string& exe, const std::string& args,
                                            DWORD* exitCodeOut) {
    std::string lower = exe;
    for (char& c : lower) c = (char) tolower((unsigned char) c);
    std::string cmd;
    if (lower.size() > 4 && lower.compare(lower.size() - 4, 4, ".cmd") == 0) {
        cmd = "cmd.exe /c \"\"" + exe + "\"";
        if (!args.empty()) cmd += " " + args;
        cmd += "\"";
    } else if (lower.size() > 4 && lower.compare(lower.size() - 4, 4, ".msi") == 0) {
        cmd = "msiexec.exe /i \"" + exe + "\" " + (args.empty() ? "/qn /norestart" : args);
    } else {
        cmd = "\"" + exe + "\"";
        if (!args.empty()) cmd += " " + args;
    }

    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    memset(&si, 0, sizeof(si));
    memset(&pi, 0, sizeof(pi));
    si.cb = sizeof(si);
    std::vector<char> mutableCmd(cmd.begin(), cmd.end());
    mutableCmd.push_back('\0');

    std::filesystem::path exePath(exe);
    std::string workDir = exePath.parent_path().string();

    if (!CreateProcessA(NULL, mutableCmd.data(), NULL, NULL, FALSE, 0, NULL,
                        workDir.empty() ? NULL : workDir.c_str(), &si, &pi)) {
        log_line("[wn-launcher] installscript: CreateProcess failed for %s (GLE=%lu)",
                 cmd.c_str(), GetLastError());
        return RedistInstallResult::FAILED;
    }
    DWORD wait = WaitForSingleObject(pi.hProcess, 180000);
    DWORD exitCode = 0;
    if (wait == WAIT_TIMEOUT) {
        TerminateProcess(pi.hProcess, 1);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        return RedistInstallResult::TIMED_OUT;
    }
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    if (exitCodeOut) *exitCodeOut = exitCode;
    if (exitCode == 0 || exitCode == 1638 || exitCode == 3010 || exitCode == 5100) {
        return RedistInstallResult::INSTALLED;
    }
    return RedistInstallResult::FAILED;
}

static void collect_install_scripts_at(const std::filesystem::path& dir,
                                       std::vector<std::filesystem::path>& out,
                                       int depth, bool insideRedist) {
    if (depth > 5) return;
    std::error_code ec;
    std::filesystem::directory_iterator it(dir,
            std::filesystem::directory_options::skip_permission_denied, ec);
    if (ec) return;
    for (const auto& e : it) {
        std::error_code isDirEc;
        if (e.is_directory(isDirEc)) {
            bool redisty = insideRedist || is_redisty_dir_name(e.path().filename().string());
            if (depth == 0 || redisty) {
                collect_install_scripts_at(e.path(), out, depth + 1, redisty);
            }
            continue;
        }
        std::string name = e.path().filename().string();
        for (char& c : name) c = (char) tolower((unsigned char) c);
        if (name.rfind("installscript", 0) == 0 &&
            name.size() > 4 && name.compare(name.size() - 4, 4, ".vdf") == 0) {
            out.push_back(e.path());
        }
    }
}

static void collect_install_scripts(const std::filesystem::path& root,
                                    std::vector<std::filesystem::path>& out) {
    collect_install_scripts_at(root, out, 0, false);
}

static int claim_entry_dirs(const VdfNode& entry, const std::string& scriptInstallDir,
                            std::vector<std::string>& handledDirs) {
    int claimed = 0;
    for (int idx = 1; idx <= 8; ++idx) {
        char pkey[32];
        snprintf(pkey, sizeof(pkey), "process %d", idx);
        const VdfNode* pn = entry.find(pkey);
        if (!pn || pn->value.empty()) break;
        std::string exe = expand_installdir(pn->value, scriptInstallDir);
        std::string parent = std::filesystem::path(exe).parent_path().string();
        if (parent.empty()) continue;
        bool known = false;
        for (const auto& d : handledDirs) {
            if (_stricmp(d.c_str(), parent.c_str()) == 0) { known = true; break; }
        }
        if (!known) { handledDirs.push_back(parent); claimed++; }
    }
    return claimed;
}

static void run_install_scripts(const char* gameRootDir, std::vector<std::string>& handledDirs) {
    if (!gameRootDir || !*gameRootDir) return;
    std::filesystem::path installDir(gameRootDir);
    if (installDir.empty()) return;

    std::vector<std::filesystem::path> scripts;
    collect_install_scripts(installDir, scripts);
    if (scripts.empty()) {
        log_line("[wn-launcher] installscript: none found under %s",
                 installDir.string().c_str());
        return;
    }
    log_line("[wn-launcher] installscript: %zu script(s) found", scripts.size());

    int ran = 0, already = 0, satisfied = 0, skippedOs = 0, failed = 0;
    for (const auto& scriptPath : scripts) {
        VdfNode root;
        if (!vdf_parse_file(scriptPath, root)) {
            log_line("[wn-launcher] installscript: could not parse %s",
                     scriptPath.string().c_str());
            continue;
        }
        const VdfNode* is = root.find("installscript");
        if (!is) continue;
        const VdfNode* rp = is->find("Run Process");
        if (!rp) continue;

        std::string scriptInstallDir = installDir.string();

        for (const auto& kv : rp->children) {
            const std::string& entryName = kv.first;
            const VdfNode& entry = kv.second;
            if (!entry.isBlock) continue;

            if (!install_script_os_ok(entry)) {
                skippedOs++;
                log_line("[wn-launcher] installscript: \"%s\" skipped (OS requirement)",
                         entryName.c_str());
                continue;
            }

            const VdfNode* hrk = entry.find("hasrunkey");
            std::string hasRunKey = hrk ? hrk->value : std::string();
            if (!hasRunKey.empty() && hasrunkey_is_set(hasRunKey, entryName)) {
                already++;
                int claimed = claim_entry_dirs(entry, scriptInstallDir, handledDirs);
                log_line("[wn-launcher] installscript: \"%s\" already recorded under %s — "
                         "skipping (claimed %d dir(s) so the redist scan will not rerun it)",
                         entryName.c_str(), hasRunKey.c_str(), claimed);
                continue;
            }

            const int vcYear = vc_redist_year(hasRunKey);
            std::string runtimeDir;
            if (vcYear && vc_runtime_present(vcYear, entryName, runtimeDir)) {
                satisfied++;
                int claimed = claim_entry_dirs(entry, scriptInstallDir, handledDirs);
                hasrunkey_mark(hasRunKey, entryName);
                log_line("[wn-launcher] installscript: \"%s\" satisfied — the prefix already "
                         "provides the Visual C++ %d runtime in %s; recorded under %s without "
                         "running the installer (claimed %d dir(s))",
                         entryName.c_str(), vcYear, runtimeDir.c_str(), hasRunKey.c_str(),
                         claimed);
                continue;
            }

            bool allOk = true;
            for (int idx = 1; idx <= 8; ++idx) {
                char pkey[32], ckey[32];
                snprintf(pkey, sizeof(pkey), "process %d", idx);
                snprintf(ckey, sizeof(ckey), "command %d", idx);
                const VdfNode* pn = entry.find(pkey);
                if (!pn || pn->value.empty()) break;
                const VdfNode* cn = entry.find(ckey);

                std::string exe = expand_installdir(pn->value, scriptInstallDir);
                std::string args = cn ? expand_installdir(cn->value, scriptInstallDir) : std::string();

                if (GetFileAttributesA(exe.c_str()) == INVALID_FILE_ATTRIBUTES) {
                    log_line("[wn-launcher] installscript: \"%s\" %s missing: %s",
                             entryName.c_str(), pkey, exe.c_str());
                    allOk = false;
                    break;
                }

                log_line("[wn-launcher] installscript: running \"%s\" %s -> %s %s",
                         entryName.c_str(), pkey, exe.c_str(), args.c_str());
                DWORD exitCode = 0;
                RedistInstallResult rc = run_process_line(exe, args, &exitCode);
                {
                    std::string parent = std::filesystem::path(exe).parent_path().string();
                    bool known = false;
                    for (const auto& d : handledDirs) {
                        if (_stricmp(d.c_str(), parent.c_str()) == 0) { known = true; break; }
                    }
                    if (!known) handledDirs.push_back(parent);
                }
                if (rc == RedistInstallResult::INSTALLED) {
                    log_line("[wn-launcher] installscript: \"%s\" %s OK exit=%lu",
                             entryName.c_str(), pkey, (unsigned long) exitCode);
                } else if (rc == RedistInstallResult::TIMED_OUT) {
                    log_line("[wn-launcher] installscript: \"%s\" %s timed out after 180s",
                             entryName.c_str(), pkey);
                    allOk = false;
                    break;
                } else {
                    log_line("[wn-launcher] installscript: \"%s\" %s FAILED exit=%lu",
                             entryName.c_str(), pkey, (unsigned long) exitCode);
                    allOk = false;
                    break;
                }
            }

            if (allOk) {
                ran++;
                if (!hasRunKey.empty()) {
                    hasrunkey_mark(hasRunKey, entryName);
                    log_line("[wn-launcher] installscript: \"%s\" recorded under %s",
                             entryName.c_str(), hasRunKey.c_str());
                }
            } else {
                failed++;
            }
        }
    }
    log_line("[wn-launcher] installscript done: ran %d, already-installed %d, "
             "satisfied-by-prefix %d, os-skipped %d, failed %d",
             ran, already, satisfied, skippedOs, failed);
}

static void scan_and_install_redists(const char* gameRootDir,
                                    const std::vector<std::string>& handledDirs) {
    if (!gameRootDir || !*gameRootDir) return;
    auto installers = collect_redist_installers(std::filesystem::path(gameRootDir));
    if (installers.empty()) {
        log_line("[wn-launcher] redist scan: none found");
        return;
    }

    std::vector<std::string> marker;
    load_installed_redists(marker);

    int installed = 0, skipped = 0, satisfied = 0, failedMarked = 0, timedOut = 0;
    for (const auto& installer : installers) {
        std::string abs = installer.string();
        if (marker_contains(marker, abs)) {
            skipped++;
            continue;
        }
        std::string parent = installer.parent_path().string();
        bool handledByScript = false;
        for (const auto& d : handledDirs) {
            if (_stricmp(d.c_str(), parent.c_str()) == 0) { handledByScript = true; break; }
        }
        if (handledByScript) {
            skipped++;
            log_line("[wn-launcher] redist scan: %s skipped — an installscript entry already "
                     "ran in %s", installer.filename().string().c_str(), parent.c_str());
            continue;
        }
        {
            const std::string fname = installer.filename().string();
            if (is_vc_redist_installer_name(fname)) {
                int year = vc_redist_year(abs);
                std::string lowerName = fname;
                for (char& c : lowerName) c = (char) tolower((unsigned char) c);
                if (!year && lowerName.find("vc_redist") != std::string::npos) year = 2015;
                std::string runtimeDir;
                if (year && vc_runtime_present(year, fname, runtimeDir)) {
                    marker.push_back(abs);
                    satisfied++;
                    log_line("[wn-launcher] redist scan: %s satisfied — the prefix already "
                             "provides the Visual C++ %d runtime in %s; marking done without "
                             "running it", fname.c_str(), year, runtimeDir.c_str());
                    continue;
                }
            }
        }
        DWORD exitCode = 0;
        log_line("[wn-launcher] redist install: %s%s",
                 installer.filename().string().c_str(),
                 redist_silent_args(installer).c_str());
        RedistInstallResult rc = run_redist_installer(installer, &exitCode);
        if (rc == RedistInstallResult::INSTALLED) {
            marker.push_back(abs);
            installed++;
            log_line("[wn-launcher] redist install: %s OK exit=%lu",
                     installer.filename().string().c_str(),
                     (unsigned long) exitCode);
        } else if (rc == RedistInstallResult::TIMED_OUT) {
            marker.push_back(abs);
            timedOut++;
            log_line("[wn-launcher] redist install: %s timed out — marking done "
                     "to avoid repeat hangs", installer.filename().string().c_str());
        } else {
            if (exitCode == 1638 || exitCode == 1603 || exitCode == 5100) {
                marker.push_back(abs);
                failedMarked++;
                log_line("[wn-launcher] redist install: %s exit=%lu — marking "
                         "done (already installed / not applicable)",
                         installer.filename().string().c_str(),
                         (unsigned long) exitCode);
            } else {
                log_line("[wn-launcher] redist install: %s FAILED exit=%lu",
                         installer.filename().string().c_str(),
                         (unsigned long) exitCode);
            }
        }
    }
    save_installed_redists(marker);
    log_line("[wn-launcher] redist scan done: installed %d, skipped %d, "
             "satisfied-by-prefix %d, failed-marked %d, timed-out-unmarked %d (of %zu total)",
             installed, skipped, satisfied, failedMarked, timedOut, installers.size());
}

int main(int argc, char** argv) {
    setbuf(stderr, NULL);
    setbuf(stdout, NULL);
    open_log();
    wn_launcher_set_log_sink(clean_shutdown_log_sink);
    log_line("[wn-launcher] build stamp: " __DATE__ " " __TIME__
             " (" WN_AGENT_ARCH ")");
    log_line("[wn-launcher] Steam Launcher in-process Steam launcher starting (pid=%lu tid=%lu)",
             (unsigned long) GetCurrentProcessId(),
             (unsigned long) GetCurrentThreadId());

    const char* appIdStr = getenv("WN_STEAM_APPID");
    const char* user     = getenv("WN_STEAM_USERNAME");
    const char* token    = getenv("WN_STEAM_TOKEN");
    uint64_t    steamId  = env_u64("WN_STEAM_STEAMID");
    uint32_t    appId    = appIdStr ? (uint32_t) strtoul(appIdStr, NULL, 10) : 0;

    const char* gameExe = NULL;
    static char gameExeBuf[1024];
    static char specAppBuf[64];
    const char* specSrc = (argc > 1) ? argv[1] : getenv("WN_STEAM_GAMEEXE_FILE");
    bool specIsFile = false;
    if (specSrc) {
        FILE* spec = fopen(specSrc, "r");
        if (spec) {
            if (fgets(gameExeBuf, sizeof(gameExeBuf), spec)) {
                size_t n = strlen(gameExeBuf);
                while (n && (gameExeBuf[n - 1] == '\n' || gameExeBuf[n - 1] == '\r')) gameExeBuf[--n] = 0;
                if (is_windows_path(gameExeBuf)) {
                    specIsFile = true;
                    gameExe = gameExeBuf;
                }
            }
            if (specIsFile && fgets(specAppBuf, sizeof(specAppBuf), spec)) {
                uint32_t specAppId = (uint32_t) strtoul(specAppBuf, NULL, 10);
                if (specAppId) appId = specAppId;
            }
            fclose(spec);
            if (specIsFile) {
                log_line("[wn-launcher] spec file %s -> exe=%s appId=%u",
                         specSrc, gameExe, appId);
            }
        }
    }
    if (!gameExe && argc > 1 && !specIsFile) gameExe = argv[1];

    log_line("[wn-launcher] env appId=%u steamId=%llu user=%s exe=%s",
             appId,
             (unsigned long long) steamId,
             user ? user : "(null)",
             gameExe ? gameExe : "(null)");
    if (token && *token) {
        size_t tokenLen = strlen(token);
        log_line("[wn-launcher] token len=%zu prefix=%.*s suffix=%.*s",
                 tokenLen, tokenLen > 16 ? 16 : (int) tokenLen, token,
                 tokenLen > 12 ? 12 : (int) tokenLen,
                 tokenLen > 12 ? token + tokenLen - 12 : token);
        log_token_claims(token);
    } else {
        log_line("[wn-launcher] token missing");
    }
    if (!gameExe || !*gameExe) {
        log_line("[wn-launcher] no game exe from argv[1], spec file or WN_STEAM_GAMEEXE_FILE");
        return 1;
    }

    const char* exeName = strrchr(gameExe, '\\');
    exeName = exeName ? exeName + 1 : gameExe;

    static char gameRootDir[MAX_PATH];
    resolve_game_root_dir(gameExe, gameRootDir, sizeof(gameRootDir));
    wn_launcher_set_game_exe(exeName);
    wn_launcher_set_game_dir(gameRootDir);
    {
        char stem[260];
        wn_game_exe_stem(exeName, stem, sizeof(stem));
        log_line("[wn-launcher] game identity: exe=\"%s\" stem=\"%s\" root=\"%s\"",
                 exeName, stem, gameRootDir[0] ? gameRootDir : "(none)");
    }
    std::thread steamApiScanThread;
    {
        static char scanRoot[MAX_PATH];
        snprintf(scanRoot, sizeof(scanRoot), "%s", gameRootDir);
        log_line("[wn-launcher] steam_api: scan started on background thread "
                 "(overlaps steamclient load + logon)");
        steamApiScanThread = std::thread([]() { verify_game_steam_api(scanRoot); });
    }

    const char* kSteamDir = "C:\\Program Files (x86)\\Steam";
    SetDllDirectoryA(kSteamDir);
    SetCurrentDirectoryA(kSteamDir);
    SetEnvironmentVariableA("SteamPath", kSteamDir);
    SetEnvironmentVariableA("SteamGameId", appIdStr ? appIdStr : "0");
    SetEnvironmentVariableA("SteamAppId",  appIdStr ? appIdStr : "0");
    SetEnvironmentVariableA("SteamUser",   user ? user : "");
    SetEnvironmentVariableA("Steam3Master", "127.0.0.1:27036");
    SetEnvironmentVariableA("SteamClientLaunch", "1");
    SetEnvironmentVariableA("SteamNoOverlayUIDrawing", "1");

    CreateDirectoryA("C:\\Program Files (x86)", NULL);
    CreateDirectoryA(kSteamDir, NULL);

    stage_steam_config();
    seed_active_process_registry(GetCurrentProcessId(), (uint32_t)(steamId & 0xFFFFFFFFu));
    stage_app_manifest(appId, gameExe);

#ifdef __i386__
    const char* preloadDlls[] = {
        "tier0_s.dll",
        "vstdlib_s.dll",
        "crashhandler.dll",
        "steamservice.dll",
    };
#else
    const char* preloadDlls[] = {
        "tier0_s64.dll",
        "vstdlib_s64.dll",
        "crashhandler64.dll",
        "steamservice.dll",
    };
#endif
    for (const char* dll : preloadDlls) {
        char path[MAX_PATH];
        snprintf(path, sizeof(path), "%s\\%s", kSteamDir, dll);
        HMODULE dm = LoadLibraryExA(path, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
        if (dm) {
            log_line("[wn-launcher] preload %s: ok (%p)", dll, dm);
        } else {
            log_line("[wn-launcher] preload %s: FAIL GLE=%lu", dll, GetLastError());
        }
    }

    log_line("[wn-launcher] preloads done; installing unhandled-exception filter");
    LPTOP_LEVEL_EXCEPTION_FILTER prevFilter =
        SetUnhandledExceptionFilter(launcher_unhandled_filter);
    log_line("[wn-launcher] UEF installed (prev=%p)", prevFilter);
    dump_loaded_modules("pre-LoadLibrary");

    char steamclientPath[MAX_PATH];
    snprintf(steamclientPath, sizeof(steamclientPath),
             "%s\\%s", kSteamDir, kSteamClientDll);

    struct LoadAttempt { DWORD flags; const char* desc; };
    const LoadAttempt attempts[] = {
        { LOAD_WITH_ALTERED_SEARCH_PATH, "LOAD_WITH_ALTERED_SEARCH_PATH" },
        { LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS,
          "DLL_LOAD_DIR|DEFAULT_DIRS" },
        { LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32,
          "DLL_LOAD_DIR|SYSTEM32" },
        { LOAD_IGNORE_CODE_AUTHZ_LEVEL | LOAD_WITH_ALTERED_SEARCH_PATH,
          "IGNORE_CODE_AUTHZ|ALTERED_SEARCH_PATH" },
    };

    const int kAttempts = (int)(sizeof(attempts) / sizeof(attempts[0]));
    HMODULE lsc = NULL;
    DWORD lastErr = 0;
    for (int i = 0; i < kAttempts && !lsc; i++) {
        lsc = LoadLibraryExA(steamclientPath, NULL, attempts[i].flags);
        if (lsc) {
            log_line("[wn-launcher] %s loaded at %p "
                     "(strategy %d/%d: %s)",
                     kSteamClientDll, lsc, i + 1, kAttempts, attempts[i].desc);
            break;
        }
        lastErr = GetLastError();
        log_line("[wn-launcher] steamclient64.dll load fail strategy %d/%d (%s) "
                 "GLE=%lu",
                 i + 1, kAttempts, attempts[i].desc, lastErr);
        Sleep(50);
    }
    for (int round = 0; round < 3 && !lsc; round++) {
        log_line("[wn-launcher] steamclient64.dll cold-start retry "
                 "round %d/3 after 500ms", round + 1);
        Sleep(500);
        for (int i = 0; i < kAttempts && !lsc; i++) {
            lsc = LoadLibraryExA(steamclientPath, NULL, attempts[i].flags);
            if (!lsc) lastErr = GetLastError();
        }
        if (lsc) {
            log_line("[wn-launcher] %s loaded at %p "
                     "(retry round %d)", kSteamClientDll, lsc, round + 1);
        }
    }
    if (!lsc) {
        lsc = LoadLibraryA(steamclientPath);
        if (lsc) {
            log_line("[wn-launcher] %s loaded at %p "
                     "(plain LoadLibraryA)", kSteamClientDll, lsc);
        } else {
            lastErr = GetLastError();
        }
    }
    if (!lsc) {
        HMODULE probe = LoadLibraryExA(steamclientPath, NULL,
                                        LOAD_LIBRARY_AS_DATAFILE);
        if (probe) {
            log_line("[wn-launcher] diag: DATAFILE load OK — file is "
                     "well-formed; failure is in DllMain/runtime init");
        } else {
            log_line("[wn-launcher] diag: DATAFILE load also FAILED, GLE=%lu",
                     GetLastError());
        }
        log_line("[wn-launcher] LoadLibrary(%s) FAILED after all strategies, "
                 "last GLE=%lu", steamclientPath, lastErr);
        return 2;
    }

    CreateInterfaceFn createInterface =
        (CreateInterfaceFn) GetProcAddress(lsc, "CreateInterface");
    Steam_CreateGlobalUser_fn createGlobalUser =
        (Steam_CreateGlobalUser_fn) GetProcAddress(lsc, "Steam_CreateGlobalUser");
    Steam_BLoggedOn_fn bLoggedOn =
        (Steam_BLoggedOn_fn) GetProcAddress(lsc, "Steam_BLoggedOn");
    Steam_BGetCallback_fn bGetCallback =
        (Steam_BGetCallback_fn) GetProcAddress(lsc, "Steam_BGetCallback");
    Steam_FreeLastCallback_fn freeLastCallback =
        (Steam_FreeLastCallback_fn) GetProcAddress(lsc, "Steam_FreeLastCallback");
    Breakpad_SteamSetAppID_fn breakpadSetAppId =
        (Breakpad_SteamSetAppID_fn) GetProcAddress(lsc, "Breakpad_SteamSetAppID");

    log_line("[wn-launcher] exports CreateInterface=%p CreateGlobalUser=%p "
             "BLoggedOn=%p BGetCallback=%p FreeLastCallback=%p Breakpad=%p",
             (void*) createInterface, (void*) createGlobalUser, (void*) bLoggedOn,
             (void*) bGetCallback, (void*) freeLastCallback, (void*) breakpadSetAppId);

    if (!createInterface || !createGlobalUser) {
        log_line("[wn-launcher] required steamclient exports missing");
        return 3;
    }

    if (breakpadSetAppId && appId != 0) {
        breakpadSetAppId(appId);
        log_line("[wn-launcher] Breakpad_SteamSetAppID(%u)", appId);
    }

    int retCode = 0;
    void* engine = createInterface("CLIENTENGINE_INTERFACE_VERSION005", &retCode);
    log_line("[wn-launcher] CreateInterface(CLIENTENGINE_INTERFACE_VERSION005) -> %p rc=%d",
             engine, retCode);
    if (!engine) {
        engine = createInterface("CLIENTENGINE_INTERFACE_VERSION004", &retCode);
        log_line("[wn-launcher] CreateInterface(CLIENTENGINE_INTERFACE_VERSION004) -> %p rc=%d",
                 engine, retCode);
    }
    if (!engine) {
        log_line("[wn-launcher] failed to acquire IClientEngine");
        return 4;
    }

    int pipe = 0;
    int hUser = createGlobalUser(&pipe);
    log_line("[wn-launcher] Steam_CreateGlobalUser -> pipe=%d user=%d",
             pipe, hUser);
    if (pipe == 0 || hUser == 0) {
        log_line("[wn-launcher] invalid pipe/user from Steam_CreateGlobalUser");
        return 5;
    }

    if (user && *user && token && *token && steamId != 0) {
        void** engine_vt = *(void***) engine;
        typedef void* (WN_THISCALL *GetIClientUserFn)(void* self, int hUser, int hPipe);
        GetIClientUserFn getIClientUser = (GetIClientUserFn)
            engine_vt[kVtEngine_GetIClientUser];
        void* iuser = getIClientUser(engine, hUser, pipe);
        log_line("[wn-launcher] IClientEngine.GetIClientUser -> %p", iuser);
        if (iuser) {
            void** iuser_vt = *(void***) iuser;
            if (is_exec_ptr(iuser_vt[kVtUser_BHasCachedCreds])) {
                typedef bool (WN_THISCALL *HasCachedCredsFn)(void* self, const char*);
                HasCachedCredsFn hasCachedCreds = (HasCachedCredsFn)
                    iuser_vt[kVtUser_BHasCachedCreds];
                bool cached = hasCachedCreds(iuser, user);
                log_line("[wn-launcher] BHasCachedCredentials(%s) -> %d", user, cached ? 1 : 0);
            }
            if (is_exec_ptr(iuser_vt[kVtUser_SetLoginToken])) {
                typedef int (WN_THISCALL *SetLoginTokenFn)(void* self, const char* token,
                                               const char* account);
                SetLoginTokenFn setLoginToken = (SetLoginTokenFn)
                    iuser_vt[kVtUser_SetLoginToken];
                int tokRc = setLoginToken(iuser, token, user);
                log_line("[wn-launcher] SetLoginToken(tokenLen=%d, account=%s) -> %d",
                         (int) strlen(token), user, tokRc);

                typedef void* (WN_THISCALL *GetSteamIDFn)(void* self, void* outBuf);
                GetSteamIDFn getSteamID = (GetSteamIDFn)
                    iuser_vt[kVtUser_GetSteamID];
                uint64_t outSid = 0;
                void* sidRet = getSteamID(iuser, &outSid);
                uint64_t logonSid = outSid;
                if (logonSid == 0 && sidRet) logonSid = *(uint64_t*) sidRet;
                if (logonSid == 0) {
                    logonSid = steamId;  // fall back to the env-supplied SteamID
                    log_line("[wn-launcher] GetSteamID returned 0 — falling back "
                             "to env steamId=%llu", (unsigned long long) steamId);
                } else {
                    log_line("[wn-launcher] GetSteamID -> %llu (env steamId=%llu)",
                             (unsigned long long) logonSid,
                             (unsigned long long) steamId);
                }

                typedef int (WN_THISCALL *LogOnFn)(void* self, uint64_t steamID);
                LogOnFn logOn = (LogOnFn) iuser_vt[kVtUser_LogOn];
                int logonRc = logOn(iuser, logonSid);
                log_line("[wn-launcher] LogOn(%llu) -> EResult=%d "
                         "(1=OK 5=InvalidPassword 15=AccessDenied 16=Timeout 84=RateLimit)",
                         (unsigned long long) logonSid, logonRc);
                if (logonRc == 15) {
                    log_line("[wn-launcher] WARNING: LogOn returned AccessDenied "
                             "synchronously — credentials rejected pre-network");
                }
            }
        }
    } else {
        log_line("[wn-launcher] no creds — skipping refresh-token logon "
                 "(game may run in offline / no-auth mode)");
    }

    bool loggedOn = false;
    bool cleanShutdownArmed = false;
    bool sawConnected = false, sawConnFail = false;
    int  connFailEResult = 0;
    int  polls = 0;
    if (bLoggedOn) {
        const int kMaxPolls = 600;  // 600 * 100ms = 60s
        char cbBuf[64] = {0};
        for (; polls < kMaxPolls; ++polls) {
            if (bGetCallback && freeLastCallback) {
                while (bGetCallback(pipe, cbBuf)) {
                    int cbId = *(int*)(cbBuf + 4);
                    void* param = *(void**)(cbBuf + 8);
                    if (cbId == 101) {
                        sawConnected = true;
                        log_line("[wn-launcher] callback 101 SteamServersConnected");
                    } else if (cbId == 102) {
                        sawConnFail = true;
                        int er = param ? *(int*)param : -1;
                        connFailEResult = er;
                        log_line("[wn-launcher] callback 102 SteamServerConnectFailure "
                                 "EResult=%d (3=NoConnection 5=InvalidPassword "
                                 "15=AccessDenied 16=Timeout 84=RateLimit)", er);
                    } else if (cbId == 103) {
                        int er = param ? *(int*)param : -1;
                        log_line("[wn-launcher] callback 103 SteamServersDisconnected "
                                 "EResult=%d", er);
                    } else {
                        log_line("[wn-launcher] callback id=%d drained", cbId);
                    }
                    freeLastCallback(pipe);
                }
            }
            if (bLoggedOn(pipe, hUser)) {
                loggedOn = true;
                log_line("[wn-launcher] Steam_BLoggedOn=true after %dx100ms",
                         polls + 1);
                wn_launcher_arm_clean_shutdown(lsc, pipe, hUser, "C:\\wn-launcher.log");
                cleanShutdownArmed = true;
                break;
            }
            if (sawConnFail && (connFailEResult == 5 ||
                                connFailEResult == 15 ||
                                connFailEResult == 84)) {
                log_line("[wn-launcher] hard auth failure (EResult=%d) — "
                         "skipping remaining logon wait", connFailEResult);
                break;
            }
            Sleep(100);
        }
    }
    if (!loggedOn) {
        log_line("[wn-launcher] WARNING: Steam_BLoggedOn not true after %dx100ms "
                 "(sawConnected=%d sawConnFail=%d) — proceeding with game launch "
                 "anyway (game may end up in offline mode)",
                 polls, sawConnected ? 1 : 0, sawConnFail ? 1 : 0);
    }

    if (loggedOn && engine && appId != 0) {
        sync_app_ownership(engine, hUser, pipe, appId, bGetCallback, freeLastCallback);
        prewarm_encrypted_app_ticket(engine, hUser, pipe, appId, bGetCallback,
                                     freeLastCallback);
    }


    const char* skipAppInfoEnv = getenv("WN_STEAM_SKIP_APPINFO");
    const bool skipAppInfo = skipAppInfoEnv && skipAppInfoEnv[0] != '\0';
    if (skipAppInfo) {
        log_line("[wn-launcher] WN_STEAM_SKIP_APPINFO set — not refreshing appinfo "
                 "(LaunchApp will use whatever the client already has)");
    }

    if (loggedOn && engine && appId != 0 && !skipAppInfo) {
        void** engine_vt = *(void***) engine;
        typedef void* (WN_THISCALL *GetIClientAppsFn)(void* self, int hUser, int hPipe);
        GetIClientAppsFn getApps = (GetIClientAppsFn)
            engine_vt[kVtEngine_GetIClientApps];
        void* iApps = getApps(engine, hUser, pipe);
        log_line("[wn-launcher] IClientEngine.GetIClientApps -> %p", iApps);
        log_iface_vtable("IClientApps", iApps);
        if (iApps) {
            void** apps_vt = *(void***) iApps;
            void* reqP = apps_vt[kVtApps_RequestAppInfoUpdate];
            if (!is_exec_ptr(reqP)) {
                log_line("[wn-launcher] RequestAppInfoUpdate slot not executable — "
                         "skipping appinfo refresh");
            } else {
                typedef bool (WN_THISCALL *RequestAppInfoUpdateFn)(void* self,
                                                       uint32_t* appIds, int count);
                RequestAppInfoUpdateFn reqInfo = (RequestAppInfoUpdateFn) reqP;
                uint32_t appIds[1] = { appId };
                bool reqRc = reqInfo(iApps, appIds, 1);
                log_line("[wn-launcher] RequestAppInfoUpdate(appId=%u) -> %d",
                         appId, reqRc ? 1 : 0);
                char missMarker[MAX_PATH];
                snprintf(missMarker, sizeof(missMarker),
                         "C:\\wn-appinfo-miss-%u.txt", appId);
                const bool missedBefore =
                    GetFileAttributesA(missMarker) != INVALID_FILE_ATTRIBUTES;
                const int appInfoWaitMs =
                    env_int("WN_STEAM_APPINFO_WAIT_MS",
                            missedBefore ? kAppInfoWaitMsAfterMiss : kAppInfoWaitMsDefault);
                if (missedBefore) {
                    log_line("[wn-launcher] appinfo: this app never delivered "
                             "AppInfoUpdateComplete_t before — waiting only %dms "
                             "(LaunchApp retries on MissingConfig anyway)", appInfoWaitMs);
                }
                bool appInfoDone = false;
                int  waited = 0;
                while (!appInfoDone && waited < appInfoWaitMs) {
                    if (bGetCallback && freeLastCallback) {
                        char cb[64];
                        while (bGetCallback(pipe, cb)) {
                            if (*(int*)(cb + 4) == 1003) appInfoDone = true;
                            freeLastCallback(pipe);
                        }
                    }
                    if (appInfoDone) break;
                    Sleep(100);
                    waited += 100;
                }
                log_line("[wn-launcher] AppInfoUpdateComplete_t %s after %dms",
                         appInfoDone ? "received" : "NOT received", waited);
                if (appInfoDone) {
                    DeleteFileA(missMarker);
                } else if (!missedBefore) {
                    HANDLE mh = CreateFileA(missMarker, GENERIC_WRITE, 0, NULL,
                                            CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
                    if (mh != INVALID_HANDLE_VALUE) CloseHandle(mh);
                }
            }
        }
    }

    if (loggedOn && engine && appId != 0) {
        void** engine_vt = *(void***) engine;
        typedef void* (WN_THISCALL *GetIfaceFn)(void* self, int hUser, int hPipe);
        void* appMgr = ((GetIfaceFn) engine_vt[kVtEngine_GetIClientAppManager])
                           (engine, hUser, pipe);
        log_line("[wn-launcher] readiness: IClientAppManager=%p", appMgr);
        log_iface_vtable("IClientAppManager", appMgr);

        if (appMgr) {
            void** am_vt = *(void***) appMgr;
            void* stateP   = am_vt[kVtAppMgr_GetAppInstallState];
            log_line("[wn-launcher] readiness: stateP=%p (index %d)",
                     stateP, kVtAppMgr_GetAppInstallState);
            if (is_exec_ptr(stateP)) {
                log_line("[wn-launcher] readiness: calling GetAppInstallState(%u)", appId);
                typedef int (WN_THISCALL *GetAppInstallStateFn)(void* self, uint32_t app);
                GetAppInstallStateFn getInstallState = (GetAppInstallStateFn) stateP;
                // 2s — stage_app_manifest already wrote StateFlags=4, so this
                // usually returns FullyInstalled at once; loop absorbs a slow re-parse.
                int st = 0;
                for (int i = 0; i < 20; ++i) {
                    st = getInstallState(appMgr, appId);
                    if (st & 4) break;
                    if (bGetCallback && freeLastCallback) {
                        char cb[64];
                        while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
                    }
                    Sleep(100);
                }
                log_line("[wn-launcher] GetAppInstallState(appId=%u) = 0x%x (%s)",
                         appId, st,
                         (st & 4) ? "FullyInstalled"
                                  : "NOT installed — LaunchApp may no-op");
            }
            void* upToDateP = am_vt[kVtAppMgr_BIsAppUpToDate];
            if (is_exec_ptr(upToDateP)) {
                typedef bool (WN_THISCALL *BIsAppUpToDateFn)(void* self, uint32_t app);
                bool upToDate = ((BIsAppUpToDateFn) upToDateP)(appMgr, appId);
                log_line("[wn-launcher] BIsAppUpToDate(appId=%u) = %d (%s)",
                         appId, upToDate ? 1 : 0,
                         upToDate ? "installed build is current"
                                  : "Steam considers this install OUT OF DATE");
            } else {
                log_line("[wn-launcher] BIsAppUpToDate slot not executable — skipping");
            }
        }
    }

    std::vector<std::string> installScriptDirs;
    run_install_scripts(gameRootDir, installScriptDirs);
    scan_and_install_redists(gameRootDir, installScriptDirs);

    bool svcRunning = start_steam_client_service();
    log_line("[wn-launcher] steamservice running: %d", svcRunning ? 1 : 0);

    // Pull cloud saves + set the teardown cloud context now, so the exit upload
    // has a baseline to diff.
    const bool agentCloud = env_int("WN_STEAM_AGENT_CLOUD", 1) != 0;
    if (loggedOn && engine && appId != 0 && agentCloud) {
        wn_launcher_set_cloud_context(engine, hUser, pipe, appId);
        wn_launcher_cloud_run(engine, hUser, pipe, appId, 0, 120000);
    } else if (loggedOn && engine && appId != 0) {
        log_line("[wn-launcher] cloud: agent-side sync disabled "
                 "(RemoteStorage vtable slots unvalidated on this steamclient build); "
                 "the app handles Steam Cloud");
    }

    if (steamApiScanThread.joinable()) {
        DWORD joinT0 = GetTickCount();
        steamApiScanThread.join();
        log_line("[wn-launcher] steam_api: background scan joined (blocked %lums)",
                 (unsigned long)(GetTickCount() - joinT0));
    }

    bool launchedViaApp = false;
    bool blockedAbort = false;
    bool probeOnly = false;
    bool launchedViaFallback = false;
    const char* launchFailureReason = "LaunchApp path unavailable";

    // User override: skip LaunchApp (it would spawn the app's configured entry, not the chosen exe) and CreateProcess the selected exe directly; the Steam session is already up.
    const char* directExeEnv = getenv("WN_STEAM_DIRECT_EXE");
    const bool directExe = directExeEnv && directExeEnv[0] != '\0';

    if (directExe) {
        log_line("[wn-launcher] WN_STEAM_DIRECT_EXE set — user-selected exe \"%s\"; "
                 "skipping Steam LaunchApp, launching directly via CreateProcess",
                 exeName);
        launchFailureReason = "direct-exe mode (LaunchApp skipped by override)";
    } else if (engine && appId != 0) {
        void** engine_vt = *(void***) engine;
        typedef void* (WN_THISCALL *GetIClientAppManagerFn)(void* self, int hUser, int hPipe);
        GetIClientAppManagerFn getAppMgr = (GetIClientAppManagerFn)
            engine_vt[kVtEngine_GetIClientAppManager];
        void* appMgr = getAppMgr(engine, hUser, pipe);
        log_line("[wn-launcher] IClientEngine.GetIClientAppManager -> %p", appMgr);
        if (appMgr) {
            void** appMgr_vt = *(void***) appMgr;
#ifdef __i386__
            typedef uint64_t (WN_THISCALL *LaunchAppFn)(void* self, uint64_t gameId,
                                            uint32_t uLaunchOption,
                                            uint32_t eLaunchSource,
                                            const char* pszUserArgs);
#else
            typedef uint64_t (WN_THISCALL *LaunchAppFn)(void* self, void* pGameId,
                                            uint32_t uLaunchOption,
                                            uint32_t eLaunchSource,
                                            const char* pszUserArgs);
#endif
            LaunchAppFn launchApp = (LaunchAppFn)
                appMgr_vt[kVtAppMgr_LaunchApp];
            uint64_t gameId = (uint64_t)(appId & 0xFFFFFFu);

            typedef void* (WN_THISCALL *GetIfaceFn2)(void* self, int hUser, int hPipe);
            void* retryApps = ((GetIfaceFn2) engine_vt[kVtEngine_GetIClientApps])
                                  (engine, hUser, pipe);
            void* requestAppInfoP = retryApps
                ? (*(void***) retryApps)[kVtApps_RequestAppInfoUpdate] : NULL;

            const int kMaxLaunchAttempts = 5;
            const int kSecureAppearSeconds = 120;
            const int kEAppUpdateErrorLaunchOptionMissing = 22;
            int launchOptions[8];
            int launchOptionCount = 0;
            probeOnly = run_iface_probe(gameRootDir, appId);
            if (probeOnly) {
                log_line("[wn-launcher] iface-probe ran and is now registered with Steam as "
                         "appId %u, so LaunchApp would be refused with \"Application running\" — "
                         "skipping the launch entirely. Delete C:\\wn-iface-probe.on for a normal "
                         "launch.", appId);
                launchFailureReason = "iface-probe mode (probe holds the app registration)";
            }

            void* blockUser = ((GetIfaceFn2) engine_vt[kVtEngine_GetIClientUser])
                                  (engine, hUser, pipe);
            {
                uint32_t blockingPid = 0;
                int runningCount = 0;
                uint32_t blockingAppId =
                    query_running_game(blockUser, &blockingPid, &runningCount);
                uint32_t otherAppId = 0;
                if (runningCount > 0) {
                    write_blocked_request("running", blockingAppId, appId, blockingPid);
                    if (wait_blocked_answer(kBlockedAnswerWaitMs, bGetCallback,
                                            freeLastCallback, pipe)) {
                        if (!clear_running_game(engine, blockUser, appMgr, blockingAppId,
                                                bGetCallback, freeLastCallback, pipe)) {
                            launchFailureReason =
                                "Steam kept the previous game registered";
                        }
                    } else {
                        launchFailureReason = "user declined to stop the running game";
                        blockedAbort = true;
                    }
                } else if (query_other_session(blockUser, &otherAppId)) {
                    write_blocked_request("othersession", otherAppId, appId, 0);
                    if (wait_blocked_answer(kBlockedAnswerWaitMs, bGetCallback,
                                            freeLastCallback, pipe)) {
                        if (!clear_other_session(blockUser, bGetCallback,
                                                 freeLastCallback, pipe)) {
                            launchFailureReason = "the other Steam session kept playing";
                        }
                    } else {
                        launchFailureReason = "user declined to stop the other Steam session";
                        blockedAbort = true;
                    }
                }
            }

            const int preferredOption = env_int_signed("WN_STEAM_LAUNCH_OPTION", 0);
            launchOptions[launchOptionCount++] = preferredOption < 0 ? 0 : preferredOption;
            for (int cand = 0; cand <= 6 && launchOptionCount < 8; ++cand) {
                bool dup = false;
                for (int k = 0; k < launchOptionCount; ++k) {
                    if (launchOptions[k] == cand) { dup = true; break; }
                }
                if (!dup) launchOptions[launchOptionCount++] = cand;
            }
            int launchOptionIndex = 0;
            log_line("[wn-launcher] LaunchApp: preferred launch option %d (from %s)",
                     launchOptions[0],
                     getenv("WN_STEAM_LAUNCH_OPTION") ? "WN_STEAM_LAUNCH_OPTION" : "default");
            const int kErrorAppearSeconds = 30;
            for (int attempt = 1;
                 attempt <= kMaxLaunchAttempts && !launchedViaApp && !blockedAbort
                     && !probeOnly;
                 ++attempt) {
                const int launchOption = launchOptions[launchOptionIndex];
                const char* userArgsEnv = getenv("WN_STEAM_USER_ARGS");
                const char* userArgs = (userArgsEnv && *userArgsEnv) ? userArgsEnv : "";
#ifdef __i386__
                uint64_t apiCall = launchApp(appMgr, gameId, (uint32_t) launchOption, 300, userArgs);
#else
                uint64_t apiCall = launchApp(appMgr, &gameId, (uint32_t) launchOption, 300, userArgs);
#endif
                log_line("[wn-launcher] IClientAppManager.LaunchApp(appId=%u launchOption=%d "
                         "userArgs=\"%s\") attempt=%d/%d -> HSteamAPICall=0x%llx",
                         appId, launchOption, userArgs,
                         attempt, kMaxLaunchAttempts,
                         (unsigned long long) apiCall);

            int eAppError = -1;  // -1 = not polled / unknown
            if (apiCall != 0) {
                typedef void* (WN_THISCALL *GetIClientUtilsFn)(void* self, int hPipe);
                GetIClientUtilsFn getUtils = (GetIClientUtilsFn)
                    engine_vt[kVtEngine_GetIClientUtils];
                void* utils = getUtils(engine, pipe);
                log_line("[wn-launcher] IClientEngine.GetIClientUtils -> %p", utils);
                if (utils) {
                    void** utils_vt = *(void***) utils;
                    void* isCompletedP = utils_vt[kVtUtils_IsAPICallCompleted];
                    void* getResultP   = utils_vt[kVtUtils_GetAPICallResult];
                    void* getReasonP   = utils_vt[kVtUtils_GetAPICallFailureReason];
                    log_line("[wn-launcher] utils vt IsAPICallCompleted=%p "
                             "GetAPICallFailureReason=%p GetAPICallResult=%p",
                             isCompletedP, getReasonP, getResultP);
                    if (is_exec_ptr(isCompletedP) && is_exec_ptr(getResultP)) {
                        typedef bool (WN_THISCALL *IsAPICallCompletedFn)(void* self,
                                                       uint64_t apiCall, bool* pbFailed);
                        typedef int  (WN_THISCALL *GetFailureReasonFn)(void* self,
                                                       uint64_t apiCall);
                        typedef bool (WN_THISCALL *GetAPICallResultFn)(void* self,
                                                       uint64_t apiCall, void* pCb,
                                                       int cubCb, int iCbExpected,
                                                       bool* pbFailed);
                        IsAPICallCompletedFn isCompleted = (IsAPICallCompletedFn) isCompletedP;
                        GetFailureReasonFn   getReason   = (GetFailureReasonFn) getReasonP;
                        GetAPICallResultFn   getResult   = (GetAPICallResultFn) getResultP;

                        const int kPollMaxMs = 10000;
                        int  waited = 0;
                        bool failed = false;
                        bool completed = false;
                        while (waited < kPollMaxMs) {
                            failed = false;
                            completed = isCompleted(utils, apiCall, &failed);
                            if (completed) break;
                            if (bGetCallback && freeLastCallback) {
                                char cb[64];
                                while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
                            }
                            Sleep(100);
                            waited += 100;
                        }
                        if (!completed) {
                            log_line("[wn-launcher] LaunchApp poll: TIMED OUT "
                                     "after %dms — job still pending", waited);
                        } else if (failed) {
                            int reason = is_exec_ptr(getReasonP) ? getReason(utils, apiCall) : -99;
                            log_line("[wn-launcher] LaunchApp poll: API CALL FAILED "
                                     "after %dms, reason=%d "
                                     "(-1=NoFailure 0=SteamGone 1=NetworkFailure "
                                     "2=InvalidHandle 3=MismatchedCallback)",
                                     waited, reason);
                        } else {
                            unsigned char buf[kLaunchAppResultSize];
                            memset(buf, 0, sizeof(buf));
                            bool resFailed = false;
                            bool got = getResult(utils, apiCall, buf,
                                                  kLaunchAppResultSize,
                                                  kLaunchAppResultCallbackId,
                                                  &resFailed);
                            eAppError = *(int*)(buf + kLaunchResultErrorOffset);
                            log_line("[wn-launcher] LaunchApp poll: COMPLETED in %dms "
                                     "got=%d resFailed=%d EAppUpdateError=%d (%s)",
                                     waited, got ? 1 : 0, resFailed ? 1 : 0, eAppError,
                                     eapp_update_error_name(eAppError));
                            char hex[3 * 32 + 1];
                            int hp = 0;
                            for (int i = 0; i < 32; ++i) {
                                hp += snprintf(hex + hp, sizeof(hex) - hp, "%02x ", buf[i]);
                            }
                            log_line("[wn-launcher] LaunchApp result hex+0..32: %s", hex);
                        }
                    } else {
                        log_line("[wn-launcher] LaunchApp poll: IClientUtils vtable "
                                 "slots not executable — skipping poll");
                    }
                }
            }

            if (apiCall == 0) {
                if (attempt < kMaxLaunchAttempts) {
                    log_line("[wn-launcher] LaunchApp attempt %d/%d: \"%s\" never "
                             "appeared — null call handle, retrying LaunchApp",
                             attempt, kMaxLaunchAttempts, exeName);
                    Sleep(500);
                } else {
                    log_line("[wn-launcher] LaunchApp returned a null call handle "
                             "after %d attempts — waiting %ds in case the request "
                             "still landed", kMaxLaunchAttempts, kErrorAppearSeconds);
                    launchFailureReason = "LaunchApp returned a null call handle";
                    launchedViaApp = wait_for_game_process(
                        exeName, kErrorAppearSeconds, "null-handle grace",
                        bGetCallback, freeLastCallback, pipe, blockUser);
                }
                continue;
            }

            if (eAppError == 9 /* MissingConfig */) {
                // appinfo not landed — re-prime, settle, retry fast (nothing launched).
                // "never appeared … retrying" wording disarms the Android watchdog.
                if (retryApps && is_exec_ptr(requestAppInfoP)) {
                    typedef bool (WN_THISCALL *RequestAppInfoUpdateFn)(void* self,
                                                       uint32_t* appIds, int count);
                    uint32_t retryIds[1] = { appId };
                    bool retryRc = ((RequestAppInfoUpdateFn) requestAppInfoP)
                                       (retryApps, retryIds, 1);
                    log_line("[wn-launcher] MissingConfig: RequestAppInfoUpdate(appId=%u) -> %d",
                             appId, retryRc ? 1 : 0);
                }
                log_line("[wn-launcher] LaunchApp attempt %d/%d: \"%s\" never "
                         "appeared — MissingConfig (appinfo not ready); refreshed "
                         "appinfo, retrying LaunchApp", attempt,
                         kMaxLaunchAttempts, exeName);
                for (int w = 0; w < 30; ++w) {  // ~3s of callback pumping
                    if (bGetCallback && freeLastCallback) {
                        char cb[64];
                        while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
                    }
                    Sleep(100);
                }
            } else if (eAppError == kEAppUpdateErrorLaunchOptionMissing
                       && launchOptionIndex + 1 < launchOptionCount) {
                launchOptionIndex++;
                log_line("[wn-launcher] LaunchApp attempt %d/%d: \"%s\" never appeared — "
                         "Steam has no entry for launch option %d; retrying LaunchApp with "
                         "launch option %d", attempt, kMaxLaunchAttempts, exeName,
                         launchOptions[launchOptionIndex - 1], launchOptions[launchOptionIndex]);
                Sleep(300);
            } else if (eAppError == kEAppUpdateErrorApplicationRunning
                       || eAppError == kEAppUpdateErrorOtherSessionPlaying) {
                const bool other = (eAppError == kEAppUpdateErrorOtherSessionPlaying);
                uint32_t blockingPid = 0;
                int runningCount = 0;
                uint32_t blockingAppId = 0;
                if (other) {
                    query_other_session(blockUser, &blockingAppId);
                } else {
                    blockingAppId = query_running_game(blockUser, &blockingPid, &runningCount);
                }
                log_line("[wn-launcher] LaunchApp attempt %d/%d: \"%s\" refused with "
                         "EAppUpdateError=%d (%s) — blocking appId=%u; asking the user",
                         attempt, kMaxLaunchAttempts, exeName, eAppError,
                         other ? "Other session playing" : "Application running",
                         blockingAppId);
                write_blocked_request(other ? "othersession" : "running",
                                      blockingAppId, appId, blockingPid);
                if (!wait_blocked_answer(kBlockedAnswerWaitMs, bGetCallback,
                                         freeLastCallback, pipe)) {
                    launchFailureReason = other
                        ? "user declined to stop the other Steam session"
                        : "user declined to stop the running game";
                    blockedAbort = true;
                    break;
                }
                const bool cleared = other
                    ? clear_other_session(blockUser, bGetCallback, freeLastCallback, pipe)
                    : clear_running_game(engine, blockUser, appMgr, blockingAppId,
                                         bGetCallback, freeLastCallback, pipe);
                if (!cleared) {
                    launchFailureReason = other
                        ? "the other Steam session kept playing"
                        : "Steam kept the previous game registered";
                    blockedAbort = true;
                    break;
                }
                log_line("[wn-launcher] BLOCKED: cleared — retrying LaunchApp");
                continue;
            } else if (eAppError > 0) {
                log_line("[wn-launcher] LaunchApp attempt %d/%d: \"%s\" reported "
                         "EAppUpdateError=%d (%s); not retryable in-process — waiting "
                         "%ds to see whether Steam started it anyway",
                         attempt, kMaxLaunchAttempts, exeName, eAppError,
                         eapp_update_error_name(eAppError), kErrorAppearSeconds);
                launchFailureReason = "LaunchApp returned a non-NoError EAppUpdateError";
                launchedViaApp = wait_for_game_process(
                    exeName, kErrorAppearSeconds, "post-error grace",
                    bGetCallback, freeLastCallback, pipe, blockUser);
                break;
            } else {
                log_line("[wn-launcher] LaunchApp dispatched (attempt %d/%d, "
                         "EAppUpdateError=%d); waiting up to %ds for \"%s\" to "
                         "appear (committed — no re-dispatch)",
                         attempt, kMaxLaunchAttempts, eAppError,
                         kSecureAppearSeconds, exeName);
                launchedViaApp = wait_for_game_process(
                    exeName, kSecureAppearSeconds, "secure launch",
                    bGetCallback, freeLastCallback, pipe, blockUser);
                if (!launchedViaApp) {
                    log_line("[wn-launcher] LaunchApp attempt %d/%d: \"%s\" "
                             "accepted (EAppUpdateError=%d) but never became a live "
                             "process — not re-dispatching (would cancel the pending "
                             "launch) — falling back", attempt, kMaxLaunchAttempts,
                             exeName, eAppError);
                    launchFailureReason =
                        "LaunchApp accepted but the game never spawned";
                    break;
                }
            }
            }
        } else {
            launchFailureReason = "IClientAppManager was null";
        }
    } else {
        launchFailureReason = engine ? "appId was 0" : "IClientEngine was null";
    }

    if (!launchedViaApp && !directExe && wn_launcher_count_game_processes() > 0) {
        launchedViaApp = true;
        log_line("[wn-launcher] \"%s\" appeared after the wait window — Steam owns "
                 "this launch; skipping the insecure CreateProcess fallback", exeName);
    }

    if (blockedAbort && !launchedViaApp) {
        log_line("[wn-launcher] BLOCKED: not starting \"%s\" insecurely — Steam still has a "
                 "game registered and the launch was not unblocked (%s)",
                 exeName, launchFailureReason);
    }

    // LaunchApp didn't bring the game up — start it directly; the "dispatched/never appeared/falling back" log markers disarm WnLauncherStatusTailer's post-dispatch watchdog.
    if (!launchedViaApp && !blockedAbort && !probeOnly) {
        if (directExe) {
            log_line("[wn-launcher] direct-exe mode: launching user-selected \"%s\" via "
                     "CreateProcess (Steam LaunchApp skipped)", exeName);
        } else {
            log_line("[wn-launcher] LaunchApp dispatched but \"%s\" never appeared "
                     "— falling back to CreateProcess (%s)",
                     exeName, launchFailureReason);
        }
        launchedViaFallback = create_process_game(gameExe, exeName);
    }

    if (launchedViaApp || launchedViaFallback) {
        const char* path = launchedViaApp ? "LaunchApp path"
                                           : "CreateProcess fallback";
        log_line("[wn-launcher] watching \"%s\" for exit (%s)", exeName, path);

        log_active_process_registry("post-launch");
        for (int i = 0; i < 20; ++i) {
            if (bGetCallback && freeLastCallback) {
                char cb[64];
                while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
            }
            Sleep(500);
        }
        log_game_process_modules(wn_launcher_first_game_pid());
        // Declare exit after 2 consecutive absent polls (~2s) — tolerates a brief gap.
        int absent = 0;
        while (absent < 2) {
            Sleep(1000);
            if (bGetCallback && freeLastCallback) {
                char cb[64];
                while (bGetCallback(pipe, cb)) freeLastCallback(pipe);
            }
            absent = (wn_launcher_count_game_processes() != 0) ? 0 : absent + 1;
        }
        log_line("[wn-launcher] game \"%s\" exited (%s)", exeName, path);
        if (cleanShutdownArmed) {
            wn_launcher_clean_shutdown_now("game-exit");
            // Block until teardown finishes so returning from main() doesn't kill
            // the process mid-reap (cutting the logoff flush → AlreadyRunning).
            wn_launcher_wait_clean_shutdown(90000);
        }
        log_line("[wn-launcher] Steam Launcher shutdown");
        return 0;
    }

    log_line("[wn-launcher] could not start \"%s\" via LaunchApp or CreateProcess "
             "(%s)", exeName, launchFailureReason);
    if (cleanShutdownArmed) wn_launcher_clean_shutdown_now("launch-failed");
    return 9;
}
