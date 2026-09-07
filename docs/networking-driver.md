# WinNative Network Driver

Android hides network interface details from ordinary apps: `SIOCGIFHWADDR` fails for app
uids, `getifaddrs` returns nothing usable in some builds, and `/proc/net/dev` is unreadable.
Inside a Wine container that meant every NSI-backed Windows API (`GetAdaptersInfo`,
`GetAdaptersAddresses`, `GetIfTable`, `GetNetworkParams`) reported a machine with no network
cards, which breaks anti-cheat and DRM hardware ids, LAN discovery and matchmaking.

Two pieces fix this for every container:

1. `nsiproxy` is forced to auto-start in `WineUtils.changeServicesStatus` (the service-trimming
   pass used to leave it demand-start, so `\Device\Nsi` never existed).
2. `libnetshim.so` (`app/src/main/cpp/winlator/netshim.c`) is preloaded first in `LD_PRELOAD`
   for every guest process. It substitutes stable per-interface MAC addresses when the kernel
   refuses `SIOCGIFHWADDR`, reports `lo` as a loopback so it is excluded from adapter lists, and
   synthesises one `eth0` only when the real interface list is empty.

## Settings

Both the container settings and the shortcut settings dialogs have a **Networking** section:

- **Network driver** — `WinNative Network Driver` (default, applies to all containers) or
  `Wine default`, which loads the shim but disables every substitution (`WN_NET_SHIM=0`).
- **MAC address** — optional. Empty means the automatic address: a vendor OUI chosen from a
  small table of real phone/tablet Wi-Fi vendors plus three bytes from an FNV-1a hash of the
  device's `ANDROID_ID` and the interface name. A value entered here is applied to the uplink
  interfaces (`wlan*`, `eth*`, `rmnet*`, `ccmni*`, `usb*`) and passed as `WN_NET_MAC`; the shim
  rejects malformed values and any address whose first byte has the multicast or
  locally-administered bit set, and falls back to the automatic address.

## Why the address must look globally administered

Brawlhalla's `SteamAir.dll` (the Steam ANE) builds a hardware id from the first adapter whose
MAC passes a validity check and embeds it in the encrypted app ticket it requests from Steam
(`RequestEncryptedAppTicket` user data). The check drops adapters whose physical address is not
six bytes, whose OUI is a known virtualisation vendor (`00:50:56`, `00:05:69`, `00:0c:29`,
`00:1c:14`, `00:03:ff`, `00:0f:4b`, `00:16:3e`), or whose first byte has the multicast or
locally-administered bit set. The earlier shim synthesised `02:xx:xx:xx:xx:xx` addresses, so every
adapter was discarded, the ticket carried no hardware id, the login frame was 16 bytes shorter
than a desktop client's, and the game server replied `Incorrect Version, Please Update`. Other
publisher SDKs apply the same heuristics, which is why the automatic address now uses real vendor
OUIs.

Shortcut values override the container values with the usual `saveOverride` semantics.

## Environment contract

| variable | meaning |
|---|---|
| `WN_NET_SHIM` | `0` disables all substitutions |
| `WN_NET_MAC` | fixed uplink MAC, `aa:bb:cc:dd:ee:ff` |
| `WN_NET_MAC_SEED` | seed for the automatic addresses (`ANDROID_ID`) |
| `WN_NET_LOG` | log file for the shim, written from inside the container |
| `WN_NET_TAP` | `1` logs `connect`/`getaddrinfo` and dumps traffic on ports 23001/23002 |

Log lines are prefixed with milliseconds since the shim loaded so they can be correlated with
other in-process traces.
