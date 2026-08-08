import copy
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))

from catalog import CatalogError, add_profile, is_adapted, parse_play_version


CATALOG = {
    "schemaVersion": 1,
    "revision": 2026080802,
    "targetPackage": "com.zhihu.android",
    "profiles": [{
        "id": "play-10.95.0-29522",
        "channel": "play",
        "displayName": "Google Play 版",
        "versionName": "10.95.0",
        "minVersionCode": 29522,
        "maxVersionCode": 29522,
        "status": "adapted",
        "symbols": {},
    }],
}


class CatalogTest(unittest.TestCase):
    def test_play_version_uses_google_play_version_field(self):
        html = 'prefix [[[["10.95.0"]],[[[36]],[[[21,"5.0"]]]]] suffix'
        self.assertEqual("10.95.0", parse_play_version(html))

    def test_play_version_rejects_ambiguous_fields(self):
        html = '[[["10.95.0"]],[[[36]] [[["10.96.0"]],[[[36]]'
        with self.assertRaises(CatalogError):
            parse_play_version(html)

    def test_adapted_match_is_channel_specific(self):
        self.assertTrue(is_adapted(CATALOG, "play", "10.95.0", 29522))
        self.assertFalse(is_adapted(CATALOG, "domestic", "10.95.0", 29522))
        self.assertFalse(is_adapted(CATALOG, "play", "10.95.0", 30000))

    def test_add_profile_is_exact_and_does_not_invent_symbols(self):
        catalog = copy.deepcopy(CATALOG)
        add_profile(catalog, "domestic", "11.5.0", 40500, 2026080901)
        profile = catalog["profiles"][-1]
        self.assertEqual("domestic-11.5.0-40500", profile["id"])
        self.assertEqual(40500, profile["minVersionCode"])
        self.assertEqual(40500, profile["maxVersionCode"])
        self.assertEqual({}, profile["symbols"])
        self.assertEqual(2026080901, catalog["revision"])

    def test_add_profile_rejects_duplicate(self):
        with self.assertRaises(CatalogError):
            add_profile(copy.deepcopy(CATALOG), "play", "10.95.0", 29522)


if __name__ == "__main__":
    unittest.main()
