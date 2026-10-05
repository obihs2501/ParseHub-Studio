package io.github.obihs2501.parsehubstudio.sidecar

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows 11 系统级 Mica 材质（与 PixEz-MIUIX 相同的 DWM 方案）。
 *
 * - Windows 11 22H2+ (build >= 22621): DWMWA_SYSTEMBACKDROP_TYPE = DWMSBT_MAINWINDOW
 * - Windows 11 21H2 (build >= 22000):  DWMWA_MICA_EFFECT
 * - 暗色标题栏: DWMWA_USE_IMMERSIVE_DARK_MODE
 * - 非 Windows / 低版本安全降级
 */
object WindowsMica {

    internal interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: IntByReference,
            cbAttribute: Int,
        ): WinNT.HRESULT

        companion object {
            val INSTANCE: DwmApi? by lazy {
                runCatching { Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
            }
        }
    }

    internal interface NtDll : StdCallLibrary {
        fun RtlGetVersion(versionInformation: WinNT.OSVERSIONINFOEX): Int

        companion object {
            val INSTANCE: NtDll? by lazy {
                runCatching { Native.load("ntdll", NtDll::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
            }
        }
    }

    const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    const val DWMWA_MICA_EFFECT = 1029
    const val DWMSBT_MAINWINDOW = 2

    val windowsBuildNumber: Int by lazy {
        val os = System.getProperty("os.name").orEmpty()
        if (!os.contains("Windows", ignoreCase = true)) return@lazy -1
        runCatching {
            val info = WinNT.OSVERSIONINFOEX()
            info.dwOSVersionInfoSize = WinDef.DWORD(info.size().toLong())
            val status = NtDll.INSTANCE?.RtlGetVersion(info) ?: -1
            if (status == 0) info.dwBuildNumber.toInt() else -1
        }.getOrDefault(-1)
    }

    val isWindows: Boolean get() = windowsBuildNumber > 0
    val isWindows11: Boolean get() = windowsBuildNumber >= 22000
    val isWindows11_22H2OrGreater: Boolean get() = windowsBuildNumber >= 22621

    /** 把 Mica 与主题属性应用到 AWT 窗口句柄。 */
    fun apply(hwnd: Long, isDark: Boolean): Boolean {
        val dwm = DwmApi.INSTANCE ?: return false
        if (hwnd == 0L) return false
        val handle = WinDef.HWND(Pointer.createConstant(hwnd))
        var success = false

        if (windowsBuildNumber >= 19041 || isWindows11) {
            val darkVal = IntByReference(if (isDark) 1 else 0)
            dwm.DwmSetWindowAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE, darkVal, 4)
        }
        if (isWindows11_22H2OrGreater) {
            val backdrop = IntByReference(DWMSBT_MAINWINDOW)
            val hr = dwm.DwmSetWindowAttribute(handle, DWMWA_SYSTEMBACKDROP_TYPE, backdrop, 4)
            success = hr == W32Errors.S_OK || hr.toInt() == 0
        } else if (isWindows11) {
            val mica = IntByReference(1)
            val hr = dwm.DwmSetWindowAttribute(handle, DWMWA_MICA_EFFECT, mica, 4)
            success = hr == W32Errors.S_OK || hr.toInt() == 0
        }
        return success
    }
}
