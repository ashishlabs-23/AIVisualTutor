using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Threading;
using Microsoft.Extensions.Logging;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace WgcSharp;

/// <summary>
/// WGC capture using raw vtable calls — no CsWinRT, no QI, no ComWrappers conflicts.
/// </summary>
internal static class WgcBackend
{
    [DllImport("d3d11.dll", EntryPoint = "CreateDirect3D11DeviceFromDXGIDevice", PreserveSig = false)]
    private static extern void CreateDirect3D11DeviceFromDXGIDevice(IntPtr dxgiDevice, out IntPtr graphicsDevice);

    [DllImport("combase.dll", PreserveSig = true)]
    private static extern int RoGetActivationFactory(IntPtr classId, [In] ref Guid iid, out IntPtr factory);

    [DllImport("combase.dll", PreserveSig = true)]
    private static extern int WindowsCreateString(
        [MarshalAs(UnmanagedType.LPWStr)] string sourceString, int length, out IntPtr hstring);

    [DllImport("combase.dll", PreserveSig = true)]
    private static extern int WindowsDeleteString(IntPtr hstring);

    [ComImport, Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IGraphicsCaptureItemInterop
    {
        IntPtr CreateForWindow([In] IntPtr window, [In] ref Guid iid, out IntPtr result);
    }

    [ComImport, Guid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDirect3DDxgiInterfaceAccess
    {
        IntPtr GetInterface([In] ref Guid iid);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SizeInt32
    {
        public int Width;
        public int Height;
    }

    // ─── WinRT vtable slot indices ───────────────────────────────────────────
    // IUnknown: 0=QI, 1=AddRef, 2=Release
    // IInspectable: 3=GetIids, 4=GetRuntimeClassName, 5=GetTrustLevel
    // Interface methods start at slot 6

    // IGraphicsCaptureItem: slot 6=get_DisplayName, 7=get_Size
    // IDirect3D11CaptureFramePoolStatics2: slot 6=CreateFreeThreaded
    // IDirect3D11CaptureFramePool: slot 6=Recreate, 7=TryGetNextFrame, 8=add_FrameArrived, 9=remove_FrameArrived, 10=CreateCaptureSession
    // IGraphicsCaptureSession: slot 6=StartCapture
    // IGraphicsCaptureSession3: slot 6=get_IsBorderRequired, 7=put_IsBorderRequired
    // IDirect3D11CaptureFrame: slot 6=get_Surface, 7=get_SystemRelativeTime, 8=get_ContentSize

    // ─── Vtable call delegates ───────────────────────────────────────────────

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtGetSizeDelegate(IntPtr thisPtr, out SizeInt32 size);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtCreateFreeThreadedDelegate(IntPtr thisPtr, IntPtr device, int pixelFormat, int numberOfBuffers, SizeInt32 size, out IntPtr result);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtTryGetNextFrameDelegate(IntPtr thisPtr, out IntPtr frame);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtCreateCaptureSessionDelegate(IntPtr thisPtr, IntPtr item, out IntPtr session);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtStartCaptureDelegate(IntPtr thisPtr);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtPutBoolDelegate(IntPtr thisPtr, byte value);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtGetSurfaceDelegate(IntPtr thisPtr, out IntPtr surface);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtGetContentSizeDelegate(IntPtr thisPtr, out SizeInt32 size);

    private static IntPtr GetVtableSlot(IntPtr comPtr, int slot)
    {
        var vtable = Marshal.ReadIntPtr(comPtr);
        return Marshal.ReadIntPtr(vtable, slot * IntPtr.Size);
    }

    private static TDelegate GetVtMethod<TDelegate>(IntPtr comPtr, int slot) where TDelegate : Delegate
    {
        return Marshal.GetDelegateForFunctionPointer<TDelegate>(GetVtableSlot(comPtr, slot));
    }

    // IUnknown QI helper
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int QIDelegate(IntPtr thisPtr, ref Guid iid, out IntPtr ppv);

    private static IntPtr ComQI(IntPtr comPtr, Guid iid)
    {
        var qi = GetVtMethod<QIDelegate>(comPtr, 0);
        int hr = qi(comPtr, ref iid, out var result);
        Marshal.ThrowExceptionForHR(hr);
        return result;
    }

    // IUnknown Release helper
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ReleaseDelegate(IntPtr thisPtr);

    private static void ComRelease(IntPtr comPtr)
    {
        if (comPtr != IntPtr.Zero)
        {
            var release = GetVtMethod<ReleaseDelegate>(comPtr, 2);
            release(comPtr);
        }
    }

    // IClosable.Close is slot 6 (first method after IInspectable)
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int VtCloseDelegate(IntPtr thisPtr);

    /// <summary>
    /// Closes a WinRT IClosable, then releases the ref. Releasing alone is NOT
    /// enough for capture objects: the DWM capture pipeline holds its own
    /// references, so a session that is only Released keeps capturing frames
    /// and pinning GPU buffers forever. Close() is what actually stops it.
    /// </summary>
    private static void ComCloseAndRelease(IntPtr comPtr)
    {
        if (comPtr == IntPtr.Zero) return;
        try
        {
            var closable = ComQI(comPtr, IID_IClosable);
            var close = GetVtMethod<VtCloseDelegate>(closable, 6);
            close(closable);
            ComRelease(closable);
        }
        catch
        {
            // Object doesn't implement IClosable — plain release below is all we can do.
        }
        ComRelease(comPtr);
    }

    // ─── GUIDs ───────────────────────────────────────────────────────────────

    private static readonly Guid IID_IGraphicsCaptureItem = new("79C3F95B-31F7-4EC2-A464-632EF5D30760");
    private static readonly Guid IID_IDirect3D11CaptureFramePool = new("24EB6D22-1975-4106-A4AE-7D6F77C42003");
    private static readonly Guid IID_IDirect3D11CaptureFramePoolStatics2 = new("589B103F-6BBC-5DF5-A991-02E28B3B66D5");
    private static readonly Guid IID_IGraphicsCaptureSession = new("814E42A9-F70F-4AD7-939B-FDDCC6EB880D");
    private static readonly Guid IID_IGraphicsCaptureSession3 = new("F2CDD966-22AE-5EA1-9596-3A289344C3BE");
    private static readonly Guid IID_IDirect3D11CaptureFrame = new("FA50C623-38DA-4B32-ACF3-FA9734AD800E");
    private static readonly Guid IID_IClosable = new("30D5A829-7FA4-4026-83BB-D75BAE4EA99E");

    // ─── Capture ─────────────────────────────────────────────────────────────

    public static Bitmap? Capture(IntPtr hwnd, int timeoutMs, ILogger log)
    {
        log.LogDebug("WGC: creating D3D11 device");
        D3D11.D3D11CreateDevice(
            IntPtr.Zero, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            null!, out var d3dDevice, out var d3dContext).CheckError();

        using var _dev = d3dDevice;
        using var _ctx = d3dContext;

        using var dxgiDevice = d3dDevice.QueryInterface<IDXGIDevice>();
        CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.NativePointer, out var devicePtr);
        log.LogDebug("WGC: WinRT device ptr=0x{Ptr:X}", devicePtr);

        var itemPtr = IntPtr.Zero;
        var poolPtr = IntPtr.Zero;
        var sessionPtr = IntPtr.Zero;

        Bitmap? result = null;
        Exception? capturedException = null;

        try
        {
            // Create capture item — returned pointer IS IGraphicsCaptureItem (default interface)
            itemPtr = CreateCaptureItemForWindow(hwnd, log);

            // get_Size is slot 7 on IGraphicsCaptureItem
            var getSize = GetVtMethod<VtGetSizeDelegate>(itemPtr, 7);
            Marshal.ThrowExceptionForHR(getSize(itemPtr, out var size));
            log.LogDebug("WGC: item size={W}x{H}", size.Width, size.Height);

            if (size.Width == 0 || size.Height == 0)
            {
                log.LogWarning("WGC: zero size, aborting");
                return null;
            }

            // Create free-threaded frame pool — returned pointer IS IDirect3D11CaptureFramePool
            poolPtr = CreateFreeThreadedPool(devicePtr, size, log);

            // CreateCaptureSession is slot 10 on IDirect3D11CaptureFramePool
            var createSession = GetVtMethod<VtCreateCaptureSessionDelegate>(poolPtr, 10);
            Marshal.ThrowExceptionForHR(createSession(poolPtr, itemPtr, out sessionPtr));
            log.LogDebug("WGC: session created");

            // Try disable border via IGraphicsCaptureSession3 (QI needed — different interface)
            try
            {
                var session3 = ComQI(sessionPtr, IID_IGraphicsCaptureSession3);
                var putBorder = GetVtMethod<VtPutBoolDelegate>(session3, 7);
                putBorder(session3, 0);
                ComRelease(session3);
                log.LogDebug("WGC: border disabled");
            }
            catch
            {
                log.LogDebug("WGC: border disable not supported");
            }

            // StartCapture is slot 6 on IGraphicsCaptureSession (default interface of session)
            var startCapture = GetVtMethod<VtStartCaptureDelegate>(sessionPtr, 6);
            Marshal.ThrowExceptionForHR(startCapture(sessionPtr));
            log.LogDebug("WGC: capture started, polling for frames");

            int frameCount = 0;

            // TryGetNextFrame is slot 7 on IDirect3D11CaptureFramePool
            var tryGetNextFrame = GetVtMethod<VtTryGetNextFrameDelegate>(poolPtr, 7);

            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < timeoutMs)
            {
                int hr = tryGetNextFrame(poolPtr, out var framePtr);
                if (hr < 0 || framePtr == IntPtr.Zero)
                {
                    Thread.Sleep(16);
                    continue;
                }

                frameCount++;

                // framePtr IS IDirect3D11CaptureFrame (default interface)
                // get_ContentSize is slot 8
                var getContentSize = GetVtMethod<VtGetContentSizeDelegate>(framePtr, 8);
                getContentSize(framePtr, out var contentSize);
                log.LogDebug("WGC: frame #{N} size={W}x{H}", frameCount, contentSize.Width, contentSize.Height);

                try
                {
                    // get_Surface is slot 6 on IDirect3D11CaptureFrame
                    var getSurface = GetVtMethod<VtGetSurfaceDelegate>(framePtr, 6);
                    Marshal.ThrowExceptionForHR(getSurface(framePtr, out var surfacePtr));
                    try
                    {
                        result = SurfaceToBitmap(surfacePtr, d3dDevice, d3dContext, log);
                        log.LogDebug("WGC: bitmap {W}x{H}", result.Width, result.Height);
                    }
                    finally
                    {
                        ComCloseAndRelease(surfacePtr);
                    }
                }
                catch (Exception ex)
                {
                    log.LogError(ex, "WGC: SurfaceToBitmap failed");
                    capturedException = ex;
                }
                finally
                {
                    ComCloseAndRelease(framePtr);
                }
                break;
            }

            if (frameCount == 0)
                log.LogWarning("WGC: no frames within timeout");
        }
        finally
        {
            // Session and pool MUST be Closed, not just Released — see ComCloseAndRelease.
            ComCloseAndRelease(sessionPtr);
            ComCloseAndRelease(poolPtr);
            ComRelease(itemPtr); // GraphicsCaptureItem has no IClosable
            ComCloseAndRelease(devicePtr);
        }

        if (capturedException != null)
            throw new InvalidOperationException("WGC frame processing failed.", capturedException);

        return result;
    }

    private static IntPtr CreateCaptureItemForWindow(IntPtr hwnd, ILogger log)
    {
        const string className = "Windows.Graphics.Capture.GraphicsCaptureItem";
        Marshal.ThrowExceptionForHR(WindowsCreateString(className, className.Length, out var hstring));
        try
        {
            var interopGuid = typeof(IGraphicsCaptureItemInterop).GUID;
            int hr = RoGetActivationFactory(hstring, ref interopGuid, out var factoryPtr);
            log.LogDebug("WGC: activation factory hr=0x{HR:X8}", hr);
            Marshal.ThrowExceptionForHR(hr);

            var interop = (IGraphicsCaptureItemInterop)Marshal.GetObjectForIUnknown(factoryPtr);
            Marshal.Release(factoryPtr);

            var itemGuid = IID_IGraphicsCaptureItem;
            interop.CreateForWindow(hwnd, ref itemGuid, out var rawItem);
            log.LogDebug("WGC: CreateForWindow OK, ptr=0x{Ptr:X}", rawItem);
            return rawItem;
        }
        finally
        {
            WindowsDeleteString(hstring);
        }
    }

    private static IntPtr CreateFreeThreadedPool(IntPtr devicePtr, SizeInt32 size, ILogger log)
    {
        const string className = "Windows.Graphics.Capture.Direct3D11CaptureFramePool";
        Marshal.ThrowExceptionForHR(WindowsCreateString(className, className.Length, out var hstring));
        try
        {
            var staticsGuid = IID_IDirect3D11CaptureFramePoolStatics2;
            int hr = RoGetActivationFactory(hstring, ref staticsGuid, out var factoryPtr);
            log.LogDebug("WGC: pool statics hr=0x{HR:X8}", hr);
            Marshal.ThrowExceptionForHR(hr);

            // CreateFreeThreaded is slot 6 on IDirect3D11CaptureFramePoolStatics2
            var createFreeThreaded = GetVtMethod<VtCreateFreeThreadedDelegate>(factoryPtr, 6);
            // B8G8R8A8UIntNormalized = 87
            hr = createFreeThreaded(factoryPtr, devicePtr, 87, 2, size, out var poolPtr);
            ComRelease(factoryPtr);
            log.LogDebug("WGC: CreateFreeThreaded hr=0x{HR:X8}, pool=0x{Ptr:X}", hr, poolPtr);
            Marshal.ThrowExceptionForHR(hr);
            return poolPtr;
        }
        finally
        {
            WindowsDeleteString(hstring);
        }
    }

    private static unsafe Bitmap SurfaceToBitmap(
        IntPtr surfacePtr, ID3D11Device device, ID3D11DeviceContext context, ILogger log)
    {
        var accessGuid = typeof(IDirect3DDxgiInterfaceAccess).GUID;
        int hr = Marshal.QueryInterface(surfacePtr, in accessGuid, out var accessPtr);
        log.LogDebug("WGC: QI DxgiAccess hr=0x{HR:X8}", hr);
        Marshal.ThrowExceptionForHR(hr);

        var access = (IDirect3DDxgiInterfaceAccess)Marshal.GetObjectForIUnknown(accessPtr);
        Marshal.Release(accessPtr);

        var texGuid = typeof(ID3D11Texture2D).GUID;
        var texPtr = access.GetInterface(ref texGuid);

        using var frameTexture = new ID3D11Texture2D(texPtr);
        var desc = frameTexture.Description;
        log.LogDebug("WGC: texture {W}x{H} fmt={F}", desc.Width, desc.Height, desc.Format);

        var stagingDesc = new Texture2DDescription
        {
            Width = desc.Width, Height = desc.Height,
            MipLevels = 1, ArraySize = 1, Format = desc.Format,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Staging, BindFlags = BindFlags.None,
            CPUAccessFlags = CpuAccessFlags.Read, MiscFlags = ResourceOptionFlags.None
        };

        using var staging = device.CreateTexture2D(stagingDesc);
        context.CopyResource(staging, frameTexture);

        var mapped = context.Map(staging, 0, MapMode.Read);
        try
        {
            var bitmap = new Bitmap((int)desc.Width, (int)desc.Height, PixelFormat.Format32bppArgb);
            var bmpData = bitmap.LockBits(
                new Rectangle(0, 0, bitmap.Width, bitmap.Height),
                ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);

            for (int y = 0; y < bitmap.Height; y++)
            {
                Buffer.MemoryCopy(
                    (void*)(mapped.DataPointer + y * mapped.RowPitch),
                    (void*)(bmpData.Scan0 + y * bmpData.Stride),
                    bmpData.Stride, bitmap.Width * 4);
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
