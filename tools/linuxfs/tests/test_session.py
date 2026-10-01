import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SESSION = ROOT / 'app/src/main/assets/linuxfs/usr/local/bin/winnative-session'


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.capture = self.root / 'launch.json'
        self.env = {
            'PATH': str(self.bin),
            'HOME': str(self.root),
            'WN_CAPTURE': str(self.capture),
        }
        for name in ('mkdir', 'seq'):
            (self.bin / name).symlink_to('/usr/bin/' + name)
        self.stub('wayland-info', '#!/bin/sh\nexit 0\n')
        self.stub('gamescope', '''#!/usr/bin/python3
import json
import os
import sys
with open(os.environ['WN_CAPTURE'], 'w') as output:
    json.dump({'args': sys.argv[1:], 'inside': os.environ.get('WN_INSIDE')}, output)
''')

    def stub(self, name, contents):
        path = self.bin / name
        path.write_text(contents)
        path.chmod(0o755)

    def launch(self, *args, **env):
        subprocess.run(['/bin/bash', str(SESSION), *args],
                       env={**self.env, **env}, check=True, capture_output=True, timeout=5)
        return json.loads(self.capture.read_text())

    def test_steam_enforces_session_size_for_game_windows(self):
        launch = self.launch('steam', 'steam://rungameid/123',
                             WN_WIDTH='1920', WN_HEIGHT='1080', WN_REFRESH='120')
        self.assertEqual(launch['args'], [
            '--backend', 'wayland', '--expose-wayland', '-f',
            '-W', '1920', '-H', '1080', '-w', '1920', '-h', '1080', '-r', '120',
            '-e', '--force-windows-fullscreen', '--', str(SESSION),
            'steam', 'steam://rungameid/123',
        ])
        self.assertEqual(launch['inside'], '1')

    def test_frame_limit_takes_precedence_over_refresh_rate(self):
        args = self.launch('steam', WN_FPS='40', WN_REFRESH='120')['args']
        self.assertEqual(args[args.index('-r') + 1], '40')
        self.assertIn('--force-windows-fullscreen', args)

    def test_other_modes_keep_their_window_policy(self):
        for mode, target in (('run', '/games/My Game/game.sh'), ('desktop', '')):
            with self.subTest(mode=mode):
                args = self.launch(mode, *([target] if target else []))['args']
                self.assertNotIn('--force-windows-fullscreen', args)
                self.assertNotIn('-e', args)
                self.assertNotIn('-r', args)
                self.assertEqual(args[args.index('--') + 1:],
                                 [str(SESSION), mode] + ([target] if target else []))


if __name__ == '__main__':
    unittest.main()
