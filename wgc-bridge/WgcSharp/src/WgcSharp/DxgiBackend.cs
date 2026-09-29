using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace WgcSharp;

internal static class DxgiBackend
{
    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

    [DllImport("user32.dll")]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

    [DllImport("dwmapi.dll")]
    private static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out RECT pvAttribute, int cbAttribute);

    [StructLayout(LayoutKind.Sequential)]
    internal struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MONITORINFO
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public uint dwFlags;
    }

    public static Bitmap? Capture(IntPtr hwnd, int timeoutMs, ILogger log)
    {
        log.LogDebug("DXGI: getting window rect for hwnd=0x{Hwnd:X}", hwnd);
        if (!GetWindowScreenRect(hwnd, out var windowRect))
        {
            log.LogWarning("DXGI: failed to get window rect");
            return null;
        }
        log.LogDebug("DXGI: window rect L={L} T={T} R={R} B={B}",
            windowRect.Left, windowRect.Top, windowRect.Right, windowRect.Bottom);

        var hMonitor = MonitorFromWindow(hwnd, 2);
        var monitorInfo = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        if (!GetMonitorInfo(hMonitor, ref monitorInfo))
        {
            log.LogWarning("DXGI: failed to get monitor info");
            return null;
        }
        log.LogDebug("DXGI: monitor rect L={L} T={T} R={R} B={B}",
            monitorInfo.rcMonitor.Left, monitorInfo.rcMonitor.Top,
            monitorInfo.rcMonitor.Right, monitorInfo.rcMonitor.Bottom);

        D3D11.D3D11CreateDevice(
            IntPtr.Zero, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            null!, out var d3dDevice, out var d3dContext).CheckError();

        using var _dev = d3dDevice;
        using var _ctx = d3dContext;

        using var dxgiDevice = d3dDevice.QueryInterface<IDXGIDevice>();
        using var adapter = dxgiDevice.GetAdapter();

        var output = FindOutputForMonitor(adapter, monitorInfo.rcMonitor);
        if (output == null)
        {
            log.LogWarning("DXGI: no output found for monitor");
            return null;
        }

        using var _out = output;
        using var output1 = output.QueryInterface<IDXGIOutput1>();
        log.LogDebug("DXGI: duplicating output");
        using var duplication = output1.DuplicateOutput(d3dDevice);

        var result = duplication.AcquireNextFrame((uint)timeoutMs, out _, out var desktopResource);
        if (result.Failure)
        {
            log.LogWarning("DXGI: AcquireNextFrame failed, hr=0x{HR:X8}", result.Code);
            desktopResource?.Dispose();
            return null;
        }

        try
        {
            using var frameTexture = desktopResource.QueryInterface<ID3D11Texture2D>();
            log.LogDebug("DXGI: frame acquired, cropping to window rect");
            return CropFrameToBitmap(frameTexture, d3dDevice, d3dContext, windowRect, monitorInfo.rcMonitor);
        }
        finally
        {
            desktopResource.Dispose();
            duplication.ReleaseFrame();
        }
    }

    private static bool GetWindowScreenRect(IntPtr hwnd, out RECT rect)
    {
        int hr = DwmGetWindowAttribute(hwnd, 9, out rect, Marshal.SizeOf<RECT>());
        if (hr == 0) return true;
        return GetWindowRect(hwnd, out rect);
    }

    private static IDXGIOutput? FindOutputForMonitor(IDXGIAdapter adapter, RECT monitorRect)
    {
        for (uint i = 0; adapter.EnumOutputs(i, out var output).Success; i++)
        {
            var r = output.Description.DesktopCoordinates;
            if (r.Left == monitorRect.Left && r.Top == monitorRect.Top &&
                r.Right == monitorRect.Right && r.Bottom == monitorRect.Bottom)
                return output;
            output.Dispose();
        }

        if (adapter.EnumOutputs(0, out var fallback).Success)
            return fallback;
        return null;
    }

    private static unsafe Bitmap CropFrameToBitmap(
        ID3D11Texture2D frameTexture, ID3D11Device device, ID3D11DeviceContext context,
        RECT windowRect, RECT monitorRect)
    {
        int srcX = Math.Max(0, windowRect.Left - monitorRect.Left);
        int srcY = Math.Max(0, windowRect.Top - monitorRect.Top);
        int width = windowRect.Right - windowRect.Left;
        int height = windowRect.Bottom - windowRect.Top;

        var frameDesc = frameTexture.Description;
        width = Math.Min(width, (int)frameDesc.Width - srcX);
        height = Math.Min(height, (int)frameDesc.Height - srcY);

        if (width <= 0 || height <= 0)
            throw new InvalidOperationException("Window is outside the captured monitor area.");

        var stagingDesc = new Texture2DDescription
        {
            Width = frameDesc.Width, Height = frameDesc.Height,
            MipLevels = 1, ArraySize = 1, Format = frameDesc.Format,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Staging, BindFlags = BindFlags.None,
            CPUAccessFlags = CpuAccessFlags.Read, MiscFlags = ResourceOptionFlags.None
        };

        using var staging = device.CreateTexture2D(stagingDesc);
        context.CopyResource(staging, frameTexture);

        var mapped = context.Map(staging, 0, MapMode.Read);
        try
        {
            var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
            var bmpData = bitmap.LockBits(
                new Rectangle(0, 0, width, height),
                ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);

            for (int y = 0; y < height; y++)
            {
                Buffer.MemoryCopy(
                    (void*)(mapped.DataPointer + (srcY + y) * mapped.RowPitch + srcX * 4),
                    (void*)(bmpData.Scan0 + y * bmpData.Stride),
                    bmpData.Stride, width * 4);
            }

            bitmap.UnlockBits(bmpData);
            return bitmap;
        }
        finally
        {
            context.Unmap(staging, 0);
        }
    }
}
