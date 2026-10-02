import json
import tomllib
import unittest
from pathlib import Path

from parsehub_studio import __version__


class VersionTests(unittest.TestCase):
    def test_release_versions_are_consistent(self):
        root = Path(__file__).resolve().parents[1]
        for filename, section in (("pyproject.toml", "project"), ("src-tauri/Cargo.toml", "package")):
            data = tomllib.loads((root / filename).read_text(encoding="utf-8"))
            self.assertEqual(data[section]["version"], __version__, filename)
        for filename in ("package.json", "package-lock.json", "src-tauri/tauri.conf.json"):
            data = json.loads((root / filename).read_text(encoding="utf-8"))
            self.assertEqual(data["version"], __version__, filename)
            if filename == "package-lock.json":
                self.assertEqual(data["packages"][""]["version"], __version__)
        for filename in ("uv.lock", "src-tauri/Cargo.lock"):
            data = tomllib.loads((root / filename).read_text(encoding="utf-8"))
            package = next(item for item in data["package"] if item["name"] == "parsehub-studio")
            self.assertEqual(package["version"], __version__, filename)
        workflow = (root.parent / ".github/workflows/studio-build.yml").read_text(encoding="utf-8")
        self.assertIn(f"tag_name: studio-v{__version__}-", workflow)
        self.assertIn(f"name: ParseHub Studio {__version__} ", workflow)
