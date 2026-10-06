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

    def test_dependency_dll_present_in_env_but_not_bundled_is_rejected(self):
        # 2026-10-05 事故：_ctypes.pyd 进了包，环境里明明有 ffi.dll 却被静默丢弃
        entries = [("_ctypes.pyd", r"C:\env\DLLs\_ctypes.pyd", "EXTENSION")]
        problems = checker["check_runtime_dlls"](
            entries, exists=lambda path: str(path).lower().endswith("ffi.dll"),
        )
        self.assertTrue(problems)

    def test_bundled_dependency_dll_is_accepted(self):
        entries = [
            ("_ctypes.pyd", r"C:\env\DLLs\_ctypes.pyd", "EXTENSION"),
            ("ffi.dll", r"C:\env\Library\bin\ffi.dll", "BINARY"),
            ("_sqlite3.pyd", r"C:\env\DLLs\_sqlite3.pyd", "EXTENSION"),
            ("sqlite3.dll", r"C:\env\Library\bin\sqlite3.dll", "BINARY"),
        ]
        self.assertEqual([], checker["check_runtime_dlls"](entries, exists=lambda path: True))

    def test_statically_linked_extension_is_accepted(self):
        # python.org 的 _lzma/_bz2/pyexpat 是静态链接，环境里根本没有独立 DLL
        entries = [("_lzma.pyd", r"C:\env\DLLs\_lzma.pyd", "EXTENSION")]
        advisory = checker["check_runtime_dlls"](
            entries, exists=lambda path: False, groups=checker["ADVISORY_DLL_GROUPS"],
        )
        self.assertEqual([], advisory)

    def test_advisory_group_reports_env_dll_missing_from_bundle(self):
        entries = [("_lzma.pyd", r"C:\env\DLLs\_lzma.pyd", "EXTENSION")]
        advisory = checker["check_runtime_dlls"](
            entries,
            exists=lambda path: str(path).lower().endswith("liblzma.dll"),
            groups=checker["ADVISORY_DLL_GROUPS"],
        )
        self.assertTrue(advisory)

    def test_foreign_toolchain_dll_source_is_rejected(self):
        for source in (
            r"C:\Program Files\Git\mingw64\bin\libssl-3-x64.dll",
            r"C:\Program Files\Netease\MuMu\nx_main\liblzma.dll",
            r"C:\msys64\mingw64\bin\libcrypto-3-x64.dll",
        ):
            with self.subTest(source=source):
                self.assertTrue(checker["check_sources"]([("libssl-3-x64.dll", source, "BINARY")]))

    def test_system_crt_dll_is_rejected(self):
        self.assertTrue(checker["check_sources"]([("ucrtbase.dll", r"C:\env\ucrtbase.dll", "BINARY")]))

    def test_interpreter_library_dlls_are_accepted(self):
        entries = [
            ("libcrypto-3-x64.dll", r"C:\env\Library\bin\libcrypto-3-x64.dll", "BINARY"),
            ("_ssl.pyd", r"C:\env\DLLs\_ssl.pyd", "EXTENSION"),
        ]
        self.assertEqual([], checker["check_sources"](entries))


if __name__ == "__main__":
    unittest.main()
