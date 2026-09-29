namespace WgcSharp;

/// <summary>
/// Specifies which capture backend to use.
/// </summary>
public enum CaptureStrategy
{
    /// <summary>
    /// Try WGC first, fall back to DXGI Desktop Duplication.
    /// </summary>
    Auto,

    /// <summary>
    /// Use only Windows.Graphics.Capture.
    /// Works for occluded, minimized, and DirectX/OpenGL windows.
    /// Requires Windows 10 1803+.
    /// </summary>
    WgcOnly,

    /// <summary>
    /// Use only DXGI Desktop Duplication.
    /// Only captures visible (non-occluded) window content.
    /// </summary>
    DxgiOnly
}
