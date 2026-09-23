import json
from pathlib import Path
import runpy
import tempfile
import unittest

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'overlay/usr/local/bin/winnative-steam-compat'))


class ProtonRegistrationTest(unittest.TestCase):
    def test_adopts_arm_tools_without_overriding_per_game_choice(self):
        with tempfile.TemporaryDirectory() as temporary:
            steam = Path(temporary)
            tools = steam / 'compatibilitytools.d'
            names = ['GE-Proton11-7-aarch64', 'proton-cachyos-11.0-20260703-slr-arm64']
            for name in names:
                tool = tools / name
                tool.mkdir(parents=True)
                (tool / 'winnative-proton.json').write_text(json.dumps({'id': name}))
                (tool / 'proton').write_text('#!/bin/sh\nexit 0\n')
                (tool / 'toolmanifest.vdf').write_text('"manifest" { "commandline" "/proton %verb%" "require_tool_appid" "4185400" }')
                (tool / 'compatibilitytool.vdf').write_text('"compatibilitytools" { "compat_tools" { "%s" { "install_path" "." } } }' % name)
            for _ in range(2):
                adopted = MODULE['adopt_extras'](str(tools))
                self.assertEqual(set(names), adopted)
                for name in names:
                    tool = tools / name
                    manifest = (tool / 'toolmanifest.vdf').read_text()
                    self.assertNotIn('require_tool_appid', manifest)
                    self.assertIn(MODULE['EXTRA_WRAPPER'], manifest)
                    self.assertIn('require_tool_appid', (tool / 'toolmanifest.vdf.winnative-original').read_text())
                    wrapper = tool / MODULE['EXTRA_WRAPPER']
                    self.assertIn('"$here/proton"', wrapper.read_text())
                    self.assertTrue(wrapper.stat().st_mode & 0o111)
            config = steam / 'config.vdf'
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { "123" { "name" "%s" } "124" { "name" "proton_experimental" } } } } } }' % names[1])
            MODULE['register_default'](str(config), ['123', '124'], adopted)
            content = config.read_text()
            self.assertIn(names[1], content)
            self.assertNotIn('proton_experimental', content)
            self.assertIn('winnative-proton', content)


if __name__ == '__main__':
    unittest.main()
