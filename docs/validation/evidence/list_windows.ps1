Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public class WinList {
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc enumProc, IntPtr lParam);
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
}
"@

[WinList]::EnumWindows({
    param($hWnd, $lParam)
    if ([WinList]::IsWindowVisible($hWnd)) {
        $sb = New-Object System.Text.StringBuilder 256
        [WinList]::GetWindowText($hWnd, $sb, 256)
        $title = $sb.ToString()
        $pid = 0
        [WinList]::GetWindowThreadProcessId($hWnd, [ref]$pid)
        if ($title.Length -gt 0) {
            Write-Host "PID: $pid | Title: $title"
        }
    }
    return $true
}, [IntPtr]::Zero)
