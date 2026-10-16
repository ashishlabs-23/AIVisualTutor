Add-Type @"
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

public static class AivtBlenderFinder {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();

    public static string Find() {
        var blenderProcesses = Process.GetProcessesByName("blender");
        if (blenderProcesses.Length == 0) {
            return "NOT_RUNNING";
        }

        IntPtr bestHwnd = IntPtr.Zero;
        uint bestPid = 0;
        string bestTitle = "";
        RECT bestRect = new RECT();
        bool bestVisible = false;
        bool bestMinimized = false;
        bool bestMaximized = false;
        long maxArea = -1;

        IntPtr fg = GetForegroundWindow();

        EnumWindows((hWnd, lParam) => {
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            bool isBlenderProcess = false;
            foreach (var p in blenderProcesses) {
                if (p.Id == (int)pid) { isBlenderProcess = true; break; }
            }

            if (!isBlenderProcess) return true;

            var sb = new StringBuilder(512);
            GetWindowText(hWnd, sb, 512);
            string title = sb.ToString();

            bool visible = IsWindowVisible(hWnd);
            bool minimized = IsIconic(hWnd);
            bool maximized = IsZoomed(hWnd);
            RECT r;
            GetWindowRect(hWnd, out r);
            int width = r.Right - r.Left;
            int height = r.Bottom - r.Top;
            long area = (long)width * height;

            Console.WriteLine("Found window: hWnd=" + hWnd + ", title='" + title + "', visible=" + visible + ", rect=[" + r.Left + "," + r.Top + "," + width + "x" + height + "]");

            if (minimized && bestHwnd == IntPtr.Zero) {
                bestHwnd = hWnd;
                bestPid = pid;
                bestTitle = title;
                bestRect = r;
                bestVisible = visible;
                bestMinimized = true;
                bestMaximized = false;
            } else if (visible && area > maxArea && (width > 100 && height > 100)) {
                maxArea = area;
                bestHwnd = hWnd;
                bestPid = pid;
                bestTitle = title;
                bestRect = r;
                bestVisible = visible;
                bestMinimized = false;
                bestMaximized = maximized;
            }

            return true;
        }, IntPtr.Zero);

        if (bestHwnd == IntPtr.Zero) {
            return "NOT_FOUND";
        }

        int w = bestRect.Right - bestRect.Left;
        int h = bestRect.Bottom - bestRect.Top;
        bool isFg = (bestHwnd == fg);
        string b64Title = Convert.ToBase64String(Encoding.UTF8.GetBytes(bestTitle));

        return "FOUND|" + bestHwnd.ToInt64() + "|" + bestPid + "|" + b64Title + "|" +
               bestRect.Left + "|" + bestRect.Top + "|" + w + "|" + h + "|" +
               bestVisible + "|" + bestMinimized + "|" + bestMaximized + "|" + isFg;
    }
}
"@
[AivtBlenderFinder]::Find()
