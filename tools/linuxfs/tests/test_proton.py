import importlib.machinery
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'app/src/main/assets/linuxfs/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(ASSETS / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class ProtonTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.compat = load('winnative-steam-compat')
        self.audio = load('winnative-directaudio')

    def test_runtime_sources_match_apk(self):
        for name in ('winnative-session', 'winnative-steam-compat', 'winnative-directaudio',
                     'winnative-seed-redists', 'winnative-proton-launch'):
            self.assertEqual((ASSETS / name).read_bytes(),
                             (ROOT / 'tools/linuxfs/overlay/usr/local/bin' / name).read_bytes())

    def test_tools_are_stable_and_user_choices_survive(self):
        tools = self.root / 'compatibilitytools.d'
        tool = tools / 'proton-cachyos-arm64'
        tool.mkdir(parents=True)
        manifest = tool / 'toolmanifest.vdf'
        original = '"manifest" { "commandline" "/proton %verb%" "require_tool_appid" "4185400" }'
        manifest.write_text(original)
        (tool / 'proton').write_text('')
        (tool / 'compatibilitytool.vdf').write_text('"compatibilitytools" { "compat_tools" { "cachy-custom" {} } }')
        self.compat.build_tool(str(tools))
        names = self.compat.adopt_extras(str(tools))
        self.assertEqual(names, {'cachy-custom'})
        self.assertNotIn('require_tool_appid', manifest.read_text())
        self.assertEqual((tool / 'toolmanifest.vdf.winnative-original').read_text(), original)
        generated = [manifest, tool / 'winnative-proton-wrap', tools / 'winnative-proton/winnative-launch']
        before = [(p.stat().st_ino, p.stat().st_mtime_ns) for p in generated]
        self.compat.build_tool(str(tools))
        self.compat.adopt_extras(str(tools))
        self.assertEqual(before, [(p.stat().st_ino, p.stat().st_mtime_ns) for p in generated])
        config = self.root / 'config.vdf'
        config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { "7" { "name" "cachy-custom" } "8" { "name" "proton-experimental-arm64" } } } } } }')
        self.compat.register_default(str(config), ['7', '8', '9'], names)
        self.assertIn('"cachy-custom"', config.read_text())
        self.assertNotIn('"proton-experimental-arm64"', config.read_text())
        config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" {} } } }')
        self.compat.register_default(str(config), ['9'])
        self.assertIn('"9"', config.read_text())

    def test_refresh_does_not_rewrite_steam_config(self):
        config = self.root / 'config/config.vdf'
        config.parent.mkdir()
        content = '"InstallConfigStore" { "Software" { "Valve" { "Steam" {} } } }'
        config.write_text(content)
        subprocess.run(['python3', str(ASSETS / 'winnative-steam-compat'), str(self.root), '--refresh'], check=True, capture_output=True)
        self.assertEqual(config.read_text(), content)

    def test_audio_prepares_selected_tool_and_clears_incompatible_override(self):
        stage = self.root / 'stage'
        self.audio.STAGE = str(stage)
        files = self.root / 'GE/files'
        template = files / 'share/default_pfx_arm64'
        prefix = self.root / 'compat/pfx'
        for p in (template, prefix):
            (p / 'drive_c/windows/system32').mkdir(parents=True)
            (p / 'drive_c/windows/syswow64').mkdir()
            (p / 'user.reg').write_text('WINE REGISTRY Version 2\n')
        for part in (self.audio.UNIXLIB, *self.audio.HALVES):
            dest = stage / part
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(b'driver')
            (files / 'lib/wine' / Path(part).parent).mkdir(parents=True, exist_ok=True)
        def library(path, count):
            subprocess.run(['cc', '-shared', '-fPIC', '-x', 'c', '-', '-o', str(path)],
                           input=f'void *__wine_unix_call_funcs[{count}];'.encode(), check=True)
        library(stage / self.audio.UNIXLIB, 8)
        alsa = files / 'lib/wine/aarch64-unix/winealsa.so'
        library(alsa, 8)
        self.audio.prepare_launch(str(files), str(prefix.parent), True)
        for p in (prefix, template):
            self.assertIn('"Audio"="directaudio"', (p / 'user.reg').read_text())
            self.assertEqual((p / 'drive_c/windows/system32/winedirectaudio.drv').read_bytes(), b'driver')
        library(alsa, 9)
        self.audio.prepare_launch(str(files), str(prefix.parent), True)
        for p in (prefix, template):
            self.assertNotIn('"Audio"="directaudio"', (p / 'user.reg').read_text())

    def test_launch_preserves_arguments_environment_and_exit_status(self):
        tool = self.root / 'GE Proton'
        (tool / 'files/bin-arm64').mkdir(parents=True)
        wine = tool / 'files/bin-arm64/wine'
        wine.write_text('#!/bin/sh\nexit 0\n')
        wine.chmod(0o755)
        proton = tool / 'proton'
        proton.write_text('#!/bin/sh\nprintf "%s\\n" "$PROTON_USE_ARM64" "$PROTON_LOG_DIR" "$@"\nexit 23\n')
        proton.chmod(0o755)
        audio = self.root / 'winnative-directaudio'
        audio.write_text('#!/bin/sh\nexit 0\n')
        audio.chmod(0o755)
        env = dict(os.environ, PATH=str(self.root) + ':' + os.environ['PATH'], WN_LOG=str(self.root / 'logs/session.log'))
        result = subprocess.run(['sh', str(ASSETS / 'winnative-proton-launch'), str(proton),
                                 'waitforexitandrun', '/game path/a.exe', '', 'one "two"'], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(result.stdout.splitlines(), ['1', str(self.root / 'logs'), 'waitforexitandrun', '/game path/a.exe', '', 'one "two"'])
        wine.unlink()
        self.assertEqual(subprocess.run(['sh', str(ASSETS / 'winnative-proton-launch'), str(proton)], env=env, capture_output=True).returncode, 126)


if __name__ == '__main__':
    unittest.main()
