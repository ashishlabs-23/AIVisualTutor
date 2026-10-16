Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$graphics = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero)
$dpiX = $graphics.DpiX
$dpiY = $graphics.DpiY
$scale = $dpiX / 96.0
Write-Host "DpiX: $dpiX, DpiY: $dpiY, Scale: $scale"
