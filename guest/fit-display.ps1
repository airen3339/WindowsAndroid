# SPDX-License-Identifier: GPL-2.0-or-later
# WindowsAndroid: make the desktop fill the screen.
#
# The virtual PC's screen is whatever size the app booted it at (the phone, or an external
# monitor's size). Windows remembers the desktop size from earlier boots and would show that,
# scaled, instead; at sign-in this sets the desktop to the size actually being sent to the
# screen (the display path's target mode). Run from HKLM\...\Run by provisioning.
#
# Compiling the C# below takes a while under emulation, so the first run keeps the result in
# %LOCALAPPDATA%\WindowsAndroid and later sign-ins only load it.
$ErrorActionPreference = 'Stop'
$source = @'
using System;
using System.Runtime.InteropServices;
public static class WaFitDisplay {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DEVMODE {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency,
            dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2,
            dmPanningWidth, dmPanningHeight;
    }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int ChangeDisplaySettingsEx(string device, ref DEVMODE dm, IntPtr hwnd, int flags, IntPtr param);

    [DllImport("user32.dll")]
    static extern int GetDisplayConfigBufferSizes(uint flags, out uint numPaths, out uint numModes);
    [DllImport("user32.dll")]
    static extern int QueryDisplayConfig(uint flags, ref uint numPaths, byte[] paths, ref uint numModes, byte[] modes, IntPtr topology);

    // The screen's own size: the active size of the first target mode of the active paths.
    // DISPLAYCONFIG_PATH_INFO is 72 bytes, DISPLAYCONFIG_MODE_INFO 64; in a target mode
    // (infoType 2) the video signal's activeSize sits at bytes 40..47.
    static bool ScreenSize(out int w, out int h) {
        w = h = 0;
        uint np, nm;
        if (GetDisplayConfigBufferSizes(2 /* QDC_ONLY_ACTIVE_PATHS */, out np, out nm) != 0) return false;
        var paths = new byte[np * 72];
        var modes = new byte[nm * 64];
        if (QueryDisplayConfig(2, ref np, paths, ref nm, modes, IntPtr.Zero) != 0) return false;
        for (int i = 0; i < nm; i++) {
            int o = i * 64;
            if (BitConverter.ToInt32(modes, o) != 2) continue;
            w = BitConverter.ToInt32(modes, o + 40);
            h = BitConverter.ToInt32(modes, o + 44);
            if (w >= 640 && h >= 480) return true;
        }
        return false;
    }

    static DEVMODE New() { var dm = new DEVMODE(); dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)); return dm; }

    public static string Run() {
        var cur = New();
        if (!EnumDisplaySettings(null, -1, ref cur)) return "no current mode";
        int w, h;
        if (!ScreenSize(out w, out h)) return "no screen size";
        var best = cur;
        best.dmPelsWidth = w;
        best.dmPelsHeight = h;
        if (best.dmPelsWidth == cur.dmPelsWidth && best.dmPelsHeight == cur.dmPelsHeight)
            return "already " + cur.dmPelsWidth + "x" + cur.dmPelsHeight;
        best.dmFields = 0x80000 | 0x100000; // DM_PELSWIDTH | DM_PELSHEIGHT
        int r = ChangeDisplaySettingsEx(null, ref best, IntPtr.Zero, 1 /* CDS_UPDATEREGISTRY */, IntPtr.Zero);
        return cur.dmPelsWidth + "x" + cur.dmPelsHeight + " -> " + best.dmPelsWidth + "x" + best.dmPelsHeight + " (" + r + ")";
    }
}
'@
$cache = Join-Path $env:LOCALAPPDATA 'WindowsAndroid'
$dll = Join-Path $cache 'WaFitDisplay-2.dll'
if (-not (Test-Path $dll)) {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    Add-Type -TypeDefinition $source -OutputAssembly $dll
}
if (-not ('WaFitDisplay' -as [type])) { Add-Type -Path $dll }
$result = [WaFitDisplay]::Run()
try { Set-Content -Path (Join-Path $cache 'fit-display.log') -Value "$(Get-Date -Format s) $result" } catch {}
