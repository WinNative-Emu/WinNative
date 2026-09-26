
#include "wn_libsteamclient/runtime_state.h"

#include <android/log.h>
#include <cstdint>
#include <cstring>

namespace wn_libsteamclient {

extern "C" void* wn_get_isteam_utils();
extern "C" void* wn_get_isteam_user();
extern "C" void* wn_get_isteam_apps();
extern "C" void* wn_get_isteam_friends();
extern "C" void* wn_get_isteam_remote_storage();
extern "C" void* wn_get_isteam_user_stats();
extern "C" void* wn_get_isteam_inventory();
extern "C" void* wn_get_isteam_screenshots();
extern "C" void* wn_get_isteam_music();
extern "C" void* wn_get_isteam_app_list();
extern "C" void* wn_get_isteam_video();
extern "C" void* wn_get_isteam_parental();
extern "C" void* wn_get_isteam_matchmaking_servers();
extern "C" void* wn_get_isteam_matchmaking();
extern "C" void* wn_get_isteam_networking();
extern "C" void* wn_get_isteam_ugc();
extern "C" void* wn_get_isteam_game_server();
extern "C" void* wn_get_isteam_music_remote();
extern "C" void* wn_get_isteam_html_surface();
extern "C" void* wn_get_isteam_input();
extern "C" void* wn_get_isteam_parties();
extern "C" void* wn_get_isteam_remote_play();
extern "C" void* wn_get_isteam_networking_sockets();
extern "C" void* wn_get_isteam_networking_utils();
extern "C" void* wn_get_isteam_networking_messages();
extern "C" void* wn_get_iclient_engine();

extern "C" void* CreateInterface(const char* version_name, int* return_code);

class ISteamClientImpl {
public:
    virtual int  CreateSteamPipe()                           {
        int pipe = alloc_pipe();
        if (pipe == 0) pipe = state().pipe.load();
        return pipe;
    }
    virtual bool BReleaseSteamPipe(int pipe)                 { return release_pipe(pipe); }
    virtual int  ConnectToGlobalUser(int pipe)               { return alloc_global_user(pipe); }
    virtual int  CreateLocalUser(int* pipe_inout, int /*type*/) {
        if (!pipe_inout) return 0;
        int p = alloc_pipe(); if (p == 0) p = state().pipe.load();
        int u = alloc_global_user(p);
        return u;
    }
    virtual void ReleaseUser(int pipe, int user)             { release_user(pipe, user); }
    virtual void* GetISteamUser(int /*u*/, int /*p*/, const char* /*v*/)              { return wn_get_isteam_user(); }
    virtual void* GetISteamGameServer(int, int, const char*)                          { return wn_get_isteam_game_server(); }
    virtual void  SetLocalIPBinding(uint32_t, uint16_t)                               {}
    virtual void* GetISteamFriends(int /*u*/, int /*p*/, const char* /*v*/)           { return wn_get_isteam_friends(); }
    virtual void* GetISteamUtils(int /*p*/, const char* /*v*/)                        { return wn_get_isteam_utils(); }
    virtual void* GetISteamMatchmaking(int, int, const char*)                         { return wn_get_isteam_matchmaking(); }
    virtual void* GetISteamMatchmakingServers(int, int, const char*)                  { return wn_get_isteam_matchmaking_servers(); }
    virtual void* GetISteamGenericInterface(int, int, const char* version) {
        int err = 0;
        return CreateInterface(version, &err);
    }
    virtual void* GetISteamUserStats(int /*u*/, int /*p*/, const char* /*v*/)         { return wn_get_isteam_user_stats(); }
    virtual void* GetISteamApps(int /*u*/, int /*p*/, const char* /*v*/)              { return wn_get_isteam_apps(); }
    virtual void* GetISteamNetworking(int, int, const char*)                          { return wn_get_isteam_networking(); }
    virtual void* GetISteamRemoteStorage(int /*u*/, int /*p*/, const char* /*v*/)     { return wn_get_isteam_remote_storage(); }
    virtual void* GetISteamScreenshots(int, int, const char*)                         { return wn_get_isteam_screenshots(); }
    virtual void* GetISteamUGC(int, int, const char*)                                 { return wn_get_isteam_ugc(); }
    virtual void* GetISteamAppList(int, int, const char*)                             { return wn_get_isteam_app_list(); }
    virtual void* GetISteamMusic(int, int, const char*)                               { return wn_get_isteam_music(); }
    virtual void* GetISteamMusicRemote(int, int, const char*)                         { return wn_get_isteam_music_remote(); }
    virtual void* GetISteamHTMLSurface(int, int, const char*)                         { return wn_get_isteam_html_surface(); }
    virtual void  Set_SteamAPI_CPostAPIResultInProcess(void*)                         {}
    virtual void  Remove_SteamAPI_CPostAPIResultInProcess(void*)                      {}
    virtual void  Set_SteamAPI_CCheckCallbackRegisteredInProcess(void*)               {}
    virtual void* GetISteamInventory(int, int, const char*)                           { return wn_get_isteam_inventory(); }
    virtual void* GetISteamVideo(int, int, const char*)                               { return wn_get_isteam_video(); }
    virtual void* GetISteamParentalSettings(int, int, const char*)                    { return wn_get_isteam_parental(); }
    virtual void* GetISteamInput(int, int, const char*)                               { return wn_get_isteam_input(); }
    virtual void* GetISteamParties(int, int, const char*)                             { return wn_get_isteam_parties(); }
    virtual void* GetISteamRemotePlay(int, int, const char*)                          { return wn_get_isteam_remote_play(); }
};

static ISteamClientImpl g_steam_client;

}  // namespace wn_libsteamclient


extern "C" __attribute__((visibility("default")))
void* CreateInterface(const char* version_name, int* return_code) {
    if (!version_name) {
        if (return_code) *return_code = -1;
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "WnLibSteamClient",
        "CreateInterface(%s)", version_name);
    if (std::strncmp(version_name, "SteamClient", 11) == 0) {
        if (return_code) *return_code = 0;
        return &wn_libsteamclient::g_steam_client;
    }
    if (std::strncmp(version_name, "SteamNetworkingSockets", 22) == 0) {
        if (return_code) *return_code = 0;
        return wn_libsteamclient::wn_get_isteam_networking_sockets();
    }
    if (std::strncmp(version_name, "SteamNetworkingUtils", 20) == 0) {
        if (return_code) *return_code = 0;
        return wn_libsteamclient::wn_get_isteam_networking_utils();
    }
    if (std::strncmp(version_name, "SteamNetworkingMessages", 23) == 0) {
        if (return_code) *return_code = 0;
        return wn_libsteamclient::wn_get_isteam_networking_messages();
    }
    auto dispatch_iface = [&](const char* prefix, int prefix_len,
                              void* (*getter)()) -> void* {
        if (std::strncmp(version_name, prefix, prefix_len) != 0) return nullptr;
        if (return_code) *return_code = 0;
        return getter();
    };
    if (void* p = dispatch_iface("SteamMatchMakingServers", 23, wn_libsteamclient::wn_get_isteam_matchmaking_servers)) return p;
    if (void* p = dispatch_iface("SteamMatchMaking",  16, wn_libsteamclient::wn_get_isteam_matchmaking))         return p;
    if (void* p = dispatch_iface("SteamUser",         9,  wn_libsteamclient::wn_get_isteam_user))                return p;
    if (void* p = dispatch_iface("SteamFriends",      12, wn_libsteamclient::wn_get_isteam_friends))             return p;
    if (void* p = dispatch_iface("SteamUtils",        10, wn_libsteamclient::wn_get_isteam_utils))               return p;
    if (void* p = dispatch_iface("STEAMAPPS_INTERFACE_VERSION", 26, wn_libsteamclient::wn_get_isteam_apps))      return p;
    if (void* p = dispatch_iface("STEAMUSERSTATS_INTERFACE_VERSION", 31, wn_libsteamclient::wn_get_isteam_user_stats)) return p;
    if (void* p = dispatch_iface("STEAMREMOTESTORAGE_INTERFACE_VERSION", 35, wn_libsteamclient::wn_get_isteam_remote_storage)) return p;
    if (void* p = dispatch_iface("STEAMSCREENSHOTS_INTERFACE_VERSION", 33, wn_libsteamclient::wn_get_isteam_screenshots)) return p;
    if (void* p = dispatch_iface("STEAMINVENTORY_INTERFACE_V", 26, wn_libsteamclient::wn_get_isteam_inventory))  return p;
    if (void* p = dispatch_iface("STEAMVIDEO_INTERFACE_V",     22, wn_libsteamclient::wn_get_isteam_video))      return p;
    if (void* p = dispatch_iface("STEAMMUSIC_INTERFACE_VERSION", 28, wn_libsteamclient::wn_get_isteam_music))    return p;
    if (void* p = dispatch_iface("STEAMMUSICREMOTE_INTERFACE_VERSION", 33, wn_libsteamclient::wn_get_isteam_music_remote)) return p;
    if (void* p = dispatch_iface("STEAMHTMLSURFACE_INTERFACE_",27, wn_libsteamclient::wn_get_isteam_html_surface)) return p;
    if (void* p = dispatch_iface("STEAMUGC_INTERFACE_VERSION", 26, wn_libsteamclient::wn_get_isteam_ugc))        return p;
    if (void* p = dispatch_iface("STEAMAPPLIST_INTERFACE_VERSION", 30, wn_libsteamclient::wn_get_isteam_app_list)) return p;
    if (void* p = dispatch_iface("STEAMPARENTALSETTINGS_INTERFACE_VERSION", 38, wn_libsteamclient::wn_get_isteam_parental)) return p;
    if (void* p = dispatch_iface("SteamGameServer",   15, wn_libsteamclient::wn_get_isteam_game_server))         return p;
    if (void* p = dispatch_iface("SteamNetworking",   15, wn_libsteamclient::wn_get_isteam_networking))          return p;
    if (void* p = dispatch_iface("SteamInput",        10, wn_libsteamclient::wn_get_isteam_input))               return p;
    if (void* p = dispatch_iface("SteamParties",      12, wn_libsteamclient::wn_get_isteam_parties))             return p;
    if (void* p = dispatch_iface("SteamRemotePlay",   15, wn_libsteamclient::wn_get_isteam_remote_play))         return p;
    if (std::strncmp(version_name,
                     "CLIENTENGINE_INTERFACE_VERSION", 30) == 0) {
        if (return_code) *return_code = 0;
        return wn_libsteamclient::wn_get_iclient_engine();
    }
    if (return_code) *return_code = -1;
    __android_log_print(ANDROID_LOG_WARN, "WnLibSteamClient",
        "CreateInterface: unknown name='%s' — returning null", version_name);
    return nullptr;
}

extern "C" __attribute__((visibility("default")))
void* SteamInternal_FindOrCreateUserInterface(int /*hSteamUser*/,
                                              const char* version_name) {
    int rc = 0;
    return CreateInterface(version_name, &rc);
}

extern "C" __attribute__((visibility("default")))
void* SteamInternal_FindOrCreateGameServerInterface(int /*hSteamUser*/,
                                                    const char* version_name) {
    int rc = 0;
    return CreateInterface(version_name, &rc);
}

extern "C" __attribute__((visibility("default")))
void* SteamInternal_CreateInterface(const char* version_name) {
    int rc = 0;
    return CreateInterface(version_name, &rc);
}
