Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Drawing;
using System.Drawing.Imaging;

public class WinHelper {
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc enumProc, IntPtr lParam);
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlink, uint nFlags);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }
}
"@ -ReferencedAssemblies "System.Drawing.dll"

Write-Host "Launching app..."
$proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c gradlew.bat run" -PassThru -NoNewWindow
Start-Sleep -Seconds 18

$javaProcs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*MainKt*" }
Write-Host "Found java processes: $($javaProcs.Count)"

$foundWindows = @()
$procIds = $javaProcs | ForEach-Object { $_.ProcessId }

[WinHelper]::EnumWindows({
    param($hWnd, $lParam)
    $pid = 0
    [WinHelper]::GetWindowThreadProcessId($hWnd, [ref]$pid)
    if ($procIds -contains $pid) {
        $sb = New-Object System.Text.StringBuilder 256
        [WinHelper]::GetWindowText($hWnd, $sb, 256)
        $title = $sb.ToString()
        $isVisible = [WinHelper]::IsWindowVisible($hWnd)
        $rect = New-Object WinHelper+RECT
        [WinHelper]::GetWindowRect($hWnd, [ref]$rect)
        $w = $rect.Right - $rect.Left
        $h = $rect.Bottom - $rect.Top
        Write-Host "Window: '$title', Visible: $isVisible, Rect: [$($rect.Left), $($rect.Top), $w x $h]"

        if ($isVisible -and $w -gt 0 -and $h -gt 0) {
            $bmp = New-Object System.Drawing.Bitmap $w, $h
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $hdc = $g.GetHdc()
            # 2 = PW_RENDERFULLCONTENT
            [WinHelper]::PrintWindow($hWnd, $hdc, 2)
            $g.ReleaseHdc($hdc)
            $g.Dispose()
            $safeTitle = if ($title) { $title -replace '[^a-zA-Z0-9]', '_' } else { "window_$hWnd" }
            $path = "c:\Users\PC\Desktop\AIVisualTutor\docs\validation\evidence\snap_$safeTitle.png"
            $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
            $bmp.Dispose()
            Write-Host "Saved snap to $path"
        }
    }
    return $true
}, [IntPtr]::Zero)

# Also capture full desktop
Add-Type -AssemblyName System.Windows.Forms
$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmpFull = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$gFull = [System.Drawing.Graphics]::FromImage($bmpFull)
$gFull.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bmpFull.Save("c:\Users\PC\Desktop\AIVisualTutor\docs\validation\evidence\full_screen_probe.png", [System.Drawing.Imaging.ImageFormat]::Png)
$gFull.Dispose()
$bmpFull.Dispose()
Write-Host "Saved full screen probe."

Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*MainKt*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Write-Host "Finished probe."
