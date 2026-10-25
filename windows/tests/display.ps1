# Changes the main screen's resolution (for the test of a screen that
# changes size while it's being sent), or lists the modes it offers.
#   powershell -File display.ps1 -Width 1280 -Height 720
#   powershell -File display.ps1 -List
param([int]$Width = 0, [int]$Height = 0, [switch]$List)

Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class MangoDisplay {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DEVMODE {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2;
        public int dmPanningWidth, dmPanningHeight;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int ChangeDisplaySettings(ref DEVMODE dm, int flags);

    static DEVMODE Fresh() {
        DEVMODE dm = new DEVMODE();
        dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE));
        return dm;
    }

    public static string[] Modes() {
        var seen = new SortedSet<string>();
        DEVMODE dm = Fresh();
        for (int i = 0; EnumDisplaySettings(null, i, ref dm); i++) seen.Add(dm.dmPelsWidth + "x" + dm.dmPelsHeight);
        var list = new string[seen.Count];
        seen.CopyTo(list);
        return list;
    }

    public static int Set(int width, int height) {
        DEVMODE dm = Fresh();
        EnumDisplaySettings(null, -1, ref dm);  // the current mode
        dm.dmPelsWidth = width;
        dm.dmPelsHeight = height;
        dm.dmFields = 0x80000 | 0x100000;  // DM_PELSWIDTH | DM_PELSHEIGHT
        return ChangeDisplaySettings(ref dm, 0);
    }
}
"@

if ($List) {
    [MangoDisplay]::Modes() -join ' '
    exit 0
}
$result = [MangoDisplay]::Set($Width, $Height)
"display.ps1: ${Width}x${Height}: result $result"
exit $result
