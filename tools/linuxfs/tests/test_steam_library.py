import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'app/src/main/assets/linuxfs/usr/local/bin/winnative-steam-library'


def load():
    loader = importlib.machinery.SourceFileLoader('winnative_steam_library', str(SCRIPT))
    spec = importlib.util.spec_from_loader('winnative_steam_library', loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class SteamLibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = load()
        self.module.LIBRARY = str(self.root / 'mnt/winnative')
        self.module.LIBRARIES = str(self.root / 'mnt/winnative-lib') + '/'
        self.steam = self.root / 'steam'
        (self.steam / 'steamapps').mkdir(parents=True)

    def library(self, key, apps=()):
        path = self.root / 'mnt/winnative-lib' / key
        (path / 'steamapps').mkdir(parents=True)
        for app in apps:
            (path / 'steamapps' / ('appmanifest_%d.acf' % app)).write_text('"AppState" {}')
        return str(path)

    def folders(self):
        text = (self.steam / 'steamapps/libraryfolders.vdf').read_text()
        entries = self.module.value_of(self.module.parse(text), 'libraryfolders')
        return [(key, self.module.value_of(value, 'path'), self.module.value_of(value, 'label'))
                for key, value in entries]

    def test_missing_list_leaves_client_config_alone(self):
        self.assertIsNone(self.module.wanted_libraries(str(self.root / 'absent')))

    def test_registers_libraries_with_labels_and_apps(self):
        first = self.library('aaa', [70, 10])
        second = self.library('bbb')
        self.module.register(str(self.steam), [(first, 'WinNative'), (second, 'WinNative (SD)')])
        self.assertEqual(self.folders(), [
            ('0', str(self.steam), ''),
            ('1', first, 'WinNative'),
            ('2', second, 'WinNative (SD)'),
        ])
        text = (self.steam / 'steamapps/libraryfolders.vdf').read_text()
        self.assertLess(text.index('"10"'), text.index('"70"'))

    def test_drops_libraries_no_longer_listed_and_keeps_user_folders(self):
        first = self.library('aaa')
        second = self.library('bbb')
        legacy = str(self.root / 'mnt/winnative')
        (self.steam / 'steamapps/libraryfolders.vdf').write_text(
            '"libraryfolders"\n{\n'
            '\t"0"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"contentid"\t\t"5"\n\t}\n'
            '\t"1"\n\t{\n\t\t"path"\t\t"/media/games"\n\t\t"label"\t\t"Games"\n\t}\n'
            '\t"2"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"label"\t\t"WinNative"\n\t}\n'
            '\t"3"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"label"\t\t"old"\n\t}\n'
            '\t"4"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n'
            '}\n' % (self.steam, legacy, first, second))
        self.module.register(str(self.steam), [(first, 'WinNative')])
        self.assertEqual(self.folders(), [
            ('0', str(self.steam), None),
            ('1', '/media/games', 'Games'),
            ('2', first, 'WinNative'),
        ])
        self.assertIn('"contentid"\t\t"5"', (self.steam / 'steamapps/libraryfolders.vdf').read_text())

    def test_unchanged_config_is_not_rewritten(self):
        first = self.library('aaa')
        self.module.register(str(self.steam), [(first, 'WinNative')])
        path = self.steam / 'steamapps/libraryfolders.vdf'
        before = path.stat().st_mtime_ns
        self.module.register(str(self.steam), [(first, 'WinNative')])
        self.assertEqual(before, path.stat().st_mtime_ns)

    def test_marker_keeps_content_id_and_follows_label(self):
        first = self.library('aaa')
        self.module.write_marker(first, 'WinNative')
        marker = Path(first) / 'libraryfolder.vdf'
        block = self.module.value_of(self.module.parse(marker.read_text()), 'libraryfolder')
        content = self.module.value_of(block, 'contentid')
        self.module.write_marker(first, 'WinNative (SD)')
        block = self.module.value_of(self.module.parse(marker.read_text()), 'libraryfolder')
        self.assertEqual(self.module.value_of(block, 'contentid'), content)
        self.assertEqual(self.module.value_of(block, 'label'), 'WinNative (SD)')

    def test_list_skips_folders_without_steamapps(self):
        first = self.library('aaa')
        listing = self.root / 'list'
        listing.write_text('%s\tWinNative\n%s\tGone\n' % (first, self.root / 'mnt/winnative-lib/zzz'))
        self.assertEqual(self.module.wanted_libraries(str(listing)), [(first, 'WinNative')])


if __name__ == '__main__':
    unittest.main()
