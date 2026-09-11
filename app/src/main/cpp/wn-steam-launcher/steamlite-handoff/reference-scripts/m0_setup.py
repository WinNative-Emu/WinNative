import json, os, shutil, subprocess, re

PREFIX = "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine"
STEAMDIR = PREFIX + "/drive_c/Program Files (x86)/Steam"
COMMON = PREFIX + "/drive_c/Program Files (x86)/Common Files/Steam"
DESKTOP = PREFIX + "/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop"
CONTAINER = "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.container"
PREFS = "/data/data/com.tencent.ig/shared_prefs/steam_prefs.xml"
M0 = "/storage/emulated/0/Download/steamlite/m0"
U = 10249

def rp(k):
    s = open(PREFS).read()
    m = re.search(r'name="%s">([^<]*)<' % k, s)        # <string name=..>V</string>
    if m:
        return m.group(1)
    m = re.search(r'name="%s"\s+value="([^"]*)"' % k, s)  # <long/int/bool name=.. value="V"/>
    return m.group(1) if m else ""

TOK, ACCT, SID = rp("refresh_token"), rp("username"), rp("steam_id_64")
assert TOK and ACCT and SID, "missing prefs tok=%d acct=%d sid=%d" % (len(TOK), len(ACCT), len(SID))

# 1. fresh Steam dir with WinNative's matched client + our binary as steam.exe
if os.path.isdir(STEAMDIR):
    shutil.rmtree(STEAMDIR)
os.makedirs(STEAMDIR)
for f in os.listdir(M0 + "/client"):
    src = M0 + "/client/" + f
    if os.path.isfile(src):
        shutil.copy2(src, STEAMDIR + "/" + f)
shutil.copy2(M0 + "/our_steam.exe", STEAMDIR + "/steam.exe")
os.makedirs(COMMON, exist_ok=True)
for f in ["steamservice.exe", "steamservice.dll", "service_current_versions.vdf", "service_minimum_versions.vdf"]:
    shutil.copy2(M0 + "/" + f, COMMON + "/" + f)

# 2. container env: base graphics vars + lsteamclient-off + WN_STEAM_* login env
cfg = json.load(open(CONTAINER))
base = ("WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact "
        "MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true "
        "WINEESYNC=1 TU_DEBUG=noconform,sysmem DXVK_HUD=fps,api")
cfg["envVars"] = (base + " PROTON_DISABLE_LSTEAMCLIENT=1"
                  " WN_STEAM_TOKEN=%s WN_STEAM_USERNAME=%s WN_STEAM_STEAMID=%s" % (TOK, ACCT, SID))
json.dump(cfg, open(CONTAINER, "w"))

# 3. shortcut -> steam.exe (4 literal backslashes, matching the working format)
bs = "\\" * 4
desk = ("[Desktop Entry]\nName=Left 4 Dead 2\n"
        "Exec=wine C:%sProgram Files (x86)%sSteam%ssteam.exe\n"
        "Icon=Left 4 Dead 2\nType=Application\nStartupWMClass=explorer\n\n"
        "[Extra Data]\nstoreSource=steam\nsteamAppId=550\neos=0\n") % (bs, bs, bs)
open(DESKTOP, "w").write(desk)

# 4. perms + SELinux
for p in [STEAMDIR, COMMON, DESKTOP, CONTAINER]:
    subprocess.run(["chown", "-R", "%d:%d" % (U, U), p])
    subprocess.run("restorecon -RF '%s' 2>/dev/null" % p, shell=True)

print("M0_SETUP_DONE tok_len=%d acct_len=%d sid_ok=%s steamdir_files=%d has_steam_exe=%s"
      % (len(TOK), len(ACCT), bool(SID), len(os.listdir(STEAMDIR)), os.path.exists(STEAMDIR + "/steam.exe")))
