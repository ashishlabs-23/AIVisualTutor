Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

[System.Windows.Forms.Screen]::AllScreens | ForEach-Object {
    Write-Host "Screen: $($_.DeviceName), Primary: $($_.Primary), Bounds: $($_.Bounds)"
}
