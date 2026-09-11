import json, os, subprocess

PREFIX = "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine"
DC = PREFIX + "/drive_c"
CONTAINER = "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.container"
U = 10249

# 1. C:\gameexe.txt holds the L4D2 Windows path (read by the agent; avoids CLI escaping)
gameexe_path = r"Z:\steam_games\Left 4 Dead 2\left4dead2.exe"
open(DC + "/gameexe.txt", "w").write(gameexe_path + "\n")

# 2. add M2 env vars (keep existing token/login env; add appId + gameexe-file)
cfg = json.load(open(CONTAINER))
env = cfg.get("envVars", "")
for kv in ["WN_STEAM_APPID=550", "WN_STEAM_GAMEEXE_FILE=C:/gameexe.txt"]:
    key = kv.split("=", 1)[0]
    if (key + "=") not in env:
        env += " " + kv
cfg["envVars"] = env
json.dump(cfg, open(CONTAINER, "w"))

# 3. perms
for p in [DC + "/gameexe.txt", CONTAINER]:
    subprocess.run(["chown", "%d:%d" % (U, U), p])
    subprocess.run("restorecon -F '%s' 2>/dev/null" % p, shell=True)

print("M2_SETUP_DONE gameexe_written=%s appid=%s gameexefile=%s"
      % (os.path.exists(DC + "/gameexe.txt"),
         "WN_STEAM_APPID=550" in cfg["envVars"],
         "WN_STEAM_GAMEEXE_FILE" in cfg["envVars"]))
