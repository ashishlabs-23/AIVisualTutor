using System.Drawing.Imaging;
using System.Globalization;
using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using WgcSharp;

internal static class Program
{
    private const int InvalidArguments = 1;
    private const int NoSuitableWindow = 2;
    private const int CaptureFailed = 3;
    private const int OutputFailed = 4;
    private const int UnexpectedFailure = 5;

    private static int Main(string[] args)
    {
        try
        {
            return Run(args);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Unexpected bridge error: {ex}");
            return UnexpectedFailure;
        }
    }

    private static int Run(string[] args)
    {
        if (args.Length > 0 && args[0] == "--capture-region")
        {
            if (!TryParseRegionArguments(args, out int x, out int y, out int width, out int height))
            {
                Console.Error.WriteLine("Usage: wgc-bridge --capture-region --x <X> --y <Y> --width <positive width> --height <positive height>");
                return InvalidArguments;
            }

            return CaptureRegion(x, y, width, height);
        }

        if (!TryParseCaptureArguments(args, out bool usePreviousWindow, out uint callerPid))
        {
            Console.Error.WriteLine(
                "Usage: wgc-bridge --capture-foreground|--capture-previous-window --caller-pid <positive PID>");
            return InvalidArguments;
        }

        IntPtr targetHwnd;
        uint targetPid;
        try
        {
            if (usePreviousWindow)
            {
                if (!TryFindPreviousEligibleWindow(callerPid, out targetHwnd, out targetPid))
                {
                    Console.Error.WriteLine(
                        "No suitable external window: no visible eligible top-level window outside the caller process was found.");
                    return NoSuitableWindow;
                }
            }
            else
            {
                targetHwnd = NativeMethods.GetForegroundWindow();
                if (targetHwnd == IntPtr.Zero)
                {
                    Console.Error.WriteLine("No suitable foreground window: Windows returned a null foreground HWND.");
                    return NoSuitableWindow;
                }

                NativeMethods.GetWindowThreadProcessId(targetHwnd, out targetPid);
                if (targetPid == 0)
                {
                    Console.Error.WriteLine("No suitable foreground window: its owning process could not be determined.");
                    return NoSuitableWindow;
                }

                if (targetPid == callerPid)
                {
                    Console.Error.WriteLine("No suitable foreground window: the foreground window belongs to the caller process.");
                    return NoSuitableWindow;
                }
            }

            Console.Error.WriteLine(
                $"Capturing {(usePreviousWindow ? "previous eligible" : "foreground")} HWND " +
                $"0x{targetHwnd.ToInt64():X} (PID {targetPid}).");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Could not determine the capture target: {ex.Message}");
            return UnexpectedFailure;
        }

        System.Drawing.Bitmap bitmap;
        try
        {
            bitmap = WindowCapture.CaptureWindow(
                targetHwnd,
                CaptureStrategy.WgcOnly,
                timeoutMs: 8000,
                logger: new StderrLogger())
                ?? throw new InvalidOperationException("Windows.Graphics.Capture returned no frame.");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Capture failed: {ex.Message}");
            return CaptureFailed;
        }

        string outputPath = Path.GetFullPath(Path.Combine(
            Path.GetTempPath(),
            $"AIVisualTutor_capture_{Guid.NewGuid():N}.png"));

        try
        {
            using (bitmap)
            {
                bitmap.Save(outputPath, ImageFormat.Png);
            }

            var output = new FileInfo(outputPath);
            if (!output.Exists || output.Length == 0)
                throw new IOException("The PNG was not created or is empty.");
        }
        catch (Exception ex)
        {
            try
            {
                if (File.Exists(outputPath)) File.Delete(outputPath);
            }
            catch (Exception cleanupException)
            {
                Console.Error.WriteLine($"Could not remove incomplete PNG: {cleanupException.Message}");
            }

            Console.Error.WriteLine($"Could not write the temporary PNG: {ex.Message}");
            return OutputFailed;
        }

        Console.Out.WriteLine(outputPath);
        return 0;
    }

    private static int CaptureRegion(int x, int y, int width, int height)
    {
        string outputPath = Path.GetFullPath(Path.Combine(
            Path.GetTempPath(),
            $"AIVisualTutor_capture_{Guid.NewGuid():N}.png"));

        try
        {
            IntPtr previousDpiContext = NativeMethods.SetThreadDpiAwarenessContext(
                new IntPtr(-4)); // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
            if (previousDpiContext == IntPtr.Zero)
                throw new InvalidOperationException("Could not enable per-monitor DPI awareness for region capture.");

            try
            {
                using var bitmap = new System.Drawing.Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                using (var graphics = System.Drawing.Graphics.FromImage(bitmap))
                {
                    graphics.CopyFromScreen(
                        x,
                        y,
                        0,
                        0,
                        new System.Drawing.Size(width, height),
                        System.Drawing.CopyPixelOperation.SourceCopy);
                }

                bitmap.Save(outputPath, ImageFormat.Png);
            }
            finally
            {
                NativeMethods.SetThreadDpiAwarenessContext(previousDpiContext);
            }

            var output = new FileInfo(outputPath);
            if (!output.Exists || output.Length == 0)
                throw new IOException("The region PNG was not created or is empty.");
        }
        catch (Exception ex)
        {
            try
            {
                if (File.Exists(outputPath)) File.Delete(outputPath);
            }
            catch (Exception cleanupException)
            {
                Console.Error.WriteLine($"Could not remove incomplete PNG: {cleanupException.Message}");
            }

            Console.Error.WriteLine($"Region capture failed: {ex.Message}");
            return CaptureFailed;
        }

        Console.Out.WriteLine(outputPath);
        return 0;
    }

    private static bool TryParseCaptureArguments(
        string[] arguments,
        out bool usePreviousWindow,
        out uint callerPid)
    {
        usePreviousWindow = false;
        callerPid = 0;
        if (arguments.Length != 3 ||
            (arguments[0] != "--capture-foreground" && arguments[0] != "--capture-previous-window"))
        {
            return false;
        }

        if (arguments[1] != "--caller-pid"
            || !uint.TryParse(arguments[2], NumberStyles.None, CultureInfo.InvariantCulture, out callerPid)
            || callerPid == 0)
        {
            return false;
        }

        usePreviousWindow = arguments[0] == "--capture-previous-window";
        return true;
    }

    private static bool TryFindPreviousEligibleWindow(uint callerPid, out IntPtr targetHwnd, out uint targetPid)
    {
        IntPtr selectedHwnd = IntPtr.Zero;
        uint selectedPid = 0;

        NativeMethods.EnumWindows((window, _) =>
        {
            if (!NativeMethods.IsWindowVisible(window) ||
                NativeMethods.GetWindow(window, NativeMethods.GwOwner) != IntPtr.Zero)
            {
                return true;
            }

            NativeMethods.GetWindowThreadProcessId(window, out uint windowPid);
            if (windowPid == 0 || windowPid == callerPid || IsShellWindow(window))
            {
                return true;
            }

            var extendedStyle = NativeMethods.GetWindowLongPtr(window, NativeMethods.GwlExStyle).ToInt64();
            if ((extendedStyle & NativeMethods.WsExToolWindow) != 0)
            {
                return true;
            }

            if (NativeMethods.DwmGetWindowAttribute(
                    window,
                    NativeMethods.DwmwaCloaked,
                    out int isCloaked,
                    sizeof(int)) == 0 &&
                isCloaked != 0)
            {
                return true;
            }

            selectedHwnd = window;
            selectedPid = windowPid;
            return false;
        }, IntPtr.Zero);

        targetHwnd = selectedHwnd;
        targetPid = selectedPid;
        return selectedHwnd != IntPtr.Zero;
    }

    private static bool IsShellWindow(IntPtr window)
    {
        var className = new System.Text.StringBuilder(256);
        NativeMethods.GetClassName(window, className, className.Capacity);
        return className.ToString() is
            "Progman" or
            "WorkerW" or
            "Shell_TrayWnd" or
            "Shell_SecondaryTrayWnd" or
            "NotifyIconOverflowWindow";
    }

    private static bool TryParseRegionArguments(
        string[] arguments,
        out int x,
        out int y,
        out int width,
        out int height)
    {
        x = 0;
        y = 0;
        width = 0;
        height = 0;

        return arguments.Length == 9
            && arguments[1] == "--x"
            && int.TryParse(arguments[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out x)
            && arguments[3] == "--y"
            && int.TryParse(arguments[4], NumberStyles.Integer, CultureInfo.InvariantCulture, out y)
            && arguments[5] == "--width"
            && int.TryParse(arguments[6], NumberStyles.Integer, CultureInfo.InvariantCulture, out width)
            && width > 0
            && arguments[7] == "--height"
            && int.TryParse(arguments[8], NumberStyles.Integer, CultureInfo.InvariantCulture, out height)
            && height > 0;
    }
}

internal sealed class StderrLogger : ILogger
{
    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
    public bool IsEnabled(LogLevel logLevel) => true;

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        Console.Error.WriteLine(formatter(state, exception));
        if (exception is not null) Console.Error.WriteLine(exception);
    }
}

internal static class NativeMethods
{
    internal const int GwOwner = 4;
    internal const int GwlExStyle = -20;
    internal const long WsExToolWindow = 0x00000080L;
    internal const int DwmwaCloaked = 14;

    internal delegate bool EnumWindowsProc(IntPtr window, IntPtr parameter);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern IntPtr GetWindow(IntPtr window, uint command);

    [DllImport("user32.dll", EntryPoint = "GetClassNameW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern int GetClassName(IntPtr window, System.Text.StringBuilder className, int maxCount);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", ExactSpelling = true, SetLastError = true)]
    internal static extern IntPtr GetWindowLongPtr(IntPtr window, int index);

    [DllImport("dwmapi.dll", ExactSpelling = true)]
    internal static extern int DwmGetWindowAttribute(
        IntPtr window,
        int attribute,
        out int value,
        int size);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    internal static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    internal static extern IntPtr SetThreadDpiAwarenessContext(IntPtr dpiContext);
}
