# Steam client DLL reference (reverse-engineered)

Generated from the exact binaries WinNative ships in
`app/src/main/assets/wnsteam/bionic/valve-steam-x86_64.tzst`. Every table below is derived
from those files by static analysis — nothing here is copied from third-party headers.

Machine-readable companion: [`steam-interfaces.json`](steam-interfaces.json)
(54 interfaces, 2029 methods, slot-indexed).

## 1. Module inventory

| File | Arch | Size | PE timestamp | Exports | ProductVersion | Role |
|---|---|---|---|---|---|---|
| `steamclient64.dll` | x64 | 25,739,928 | 2026-03-13 01:51 | 41 | 03.00.00.01 | The entire Steam client. Everything below lives here. |
| `steamclient.dll` | x86 | 21,005,976 | 2026-03-13 01:50 | 41 | 03.00.00.01 | 32-bit twin. Identical interface layout (verified). |
| `tier0_s64.dll / tier0_s.dll` | x64/x86 | 431,768 / 343,192 | 2026-03-13 01:49 | 543 / 544 | 01.00.00.01 | Valve platform layer: threads, mutexes, asserts, memory. |
| `vstdlib_s64.dll / vstdlib_s.dll` | x64/x86 | 488,088 / 602,776 | 2026-03-13 01:49 | 277 / 277 | 03.00.00.01 | Valve stdlib: KeyValues, RNG, string/URL utils. |
| `Steam.dll` | x86 | 526,488 | 2026-03-13 01:49 | 177 | — | Steam2 legacy wrapper (`steam2wrapper`). Vestigial. |
| `Steam2.dll` | x86 | 2,882,984 | 2014-02-14 01:58 | 183 | 1.0.0.0 | Original Steam2 engine. Frozen since 2014. |
| `steamservice.dll / .exe` | x86 | 3,717,272 / 2,952,856 | — | 3 / — | — | Elevated Windows service. Separate IPC pipe. |

All four `tier0`/`vstdlib` builds and both `steamclient` builds carry Valve build-server PDB paths,
e.g. `C:\buildworker\steam_rel_client_hotfix_win64\build\src\clientdll\win64\ClientRelease\steamclient64.pdb`.

`steamclient64.dll` imports only OS libraries plus `tier0_s64.dll` (165 symbols) and
`vstdlib_s64.dll` (131 symbols) — those two are hard dependencies, everything else is self-contained.

## 2. `steamclient64.dll` exports (all 41)

Only 41 exports for a 25 MB DLL — the whole API surface is vtables reached through `CreateInterface`.

| Export | Used by our agent | Purpose |
|---|---|---|
| `CreateInterface` | yes | Factory. Version string in, interface pointer out. |
| `Steam_CreateGlobalUser` | yes | Creates the pipe + global user; returns HSteamUser, writes HSteamPipe. |
| `Steam_BLoggedOn` | yes | Cheap logon poll on (pipe, user). |
| `Steam_BGetCallback` | yes | Pops one callback off the pipe. |
| `Steam_FreeLastCallback` | yes | Releases the callback popped above. |
| `Breakpad_SteamSetAppID` | yes | Tags crash reports with the app id. |
| `Steam_CreateSteamPipe / Steam_BReleaseSteamPipe` | no | Pipe lifecycle without a user. |
| `Steam_ConnectToGlobalUser / Steam_CreateLocalUser / Steam_ReleaseUser` | no | Alternative user attach paths. |
| `Steam_LogOn / Steam_LogOff` | no | Legacy logon entry points. |
| `Steam_GetAPICallResult` | no | Flat form of the `IClientUtils` call-result poll. |
| `Steam_InitiateGameConnection / Steam_TerminateGameConnection` | no | Legacy game-server auth handshake. |
| `Steam_IsKnownInterface / Steam_NotifyMissingInterface` | no | Version negotiation diagnostics. |
| `Steam_SetLocalIPBinding` | no | Binds outbound sockets. |
| `Steam_ReleaseThreadLocalMemory` | no | Per-thread cleanup. |
| `Steam_GS* (15 exports)` | no | Game-server side: `Steam_GSLogOn`, `Steam_GSBSecure`, etc. |
| `Breakpad_* (5 more)` | no | Minidump plumbing. |

## 3. The two API layers

There are two entirely separate interface families, and conflating them is the single easiest
mistake to make.

| | Public Steamworks | Internal client |
|---|---|---|
| Names | `ISteamUser`, `ISteamApps`, `ISteamUtils`, … | `IClientUser`, `IClientApps`, `IClientUtils`, … |
| Version strings | `SteamUser023`, `SteamApps008`, … | `CLIENTENGINE_INTERFACE_VERSION005` |
| Reached via | game's `steam_api64.dll` | `CreateInterface` on `steamclient64.dll` |
| Documented | yes (Valve SDK) | no |
| Stability | versioned, frozen per version | **changes between client builds** |
| Who uses it | the game | Steam UI, and our agent |

`steamclient64.dll` still serves the public family: it contains `SteamClient006`…`SteamClient023`,
`SteamFriends001`…`018`, `SteamApps001`…`009`, `SteamUser`/`SteamUtils`/`SteamUGC`/`SteamInput`
version strings, implemented by `CAdapterSteamXxxNNN` classes (e.g. `CAdapterSteamUGC021`, 99 slots)
that thunk down onto the `IClient*` layer. That adapter indirection is why a game pinned to an old
interface version keeps working across client updates while the `IClient*` layer underneath moves.

## 4. How the interfaces are dispatched

Every `IClient*` interface exists three times in the binary:

1. an **abstract** vtable (all slots point at `_purecall`) — gives the exact virtual count;
2. `InterfaceMapBase<IClientXxx>` — the IPC marshalling base;
3. `IClientXxxMap` — the concrete client-side stub you actually receive.

Each `IClientXxxMap` slot is a serializer. Disassembled, every one has the same shape:

```
mov  dl, 1                 ; framing tag
call WriteByte
mov  dl, 0x11              ; interface id  (0x11 = IClientAppManager)
call WriteByte
mov  eax, [this+8]         ; handle
call Write(4 bytes)
mov  dword ptr [..], 0xdd9af17b   ; 32-bit method id
call Write(4 bytes)
...                        ; then each argument
```

The method id is a 32-bit hash, not an index. Each one appears exactly twice in `.text`: once in
the client stub, once in the server-side dispatcher. The `interfacemap_client.h` diagnostics
confirm the scheme — `"Unknown IPC call %s::0x%x"` and `"Fencepost deserialize mismatch in %s::%s"`.

**This is why slot indices are extractable at all**: each stub also carries a RIP-relative
reference to its own method-name string, for those diagnostics. Slot -> name is read straight
out of the code.

> Do not derive slot order from the raw string layout in `.rdata`. The name literals are pooled
> in link order, not declaration order — 24 of 54 interfaces come out permuted that way, including
> `IClientUser` and `IClientAppManager`.

## 5. Verification

Four independent measures were cross-checked per interface:

| Measure | Source |
|---|---|
| abstract virtual count | RTTI `_purecall` vtable |
| `IClientXxxMap` slot count | RTTI complete-object-locator walk |
| `InterfaceMapBase<IClientXxx>` slot count | same |
| slot -> name | RIP-relative string reference inside each stub |

They agree on **48 of 52** interfaces that have all four; the four that differ do so only because
the pooled string run is short, and the code-derived names resolve those too.

Independently, the map reproduces **14 of the 15** vtable constants our agent already had — including
non-obvious ones like `IClientUser::BIsSubscribedApp` = 181 and `GetAppOwnershipTicketLength` = 103,
which were established by trial on-device. The 15th was wrong; see section 8.

**x86 and x64 layouts are identical** — same 54 interfaces, same slot counts, same names at every
slot. `steam.exe` and `steam32.exe` can share one set of constants.

## 6. Interface catalogue

`ipc id` is the byte written as the interface tag. The client-engine pipe and the elevated-service
pipe have independent id spaces, so ids repeat across the two (marked in the pipe column).

| Interface | ipc id | pipe | slots | x64 Map vtable RVA |
|---|---|---|---|---|
| `IClientUser` | 0x1 | client | 259 | 0x12e0cf0 |
| `IClientFriends` | 0x3 | client | 246 | 0x12e4260 |
| `IClientControllerSerialized` | 0x29 | client | 157 | 0x12f2360 |
| `IClientRemoteClientManager` | 0x1f | client | 127 | 0x12edc98 |
| `IClientUtils` | 0x4 | client | 116 | 0x12e6718 |
| `IClientAppManager` | 0x11 | client | 113 | 0x12e8600 |
| `IClientUGC` | 0x20 | client | 110 | 0x12ef348 |
| `IClientRemoteStorage` | 0xd | client | 100 | 0x12eb2d0 |
| `IClientGameServerInternal` | 0x2 | client | 68 | 0x12e30b8 |
| `IClientUserStats` | 0xb | client | 55 | 0x12ea538 |
| `IClientMatchmaking` | 0x6 | client | 47 | 0x12e77e8 |
| `IClientVideo` | 0x26 | client | 47 | 0x12f14a8 |
| `IClientNetworkDeviceManager` | 0x1d | client | 43 | 0x12e9780 |
| `IClientScreenshots` | 0x17 | client | 38 | 0x12ec420 |
| `IClientInventory` | 0x27 | client | 37 | 0x12f0260 |
| `IClientHTTP` | 0x16 | client | 28 | 0x12ecc48 |
| `IClientMusic` | 0x1e | client | 27 | 0x12ed310 |
| `IClientShortcuts` | 0x23 | client | 27 | 0x12f0b40 |
| `IClientVR` | 0x28 | client | 27 | 0x12f1b88 |
| `IClientInstallUtils` | 0x1 | service | 25 | 0x131b0f8 |
| `IClientShader` | 0x2d | client | 25 | 0x12f3ab8 |
| `IClientTimeline` | 0x3d | client | 24 | 0x12f1008 |
| `IClientNetworking` | 0xc | client | 22 | 0x12ead00 |
| `IClientConfigStore` | 0x12 | client | 21 | 0x12ec0f8 |
| `IClientCompat` | 0x30 | client | 19 | 0x12f3ee0 |
| `IClientSystemManager` | 0x36 | client | 18 | 0x12ea168 |
| `IClientApps` | 0x8 | client | 16 | 0x12e8070 |
| `IClientRemotePlay` | 0x34 | client | 16 | 0x12f4568 |
| `IClientStreamClient` | 0x21 | client | 15 | 0x12f0708 |
| `IClientGameStats` | 0x15 | client | 13 | 0x12ec9c0 |
| `IClientNetworkingSocketsSerialized` | 0x2e | client | 13 | 0x12f41e0 |
| `IClientParties` | 0x31 | client | 13 | 0x12e7e48 |
| `IClientAudio` | 0x18 | client | 11 | 0x12ed0d8 |
| `IClientGameServerStats` | 0x14 | client | 11 | 0x12e3968 |
| `IClientParentalSettings` | 0x1b | client | 9 | 0x12ed720 |
| `IClientSharedConnection` | 0x2c | client | 8 | 0x12f0948 |
| `IClientBilling` | 0x5 | client | 7 | 0x12e6280 |
| `IClientNetworkingUtilsSerialized` | 0x32 | client | 7 | 0x12f43d8 |
| `IClientDepotBuilder` | 0x10 | client | 6 | 0x12e9578 |
| `IClientModuleManager` | 0x2 | service | 6 | 0x131b4a8 |
| `IClientSystemDisplayManager` | 0x3c | client | 6 | 0x12ea020 |
| `IClientSystemPerfManager` | 0x39 | client | 6 | 0x12e9e18 |
| `IClientGameNotifications` | 0x25 | client | 5 | 0x12f0eb0 |
| `IClientProcessMonitor` | 0x4 | service | 5 | 0x131b758 |
| `IClientUnifiedMessages` | 0x19 | client | 5 | 0x12ed638 |
| `IClientSystemDockManager` | 0x3a | client | 4 | 0x12e9f00 |
| `IClientWindowsHWMonitor` | 0x6 | service | 4 | 0x131b878 |
| `IClientGameCoordinator` | 0x13 | client | 3 | 0x12ec8f8 |
| `IClientProductBuilder` | 0x22 | client | 3 | 0x12e94f8 |
| `IClientSecureDesktop` | 0x5 | service | 3 | 0x131b808 |
| `IClientSystemAudioManager` | 0x3b | client | 3 | 0x12e9f90 |
| `IClientGameServerPacketHandler` | 0x35 | client | 2 | 0x12e38c0 |
| `IClientStreamLauncher` | 0x1a | client | 2 | 0x12ed860 |
| `IClientAppDisableUpdate` | 0x2a | client | 1 | 0x12f39b0 |

Five more interfaces exist as abstract vtables with no marshalling stub — they are in-process only:
`IClientEngine` (80 virtuals), `IClientController` (86), `IClientHTMLSurface` (39),
`IClientUnifiedServiceTransport` (10), `IClientNetworkingMessages` (8), `IClientServiceMethodRPC` (4).

## 7. `IClientEngine` — complete getter map

`CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` is the root object: **80 virtual methods**,
of which **55 are interface getters**. The singleton lives at RVA `0x1780BA0`; its factory is
`0x957AF0` (registered by the `InterfaceReg` at `0xBCE10`) and its vtable is at **RVA `0x13151B0`**,
installed by the constructor at `0x9562D0`.

### How a getter works

Every getter compiles to the same shape — the only thing that differs between them is one
instruction, the struct offset of the cached interface pointer:

```
mov  rdi, rcx                    ; this
mov  esi, edx                    ; hUser
mov  ebx, r8d                    ; hPipe
call <enter critical section>
lea  rcx, [rdi + 0xC8]           ; the (hUser,hPipe) -> index map
call 0x9579A0                    ; lookup, -1 on miss
movsxd rdx, eax
shl  rdx, 5                      ; 32-byte map entries
mov  rbx, [rdx + rax + 0x18]     ; -> the per-user interface container
call <leave critical section>
mov  rax, [rbx + FIELD]          ; <-- the ONLY per-getter difference
ret
```

So the getters are pure field reads. Everything is created up front, not lazily: the container is
constructed by `0xA09BA0` (which null-initialises fields `0x18`–`0x168`) and then populated by
**`CClientUserContainer::Init` at `0xA0A650`**, which calls 37 `CreateIClient*Map` factories in a
row and stores each result into its field. That function is the authoritative field-to-interface
table, and it is where the mapping below comes from.

Pipe-scoped interfaces work the same way but hang off a second map at `[this+0x138]` with **0x80-byte
entries**, built by **`Init` at `0x95779B`**. That one assembles the entry on the stack and hands it
to the map insert at `0x955FB0`, so the payload starts `0x10` bytes into the entry — which is why
`GetIClientUtils` reads `entry+0x18` for what is the first pointer in the struct.

### Validation

Five getter slots were already known from independent sources (four from our own agent's working
code, `IClientUserStats` from the SteamLite agent). This derivation reproduces **5 of 5** with no
adjustment:

| Slot | Previously known | Derived here |
|---|---|---|
| 8 | `IClientUser` | `IClientUserMap` (user field `0x18`) |
| 14 | `IClientUtils` | `IClientUtilsMap` (pipe field `0x18`) |
| 17 | `IClientApps` | `IClientAppsMap` (user field `0x50`) |
| 21 | `IClientUserStats` | `IClientUserStatsMap` (user field `0x58`) |
| 43 | `IClientAppManager` | `IClientAppManagerMap` (user field `0x90`) |

Class names are recovered from MSVC RTTI on the vtable each factory installs, so they are the
binary's own names, not guesses. Note that what the engine hands out is the **`IClientXxxMap`
marshalling proxy**, not the implementation object.

### The map

Byte offset = slot x 8 on x64, slot x 4 on x86. Scope is which map the interface is cached in:
`user` = per `(hUser,hPipe)`, signature `(HSteamUser, HSteamPipe)`; `pipe` = per `hPipe` only,
signature `(HSteamPipe)`. Getting that wrong passes `hUser` where `hPipe` is expected.

| Slot | Byte off | Scope | Field | Returns |
|---|---|---|---|---|
| 8 | 0x40 | user | 0x18 | `IClientUser` |
| 9 | 0x48 | user | 0x30 | `IClientGameServerInternal` |
| 10 | 0x50 | user | None | `IClientGameServerPacketHandler` |
| 13 | 0x68 | user | 0x20 | `IClientFriends` |
| 14 | 0x70 | pipe | 0x18 | `IClientUtils` |
| 15 | 0x78 | user | 0x28 | `IClientBilling` |
| 16 | 0x80 | user | 0x48 | `IClientMatchmaking` |
| 17 | 0x88 | user | 0x50 | `IClientApps` |
| 18 | 0x90 | user | None | (CSteamMatchMakingServers) |
| 21 | 0xa8 | user | 0x58 | `IClientUserStats` |
| 22 | 0xb0 | user | 0xa0 | `IClientGameServerStats` |
| 23 | 0xb8 | user | 0x60 | `IClientNetworking` |
| 24 | 0xc0 | user | 0x68 | `IClientRemoteStorage` |
| 25 | 0xc8 | user | 0x70 | `IClientScreenshots` |
| 27 | 0xd8 | user | 0x78 | `IClientGameCoordinator` |
| 34 | 0x110 | user | 0x80 | `IClientProductBuilder` |
| 35 | 0x118 | user | 0x88 | `IClientDepotBuilder` |
| 36 | 0x120 | pipe | 0x28 | `IClientNetworkDeviceManager` |
| 37 | 0x128 | pipe | 0x30 | `IClientSystemPerfManager` |
| 38 | 0x130 | pipe | 0x38 | `IClientSystemManager` |
| 39 | 0x138 | pipe | 0x40 | `IClientSystemDockManager` |
| 40 | 0x140 | pipe | 0x48 | `IClientSystemAudioManager` |
| 41 | 0x148 | pipe | 0x50 | `IClientSystemDisplayManager` |
| 43 | 0x158 | user | 0x90 | `IClientAppManager` |
| 44 | 0x160 | user | 0x98 | `IClientConfigStore` |
| 46 | 0x170 | user | 0xa8 | `IClientGameStats` |
| 47 | 0x178 | user | 0xb0 | `IClientHTTP` |
| 50 | 0x190 | user | 0xb8 | `IClientAudio` |
| 51 | 0x198 | user | 0xc0 | `IClientMusic` |
| 52 | 0x1a0 | user | 0xc8 | `IClientUnifiedMessages` |
| 53 | 0x1a8 | pipe | 0x78 | (factory 0x5c88e0) |
| 54 | 0x1b0 | user | 0x148 | `IClientParentalSettings` |
| 55 | 0x1b8 | user | 0xd0 | `IClientStreamLauncher` |
| 56 | 0x1c0 | pipe | 0x20 | `IClientRemoteClientManager` |
| 57 | 0x1c8 | user | 0xe8 | `IClientStreamClient` |
| 58 | 0x1d0 | user | 0xf0 | `IClientShortcuts` |
| 59 | 0x1d8 | user | 0xd8 | `IClientUGC` |
| 60 | 0x1e0 | user | 0xe0 | `IClientInventory` |
| 61 | 0x1e8 | pipe | 0x58 | `IClientVR` |
| 62 | 0x1f0 | user | 0xf8 | `IClientGameNotifications` |
| 63 | 0x1f8 | user | None | (CSteamHTMLSurface) |
| 64 | 0x200 | user | 0x100 | `IClientTimeline` |
| 65 | 0x208 | user | 0x108 | `IClientVideo` |
| 66 | 0x210 | pipe | 0x60 | `IClientControllerSerialized` |
| 67 | 0x218 | user | 0x110 | `IClientAppDisableUpdate` |
| 69 | 0x228 | user | 0x118 | `IClientSharedConnection` |
| 70 | 0x230 | user | 0x120 | `IClientShader` |
| 71 | 0x238 | user | 0x128 | `IClientNetworkingSocketsSerialized` |
| 72 | 0x240 | user | 0x130 | `IClientCompat` |
| 74 | 0x250 | user | 0x138 | `IClientParties` |
| 75 | 0x258 | user | None | (CSteamClient-family) |
| 76 | 0x260 | user | None | (CSteamClient-family) |
| 77 | 0x268 | pipe | 0x68 | (CSteamClient-family, factory 0xa78d90) |
| 78 | 0x270 | pipe | 0x70 | `IClientNetworkingUtilsSerialized` |
| 79 | 0x278 | user | 0x140 | `IClientRemotePlay` |

Six slots return objects that are not `IClient*` internals — they are the public
`ISteamClient`-family objects (`CSteamClient`, `CSteamMatchMakingServers`, `CSteamHTMLSurface`),
which is what `steam_api` consumes.

## 7a. Where the other five interfaces live

49 of the 54 documented interfaces are reachable from `IClientEngine`. The remaining five are
**not in `steamclient64.dll` at all** — their RTTI and implementations are in **`steamservice.dll`**,
the elevated Windows service, reached over its **separate IPC pipe**:

| Interface | ipc id | Slots | Why it is service-side |
|---|---|---|---|
| `IClientInstallUtils` | 0x1 | 25 | Installer/registry work needing elevation |
| `IClientModuleManager` | 0x2 | 6 | Loads privileged modules |
| `IClientProcessMonitor` | 0x4 | 5 | Cross-session process inspection |
| `IClientSecureDesktop` | 0x5 | 3 | Secure-desktop transitions |
| `IClientWindowsHWMonitor` | 0x6 | 4 | Hardware/driver queries |

This also explains the apparent duplicate ipc ids noted earlier: `IClientInstallUtils` is 0x1 and so
is `IClientUser`; `IClientModuleManager` is 0x2 and so is `IClientGameServerInternal`. **ipc ids are
per-pipe namespaces, not global.** They collide only across the client pipe and the service pipe,
never within one. Any code that keys interfaces by ipc id alone will mis-dispatch.

Machine-readable: [`steam-engine-getters.json`](steam-engine-getters.json).

## 7b. The Steam Service is 32-bit only — and that decides the agent's bitness

`steamservice.dll` is **i386, and Valve ships no 64-bit build**. Every copy across every client
version pulled to this machine (78 files, spanning several client releases) is machine `0x14c`.
`Steam.dll` and `Steam2.dll` are likewise 32-bit only, while `steamclient` / `tier0_s` / `vstdlib_s`
each ship both bitnesses.

That asymmetry is the shape of the real product: Valve's client host process (`steam.exe`) is
**32-bit**. `steamclient64.dll` exists to be loaded into 64-bit *game* processes, which then talk IPC
back to the 32-bit client. A device log confirms the game does exactly that on its own —
`gamemodules: pid=484 steamclient64.dll <- C:\Program Files (x86)\Steam\steamclient64.dll`.

**Consequence for the service pipe.** The client reaches the service by loading `steamservice.dll`
in-process and calling its `CreateInterface`; the channel to the elevated host is a
`CCrossProcessPipe` (`src/common/processpipe_any.cpp`) — an anonymous pipe with inherited handles,
created by the client as part of that sequence. It is **not** a named pipe that anything external can
pre-create or provision. So when the host is 64-bit:

```
Failed to create Service pipe (GLE 2)            ERROR_FILE_NOT_FOUND
Failed to connect to Steam Service (GLE 131)
Failed to load Steam Service (GLE 193)           ERROR_BAD_EXE_FORMAT
```

and the five service-side interfaces (§7a) are unreachable. Measured, agent bitness is the only
variable:

| Agent host | `preload steamservice.dll` |
|---|---|
| 32-bit | `ok (79420000)` |
| 64-bit | `FAIL GLE=193` |

**There is no fix that keeps a 64-bit host.** The client host must be 32-bit, which is also what
Valve ships. `PrefManager.wnSteamAgent32` therefore defaults to `true`; the `.wn_steam_agent_64`
marker forces the 64-bit agent for experiments and gives up the Steam Service in exchange.

The client also validates a live service by round-tripping a `TestServiceTime` value under
`Software\Valve\SteamService` *through the service connection* — so that key appearing in the
prefix is a symptom of the handshake, not something to seed by hand.

## 8. Defect this map exposed

`kVtAppMgr_RefreshAppInfo = 83` was wrong. `IClientAppManager` slot 83 is **`RefreshLibraryFolders`**,
and **no `IClient*` interface in this build has a method called `RefreshAppInfo` at all**.

The agent called it on every launch (readiness path) and again on every `MissingConfig` retry —
25 occurrences of `RefreshAppInfo() called` across captured device logs. So each launch was telling
Steam to rescan its library folders, which is a disk walk that re-reads and can rewrite
`appmanifest_*.acf`, immediately before `LaunchApp`.

Fixed: the readiness call is removed (the correct `IClientApps::RequestAppInfoUpdate` already runs
just above it), and the retry path now calls `IClientApps::RequestAppInfoUpdate(&appId, 1)`.

## 9. Per-interface slot tables

Slot index is the vtable index. Byte offset is `slot x 8` (x64) or `slot x 4` (x86).
`null` means the stub carried no name reference.

### `IClientAppDisableUpdate` — 1 slots, ipc id 0x2a

| Slot | Method |
|---|---|
| 0 | `SetAppUpdateDisabledSecondsRemaining` |

### `IClientAppManager` — 113 slots, ipc id 0x11

| Slot | Method |
|---|---|
| 0 | `InstallApp` |
| 1 | `UninstallApp` |
| 2 | `LaunchApp` |
| 3 | `ShutdownApp` |
| 4 | `GetAppInstallState` |
| 5 | `GetAppInstallDir` |
| 6 | `GetAppContentInfo` |
| 7 | `GetAppStagingInfo` |
| 8 | `m_bInProcessPipe` |
| 9 | `IsAppDlcInstalled` |
| 10 | `GetDlcDownloadProgress` |
| 11 | `BIsDlcEnabled` |
| 12 | `SetDlcEnabled` |
| 13 | `SetDlcContext` |
| 14 | `GetDlcSizes` |
| 15 | `GetNumInstalledApps` |
| 16 | `GetInstalledApps` |
| 17 | `BIsWaitingForInstalledApps` |
| 18 | `GetAppDependencies` |
| 19 | `GetDependentApps` |
| 20 | `GetUpdateInfo` |
| 21 | `BIsAppUpToDate` |
| 22 | `GetAvailableLanguages` |
| 23 | `GetCurrentLanguage` |
| 24 | `GetCurrentLanguage` |
| 25 | `GetFallbackLanguage` |
| 26 | `SetCurrentLanguage` |
| 27 | `StartValidatingApp` |
| 28 | `CancelValidation` |
| 29 | `MarkContentCorrupt` |
| 30 | `GetInstalledDepots` |
| 31 | `GetFileDetails` |
| 32 | `VerifySignedFiles` |
| 33 | `GetNumBetas` |
| 34 | `GetBetaInfo` |
| 35 | `CheckBetaPassword` |
| 36 | `SetActiveBeta` |
| 37 | `GetActiveBeta` |
| 38 | `m_bInProcessPipe` |
| 39 | `SetDownloadingEnabled` |
| 40 | `BIsDownloadingEnabled` |
| 41 | `GetDownloadStats` |
| 42 | `GetDownloadingAppID` |
| 43 | `GetAutoUpdateTimeRestrictionEnabled` |
| 44 | `SetAutoUpdateTimeRestrictionEnabled` |
| 45 | `GetAutoUpdateTimeRestrictionHours` |
| 46 | `SetAutoUpdateTimeRestrictionStartHour` |
| 47 | `SetAutoUpdateTimeRestrictionEndHour` |
| 48 | `GetAppAutoUpdateBehavior` |
| 49 | `SetAppAutoUpdateBehavior` |
| 50 | `SetGlobalAppUpdateBehavior` |
| 51 | `GetGlobalAppUpdateBehavior` |
| 52 | `SetAppAllowDownloadsWhileRunningBehavior` |
| 53 | `GetAppAllowDownloadsWhileRunningBehavior` |
| 54 | `SetAllowDownloadsWhileAnyAppRunning` |
| 55 | `BAllowDownloadsWhileAnyAppRunning` |
| 56 | `ChangeAppDownloadQueuePlacement` |
| 57 | `SetAppDownloadQueueIndex` |
| 58 | `GetAppDownloadQueueIndex` |
| 59 | `GetAppAutoUpdateDelayedUntilTime` |
| 60 | `GetNumAppsInDownloadQueue` |
| 61 | `BHasLocalContentServer` |
| 62 | `BuildBackup` |
| 63 | `BuildInstaller` |
| 64 | `CancelBackup` |
| 65 | `RestoreAppFromBackup` |
| 66 | `RecoverAppFromFolder` |
| 67 | `CanMoveApp` |
| 68 | `MoveApp` |
| 69 | `GetMoveAppProgress` |
| 70 | `CancelMoveApp` |
| 71 | `GetAppStateInfo` |
| 72 | `m_bInProcessPipe` |
| 73 | `BCanRemotePlayTogether` |
| 74 | `BIsLocalMultiplayerApp` |
| 75 | `GetNumLibraryFolders` |
| 76 | `GetLibraryFolderPath` |
| 77 | `AddLibraryFolder` |
| 78 | `SetLibraryFolderLabel` |
| 79 | `GetLibraryFolderLabel` |
| 80 | `RemoveLibraryFolder` |
| 81 | `BGetLibraryFolderInfo` |
| 82 | `GetAppLibraryFolder` |
| 83 | `RefreshLibraryFolders` |
| 84 | `GetNumAppsInFolder` |
| 85 | `GetAppsInFolder` |
| 86 | `ForceInstallDirOverride` |
| 87 | `SetDebugInstallDir` |
| 88 | `SetDownloadThrottleRateKbps` |
| 89 | `GetDownloadThrottleRateKbps` |
| 90 | `SuspendDownloadThrottling` |
| 91 | `SetThrottleDownloadsWhileStreaming` |
| 92 | `BThrottleDownloadsWhileStreaming` |
| 93 | `GetLaunchQueryParam` |
| 94 | `BeginLaunchQueryParams` |
| 95 | `SetLaunchQueryParam` |
| 96 | `CommitLaunchQueryParams` |
| 97 | `GetLaunchCommandLine` |
| 98 | `AllowExternalArguments` |
| 99 | `AddContentLogLine` |
| 100 | `SetUseHTTPSForDownloads` |
| 101 | `GetUseHTTPSForDownloads` |
| 102 | `SetPeerContentServerMode` |
| 103 | `SetPeerContentClientMode` |
| 104 | `GetPeerContentServerMode` |
| 105 | `GetPeerContentClientMode` |
| 106 | `GetPeerContentServerStats` |
| 107 | `SuspendPeerContentClient` |
| 108 | `SuspendPeerContentServer` |
| 109 | `GetPeerContentServerForApp` |
| 110 | `NotifyDriveAdded` |
| 111 | `NotifyDriveRemoved` |
| 112 | `SetAudioDownloadQuality` |

### `IClientApps` — 16 slots, ipc id 0x8

| Slot | Method |
|---|---|
| 0 | `GetAppData` |
| 1 | `SetLocalAppConfig` |
| 2 | `GetInternalAppIDFromGameID` |
| 3 | `GetAllOwnedMultiplayerApps` |
| 4 | `GetAvailableLaunchOptions` |
| 5 | `GetAppDataSection` |
| 6 | `GetMultipleAppDataSections` |
| 7 | `RequestAppInfoUpdate` |
| 8 | `GetDLCCount` |
| 9 | `BGetDLCDataByIndex` |
| 10 | `GetAppType` |
| 11 | `TakeUpdateLock` |
| 12 | `m_bInProcessPipe` |
| 13 | `ReleaseUpdateLock` |
| 14 | `PrintAppInfo` |
| 15 | `GetLastChangeNumberReceived` |

### `IClientAudio` — 11 slots, ipc id 0x18

| Slot | Method |
|---|---|
| 0 | `StartVoiceRecording` |
| 1 | `StopVoiceRecording` |
| 2 | `ResetVoiceRecording` |
| 3 | `GetAvailableVoice` |
| 4 | `GetVoice` |
| 5 | `GetCompressedVoice` |
| 6 | `DecompressVoice` |
| 7 | `(unnamed)` |
| 8 | `BAppUsesVoice` |
| 9 | `GetGameSystemVolume` |
| 10 | `SetGameSystemVolume` |

### `IClientBilling` — 7 slots, ipc id 0x5

| Slot | Method |
|---|---|
| 0 | `PurchaseWithActivationCode` |
| 1 | `HasActiveLicense` |
| 2 | `GetLicenseInfo` |
| 3 | `EnableTestLicense` |
| 4 | `DisableTestLicense` |
| 5 | `GetAppsInPackage` |
| 6 | `RequestFreeLicenseForApps` |

### `IClientCompat` — 19 slots, ipc id 0x30

| Slot | Method |
|---|---|
| 0 | `BIsCompatLayerEnabled` |
| 1 | `GetAvailableCompatTools` |
| 2 | `GetAvailableCompatToolsFiltered` |
| 3 | `GetAvailableCompatToolsForApp` |
| 4 | `SpecifyCompatTool` |
| 5 | `SpecifyCompatExperiment` |
| 6 | `BIsCompatibilityToolEnabled` |
| 7 | `GetCompatToolName` |
| 8 | `GetCompatToolMappingPriority` |
| 9 | `GetCompatToolDisplayName` |
| 10 | `GetCompatExperiment` |
| 11 | `GetAppCompatCategories` |
| 12 | `StartSession` |
| 13 | `ReleaseSession` |
| 14 | `BIsLauncherServiceEnabled` |
| 15 | `DeleteCompatData` |
| 16 | `GetCompatibilityDataDiskSize` |
| 17 | `BNeedsUnlockH264` |
| 18 | `BNeedsProtonVoiceFiles` |

### `IClientConfigStore` — 21 slots, ipc id 0x12

| Slot | Method |
|---|---|
| 0 | `IsSet` |
| 1 | `GetBool` |
| 2 | `GetInt` |
| 3 | `GetUint64` |
| 4 | `GetFloat` |
| 5 | `GetString` |
| 6 | `GetBinary` |
| 7 | `GetBinary` |
| 8 | `GetBinaryWatermarked` |
| 9 | `SetBool` |
| 10 | `SetInt` |
| 11 | `SetUint64` |
| 12 | `SetFloat` |
| 13 | `SetString` |
| 14 | `SetBinary` |
| 15 | `SetBinaryWatermarked` |
| 16 | `RemoveKey` |
| 17 | `GetKeySerialized` |
| 18 | `FlushToDisk` |
| 19 | `GetSubKeyCount` |
| 20 | `GetSubKeyName` |

### `IClientControllerSerialized` — 157 slots, ipc id 0x29

| Slot | Method |
|---|---|
| 0 | `(unnamed)` |
| 1 | `ShowBindingPanel` |
| 2 | `GetControllerTypeForHandle` |
| 3 | `GetGamepadIndexForHandle` |
| 4 | `GetHandleForGamepadIndex` |
| 5 | `GetActionSetHandle` |
| 6 | `GetActionSetHandleByTitle` |
| 7 | `GetDigitalActionHandle` |
| 8 | `GetAnalogActionHandle` |
| 9 | `StopAnalogActionMomentum` |
| 10 | `EnableDeviceCallbacks` |
| 11 | `GetStringForDigitalActionName` |
| 12 | `GetStringForAnalogActionName` |
| 13 | `BCheckGameDirectoryAndReloadConfigIfNecessary` |
| 14 | `GetActionManifestPath` |
| 15 | `GetActionManifestPath` |
| 16 | `InvalidateActionManifestPath` |
| 17 | `DumpConfigurationToDisk` |
| 18 | `FlushCloudedConfigFilesToDisk` |
| 19 | `StartBindingVisualization` |
| 20 | `StopBindingVisualization` |
| 21 | `SetControllerStateDropEnabled` |
| 22 | `GetNumConnectedControllers` |
| 23 | `GetAllControllersStatus` |
| 24 | `QueueFetchingControllerDetails` |
| 25 | `SetDefaultConfig` |
| 26 | `CalibrateTrackpads` |
| 27 | `CalibrateJoystick` |
| 28 | `CalibrateIMU` |
| 29 | `SetEditingTritonCapSenseSettings` |
| 30 | `SetAudioMapping` |
| 31 | `PlayAudio` |
| 32 | `BIsStreamingController` |
| 33 | `SetUserLedColor` |
| 34 | `IdentifyControllerRumbleEffect` |
| 35 | `SetGyroAutoCalibrate` |
| 36 | `SetGyroOneEuroFilterActive` |
| 37 | `RequestGyroActive` |
| 38 | `LoadConfigFromVDFString` |
| 39 | `InvalidateBindingCache` |
| 40 | `ActivateConfig` |
| 41 | `WarmOptInStatus` |
| 42 | `GetGamepadIndexForControllerIndex` |
| 43 | `CreateBindingInstanceFromVDFString` |
| 44 | `FreeBindingInstance` |
| 45 | `GetControllerConfiguration` |
| 46 | `SetControllerActionSet` |
| 47 | `SetControllerSourceMode` |
| 48 | `DuplicateControllerSourceMode` |
| 49 | `SwapControllerConfigurationSourceModes` |
| 50 | `SetControllerInputActivator` |
| 51 | `SetControllerInputBinding` |
| 52 | `SetControllerInputActivatorEnabled` |
| 53 | `SetControllerMiscMappingSettings` |
| 54 | `SwapControllerModeInputBindings` |
| 55 | `SetControllerModeShiftBinding` |
| 56 | `IsModified` |
| 57 | `ClearModified` |
| 58 | `GetLocalizationTokenCount` |
| 59 | `GetLocalizationToken` |
| 60 | `GetLocalizedString` |
| 61 | `GetBindingVDFString` |
| 62 | `GetBindingTitle` |
| 63 | `SetBindingTitle` |
| 64 | `GetBindingDescription` |
| 65 | `GetBindingRevision` |
| 66 | `BBindingMajorRevisionMismatch` |
| 67 | `SetBindingDescription` |
| 68 | `GetConfigBindingInfo` |
| 69 | `SetBindingControllerType` |
| 70 | `GetBindingControllerType` |
| 71 | `SetBindingCreator` |
| 72 | `GetBindingCreator` |
| 73 | `GetBindingProgenitor` |
| 74 | `SetBindingProgenitor` |
| 75 | `GetBindingURL` |
| 76 | `SetBindingURL` |
| 77 | `GetBindingExportType` |
| 78 | `SetBindingExportType` |
| 79 | `GetConfigFeatures` |
| 80 | `PS4SettingsChanged` |
| 81 | `SwitchSettingsChanged` |
| 82 | `ControllerSettingsChanged` |
| 83 | `SetTrackpadPressureCurve` |
| 84 | `SetDefaultNintendoButtonLayout` |
| 85 | `IsControllerConnected` |
| 86 | `TriggerHapticPulse` |
| 87 | `TriggerSimpleHapticEvent` |
| 88 | `TriggerVibration` |
| 89 | `TriggerVibrationExtended` |
| 90 | `SetLEDColor` |
| 91 | `SetDonglePairingMode` |
| 92 | `ReserveSteamController` |
| 93 | `CancelSteamControllerReservations` |
| 94 | `OpenStreamingSession` |
| 95 | `CloseStreamingSession` |
| 96 | `UpdateStreamingSessionInputPermissions` |
| 97 | `InitiateISPFirmwareUpdate` |
| 98 | `InitiateBootloaderFirmwareUpdate` |
| 99 | `FlashControllerFirmware` |
| 100 | `TurnOffController` |
| 101 | `TurnOffAllWirelessControllers` |
| 102 | `EnumerateControllers` |
| 103 | `GetControllerStatusEvent` |
| 104 | `GetActualControllerDetails` |
| 105 | `GetControllerIdentity` |
| 106 | `GetControllerPersonalization` |
| 107 | `GetControllerReverseDiamondLayout` |
| 108 | `SetControllerPairingConnectionState` |
| 109 | `SetControllerKeyboardMouseState` |
| 110 | `GetTouchKeysForPopupMenu` |
| 111 | `PopupMenuTouchKeyClicked` |
| 112 | `AccessControllerInputGeneratorMouseButton` |
| 113 | `SetControllerSetting` |
| 114 | `SetSelectedConfigForApp` |
| 115 | `BControllerHasUniqueConfigForAppID` |
| 116 | `SendOSKeyboardEvent` |
| 117 | `SetOSKeyboardKey` |
| 118 | `SetMousePosition` |
| 119 | `GetGamepadIndexChangeCounter` |
| 120 | `BSwapGamepadIndex` |
| 121 | `GetGamepadIndexForXInputIndex` |
| 122 | `GetControllerIndexForGamepadIndex` |
| 123 | `AutoRegisterControllerRegistrationToAccount` |
| 124 | `GetConfigForAppAndController` |
| 125 | `SetControllerPersonalization` |
| 126 | `SetPersonalizationFile` |
| 127 | `SetGameWindowPos` |
| 128 | `HasGameMapping` |
| 129 | `GetControllerUsageData` |
| 130 | `BAllowAppConfigForController` |
| 131 | `ResetControllerEnableCache` |
| 132 | `GetControllerEnableSupport` |
| 133 | `GetControllerActivityByType` |
| 134 | `GetLastActiveControllerVID` |
| 135 | `GetLastActiveControllerPID` |
| 136 | `LoadControllerPersonalizationFile` |
| 137 | `SaveControllerPersonalizationFile` |
| 138 | `LoadRemotePlayControllerPersonalizationVDF` |
| 139 | `FindControllerByPath` |
| 140 | `GetControllerPath` |
| 141 | `GetControllerProductName` |
| 142 | `SetControllerHapticsSetting` |
| 143 | `SetControllerName` |
| 144 | `SetControllerRumbleSetting` |
| 145 | `SetControllerNintendoLayoutSetting` |
| 146 | `SetControllerUseUniversalFaceButtonGlyphs` |
| 147 | `BGetTouchConfigData` |
| 148 | `BSaveTouchConfigLayout` |
| 149 | `SetGyroOn` |
| 150 | `ForceSimpleHapticEvent` |
| 151 | `GetControllerMacAddr` |
| 152 | `SetGameFrameLimit` |
| 153 | `ResetGamepadIndexes` |
| 154 | `RegisterForIMUJerkEvent` |
| 155 | `UnregisterForIMUJerkEvent` |
| 156 | `GetBuiltInControllerIndex` |

### `IClientDepotBuilder` — 6 slots, ipc id 0x10

| Slot | Method |
|---|---|
| 0 | `BGetDepotBuildStatus` |
| 1 | `VerifyChunkStore` |
| 2 | `DownloadDepot` |
| 3 | `DownloadChunk` |
| 4 | `StartDepotBuild` |
| 5 | `CommitAppBuild` |

### `IClientFriends` — 246 slots, ipc id 0x3

| Slot | Method |
|---|---|
| 0 | `GetPersonaName` |
| 1 | `SetPersonaName` |
| 2 | `SetPersonaNameSDK` |
| 3 | `IsPersonaNameSet` |
| 4 | `GetPersonaState` |
| 5 | `SetPersonaState` |
| 6 | `NotifyUIOfMenuChange` |
| 7 | `GetFriendCount` |
| 8 | `GetFriendArray` |
| 9 | `GetFriendArrayInGame` |
| 10 | `GetFriendByIndex` |
| 11 | `GetOnlineFriendCount` |
| 12 | `GetFriendRelationship` |
| 13 | `GetFriendPersonaState` |
| 14 | `GetFriendPersonaName` |
| 15 | `GetSmallFriendAvatar` |
| 16 | `GetMediumFriendAvatar` |
| 17 | `GetLargeFriendAvatar` |
| 18 | `BGetFriendAvatarURL` |
| 19 | `GetFriendAvatarHash` |
| 20 | `SetFriendRegValue` |
| 21 | `GetFriendRegValue` |
| 22 | `DeleteFriendRegValue` |
| 23 | `GetFriendGamePlayed` |
| 24 | `GetFriendGamePlayedExtraInfo` |
| 25 | `GetFriendGameServer` |
| 26 | `GetFriendPersonaStateFlags` |
| 27 | `GetFriendBroadcastID` |
| 28 | `GetFriendPersonaNameHistory` |
| 29 | `RequestPersonaNameHistory` |
| 30 | `GetFriendPersonaNameHistoryAndDate` |
| 31 | `HasFriend` |
| 32 | `RequestUserInformation` |
| 33 | `SetIgnoreFriend` |
| 34 | `ReportChatDeclined` |
| 35 | `CreateFriendsGroup` |
| 36 | `DeleteFriendsGroup` |
| 37 | `RenameFriendsGroup` |
| 38 | `AddFriendToGroup` |
| 39 | `RemoveFriendFromGroup` |
| 40 | `IsFriendMemberOfFriendsGroup` |
| 41 | `GetFriendsGroupCount` |
| 42 | `GetFriendsGroupIDByIndex` |
| 43 | `GetFriendsGroupName` |
| 44 | `GetFriendsGroupMembershipCount` |
| 45 | `GetFirstFriendsGroupMember` |
| 46 | `GetNextFriendsGroupMember` |
| 47 | `GetGroupFriendsMembershipCount` |
| 48 | `GetFirstGroupFriendsMember` |
| 49 | `GetNextGroupFriendsMember` |
| 50 | `GetPlayerNickname` |
| 51 | `SetPlayerNickname` |
| 52 | `GetFriendSteamLevel` |
| 53 | `GetChatMessagesCount` |
| 54 | `GetChatMessage` |
| 55 | `SendMsgToFriend` |
| 56 | `ClearChatHistory` |
| 57 | `GetKnownClanCount` |
| 58 | `GetKnownClanByIndex` |
| 59 | `GetClanCount` |
| 60 | `GetClanByIndex` |
| 61 | `GetClanName` |
| 62 | `GetClanTag` |
| 63 | `GetFriendActivityCounts` |
| 64 | `GetClanActivityCounts` |
| 65 | `DownloadClanActivityCounts` |
| 66 | `GetFriendsGroupActivityCounts` |
| 67 | `IsClanPublic` |
| 68 | `IsClanOfficialGameGroup` |
| 69 | `JoinClanChatRoom` |
| 70 | `LeaveClanChatRoom` |
| 71 | `GetClanChatMemberCount` |
| 72 | `GetChatMemberByIndex` |
| 73 | `SendClanChatMessage` |
| 74 | `GetClanChatMessage` |
| 75 | `IsClanChatAdmin` |
| 76 | `IsClanChatWindowOpenInSteam` |
| 77 | `OpenClanChatWindowInSteam` |
| 78 | `CloseClanChatWindowInSteam` |
| 79 | `SetListenForFriendsMessages` |
| 80 | `ReplyToFriendMessage` |
| 81 | `GetFriendMessage` |
| 82 | `InviteFriendToClan` |
| 83 | `AcknowledgeInviteToClan` |
| 84 | `GetFriendCountFromSource` |
| 85 | `GetFriendFromSourceByIndex` |
| 86 | `IsUserInSource` |
| 87 | `GetCoplayFriendCount` |
| 88 | `GetCoplayFriend` |
| 89 | `GetFriendCoplayTime` |
| 90 | `GetFriendCoplayGame` |
| 91 | `SetRichPresence` |
| 92 | `ClearRichPresence` |
| 93 | `GetFriendRichPresence` |
| 94 | `GetFriendRichPresenceKeyCount` |
| 95 | `GetFriendRichPresenceKeyByIndex` |
| 96 | `RequestFriendRichPresence` |
| 97 | `JoinChatRoom` |
| 98 | `LeaveChatRoom` |
| 99 | `InviteUserToChatRoom` |
| 100 | `SendChatMsg` |
| 101 | `GetChatRoomMessagesCount` |
| 102 | `GetChatRoomEntry` |
| 103 | `ClearChatRoomHistory` |
| 104 | `SerializeChatRoomDlg` |
| 105 | `GetSizeOfSerializedChatRoomDlg` |
| 106 | `GetSerializedChatRoomDlg` |
| 107 | `ClearSerializedChatRoomDlg` |
| 108 | `KickChatMember` |
| 109 | `BanChatMember` |
| 110 | `UnBanChatMember` |
| 111 | `SetChatRoomType` |
| 112 | `GetChatRoomLockState` |
| 113 | `GetChatRoomPermissions` |
| 114 | `SetChatRoomModerated` |
| 115 | `BChatRoomModerated` |
| 116 | `NotifyChatRoomDlgsOfUIChange` |
| 117 | `TerminateChatRoom` |
| 118 | `GetChatRoomCount` |
| 119 | `GetChatRoomByIndex` |
| 120 | `GetChatRoomName` |
| 121 | `BGetChatRoomMemberDetails` |
| 122 | `CreateChatRoom` |
| 123 | `JoinChatRoomGroup` |
| 124 | `ShowChatRoomGroupInvite` |
| 125 | `VoiceCallNew` |
| 126 | `VoiceCall` |
| 127 | `VoiceHangUp` |
| 128 | `SetVoiceSpeakerVolume` |
| 129 | `SetVoiceMicrophoneVolume` |
| 130 | `SetAutoAnswer` |
| 131 | `VoiceAnswer` |
| 132 | `AcceptVoiceCall` |
| 133 | `VoicePutOnHold` |
| 134 | `BVoiceIsLocalOnHold` |
| 135 | `BVoiceIsRemoteOnHold` |
| 136 | `SetDoNotDisturb` |
| 137 | `EnableVoiceNotificationSounds` |
| 138 | `SetPushToTalkEnabled` |
| 139 | `IsPushToTalkEnabled` |
| 140 | `IsPushToMuteEnabled` |
| 141 | `SetPushToTalkKey` |
| 142 | `GetPushToTalkKey` |
| 143 | `IsPushToTalkKeyDown` |
| 144 | `EnableVoiceCalibration` |
| 145 | `IsVoiceCalibrating` |
| 146 | `GetVoiceCalibrationSamplePeak` |
| 147 | `SetMicBoost` |
| 148 | `GetMicBoost` |
| 149 | `HasHardwareMicBoost` |
| 150 | `GetMicDeviceName` |
| 151 | `StartTalking` |
| 152 | `EndTalking` |
| 153 | `VoiceIsValid` |
| 154 | `SetAutoReflectVoice` |
| 155 | `GetCallState` |
| 156 | `GetVoiceMicrophoneVolume` |
| 157 | `GetVoiceSpeakerVolume` |
| 158 | `TimeSinceLastVoiceDataReceived` |
| 159 | `TimeSinceLastVoiceDataSend` |
| 160 | `BCanSend` |
| 161 | `BCanReceive` |
| 162 | `GetEstimatedBitsPerSecond` |
| 163 | `GetPeakSample` |
| 164 | `SendResumeRequest` |
| 165 | `OpenFriendsDialog` |
| 166 | `OpenChatDialog` |
| 167 | `OpenInviteToTradeDialog` |
| 168 | `StartChatRoomVoiceSpeaking` |
| 169 | `EndChatRoomVoiceSpeaking` |
| 170 | `GetFriendLastLogonTime` |
| 171 | `GetFriendLastLogoffTime` |
| 172 | `GetChatRoomVoiceTotalSlotCount` |
| 173 | `GetChatRoomVoiceUsedSlotCount` |
| 174 | `GetChatRoomVoiceUsedSlot` |
| 175 | `GetChatRoomVoiceStatus` |
| 176 | `BChatRoomHasAvailableVoiceSlots` |
| 177 | `BIsChatRoomVoiceSpeaking` |
| 178 | `GetChatRoomPeakSample` |
| 179 | `ChatRoomVoiceRetryConnections` |
| 180 | `SetPortTypes` |
| 181 | `ReinitAudio` |
| 182 | `SetInGameVoiceSpeaking` |
| 183 | `IsInGameVoiceSpeaking` |
| 184 | `ActivateGameOverlay` |
| 185 | `ActivateGameOverlayToUser` |
| 186 | `ActivateGameOverlayToWebPage` |
| 187 | `ActivateGameOverlayToStore` |
| 188 | `ActivateGameOverlayInviteDialog` |
| 189 | `ActivateGameOverlayRemotePlayTogetherInviteDialog` |
| 190 | `ActivateGameOverlayInviteDialogConnectString` |
| 191 | `ProcessActivateGameOverlayInMainUI` |
| 192 | `NotifyGameOverlayStateChanged` |
| 193 | `NotifyGameServerChangeRequested` |
| 194 | `NotifyLobbyJoinRequested` |
| 195 | `NotifyRichPresenceJoinRequested` |
| 196 | `GetClanRelationship` |
| 197 | `GetClanInviteCount` |
| 198 | `GetFriendClanRank` |
| 199 | `VoiceIsAvailable` |
| 200 | `TestVoiceDisconnect` |
| 201 | `TestChatRoomPeerDisconnect` |
| 202 | `TestVoicePacketLoss` |
| 203 | `FindFriendVoiceChatHandle` |
| 204 | `RequestFriendsWhoPlayGame` |
| 205 | `GetCountFriendsWhoPlayGame` |
| 206 | `GetFriendWhoPlaysGame` |
| 207 | `GetCountFriendsInGame` |
| 208 | `SetPlayedWith` |
| 209 | `RequestClanOfficerList` |
| 210 | `GetClanOwner` |
| 211 | `GetClanOfficerCount` |
| 212 | `GetClanOfficerByIndex` |
| 213 | `RequestFriendProfileInfo` |
| 214 | `GetFriendProfileInfo` |
| 215 | `InviteUserToGame` |
| 216 | `RequestTrade` |
| 217 | `TradeResponse` |
| 218 | `CancelTradeRequest` |
| 219 | `HideFriend` |
| 220 | `GetFollowerCount` |
| 221 | `IsFollowing` |
| 222 | `EnumerateFollowingList` |
| 223 | `RequestFriendMessageHistory` |
| 224 | `RequestFriendMessageHistoryForOfflineMessages` |
| 225 | `GetCountFriendsWithOfflineMessages` |
| 226 | `GetFriendWithOfflineMessage` |
| 227 | `ClearFriendHasOfflineMessage` |
| 228 | `RequestEmoticonList` |
| 229 | `GetEmoticonCount` |
| 230 | `GetEmoticonName` |
| 231 | `ClientLinkFilterInit` |
| 232 | `LinkDisposition` |
| 233 | `GetFriendPersonaName_Public` |
| 234 | `GetPlayerNickname_Public` |
| 235 | `SetFriendsUIActiveClanChatList` |
| 236 | `GetNumChatsWithUnreadPriorityMessages` |
| 237 | `SetNumChatsWithUnreadPriorityMessages` |
| 238 | `RegisterProtocolInOverlayBrowser` |
| 239 | `HandleProtocolForOverlayBrowser` |
| 240 | `RequestEquippedProfileItems` |
| 241 | `BHasEquippedProfileItem` |
| 242 | `GetProfileItemPropertyString` |
| 243 | `GetProfileItemPropertyUint` |
| 244 | `DownloadCommunityItemAsset` |
| 245 | `GetMultiplayerSessionShareURL` |

### `IClientGameCoordinator` — 3 slots, ipc id 0x13

| Slot | Method |
|---|---|
| 0 | `SendMessage` |
| 1 | `IsMessageAvailable` |
| 2 | `RetrieveMessage` |

### `IClientGameNotifications` — 5 slots, ipc id 0x25

| Slot | Method |
|---|---|
| 0 | `EnumerateNotifications` |
| 1 | `GetNotificationCount` |
| 2 | `GetNotification` |
| 3 | `RemoveSession` |
| 4 | `UpdateSession` |

### `IClientGameServerInternal` — 68 slots, ipc id 0x2

| Slot | Method |
|---|---|
| 0 | `(unnamed)` |
| 1 | `(unnamed)` |
| 2 | `SetSDRLogin` |
| 3 | `SDR_POPID` |
| 4 | `InitGameServerSerialized` |
| 5 | `SetProduct` |
| 6 | `SetGameDescription` |
| 7 | `SetModDir` |
| 8 | `SetDedicatedServer` |
| 9 | `LogOn` |
| 10 | `LogOnAnonymous` |
| 11 | `LogOff` |
| 12 | `GetSteamID` |
| 13 | `BLoggedOn` |
| 14 | `BSecure` |
| 15 | `WasRestartRequested` |
| 16 | `SetMaxPlayerCount` |
| 17 | `SetBotPlayerCount` |
| 18 | `SetServerName` |
| 19 | `SetMapName` |
| 20 | `SetPasswordProtected` |
| 21 | `SetSpectatorPort` |
| 22 | `SetSpectatorServerName` |
| 23 | `ClearAllKeyValues` |
| 24 | `SetKeyValue` |
| 25 | `SetGameTags` |
| 26 | `SetGameData` |
| 27 | `SetRegion` |
| 28 | `SendUserConnectAndAuthenticate` |
| 29 | `CreateUnauthenticatedUserConnection` |
| 30 | `SendUserDisconnect` |
| 31 | `BUpdateUserData` |
| 32 | `GetAuthSessionTicket` |
| 33 | `GetAuthSessionTicketV2` |
| 34 | `BeginAuthSession` |
| 35 | `EndAuthSession` |
| 36 | `CancelAuthTicket` |
| 37 | `IsUserSubscribedAppInTicket` |
| 38 | `RequestUserGroupStatus` |
| 39 | `GetGameplayStats` |
| 40 | `GetServerReputation` |
| 41 | `GetPublicIP` |
| 42 | `EnableHeartbeats` |
| 43 | `SetHeartbeatInterval` |
| 44 | `ForceHeartbeat` |
| 45 | `SetAdditionalAppId` |
| 46 | `GetLogonState` |
| 47 | `BConnected` |
| 48 | `RaiseConnectionPriority` |
| 49 | `ResetConnectionPriority` |
| 50 | `SetCellID` |
| 51 | `TrackSteamUsageEvent` |
| 52 | `SetCountOfSimultaneousGuestUsersPerSteamAccount` |
| 53 | `EnumerateConnectedUsers` |
| 54 | `AssociateWithClan` |
| 55 | `ComputeNewPlayerCompatibility` |
| 56 | `_BGetUserAchievementStatus` |
| 57 | `_GSSetSpawnCount` |
| 58 | `_GSGetSteam2GetEncryptionKeyToSendToNewClient` |
| 59 | `_GSSendSteam2UserConnect` |
| 60 | `_GSSendSteam3UserConnect` |
| 61 | `_GSSendUserConnect` |
| 62 | `_GSRemoveUserConnect` |
| 63 | `_GSUpdateStatus` |
| 64 | `_GSCreateUnauthenticatedUser` |
| 65 | `_GSSetServerType` |
| 66 | `_SetBasicServerData` |
| 67 | `_GSSendUserDisconnect` |

### `IClientGameServerPacketHandler` — 2 slots, ipc id 0x35

| Slot | Method |
|---|---|
| 0 | `HandleIncomingPacket` |
| 1 | `GetNextOutgoingPacket` |

### `IClientGameServerStats` — 11 slots, ipc id 0x14

| Slot | Method |
|---|---|
| 0 | `RequestUserStats` |
| 1 | `GetUserStat` |
| 2 | `GetUserStat` |
| 3 | `GetUserAchievement` |
| 4 | `SetUserStat` |
| 5 | `SetUserStat` |
| 6 | `UpdateUserAvgRateStat` |
| 7 | `SetUserAchievement` |
| 8 | `ClearUserAchievement` |
| 9 | `StoreUserStats` |
| 10 | `SetMaxStatsLoaded` |

### `IClientGameStats` — 13 slots, ipc id 0x15

| Slot | Method |
|---|---|
| 0 | `GetNewSession` |
| 1 | `EndSession` |
| 2 | `AddSessionAttributeInt` |
| 3 | `AddSessionAttributeString` |
| 4 | `AddSessionAttributeFloat` |
| 5 | `AddNewRow` |
| 6 | `CommitRow` |
| 7 | `CommitOutstandingRows` |
| 8 | `AddRowAttributeInt` |
| 9 | `AddRowAttributeString` |
| 10 | `AddRowAttributeFloat` |
| 11 | `AddSessionAttributeInt64` |
| 12 | `AddRowAttributeInt64` |

### `IClientHTTP` — 28 slots, ipc id 0x16

| Slot | Method |
|---|---|
| 0 | `CreateHTTPRequest` |
| 1 | `SetHTTPRequestContextValue` |
| 2 | `SetHTTPRequestNetworkActivityTimeout` |
| 3 | `SetHTTPRequestHeaderValue` |
| 4 | `SetHTTPRequestGetOrPostParameter` |
| 5 | `SendHTTPRequest` |
| 6 | `SendHTTPRequestAndStreamResponse` |
| 7 | `DeferHTTPRequest` |
| 8 | `PrioritizeHTTPRequest` |
| 9 | `CancelHTTPRequest` |
| 10 | `GetHTTPResponseHeaderSize` |
| 11 | `GetHTTPResponseHeaderValue` |
| 12 | `GetHTTPResponseBodySize` |
| 13 | `GetHTTPResponseBodyData` |
| 14 | `GetHTTPStreamingResponseBodyData` |
| 15 | `ReleaseHTTPRequest` |
| 16 | `GetHTTPDownloadProgressPct` |
| 17 | `SetHTTPRequestRawPostBody` |
| 18 | `CreateCookieContainer` |
| 19 | `ReleaseCookieContainer` |
| 20 | `SetCookie` |
| 21 | `SetHTTPRequestCookieContainer` |
| 22 | `SetHTTPRequestUserAgentInfo` |
| 23 | `SetHTTPRequestRequiresVerifiedCertificate` |
| 24 | `SetHTTPRequestAbsoluteTimeoutMS` |
| 25 | `GetHTTPRequestWasTimedOut` |
| 26 | `SetHTTPRequestRedirectsEnabled` |
| 27 | `SaveHTTPResponseBodyToDisk` |

### `IClientInstallUtils` — 25 slots, ipc id 0x1

| Slot | Method |
|---|---|
| 0 | `SetUniverse` |
| 1 | `SetSteamID` |
| 2 | `AddToGameExplorer` |
| 3 | `RemoveFromGameExplorer` |
| 4 | `AddRichSavedGames` |
| 5 | `RemoveRichSavedGames` |
| 6 | `DetectKnownBadAppCompatFlags` |
| 7 | `ClearKnownBadAppCompatFlags` |
| 8 | `InstallStreamingAudioDrivers` |
| 9 | `InstallXboxDriver` |
| 10 | `UninstallXboxDriver` |
| 11 | `FindProcessHoldingLock` |
| 12 | `SetProcessSchedulingPriorityClass` |
| 13 | `SetPathReadableFromAppContainers` |
| 14 | `SetCurrentOpenXrRuntime` |
| 15 | `InstallVrLinkDongleDriver` |
| 16 | `AddUninstallEntry` |
| 17 | `RemoveUninstallEntry` |
| 18 | `AddToFirewall` |
| 19 | `RemoveFromFirewall` |
| 20 | `RegisterSteamProtocolHandler` |
| 21 | `AddInstallScriptToWhiteList` |
| 22 | `RunInstallScript` |
| 23 | `GetInstallScriptExitCode` |
| 24 | `ConfigureNetworDeviceIPAddresses` |

### `IClientInventory` — 37 slots, ipc id 0x27

| Slot | Method |
|---|---|
| 0 | `GetResultStatus` |
| 1 | `DestroyResult` |
| 2 | `GetResultItems` |
| 3 | `GetResultItemProperty` |
| 4 | `GetResultTimestamp` |
| 5 | `CheckResultSteamID` |
| 6 | `SerializeResult` |
| 7 | `DeserializeResult` |
| 8 | `GetAllItems` |
| 9 | `GetItemsByID` |
| 10 | `GenerateItems` |
| 11 | `AddPromoItems` |
| 12 | `ConsumeItem` |
| 13 | `ExchangeItems` |
| 14 | `TransferItemQuantity` |
| 15 | `SendItemDropHeartbeat` |
| 16 | `TriggerItemDrop` |
| 17 | `TradeItems` |
| 18 | `LoadItemDefinitions` |
| 19 | `GetItemDefinitionIDs` |
| 20 | `GetItemDefinitionProperty` |
| 21 | `RequestEligiblePromoItemDefinitionsIDs` |
| 22 | `GetEligiblePromoItemDefinitionIDs` |
| 23 | `StartPurchase` |
| 24 | `RequestPrices` |
| 25 | `GetNumItemsWithPrices` |
| 26 | `GetItemsWithPrices` |
| 27 | `GetItemPrice` |
| 28 | `StartUpdateProperties` |
| 29 | `RemoveProperty` |
| 30 | `SetProperty` |
| 31 | `SetProperty` |
| 32 | `SetProperty` |
| 33 | `SetProperty` |
| 34 | `SubmitUpdateProperties` |
| 35 | `InspectItem` |
| 36 | `TEST_ClearMsgCache` |

### `IClientMatchmaking` — 47 slots, ipc id 0x6

| Slot | Method |
|---|---|
| 0 | `GetFavoriteGameCount` |
| 1 | `GetFavoriteGame` |
| 2 | `AddFavoriteGame` |
| 3 | `RemoveFavoriteGame` |
| 4 | `RequestLobbyList` |
| 5 | `AddRequestLobbyListStringFilter` |
| 6 | `AddRequestLobbyListNumericalFilter` |
| 7 | `AddRequestLobbyListNearValueFilter` |
| 8 | `AddRequestLobbyListFilterSlotsAvailable` |
| 9 | `AddRequestLobbyListDistanceFilter` |
| 10 | `AddRequestLobbyListResultCountFilter` |
| 11 | `AddRequestLobbyListCompatibleMembersFilter` |
| 12 | `GetLobbyByIndex` |
| 13 | `CreateLobby` |
| 14 | `JoinLobby` |
| 15 | `LeaveLobby` |
| 16 | `InviteUserToLobby` |
| 17 | `GetNumLobbyMembers` |
| 18 | `GetLobbyMemberByIndex` |
| 19 | `GetLobbyData` |
| 20 | `SetLobbyData` |
| 21 | `GetLobbyDataCount` |
| 22 | `GetLobbyDataByIndex` |
| 23 | `DeleteLobbyData` |
| 24 | `GetLobbyMemberData` |
| 25 | `SetLobbyMemberData` |
| 26 | `SendLobbyChatMsg` |
| 27 | `GetLobbyChatEntry` |
| 28 | `RequestLobbyData` |
| 29 | `SetLobbyGameServer` |
| 30 | `GetLobbyGameServer` |
| 31 | `SetLobbyMemberLimit` |
| 32 | `GetLobbyMemberLimit` |
| 33 | `SetLobbyVoiceEnabled` |
| 34 | `RequestFriendsLobbies` |
| 35 | `SetLobbyType` |
| 36 | `SetLobbyJoinable` |
| 37 | `GetLobbyOwner` |
| 38 | `SetLobbyOwner` |
| 39 | `SetLinkedLobby` |
| 40 | `BeginGMSQuery` |
| 41 | `PollGMSQuery` |
| 42 | `GetGMSQueryResults` |
| 43 | `ReleaseGMSQuery` |
| 44 | `QueryServerByFakeIP` |
| 45 | `EnsureFavoriteGameAccountsUpdated` |
| 46 | `ReportGameServerPingReply` |

### `IClientModuleManager` — 6 slots, ipc id 0x2

| Slot | Method |
|---|---|
| 0 | `LoadModule` |
| 1 | `UnloadModule` |
| 2 | `CallFunction` |
| 3 | `CallFunctionAsync` |
| 4 | `PollResponseAsync` |
| 5 | `SetProtonEnvironment` |

### `IClientMusic` — 27 slots, ipc id 0x1e

| Slot | Method |
|---|---|
| 0 | `BIsEnabled` |
| 1 | `Enable` |
| 2 | `BIsPlaying` |
| 3 | `GetQueueCount` |
| 4 | `GetCurrentQueueEntry` |
| 5 | `GetPlaybackStatus` |
| 6 | `SetPlayingRepeatStatus` |
| 7 | `GetPlayingRepeatStatus` |
| 8 | `TogglePlayingRepeatStatus` |
| 9 | `SetPlayingShuffled` |
| 10 | `IsPlayingShuffled` |
| 11 | `Play` |
| 12 | `Pause` |
| 13 | `PlayPrevious` |
| 14 | `PlayNext` |
| 15 | `PlayEntry` |
| 16 | `TogglePlayPause` |
| 17 | `SetVolume` |
| 18 | `GetVolume` |
| 19 | `ToggleMuteVolume` |
| 20 | `IncreaseVolume` |
| 21 | `DecreaseVolume` |
| 22 | `SetPlaybackPosition` |
| 23 | `GetPlaybackPosition` |
| 24 | `GetPlaybackDuration` |
| 25 | `ReplacePlaylistWithSoundtrackAlbum` |
| 26 | `GetQueueSoundtrackAppID` |

### `IClientNetworkDeviceManager` — 43 slots, ipc id 0x1d

| Slot | Method |
|---|---|
| 0 | `IsInterfaceValid` |
| 1 | `RefreshDevices` |
| 2 | `GetNetworkDevicesData` |
| 3 | `ConnectToDevice` |
| 4 | `DisconnectFromDevice` |
| 5 | `SetDeviceOptions` |
| 6 | `SetWifiEnabled` |
| 7 | `SetWifiScanningEnabled` |
| 8 | `ForgetWirelessEndpoint` |
| 9 | `SetCustomIPSettings` |
| 10 | `GetCustomIPSettings` |
| 11 | `SetProxyInfo` |
| 12 | `GetProxyInfo` |
| 13 | `GetObviousConnectivityProblem` |
| 14 | `TEST_SetFakeLocalSystemStateSetting` |
| 15 | `TEST_GetFakeLocalSystemStateSetting` |
| 16 | `TEST_GetFakeLocalSystemEffectiveState` |
| 17 | `TEST_SetEmulateSingleWirelessDevice` |
| 18 | `TEST_GetEmulateSingleWirelessDevice` |
| 19 | `LEGACY_EnumerateNetworkDevices` |
| 20 | `LEGACY_GetDeviceType` |
| 21 | `LEGACY_IsCurrentDevice` |
| 22 | `LEGACY_IsCurrentlyConnected` |
| 23 | `LEGACY_GetDeviceIP4` |
| 24 | `LEGACY_GetDeviceBroadcastIP4` |
| 25 | `LEGACY_GetDeviceIPV6InterfaceIndex` |
| 26 | `LEGACY_GetDeviceVendor` |
| 27 | `LEGACY_GetDeviceProduct` |
| 28 | `LEGACY_GetMacAddress` |
| 29 | `LEGACY_GetSubnetMaskBitCount` |
| 30 | `LEGACY_GetRouterAddressIP4` |
| 31 | `LEGACY_GetDNSResolversIP4` |
| 32 | `LEGACY_GetDeviceState` |
| 33 | `LEGACY_GetDevicePluggedState` |
| 34 | `LEGACY_EnumerateWirelessEndpoints` |
| 35 | `LEGACY_GetConnectedWirelessEndpointSSID` |
| 36 | `LEGACY_GetWirelessSecurityCapabilities` |
| 37 | `LEGACY_GetWirelessEndpointSSIDUserDisplayString` |
| 38 | `LEGACY_GetWirelessEndpointStrength` |
| 39 | `LEGACY_IsSecurityRequired` |
| 40 | `LEGACY_GetCachedWirelessCredentials` |
| 41 | `LEGACY_IsWirelessEndpointForgettable` |
| 42 | `LEGACY_IsUsingDHCP` |

### `IClientNetworking` — 22 slots, ipc id 0xc

| Slot | Method |
|---|---|
| 0 | `SendP2PPacket` |
| 1 | `IsP2PPacketAvailable` |
| 2 | `ReadP2PPacket` |
| 3 | `AcceptP2PSessionWithUser` |
| 4 | `CloseP2PSessionWithUser` |
| 5 | `CloseP2PChannelWithUser` |
| 6 | `GetP2PSessionState` |
| 7 | `AllowP2PPacketRelay` |
| 8 | `CreateListenSocket` |
| 9 | `CreateP2PConnectionSocket` |
| 10 | `CreateConnectionSocket` |
| 11 | `DestroySocket` |
| 12 | `DestroyListenSocket` |
| 13 | `SendDataOnSocket` |
| 14 | `IsDataAvailableOnSocket` |
| 15 | `RetrieveDataFromSocket` |
| 16 | `IsDataAvailable` |
| 17 | `RetrieveData` |
| 18 | `GetSocketInfo` |
| 19 | `GetListenSocketInfo` |
| 20 | `GetSocketConnectionType` |
| 21 | `GetMaxPacketSize` |

### `IClientNetworkingSocketsSerialized` — 13 slots, ipc id 0x2e

| Slot | Method |
|---|---|
| 0 | `SendP2PRendezvous` |
| 1 | `SendP2PConnectionFailureLegacy` |
| 2 | `GetCertAsync` |
| 3 | `CacheRelayTicket` |
| 4 | `GetCachedRelayTicketCount` |
| 5 | `GetCachedRelayTicket` |
| 6 | `GetSTUNServer` |
| 7 | `AllowDirectConnectToPeerString` |
| 8 | `BeginAsyncRequestFakeIP` |
| 9 | `(unnamed)` |
| 10 | `SetAllowShareIPUserSetting` |
| 11 | `GetAllowShareIPUserSetting` |
| 12 | `TEST_ClearInMemoryCachedCredentials` |

### `IClientNetworkingUtilsSerialized` — 7 slots, ipc id 0x32

| Slot | Method |
|---|---|
| 0 | `GetNetworkConfigJSON_DEPRECATED` |
| 1 | `GetLauncherType` |
| 2 | `TEST_ClearCachedNetworkConfig` |
| 3 | `PostConnectionStateMsg` |
| 4 | `PostConnectionStateUpdatesForAllConnections` |
| 5 | `PostAppSummaryUpdates` |
| 6 | `GotLocationString` |

### `IClientParentalSettings` — 9 slots, ipc id 0x1b

| Slot | Method |
|---|---|
| 0 | `BIsParentalLockEnabled` |
| 1 | `BIsParentalLockLocked` |
| 2 | `BIsAppBlocked` |
| 3 | `BIsAppInBlockList` |
| 4 | `BIsFeatureBlocked` |
| 5 | `BIsFeatureInBlockList` |
| 6 | `BGetSerializedParentalSettings` |
| 7 | `BGetRecoveryEmail` |
| 8 | `BIsLockFromSiteLicense` |

### `IClientParties` — 13 slots, ipc id 0x31

| Slot | Method |
|---|---|
| 0 | `GetNumActiveBeacons` |
| 1 | `GetBeaconByIndex` |
| 2 | `GetBeaconDetails` |
| 3 | `JoinParty` |
| 4 | `GetNumAvailableBeaconLocations` |
| 5 | `GetAvailableBeaconLocations` |
| 6 | `CreateBeacon` |
| 7 | `OnReservationCompleted` |
| 8 | `CancelReservation` |
| 9 | `ChangeNumOpenSlots` |
| 10 | `DestroyBeacon` |
| 11 | `GetBeaconLocationData` |
| 12 | `ReservePartySlot` |

### `IClientProcessMonitor` — 5 slots, ipc id 0x4

| Slot | Method |
|---|---|
| 0 | `RegisterProcess` |
| 1 | `UnregisterProcess` |
| 2 | `TerminateProcess` |
| 3 | `SuspendProcess` |
| 4 | `ResumeProcess` |

### `IClientProductBuilder` — 3 slots, ipc id 0x22

| Slot | Method |
|---|---|
| 0 | `SignInstallScript` |
| 1 | `DRMWrap` |
| 2 | `CEGWrap` |

### `IClientRemoteClientManager` — 127 slots, ipc id 0x1f

| Slot | Method |
|---|---|
| 0 | `SetUIReadyForStream` |
| 1 | `StreamingAudioPreparationComplete` |
| 2 | `StreamingAudioFinished` |
| 3 | `ProcessStreamAvailable` |
| 4 | `ProcessStreamShutdown` |
| 5 | `UpdateStreamClientResolution` |
| 6 | `ProcessStreamClientConnected` |
| 7 | `GetStreamClientPlayer` |
| 8 | `GetStreamClientFormFactor` |
| 9 | `UpdateStreamClientNetworkUtilization` |
| 10 | `ProcessStreamClientDisconnected` |
| 11 | `BGetStreamTransportSignal` |
| 12 | `SendStreamTransportSignal` |
| 13 | `ConnectToRemote` |
| 14 | `ConnectToRemoteAddress` |
| 15 | `RefreshRemoteClients` |
| 16 | `GetClientPlatformTypes` |
| 17 | `GetRemoteClientCount` |
| 18 | `GetRemoteClientIDByIndex` |
| 19 | `GetRemoteClientNameByIndex` |
| 20 | `GetRemoteClientConnectStateByIndex` |
| 21 | `BRemoteClientHasStreamingSupportedByIndex` |
| 22 | `BRemoteClientHasStreamingEnabledByIndex` |
| 23 | `GetRemoteClientAppStateByIndex` |
| 24 | `GetRemoteClientConnectedCount` |
| 25 | `GetRemoteClientStreamingEnabledCount` |
| 26 | `GetRemoteClientName` |
| 27 | `BRemoteClientStreaming` |
| 28 | `GetRemoteClientStreamingSession` |
| 29 | `GetRemoteClientFormFactor` |
| 30 | `BRemoteClientCanStreamSteamVR` |
| 31 | `BAnyRemoteClientCanSteamVR` |
| 32 | `GetRemoteClientConnectState` |
| 33 | `BRemoteClientHasLocalConnection` |
| 34 | `BRemoteClientHasStreamingSupported` |
| 35 | `BRemoteClientHasStreamingEnabled` |
| 36 | `GetRemoteClientAppState` |
| 37 | `BRemoteClientIsSteamDeck` |
| 38 | `BRemoteClientConnectedToWifiAP` |
| 39 | `GetConnectedWifiAPClientID` |
| 40 | `GetActiveVRStreamingInvitationClientID` |
| 41 | `GetWifiAPStateJSONString` |
| 42 | `BCanPairViaWifiAP` |
| 43 | `BRemoteClientCanPairViaWifiAP` |
| 44 | `BRemoteClientWifiAPUnpaired` |
| 45 | `PairViaWifiAP` |
| 46 | `UnpairLocalWifiAP` |
| 47 | `GetRemoteDeviceCount` |
| 48 | `GetRemoteDeviceIDByIndex` |
| 49 | `GetRemoteDeviceNameByIndex` |
| 50 | `GetRemoteDeviceName` |
| 51 | `BRemoteDeviceStreaming` |
| 52 | `GetRemoteDeviceStreamingSession` |
| 53 | `GetRemoteDeviceFormFactor` |
| 54 | `UnpairRemoteClient` |
| 55 | `UnpairRemoteDevice` |
| 56 | `UnpairRemoteDevices` |
| 57 | `BIsStreamingSupported` |
| 58 | `BIsStreamingDisabledBySystemPolicy` |
| 59 | `BIsStreamingEnabled` |
| 60 | `SetStreamingEnabled` |
| 61 | `StartStream` |
| 62 | `BIsRemoteLaunch` |
| 63 | `BIsBigPictureActiveForStreaming` |
| 64 | `BIsStreamingSessionActive` |
| 65 | `BIsStreamingSessionActiveForGame` |
| 66 | `BIsStreamingClientConnected` |
| 67 | `BStreamingClientWantsRecentGames` |
| 68 | `StopStreamingSession` |
| 69 | `LaunchAppProgress` |
| 70 | `LaunchAppResult` |
| 71 | `BIsStreamStartInProgress` |
| 72 | `LaunchAppResultRequestLaunchOption` |
| 73 | `AcceptEULA` |
| 74 | `GetRemoteClientPlatformName` |
| 75 | `BIsStreamClientRunning` |
| 76 | `BIsStreamClientRunningConnectedToClient` |
| 77 | `BIsStreamClientRemotePlayTogether` |
| 78 | `GetStreamClientRemoteSteamVersion` |
| 79 | `BGetStreamingClientConfig` |
| 80 | `BSetStreamingClientConfig` |
| 81 | `BQueueControllerConfigMessageForRemote` |
| 82 | `BGetControllerConfigMessageForLocal` |
| 83 | `RequestControllerConfig` |
| 84 | `PostControllerConfig` |
| 85 | `GetControllerConfig` |
| 86 | `SetRemoteDeviceAuthorized` |
| 87 | `SetStreamingDriversInstalled` |
| 88 | `SetStreamingPIN` |
| 89 | `GetStreamingPINSize` |
| 90 | `CancelRemoteClientPairing` |
| 91 | `UsedVideoX264` |
| 92 | `UsedVideoH264` |
| 93 | `UsedVideoHEVC` |
| 94 | `SetRemotePlayTogetherQualityOverride` |
| 95 | `SetRemotePlayTogetherBitrateOverride` |
| 96 | `BHasRemotePlayInviteAndSession` |
| 97 | `BCreateRemotePlayGroup` |
| 98 | `GetLocalRemotePlayTogetherGroupID` |
| 99 | `GetRemotePlayTogetherGroupIDForOverlayPID` |
| 100 | `GetAvailableRemotePlayTogetherGuestID` |
| 101 | `BCreateRemotePlayInviteAndSession` |
| 102 | `CancelRemotePlayInviteAndSession` |
| 103 | `JoinRemotePlaySession` |
| 104 | `BStreamingDesktopToRemotePlayTogetherEnabled` |
| 105 | `SetStreamingDesktopToRemotePlayTogetherEnabled` |
| 106 | `GetStreamingSessionForRemotePlayer` |
| 107 | `SetPerUserKeyboardInputEnabled` |
| 108 | `SetPerUserMouseInputEnabled` |
| 109 | `SetPerUserControllerInputEnabled` |
| 110 | `GetPerUserInputSettings` |
| 111 | `OnClientUsedInput` |
| 112 | `OnPlaceholderStateChanged` |
| 113 | `OnRemoteClientRemotePlayClearControllers` |
| 114 | `OnRemoteClientRemotePlayControllerIndexSet` |
| 115 | `UpdateRemotePlayTogetherGroups` |
| 116 | `UpdateRemotePlayTogetherGroup` |
| 117 | `DisbandRemotePlayTogetherGroup` |
| 118 | `OnRemotePlayUIMovedController` |
| 119 | `OnSendRemotePlayTogetherInvite` |
| 120 | `ShowRemotePlayTogetherUI` |
| 121 | `BGetRemotePlayTogetherMouseCursor` |
| 122 | `GetCloudGameTimeRemaining` |
| 123 | `StopRemoteClientStream` |
| 124 | `MarkTaskComplete` |
| 125 | `NotifySettingsChanged` |
| 126 | `Shutdown` |

### `IClientRemotePlay` — 16 slots, ipc id 0x34

| Slot | Method |
|---|---|
| 0 | `GetSessionCount` |
| 1 | `GetSessionID` |
| 2 | `BSessionRemotePlayTogether` |
| 3 | `GetSessionSteamID` |
| 4 | `GetSessionGuestID` |
| 5 | `GetSmallSessionAvatar` |
| 6 | `GetMediumSessionAvatar` |
| 7 | `GetLargeSessionAvatar` |
| 8 | `GetSessionClientName` |
| 9 | `GetSessionClientFormFactor` |
| 10 | `BGetSessionClientResolution` |
| 11 | `ShowRemotePlayTogetherUI` |
| 12 | `BSendRemotePlayTogetherInvite` |
| 13 | `BEnableRemotePlayTogetherInputEvents` |
| 14 | `DisableRemotePlayTogetherInputEvents` |
| 15 | `CreateRemotePlayTogetherMouseCursor` |

### `IClientRemoteStorage` — 100 slots, ipc id 0xd

| Slot | Method |
|---|---|
| 0 | `FileWrite` |
| 1 | `GetFileSize` |
| 2 | `FileWriteAsync` |
| 3 | `FileReadAsync` |
| 4 | `FileReadAsyncComplete` |
| 5 | `FileRead` |
| 6 | `FileForget` |
| 7 | `FileDelete` |
| 8 | `FileShare` |
| 9 | `FileExists` |
| 10 | `FilePersisted` |
| 11 | `GetFileTimestamp` |
| 12 | `SetSyncPlatforms` |
| 13 | `GetSyncPlatforms` |
| 14 | `FileWriteStreamOpen` |
| 15 | `FileWriteStreamClose` |
| 16 | `FileWriteStreamCancel` |
| 17 | `FileWriteStreamWriteChunk` |
| 18 | `GetFileCount` |
| 19 | `GetFileNameAndSize` |
| 20 | `GetQuota` |
| 21 | `GetUGCQuotaUsage` |
| 22 | `InitializeUGCQuotaUsage` |
| 23 | `IsCloudEnabledForAccount` |
| 24 | `IsCloudEnabledForApp` |
| 25 | `SetCloudEnabledForApp` |
| 26 | `IsCloudSyncOnSuspendAvailableForApp` |
| 27 | `IsCloudSyncOnSuspendEnabledForApp` |
| 28 | `SetCloudSyncOnSuspendEnabledForApp` |
| 29 | `UGCDownload` |
| 30 | `UGCDownloadToLocation` |
| 31 | `GetUGCDownloadProgress` |
| 32 | `GetUGCDetails` |
| 33 | `UGCRead` |
| 34 | `GetCachedUGCCount` |
| 35 | `GetCachedUGCHandle` |
| 36 | `PublishFile` |
| 37 | `PublishVideo` |
| 38 | `PublishVideoFromURL` |
| 39 | `CreatePublishedFileUpdateRequest` |
| 40 | `UpdatePublishedFileFile` |
| 41 | `UpdatePublishedFilePreviewFile` |
| 42 | `UpdatePublishedFileTitle` |
| 43 | `UpdatePublishedFileDescription` |
| 44 | `UpdatePublishedFileSetChangeDescription` |
| 45 | `UpdatePublishedFileVisibility` |
| 46 | `UpdatePublishedFileTags` |
| 47 | `UpdatePublishedFileURL` |
| 48 | `CommitPublishedFileUpdate` |
| 49 | `GetPublishedFileDetails` |
| 50 | `DeletePublishedFile` |
| 51 | `EnumerateUserPublishedFiles` |
| 52 | `SubscribePublishedFile` |
| 53 | `EnumerateUserSubscribedFiles` |
| 54 | `UnsubscribePublishedFile` |
| 55 | `SetUserPublishedFileAction` |
| 56 | `EnumeratePublishedFilesByUserAction` |
| 57 | `EnumerateUserSubscribedFilesWithUpdates` |
| 58 | `GetCREItemVoteSummary` |
| 59 | `UpdateUserPublishedItemVote` |
| 60 | `GetUserPublishedItemVoteDetails` |
| 61 | `EnumerateUserSharedWorkshopFiles` |
| 62 | `EnumeratePublishedWorkshopFiles` |
| 63 | `EGetFileSyncState` |
| 64 | `BIsFileSyncing` |
| 65 | `FilePersist` |
| 66 | `FileFetch` |
| 67 | `ResolvePath` |
| 68 | `FileTouch` |
| 69 | `SetCloudEnabledForAccount` |
| 70 | `LoadLocalFileInfoCache` |
| 71 | `EvaluateRemoteStorageSyncState` |
| 72 | `GetLastKnownSyncState` |
| 73 | `GetRemoteStorageSyncState` |
| 74 | `HaveLatestFilesLocally` |
| 75 | `GetConflictingFileTimestamps` |
| 76 | `GetPendingRemoteOperationInfo` |
| 77 | `ResolveSyncConflict` |
| 78 | `SynchronizeApp` |
| 79 | `IsAppSyncInProgress` |
| 80 | `RunAutoCloudOnAppLaunch` |
| 81 | `RunAutoCloudOnAppExit` |
| 82 | `ResetFileRequestState` |
| 83 | `ClearPublishFileUpdateRequests` |
| 84 | `GetSubscribedFileDownloadCount` |
| 85 | `BGetSubscribedFileDownloadInfo` |
| 86 | `BGetSubscribedFileDownloadInfo` |
| 87 | `PauseSubscribedFileDownloadsForApp` |
| 88 | `ResumeSubscribedFileDownloadsForApp` |
| 89 | `PauseAllSubscribedFileDownloads` |
| 90 | `ResumeAllSubscribedFileDownloads` |
| 91 | `CancelCurrentAndPendingOperations` |
| 92 | `GetLocalFileChangeCount` |
| 93 | `GetLocalFileChange` |
| 94 | `BeginFileWriteBatch` |
| 95 | `EndFileWriteBatch` |
| 96 | `GetCloudEnabledForAppMap` |
| 97 | `m_bInProcessPipe` |
| 98 | `PerformAppPlatformChangeFileBackup` |
| 99 | `PerformAppPlatformChangeFileRestore` |

### `IClientScreenshots` — 38 slots, ipc id 0x17

| Slot | Method |
|---|---|
| 0 | `GetShortcutDisplayName` |
| 1 | `SetShortcutDisplayName` |
| 2 | `SendScreenshotStartedNotification` |
| 3 | `WriteScreenshot` |
| 4 | `AddScreenshotToLibrary` |
| 5 | `TriggerScreenshot` |
| 6 | `RequestScreenshotFromGame` |
| 7 | `SetLocation` |
| 8 | `TagUser` |
| 9 | `TagPublishedFile` |
| 10 | `ResolvePath` |
| 11 | `GetSizeOnDisk` |
| 12 | `GetSizeInCloud` |
| 13 | `IsPersisted` |
| 14 | `GetNumGamesWithLocalScreenshots` |
| 15 | `GetGameWithLocalScreenshots` |
| 16 | `GetLocalScreenshotCount` |
| 17 | `GetLocalScreenshot` |
| 18 | `GetLocalScreenshotByHandle` |
| 19 | `SetLocalScreenshotCaption` |
| 20 | `SetLocalScreenshotPrivacy` |
| 21 | `SetLocalScreenshotSpoiler` |
| 22 | `GetLocalLastScreenshot` |
| 23 | `StartBatch` |
| 24 | `AddToBatch` |
| 25 | `UploadBatch` |
| 26 | `DeleteBatch` |
| 27 | `CancelBatch` |
| 28 | `RecoverOldScreenshots` |
| 29 | `GetTaggedUserCount` |
| 30 | `GetTaggedUser` |
| 31 | `GetLocation` |
| 32 | `GetTaggedPublishedFileCount` |
| 33 | `GetTaggedPublishedFile` |
| 34 | `GetScreenshotVRType` |
| 35 | `SetScreenshotTimelineData` |
| 36 | `m_bInProcessPipe` |
| 37 | `BGetUserScreenshotDirectory` |

### `IClientSecureDesktop` — 3 slots, ipc id 0x5

| Slot | Method |
|---|---|
| 0 | `BStartStreaming` |
| 1 | `StopStreaming` |
| 2 | `SendSAS` |

### `IClientShader` — 25 slots, ipc id 0x2d

| Slot | Method |
|---|---|
| 0 | `BIsShaderManagementEnabled` |
| 1 | `BIsShaderBackgroundProcessingEnabled` |
| 2 | `EnableShaderManagement` |
| 3 | `EnableShaderBackgroundProcessing` |
| 4 | `GetShaderDepotsTotalDiskUsage` |
| 5 | `GetShaderCacheDiskSize` |
| 6 | `StartShaderScan` |
| 7 | `StartPipelineBuild` |
| 8 | `StartShaderConversion` |
| 9 | `StartShaderPruning` |
| 10 | `ProcessShaderCache` |
| 11 | `GetShaderCacheProcessingCompletion` |
| 12 | `GetShaderCacheProcessingAppID` |
| 13 | `SkipShaderProcessing` |
| 14 | `BAppHasPendingShaderContentDownload` |
| 15 | `GetAppPendingShaderDownloadSize` |
| 16 | `CheckDepotManifestID` |
| 17 | `GetBucketManifest` |
| 18 | `GetStaleBucket` |
| 19 | `ReportExternalBuild` |
| 20 | `PrepopulatePrecompiledCache` |
| 21 | `WritePrecompiledCache` |
| 22 | `CompileShaders` |
| 23 | `GetShaderBucketForGraphicsAPI` |
| 24 | `EnableShaderManagementSystem` |

### `IClientSharedConnection` — 8 slots, ipc id 0x2c

| Slot | Method |
|---|---|
| 0 | `AllocateSharedConnection` |
| 1 | `ReleaseSharedConnection` |
| 2 | `SendMessage` |
| 3 | `SendMessageAndAwaitResponse` |
| 4 | `RegisterEMsgHandler` |
| 5 | `RegisterServiceMethodHandler` |
| 6 | `BPopReceivedMessage` |
| 7 | `InitiateConnection` |

### `IClientShortcuts` — 27 slots, ipc id 0x23

| Slot | Method |
|---|---|
| 0 | `GetUniqueLocalAppId` |
| 1 | `GetGameIDForAppID` |
| 2 | `GetAppIDForGameID` |
| 3 | `GetDevkitAppIDByDevkitGameID` |
| 4 | `GetShortcutAppIds` |
| 5 | `GetShortcutInfos` |
| 6 | `GetShortcutInfoByAppID` |
| 7 | `AddShortcut` |
| 8 | `AddTemporaryShortcut` |
| 9 | `AddOpenVRShortcut` |
| 10 | `SetShortcutFromFullpath` |
| 11 | `SetShortcutAppName` |
| 12 | `SetShortcutExe` |
| 13 | `SetShortcutStartDir` |
| 14 | `SetShortcutIcon` |
| 15 | `SetShortcutCommandLine` |
| 16 | `SetShortcutHidden` |
| 17 | `SetAllowDesktopConfig` |
| 18 | `SetAllowOverlay` |
| 19 | `SetOpenVRShortcut` |
| 20 | `SetShortcutSortAs` |
| 21 | `SetDevkitShortcut` |
| 22 | `SetFlatpakAppID` |
| 23 | `RemoveShortcut` |
| 24 | `RemoveAllTemporaryShortcuts` |
| 25 | `LaunchShortcut` |
| 26 | `GetAppIDByExeName` |

### `IClientStreamClient` — 15 slots, ipc id 0x21

| Slot | Method |
|---|---|
| 0 | `Launched` |
| 1 | `FocusGained` |
| 2 | `FocusLost` |
| 3 | `Finished` |
| 4 | `BGetStreamingClientConfig` |
| 5 | `BSaveStreamingClientConfig` |
| 6 | `SetQualityOverride` |
| 7 | `SetBitrateOverride` |
| 8 | `ShowOnScreenKeyboard` |
| 9 | `BQueueControllerConfigMessageForLocal` |
| 10 | `BGetControllerConfigMessageForRemote` |
| 11 | `GetSystemInfo` |
| 12 | `StartStreamingSession` |
| 13 | `ReportStreamingSessionEvent` |
| 14 | `FinishStreamingSession` |

### `IClientStreamLauncher` — 2 slots, ipc id 0x1a

| Slot | Method |
|---|---|
| 0 | `StartStreaming` |
| 1 | `StopStreaming` |

### `IClientSystemAudioManager` — 3 slots, ipc id 0x3b

| Slot | Method |
|---|---|
| 0 | `IsInterfaceValid` |
| 1 | `GetState` |
| 2 | `UpdateSomething` |

### `IClientSystemDisplayManager` — 6 slots, ipc id 0x3c

| Slot | Method |
|---|---|
| 0 | `IsInterfaceValid` |
| 1 | `GetState` |
| 2 | `SetMode` |
| 3 | `ClearModeOverride` |
| 4 | `SetCompatibilityMode` |
| 5 | `SetGameResolutionGlobal` |

### `IClientSystemDockManager` — 4 slots, ipc id 0x3a

| Slot | Method |
|---|---|
| 0 | `IsInterfaceValid` |
| 1 | `GetState` |
| 2 | `UpdateFirmware` |
| 3 | `DisarmSafetyNet` |

### `IClientSystemManager` — 18 slots, ipc id 0x36

| Slot | Method |
|---|---|
| 0 | `GetSettings` |
| 1 | `UpdateSettings` |
| 2 | `ShutdownSystem` |
| 3 | `SuspendSystem` |
| 4 | `RestartSystem` |
| 5 | `GetDisplayBrightness` |
| 6 | `SetDisplayBrightness` |
| 7 | `FormatRemovableStorage` |
| 8 | `GetOSBranchList` |
| 9 | `GetCurrentOSBranch` |
| 10 | `SelectOSBranch` |
| 11 | `GetUpdateState` |
| 12 | `CheckForUpdate` |
| 13 | `ApplyUpdate` |
| 14 | `SetBackgroundUpdateCheckInterval` |
| 15 | `ClearAudioDefaults` |
| 16 | `RunDeckMicEnableHack` |
| 17 | `RunDeckEchoCancellationHack` |

### `IClientSystemPerfManager` — 6 slots, ipc id 0x39

| Slot | Method |
|---|---|
| 0 | `IsInterfaceValid` |
| 1 | `GetDiagnosticInfo` |
| 2 | `GetState` |
| 3 | `UpdateSettings` |
| 4 | `SetRefreshRateExternallyManaged` |
| 5 | `GetLegacySettings` |

### `IClientTimeline` — 24 slots, ipc id 0x3d

| Slot | Method |
|---|---|
| 0 | `SetTimelineTooltip` |
| 1 | `ClearTimelineTooltip` |
| 2 | `SetTimelineGameMode` |
| 3 | `AddInstantaneousTimelineEvent` |
| 4 | `AddRangeTimelineEvent` |
| 5 | `StartRangeTimelineEvent` |
| 6 | `UpdateRangeTimelineEvent` |
| 7 | `EndRangeTimelineEvent` |
| 8 | `RemoveTimelineEvent` |
| 9 | `DoesEventRecordingExist` |
| 10 | `StartGamePhase` |
| 11 | `EndGamePhase` |
| 12 | `SetGamePhaseID` |
| 13 | `DoesGamePhaseRecordingExist` |
| 14 | `AddGamePhaseTag` |
| 15 | `SetGamePhaseAttribute` |
| 16 | `OpenOverlayToGamePhase` |
| 17 | `OpenOverlayToTimelineEvent` |
| 18 | `AddUserMarkerForGame` |
| 19 | `ToggleVideoRecordingForGame` |
| 20 | `TakeInstantClipForGame` |
| 21 | `GetNextEventID` |
| 22 | `AnswerDoesGamePhaseRecordingExist` |
| 23 | `AnswerDoesEventRecordingExist` |

### `IClientUGC` — 110 slots, ipc id 0x20

| Slot | Method |
|---|---|
| 0 | `CreateQueryUserUGCRequest` |
| 1 | `CreateQueryAllUGCRequest` |
| 2 | `CreateQueryAllUGCRequest` |
| 3 | `CreateQueryUGCDetailsRequest` |
| 4 | `SendQueryUGCRequest` |
| 5 | `GetQueryUGCResult` |
| 6 | `GetQueryUGCNumTags` |
| 7 | `GetQueryUGCTag` |
| 8 | `GetQueryUGCTagDisplayName` |
| 9 | `GetQueryUGCPreviewURL` |
| 10 | `GetQueryUGCImageURL` |
| 11 | `GetQueryUGCMetadata` |
| 12 | `GetQueryUGCChildren` |
| 13 | `GetQueryUGCStatistic` |
| 14 | `GetQueryUGCNumAdditionalPreviews` |
| 15 | `GetQueryUGCAdditionalPreview` |
| 16 | `GetQueryUGCNumKeyValueTags` |
| 17 | `GetQueryUGCKeyValueTag` |
| 18 | `GetQueryUGCKeyValueTag` |
| 19 | `GetQueryUGCContentDescriptors` |
| 20 | `GetNumSupportedGameVersions` |
| 21 | `GetSupportedGameVersionData` |
| 22 | `GetQueryUGCIsDepotBuild` |
| 23 | `ReleaseQueryUGCRequest` |
| 24 | `AddRequiredTag` |
| 25 | `AddRequiredTagGroup` |
| 26 | `AddExcludedTag` |
| 27 | `SetReturnOnlyIDs` |
| 28 | `SetReturnKeyValueTags` |
| 29 | `SetReturnLongDescription` |
| 30 | `SetReturnMetadata` |
| 31 | `SetReturnChildren` |
| 32 | `SetReturnAdditionalPreviews` |
| 33 | `SetReturnTotalOnly` |
| 34 | `SetReturnPlaytimeStats` |
| 35 | `SetLanguage` |
| 36 | `SetAllowCachedResponse` |
| 37 | `SetAdminQuery` |
| 38 | `SetCloudFileNameFilter` |
| 39 | `SetMatchAnyTag` |
| 40 | `SetSearchText` |
| 41 | `SetRankedByTrendDays` |
| 42 | `SetTimeCreatedDateRange` |
| 43 | `SetTimeUpdatedDateRange` |
| 44 | `AddRequiredKeyValueTag` |
| 45 | `RequestUGCDetails` |
| 46 | `CreateItem` |
| 47 | `StartItemUpdate` |
| 48 | `SetItemTitle` |
| 49 | `SetItemDescription` |
| 50 | `SetItemUpdateLanguage` |
| 51 | `SetItemMetadata` |
| 52 | `SetItemVisibility` |
| 53 | `SetItemTags` |
| 54 | `SetItemContent` |
| 55 | `SetItemPreview` |
| 56 | `SetAllowLegacyUpload` |
| 57 | `RemoveAllItemKeyValueTags` |
| 58 | `RemoveItemKeyValueTags` |
| 59 | `AddItemKeyValueTag` |
| 60 | `AddItemPreviewFile` |
| 61 | `AddItemPreviewVideo` |
| 62 | `UpdateItemPreviewFile` |
| 63 | `UpdateItemPreviewVideo` |
| 64 | `RemoveItemPreview` |
| 65 | `AddContentDescriptor` |
| 66 | `RemoveContentDescriptor` |
| 67 | `SetRequiredGameVersions` |
| 68 | `SetExternalAssetID` |
| 69 | `SubmitItemUpdate` |
| 70 | `GetItemUpdateProgress` |
| 71 | `SetUserItemVote` |
| 72 | `GetUserItemVote` |
| 73 | `AddItemToFavorites` |
| 74 | `RemoveItemFromFavorites` |
| 75 | `SubscribeItem` |
| 76 | `UnsubscribeItem` |
| 77 | `GetNumSubscribedItems` |
| 78 | `GetSubscribedItems` |
| 79 | `GetSubscribedItemsInternal` |
| 80 | `SetWorkshopItemsDisabledLocally` |
| 81 | `SetSubscriptionsLoadOrder` |
| 82 | `SetSubscriptionsLoadOrder` |
| 83 | `MoveSubscriptionsLoadOrder` |
| 84 | `GetItemState` |
| 85 | `GetItemInstallInfo` |
| 86 | `GetItemDownloadInfo` |
| 87 | `DownloadItem` |
| 88 | `GetAppItemsStatus` |
| 89 | `BInitWorkshopForGameServer` |
| 90 | `SuspendDownloads` |
| 91 | `GetAllItemsSizeOnDisk` |
| 92 | `StartPlaytimeTracking` |
| 93 | `StopPlaytimeTracking` |
| 94 | `StopPlaytimeTrackingForAllItems` |
| 95 | `AddDependency` |
| 96 | `RemoveDependency` |
| 97 | `AddAppDependency` |
| 98 | `RemoveAppDependency` |
| 99 | `GetAppDependencies` |
| 100 | `DeleteItem` |
| 101 | `ShowWorkshopEULA` |
| 102 | `GetWorkshopEULAStatus` |
| 103 | `GetUserContentDescriptorPreferences` |
| 104 | `SetItemsDisabledLocally` |
| 105 | `MarkDownloadedItemAsUnused` |
| 106 | `GetNumDownloadedItems` |
| 107 | `GetDownloadedItems` |
| 108 | `GetFullQueryUGCResponse` |
| 109 | `GetSerializedQueryUGCResponse` |

### `IClientUnifiedMessages` — 5 slots, ipc id 0x19

| Slot | Method |
|---|---|
| 0 | `SendMethod` |
| 1 | `GetMethodResponseInfo` |
| 2 | `GetMethodResponseData` |
| 3 | `ReleaseMethod` |
| 4 | `SendNotification` |

### `IClientUser` — 259 slots, ipc id 0x1

| Slot | Method |
|---|---|
| 0 | `(unnamed)` |
| 1 | `LogOn` |
| 2 | `InvalidateCredentials` |
| 3 | `LogOff` |
| 4 | `BLoggedOn` |
| 5 | `GetLogonState` |
| 6 | `BConnected` |
| 7 | `BInitiateReconnect` |
| 8 | `EConnect` |
| 9 | `BTryingToLogin` |
| 10 | `GetSteamID` |
| 11 | `GetClientInstanceID` |
| 12 | `GetUserCountry` |
| 13 | `IsVACBanned` |
| 14 | `SetEmail` |
| 15 | `SetConfigString` |
| 16 | `GetConfigString` |
| 17 | `SetConfigInt` |
| 18 | `GetConfigInt` |
| 19 | `SetConfigBinaryBlob` |
| 20 | `GetConfigBinaryBlob` |
| 21 | `DeleteConfigKey` |
| 22 | `GetConfigStoreKeyName` |
| 23 | `InitiateGameConnection` |
| 24 | `InitiateGameConnectionOld` |
| 25 | `TerminateGameConnection` |
| 26 | `TerminateGame` |
| 27 | `SetSelfAsChatDestination` |
| 28 | `IsPrimaryChatDestination` |
| 29 | `RequestLegacyCDKey` |
| 30 | `AckGuestPass` |
| 31 | `RedeemGuestPass` |
| 32 | `GetGuestPassToGiveCount` |
| 33 | `GetGuestPassToRedeemCount` |
| 34 | `GetGuestPassToGiveInfo` |
| 35 | `GetGuestPassToGiveOut` |
| 36 | `GetGuestPassToRedeem` |
| 37 | `GetGuestPassToRedeemInfo` |
| 38 | `GetGuestPassToRedeemSenderName` |
| 39 | `GetNumAppsInGuestPassesToRedeem` |
| 40 | `GetAppsInGuestPassesToRedeem` |
| 41 | `GetCountUserNotifications` |
| 42 | `GetCountUserNotification` |
| 43 | `RequestStoreAuthURL` |
| 44 | `SetLanguage` |
| 45 | `TrackAppUsageEvent` |
| 46 | `RaiseConnectionPriority` |
| 47 | `ResetConnectionPriority` |
| 48 | `GetDesiredNetQOSLevel` |
| 49 | `BHasCachedCredentials` |
| 50 | `SetAccountNameForCachedCredentialLogin` |
| 51 | `DestroyCachedCredentials` |
| 52 | `GetCurrentWebAuthToken` |
| 53 | `RequestWebAuthToken` |
| 54 | `SetLoginInformation` |
| 55 | `SetTwoFactorCode` |
| 56 | `SetLoginToken` |
| 57 | `GetLoginTokenID` |
| 58 | `ClearAllLoginInformation` |
| 59 | `BEnableEmbeddedClient` |
| 60 | `ResetEmbeddedClient` |
| 61 | `BHasEmbeddedClientToken` |
| 62 | `RequestEmbeddedClientToken` |
| 63 | `AuthorizeNewDevice` |
| 64 | `GetLanguage` |
| 65 | `TrackSteamUsageEvent` |
| 66 | `SetComputerInUse` |
| 67 | `BIsGameRunning` |
| 68 | `BIsGameWindowReady` |
| 69 | `BUpdateAppOwnershipTicket` |
| 70 | `GetCustomBinariesState` |
| 71 | `RequestCustomBinaries` |
| 72 | `SetCellID` |
| 73 | `GetCellList` |
| 74 | `(unnamed)` |
| 75 | `GetUserDataFolder` |
| 76 | `GetUserConfigFolder` |
| 77 | `GetAccountName` |
| 78 | `GetAccountName` |
| 79 | `IsPasswordRemembered` |
| 80 | `IsSiteLicenseAssociationPending` |
| 81 | `CheckoutSiteLicenseSeat` |
| 82 | `GetAvailableSeats` |
| 83 | `GetAssociatedSiteName` |
| 84 | `BIsRunningInCafe` |
| 85 | `BAllowCachedCredentialsInCafe` |
| 86 | `RequiresLegacyCDKey` |
| 87 | `GetLegacyCDKey` |
| 88 | `SetLegacyCDKey` |
| 89 | `WriteLegacyCDKey` |
| 90 | `RemoveLegacyCDKey` |
| 91 | `RequestLegacyCDKeyFromApp` |
| 92 | `BIsAnyGameRunning` |
| 93 | `GetSteamGuardDetails` |
| 94 | `GetTwoFactorDetails` |
| 95 | `BHasTwoFactor` |
| 96 | `GetEmail` |
| 97 | `Test_FakeConnectionTimeout` |
| 98 | `RunInstallScript` |
| 99 | `IsInstallScriptRunning` |
| 100 | `GetInstallScriptState` |
| 101 | `StopInstallScript` |
| 102 | `ResetInstallScript` |
| 103 | `GetAppOwnershipTicketLength` |
| 104 | `GetAppOwnershipTicketData` |
| 105 | `GetAppOwnershipTicketExtendedData` |
| 106 | `GetMarketingMessageCount` |
| 107 | `GetMarketingMessage` |
| 108 | `MarkMarketingMessageSeen` |
| 109 | `CheckForPendingMarketingMessages` |
| 110 | `GetAuthSessionTicket` |
| 111 | `GetAuthSessionTicketV2` |
| 112 | `GetAuthSessionTicketV3` |
| 113 | `GetAuthTicketForWebApi` |
| 114 | `GetAuthSessionTicketForGameID` |
| 115 | `BeginAuthSession` |
| 116 | `EndAuthSession` |
| 117 | `CancelAuthTicket` |
| 118 | `IsUserSubscribedAppInTicket` |
| 119 | `AdvertiseGame` |
| 120 | `RequestEncryptedAppTicket` |
| 121 | `GetEncryptedAppTicket` |
| 122 | `GetGameBadgeLevel` |
| 123 | `GetPlayerSteamLevel` |
| 124 | `SetAccountLimited` |
| 125 | `BIsAccountLimited` |
| 126 | `SetAccountCommunityBanned` |
| 127 | `BIsAccountCommunityBanned` |
| 128 | `SetLimitedAccountCanInviteFriends` |
| 129 | `BLimitedAccountCanInviteFriends` |
| 130 | `BGameConnectTokensAvailable` |
| 131 | `NumGamesRunning` |
| 132 | `GetRunningGameID` |
| 133 | `GetRunningGamePID` |
| 134 | `RaiseWindowForGame` |
| 135 | `GetAccountSecurityPolicyFlags` |
| 136 | `BSupportUser` |
| 137 | `BNeedsSSANextSteamLogon` |
| 138 | `ClearNeedsSSANextSteamLogon` |
| 139 | `BIsAppOverlayEnabled` |
| 140 | `BOverlayIgnoreChildProcesses` |
| 141 | `SetOverlayState` |
| 142 | `NotifyOverlaySettingsChanged` |
| 143 | `BIsBehindNAT` |
| 144 | `GetMicroTxnAppID` |
| 145 | `GetMicroTxnOrderID` |
| 146 | `BGetMicroTxnPrice` |
| 147 | `GetMicroTxnSteamRealm` |
| 148 | `GetMicroTxnLineItemCount` |
| 149 | `BGetMicroTxnLineItem` |
| 150 | `BIsSandboxMicroTxn` |
| 151 | `BMicroTxnRequiresCachedPmtMethod` |
| 152 | `AuthorizeMicroTxn` |
| 153 | `BGetWalletBalance` |
| 154 | `RequestMicroTxnInfo` |
| 155 | `BMicroTxnRefundable` |
| 156 | `BGetAppMinutesPlayed` |
| 157 | `GetAppLastPlayedTime` |
| 158 | `GetAppUpdateDisabledSecondsRemaining` |
| 159 | `BGetGuideURL` |
| 160 | `BPromptToChangePassword` |
| 161 | `BAccountExtraSecurity` |
| 162 | `BAccountShouldShowLockUI` |
| 163 | `GetCountAuthedComputers` |
| 164 | `GetSteamGuardEnabledTime` |
| 165 | `SetPhoneIsVerified` |
| 166 | `BIsPhoneVerified` |
| 167 | `SetPhoneIsIdentifying` |
| 168 | `BIsPhoneIdentifying` |
| 169 | `SetPhoneIsRequiringVerification` |
| 170 | `BIsPhoneRequiringVerification` |
| 171 | `Set2ndFactorAuthCode` |
| 172 | `SetUserMachineName` |
| 173 | `GetUserMachineName` |
| 174 | `GetEmailDomainFromLogonFailure` |
| 175 | `GetAgreementSessionUrl` |
| 176 | `GetDurationControl` |
| 177 | `GetDurationControlForApp` |
| 178 | `BSetDurationControlOnlineState` |
| 179 | `BSetDurationControlOnlineStateForApp` |
| 180 | `BGetDurationControlExtendedResults` |
| 181 | `BIsSubscribedApp` |
| 182 | `GetSubscribedApps` |
| 183 | `AckSystemIM` |
| 184 | `RequestSpecialSurvey` |
| 185 | `SendSpecialSurveyResponse` |
| 186 | `RequestNotifications` |
| 187 | `GetAppOwnershipInfo` |
| 188 | `SendGameWebCallback` |
| 189 | `BIsStreamingUIToRemoteDevice` |
| 190 | `BIsCurrentlyNVStreaming` |
| 191 | `OnBigPictureForStreamingStartResult` |
| 192 | `OnBigPictureForStreamingDone` |
| 193 | `OnBigPictureForStreamingRestarting` |
| 194 | `StopStreaming` |
| 195 | `GetAllAccountFlags` |
| 196 | `LockParentalLock` |
| 197 | `UnlockParentalLock` |
| 198 | `BIsParentalLockEnabled` |
| 199 | `BIsParentalLockLocked` |
| 200 | `BIsAppBlocked` |
| 201 | `BIsAppInBlockList` |
| 202 | `BIsFeatureBlocked` |
| 203 | `BIsFeatureInBlockList` |
| 204 | `GetParentalUnlockTime` |
| 205 | `BGetRecoveryEmail` |
| 206 | `BIsLockFromSiteLicense` |
| 207 | `EIsParentalPlaytimeBlocked` |
| 208 | `BGetSerializedParentalSettings` |
| 209 | `BGetParentalWebToken` |
| 210 | `GetCommunityPreference` |
| 211 | `SetCommunityPreference` |
| 212 | `GetTextFilterSetting` |
| 213 | `BTextFilterIgnoresFriends` |
| 214 | `CanLogonOffline` |
| 215 | `LogOnOffline` |
| 216 | `ValidateOfflineLogonTicket` |
| 217 | `BGetOfflineLogonTicket` |
| 218 | `UploadLocalClientLogs` |
| 219 | `SetAsyncNotificationEnabled` |
| 220 | `BIsOtherSessionPlaying` |
| 221 | `BKickOtherPlayingSession` |
| 222 | `BIsAccountLockedDown` |
| 223 | `RequestAccountLinkInfo` |
| 224 | `RequestSurveySchedule` |
| 225 | `RequestNewSteamAnnouncementState` |
| 226 | `UpdateSteamAnnouncementLastRead` |
| 227 | `GetMarketEligibility` |
| 228 | `UpdateGameVrDllState` |
| 229 | `KillVRTheaterPancakeGame` |
| 230 | `SetVRIsHMDAwake` |
| 231 | `BIsAnyGameOrServiceAppRunning` |
| 232 | `m_bInProcessPipe` |
| 233 | `m_bInProcessPipe` |
| 234 | `SendSteamServiceStatusUpdate` |
| 235 | `RequestSteamGroupChatMessageNotifications` |
| 236 | `RequestSteamGroupChatMessageHistory` |
| 237 | `RequestSendSteamGroupChatMessage` |
| 238 | `OnNewGroupChatMsgAdded` |
| 239 | `OnGroupChatUserStateChange` |
| 240 | `OnReceivedGroupChatSubscriptionResponse` |
| 241 | `GetTimedTrialStatus` |
| 242 | `RequestTimedTrialStatus` |
| 243 | `PrepareForSystemSuspend` |
| 244 | `ResumeSuspendedGames` |
| 245 | `GetClientInstallationID` |
| 246 | `GetAppIDForGameID` |
| 247 | `BDoNotDisturb` |
| 248 | `SetAdditionalClientArgData` |
| 249 | `GetFamilyGroupID` |
| 250 | `GetFamilyGroupName` |
| 251 | `GetFamilyGroupRole` |
| 252 | `m_bInProcessPipe` |
| 253 | `GetSharedAppLockInfo` |
| 254 | `m_bInProcessPipe` |
| 255 | `SetPreferredLender` |
| 256 | `m_bInProcessPipe` |
| 257 | `m_bInProcessPipe` |
| 258 | `CancelLicenseForApp` |

### `IClientUserStats` — 55 slots, ipc id 0xb

| Slot | Method |
|---|---|
| 0 | `GetNumStats` |
| 1 | `GetStatName` |
| 2 | `GetStatType` |
| 3 | `GetNumAchievements` |
| 4 | `GetAchievementName` |
| 5 | `RequestCurrentStats` |
| 6 | `DeprecatedPublic_RequestCurrentStats` |
| 7 | `GetStat` |
| 8 | `GetStat` |
| 9 | `SetStat` |
| 10 | `SetStat` |
| 11 | `UpdateAvgRateStat` |
| 12 | `GetAchievement` |
| 13 | `SetAchievement` |
| 14 | `ClearAchievement` |
| 15 | `GetAchievementProgress` |
| 16 | `StoreStats` |
| 17 | `GetAchievementIcon` |
| 18 | `BGetAchievementIconURL` |
| 19 | `GetAchievementDisplayAttribute` |
| 20 | `IndicateAchievementProgress` |
| 21 | `SetMaxStatsLoaded` |
| 22 | `RequestUserStats` |
| 23 | `GetUserStat` |
| 24 | `GetUserStat` |
| 25 | `GetUserAchievement` |
| 26 | `GetUserAchievementProgress` |
| 27 | `ResetAllStats` |
| 28 | `FindOrCreateLeaderboard` |
| 29 | `FindLeaderboard` |
| 30 | `GetLeaderboardName` |
| 31 | `GetLeaderboardEntryCount` |
| 32 | `GetLeaderboardSortMethod` |
| 33 | `GetLeaderboardDisplayType` |
| 34 | `DownloadLeaderboardEntries` |
| 35 | `DownloadLeaderboardEntriesForUsers` |
| 36 | `GetDownloadedLeaderboardEntry` |
| 37 | `AttachLeaderboardUGC` |
| 38 | `UploadLeaderboardScore` |
| 39 | `GetNumberOfCurrentPlayers` |
| 40 | `GetNumAchievedAchievements` |
| 41 | `GetLastAchievementUnlocked` |
| 42 | `GetMostRecentAchievementUnlocked` |
| 43 | `RequestGlobalAchievementPercentages` |
| 44 | `GetMostAchievedAchievementInfo` |
| 45 | `GetNextMostAchievedAchievementInfo` |
| 46 | `GetAchievementAchievedPercent` |
| 47 | `RequestGlobalStats` |
| 48 | `GetGlobalStat` |
| 49 | `GetGlobalStat` |
| 50 | `GetGlobalStatHistory` |
| 51 | `GetGlobalStatHistory` |
| 52 | `GetAchievementProgressLimits` |
| 53 | `GetAchievementProgressLimits` |
| 54 | `BAchievementIconLoaded` |

### `IClientUtils` — 116 slots, ipc id 0x4

| Slot | Method |
|---|---|
| 0 | `(unnamed)` |
| 1 | `(unnamed)` |
| 2 | `(unnamed)` |
| 3 | `(unnamed)` |
| 4 | `GetSecondsSinceAppActive` |
| 5 | `GetSecondsSinceComputerActive` |
| 6 | `SetComputerActive` |
| 7 | `(unnamed)` |
| 8 | `(unnamed)` |
| 9 | `GetServerRealTime` |
| 10 | `GetIPCountry` |
| 11 | `GetImageSize` |
| 12 | `GetImageRGBA` |
| 13 | `GetNumRunningApps` |
| 14 | `GetCurrentBatteryPower` |
| 15 | `GetBatteryInformation` |
| 16 | `SetOfflineMode` |
| 17 | `GetOfflineMode` |
| 18 | `SetAppIDForCurrentPipe` |
| 19 | `GetAppID` |
| 20 | `SetAPIDebuggingActive` |
| 21 | `AllocPendingAPICallHandle` |
| 22 | `IsAPICallCompleted` |
| 23 | `GetAPICallFailureReason` |
| 24 | `GetAPICallResult` |
| 25 | `SetAPICallResultWithoutPostingCallback` |
| 26 | `SignalAppsToShutDown` |
| 27 | `SignalServiceAppsToDisconnect` |
| 28 | `TerminateAllApps` |
| 29 | `GetCellID` |
| 30 | `BIsGlobalInstance` |
| 31 | `CheckFileSignature` |
| 32 | `IsSteamClientBeta` |
| 33 | `GetBuildID` |
| 34 | `SetCurrentUIMode` |
| 35 | `GetCurrentUIMode` |
| 36 | `BIsWebBasedUIMode` |
| 37 | `SetDisableOverlayScaling` |
| 38 | `ShutdownLauncher` |
| 39 | `SetLauncherType` |
| 40 | `(unnamed)` |
| 41 | `ShowGamepadTextInput` |
| 42 | `GetEnteredGamepadTextLength` |
| 43 | `GetEnteredGamepadTextInput` |
| 44 | `GamepadTextInputClosed` |
| 45 | `ShowControllerLayoutPreview` |
| 46 | `SetSpew` |
| 47 | `BDownloadsDisabled` |
| 48 | `SetFocusedWindow` |
| 49 | `GetSteamUILanguage` |
| 50 | `SetLastGameLaunchMethod` |
| 51 | `SetVideoAdapterInfo` |
| 52 | `SetOverlayWindowFocusForPipe` |
| 53 | `GetGameOverlayUIInstanceFocusGameID` |
| 54 | `GetFocusedGameWindow` |
| 55 | `SetControllerConfigFileForAppID` |
| 56 | `GetControllerConfigFileForAppID` |
| 57 | `IsSteamRunningInVR` |
| 58 | `StartVRDashboard` |
| 59 | `IsVRHeadsetStreamingEnabled` |
| 60 | `SetVRHeadsetStreamingEnabled` |
| 61 | `GenerateSupportSystemReport` |
| 62 | `GetSupportSystemReport` |
| 63 | `GetAppIdForPid` |
| 64 | `SetClientUIProcess` |
| 65 | `BIsClientUIInForeground` |
| 66 | `AllowSetForegroundThroughWebhelper` |
| 67 | `SetOverlayBrowserInfo` |
| 68 | `ClearOverlayBrowserInfo` |
| 69 | `GetOverlayBrowserInfo` |
| 70 | `SetOverlayNotificationPosition` |
| 71 | `SetOverlayNotificationInset` |
| 72 | `DispatchClientUINotification` |
| 73 | `RespondToClientUINotification` |
| 74 | `DispatchClientUICommand` |
| 75 | `DispatchComputerActiveStateChange` |
| 76 | `DispatchOpenURLInClient` |
| 77 | `DispatchClearAllBrowsingData` |
| 78 | `DispatchClientSettingsChanged` |
| 79 | `DispatchClientPostMessage` |
| 80 | `(unnamed)` |
| 81 | `NeedsSteamChinaWorkshop` |
| 82 | `InitFilterText` |
| 83 | `FilterText` |
| 84 | `GetIPv6ConnectivityState` |
| 85 | `ScheduleConnectivityTest` |
| 86 | `GetConnectivityTestState` |
| 87 | `GetCaptivePortalURL` |
| 88 | `RecordSteamInterfaceCreation` |
| 89 | `GetCloudGamingPlatform` |
| 90 | `BGetMacAddresses` |
| 91 | `BGetDiskSerialNumber` |
| 92 | `GetSteamEnvironmentForApp` |
| 93 | `TestHTTP` |
| 94 | `DumpJobs` |
| 95 | `ShowFloatingGamepadTextInput` |
| 96 | `DismissFloatingGamepadTextInput` |
| 97 | `DismissGamepadTextInput` |
| 98 | `FloatingGamepadTextInputDismissed` |
| 99 | `SetGameLauncherMode` |
| 100 | `ClearAllHTTPCaches` |
| 101 | `GetFocusedGameID` |
| 102 | `GetFocusedWindowPID` |
| 103 | `SetWebUITransportWebhelperPID` |
| 104 | `GetWebUITransportInfo` |
| 105 | `RecordFakeReactRouteMetric` |
| 106 | `SteamRuntimeSystemInfo` |
| 107 | `DumpHTTPClients` |
| 108 | `BGetMachineID` |
| 109 | `NotifyMissingInterface` |
| 110 | `(unnamed)` |
| 111 | `DesktopLockedStateChanged` |
| 112 | `ScheduleBootReserveJob` |
| 113 | `GetGameFrameRateReportFrequency` |
| 114 | `ReportGameFrameRate` |
| 115 | `SetGameFrameRateReportingEnabled` |

### `IClientVR` — 27 slots, ipc id 0x28

| Slot | Method |
|---|---|
| 0 | `GetCurrentHmd` |
| 1 | `GetCompositor` |
| 2 | `GetHeadsetView` |
| 3 | `GetOverlay` |
| 4 | `GetOverlayView` |
| 5 | `GetSettings` |
| 6 | `GetProperties` |
| 7 | `GetPaths` |
| 8 | `IsHmdPresent` |
| 9 | `UpdateHmdStatus` |
| 10 | `IsVRModeActive` |
| 11 | `InitVR` |
| 12 | `StartSteamVR` |
| 13 | `CleanupVR` |
| 14 | `QuitAllVR` |
| 15 | `QuitApplication` |
| 16 | `GetStringForHmdError` |
| 17 | `LaunchApplication` |
| 18 | `GetSteamVRAppId` |
| 19 | `GetWebSecret` |
| 20 | `BGetMutualCapabilities` |
| 21 | `BSteamCanMakeVROverlays` |
| 22 | `BServeVRGamepadUIOverlay` |
| 23 | `BServeVRGamepadUIViaGamescope` |
| 24 | `SetVRConnectionParams` |
| 25 | `BVRDeviceSeenRecently` |
| 26 | `InviteWirelessHeadsetToConnect` |

### `IClientVideo` — 47 slots, ipc id 0x26

| Slot | Method |
|---|---|
| 0 | `UnlockH264` |
| 1 | `EGetBroadcastReady` |
| 2 | `BeginBroadcastSession` |
| 3 | `EndBroadcastSession` |
| 4 | `IsBroadcasting` |
| 5 | `BIsUploadingThumbnails` |
| 6 | `GetBroadcastSessionID` |
| 7 | `ReceiveBroadcastChat` |
| 8 | `PostBroadcastChat` |
| 9 | `MuteBroadcastChatUser` |
| 10 | `InitBroadcastVideo` |
| 11 | `InitBroadcastAudio` |
| 12 | `UploadBroadcastFrame` |
| 13 | `UploadBroadcastThumbnail` |
| 14 | `DroppedVideoFrames` |
| 15 | `SetCurrentVideoEncodingRate` |
| 16 | `SetMicrophoneState` |
| 17 | `SetVideoSource` |
| 18 | `BroadcastRecorderError` |
| 19 | `LoadBroadcastSettings` |
| 20 | `SetBroadcastPermissions` |
| 21 | `GetBroadcastPermissions` |
| 22 | `GetBroadcastMaxKbps` |
| 23 | `GetBroadcastDelaySeconds` |
| 24 | `BGetBroadcastDimensions` |
| 25 | `GetBroadcastIncludeDesktop` |
| 26 | `GetBroadcastRecordSystemAudio` |
| 27 | `GetBroadcastRecordMic` |
| 28 | `GetBroadcastShowChatCorner` |
| 29 | `GetBroadcastShowDebugInfo` |
| 30 | `GetBroadcastShowReminderBanner` |
| 31 | `GetBroadcastEncoderSetting` |
| 32 | `InviteToBroadcast` |
| 33 | `IgnoreApprovalRequest` |
| 34 | `BroadcastFirstTimeComplete` |
| 35 | `SetInHomeStreamState` |
| 36 | `WatchBroadcast` |
| 37 | `GetWatchBroadcastMPD` |
| 38 | `GetApprovalRequestCount` |
| 39 | `GetApprovalRequests` |
| 40 | `GetVideoURL` |
| 41 | `GetOPFSettings` |
| 42 | `GetOPFStringForApp` |
| 43 | `WebRTCGetTURNAddress` |
| 44 | `WebRTCStartResult` |
| 45 | `WebRTCAddCandidate` |
| 46 | `WebRTCGetAnswer` |

### `IClientWindowsHWMonitor` — 4 slots, ipc id 0x6

| Slot | Method |
|---|---|
| 0 | `StartMonitoring` |
| 1 | `StopMonitoring` |
| 2 | `RefreshInformation` |
| 3 | `GetCPUTemperature` |

