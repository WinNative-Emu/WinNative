import os, subprocess
PREFIX = "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine"
IMAGEFS = "/data/data/com.tencent.ig/files/imagefs"
STEAMAPPS = PREFIX + "/drive_c/Program Files (x86)/Steam/steamapps"
COMMON = STEAMAPPS + "/common"
DC = PREFIX + "/drive_c"
U = 10249

# 1. steamapps/common/Left 4 Dead 2  ->  symlink to the real (13GB) game dir (no copy)
os.makedirs(COMMON, exist_ok=True)
link = COMMON + "/Left 4 Dead 2"
target = IMAGEFS + "/steam_games/Left 4 Dead 2"
if os.path.islink(link) or os.path.exists(link):
    try:
        os.remove(link)
    except Exception:
        pass
os.symlink(target, link)

# 2. gameexe.txt -> the steamapps\common path so stage_app_manifest fires + LaunchApp resolves it
gameexe = r"C:\Program Files (x86)\Steam\steamapps\common\Left 4 Dead 2\left4dead2.exe"
open(DC + "/gameexe.txt", "w").write(gameexe + "\n")

# 3. perms / SELinux
subprocess.run(["chown", "-h", "%d:%d" % (U, U), link])
subprocess.run(["chown", "%d:%d" % (U, U), DC + "/gameexe.txt"])
subprocess.run("restorecon -F '%s' 2>/dev/null; restorecon -RF '%s' 2>/dev/null" % (DC + "/gameexe.txt", STEAMAPPS), shell=True)

print("M2B_DONE symlink_ok=%s target_exists=%s exe_resolves=%s"
      % (os.path.islink(link), os.path.exists(target),
         os.path.exists(link + "/left4dead2.exe")))
