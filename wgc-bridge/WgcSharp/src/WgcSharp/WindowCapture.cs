using System;
using System.Drawing;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace WgcSharp;

/// <summary>
/// Captures windows by HWND, including occluded, minimized, and DirectX/OpenGL game windows.
/// Uses Windows.Graphics.Capture (WGC) as primary method with DXGI Desktop Duplication fallback.
/// </summary>
public static class WindowCapture
{
    /// <summary>
    /// Captures a window as a <see cref="Bitmap"/>.
    /// </summary>
    public static Bitmap? CaptureWindow(IntPtr hwnd, int timeoutMs = 2000)
    {
        return CaptureWindow(hwnd, CaptureStrategy.Auto, timeoutMs);
    }

    /// <summary>
    /// Captures a window as a <see cref="Bitmap"/> using the specified strategy.
    /// </summary>
    public static Bitmap? CaptureWindow(IntPtr hwnd, CaptureStrategy strategy, int timeoutMs = 2000,
        ILogger? logger = null)
    {
        if (hwnd == IntPtr.Zero)
            throw new ArgumentException("Window handle cannot be zero.", nameof(hwnd));

        var log = logger ?? NullLogger.Instance;

        return strategy switch
        {
            CaptureStrategy.WgcOnly => WgcBackend.Capture(hwnd, timeoutMs, log),
            CaptureStrategy.DxgiOnly => DxgiBackend.Capture(hwnd, timeoutMs, log),
            _ => CaptureAuto(hwnd, timeoutMs, log)
        };
    }

    private static Bitmap? CaptureAuto(IntPtr hwnd, int timeoutMs, ILogger log)
    {
        log.LogDebug("CaptureAuto: trying WGC first for hwnd=0x{Hwnd:X}", hwnd);
        try
        {
            var result = WgcBackend.Capture(hwnd, timeoutMs, log);
            if (result != null)
            {
                log.LogDebug("CaptureAuto: WGC succeeded, {W}x{H}", result.Width, result.Height);
                return result;
            }
            log.LogWarning("CaptureAuto: WGC returned null");
        }
        catch (Exception ex)
        {
            log.LogWarning(ex, "CaptureAuto: WGC failed, falling back to DXGI");
        }

        log.LogDebug("CaptureAuto: trying DXGI fallback");
        return DxgiBackend.Capture(hwnd, timeoutMs, log);
    }
}
