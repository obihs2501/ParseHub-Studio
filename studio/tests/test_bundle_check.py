import runpy
import unittest
from pathlib import Path


checker = runpy.run_path(str(Path(__file__).resolve().parents[1] / "packaging/check_bundle.py"))
inspect_bundle = checker["inspect"]


class BundleCheckTests(unittest.TestCase):
    def test_setuptools_numpy_compatibility_module_is_not_numpy(self):
        source = r"D:\a\ParseHub-Studio\studio\.venv\Lib\site-packages\setuptools\_distutils\compat\numpy.py"
        for kind in ("PYMODULE", "PYMODULE-1", "PYMODULE-2", "DATA"):
            with self.subTest(kind=kind):
                inspect_bundle([("setuptools._distutils.compat.numpy", source, kind)])

    def test_other_packages_may_have_numpy_compatibility_modules(self):
        inspect_bundle([
            ("other.compat.numpy", "/venv/lib/python3.13/site-packages/other/compat/numpy.py", "PYMODULE"),
            ("other/compat/numpy.py", "/venv/lib/python3.13/site-packages/other/compat/numpy.py", "DATA"),
        ])

    def test_real_package_modules_are_rejected(self):
        for package in ("numpy", "cv2", "pydub"):
            for kind in ("PYMODULE", "PYMODULE-1", "PYMODULE-2", "EXTENSION"):
                for name in (package, f"{package}.core", f"{package}/core/module.pyd"):
                    with self.subTest(name=name, kind=kind):
                        with self.assertRaises(AssertionError):
                            inspect_bundle([(name, "/source/module.py", kind)])

    def test_real_package_sources_and_dlls_are_rejected(self):
        for entry in (
            ("renamed.pyd", r"C:\env\Lib\site-packages\numpy\core\module.pyd", "EXTENSION"),
            ("renamed.py", "/venv/site-packages/numpy.py", "DATA"),
            ("renamed.dll", "/venv/dist-packages/numpy.libs/openblas.dll", "BINARY"),
            ("numpy.libs/openblas.dll", "/build/openblas.dll", "BINARY"),
            ("_internal/cv2/opencv.dll", "/build/opencv.dll", "BINARY"),
            ("pydub/data.json", "/build/data.json", "DATA"),
        ):
            with self.subTest(entry=entry):
                with self.assertRaises(AssertionError):
                    inspect_bundle([entry])

    def test_bundled_ffmpeg_and_ffprobe_are_rejected(self):
        for name in ("ffmpeg.exe", "resources/ffprobe.exe", "resources/FFMPEG.EXE", "bin/ffprobe"):
            with self.subTest(name=name):
                with self.assertRaises(AssertionError):
                    inspect_bundle([(name, "/source/tool", "BINARY")])

    def test_exclusion_configuration_is_not_a_bundled_entry(self):
        inspect_bundle(([], ["numpy", "cv2", "pydub"], [("app", "/source/app.py", "PYMODULE")]))

    def test_nested_analysis_entries_are_checked(self):
        with self.assertRaises(AssertionError):
            inspect_bundle(([], [[], [("numpy.core", "/source/core.py", "PYMODULE")]]))


if __name__ == "__main__":
    unittest.main()
