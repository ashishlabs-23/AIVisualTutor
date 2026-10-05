param(
    [switch]$LaunchApp,
    [string]$PngPath,
    [int]$ExpectedWidth = 0,
    [int]$ExpectedHeight = 0
)
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
$results = [System.Collections.Generic.List[object]]::new()
function Add-Result([string]$Name, [bool]$Passed, [string]$Detail) {
    $script:results.Add([pscustomobject]@{ Check=$Name; Result=if($Passed){'PASS'}else{'FAIL'}; Detail=$Detail })
}
try {
    if(-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $jdkRoots=@((Join-Path $env:USERPROFILE '.jdks'),'C:\Program Files\Java','C:\Program Files\Eclipse Adoptium')
        $java17=$null
        foreach($root in $jdkRoots) {
            if(Test-Path $root) {
                $java17=Get-ChildItem $root -Filter java.exe -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '(?i)(17|jdk-17|java-17)' } | Select-Object -First 1 -ExpandProperty FullName
                if($java17){break}
            }
        }
        if($java17){$env:JAVA_HOME=Split-Path (Split-Path $java17 -Parent) -Parent; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; Write-Host "Using detected JDK: $env:JAVA_HOME"}
    }
    $os = Get-CimInstance Win32_OperatingSystem
    Write-Host "OS: $($os.Caption) $($os.Version) ($($os.OSArchitecture))"
    Add-Type -AssemblyName System.Windows.Forms
    $screens = [System.Windows.Forms.Screen]::AllScreens
    Write-Host "Monitor count: $($screens.Count)"
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class AivtDpi {
 [DllImport("shcore.dll")] public static extern int GetDpiForMonitor(IntPtr hmonitor, int type, out uint x, out uint y);
 [DllImport("user32.dll")] public static extern IntPtr MonitorFromPoint(POINT pt, uint flags);
 [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; public POINT(int x,int y){X=x;Y=y;} }
}
'@ -ErrorAction SilentlyContinue
    foreach($screen in $screens) {
        $r=$screen.Bounds; $dpiX=[uint32]96; $dpiY=[uint32]96
        $monitor=[AivtDpi]::MonitorFromPoint([AivtDpi+POINT]::new($r.X+1,$r.Y+1),2)
        $hr=[AivtDpi]::GetDpiForMonitor($monitor,0,[ref]$dpiX,[ref]$dpiY)
        $scale=if($hr -eq 0){[math]::Round($dpiX/96,2)}else{'unknown'}
        Write-Host "Monitor $($screen.DeviceName): x=$($r.X) y=$($r.Y) width=$($r.Width) height=$($r.Height) scale=$scale primary=$($screen.Primary)"
    }

    $tempBefore=@(Get-ChildItem $env:TEMP -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'AIVT_*.png' -or $_.Name -like 'AIVisualTutor_capture_*.png' } | ForEach-Object FullName)
    $gradle=Join-Path $repo 'gradlew.bat'
    & $gradle clean composeApp:compileKotlin
    Add-Result 'clean compile' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    & $gradle composeApp:test
    Add-Result 'composeApp:test' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    $tempAfter=@(Get-ChildItem $env:TEMP -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'AIVT_*.png' -or $_.Name -like 'AIVisualTutor_capture_*.png' } | ForEach-Object FullName)
    $newTemps=@($tempAfter | Where-Object { $_ -notin $tempBefore })
    Add-Result 'temporary PNG delta' ($newTemps.Count -eq 0) "new leftovers=$($newTemps.Count); total present=$($tempAfter.Count); preexisting=$($tempBefore.Count)"

    if($LaunchApp) {
        $log=Join-Path $repo 'phase3-verification.log'
        $cmdLine='/c ""{0}" run > "{1}" 2>&1"' -f $gradle,$log
        $p=Start-Process -FilePath $env:ComSpec -ArgumentList $cmdLine -WorkingDirectory $repo -PassThru -WindowStyle Hidden
        Write-Host "App launched (PID $($p.Id)). Exercise hotkeys and close the app, then return here and press Enter. Log: $log"
        [void](Read-Host)
        if(-not $p.HasExited){ Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
        $logText=if(Test-Path $log){Get-Content $log -Raw}else{''}
        $eventNames=@('HOTKEY_REGISTERED','HOTKEY_TRIGGERED','SELECTION_STARTED','SELECTION_COMPLETED','CAPTURE_COMPLETED','SELECTION_CANCELLED','PROCESSING_FAILED','CONTEXT_CREATED')
        $observed=@(); foreach($line in ($logText -split "`r?`n")){ foreach($event in $eventNames){ if($line.StartsWith($event)){ $observed += [pscustomobject]@{Name=$event;Index=$observed.Count} } } }
        $registeredIndex=($observed | Where-Object Name -eq 'HOTKEY_REGISTERED' | Select-Object -First 1).Index
        $triggerIndex=($observed | Where-Object Name -eq 'HOTKEY_TRIGGERED' | Select-Object -First 1).Index
        $starts=@($observed | Where-Object Name -eq 'SELECTION_STARTED')
        $flowOk=($null -ne $registeredIndex) -and ($null -ne $triggerIndex) -and $starts.Count -gt 0 -and $registeredIndex -lt $triggerIndex
        foreach($startEvent in $starts){
            if(-not ($observed | Where-Object { $_.Name -eq 'HOTKEY_TRIGGERED' -and $_.Index -lt $startEvent.Index })){ $flowOk=$false }
            $tail=@($observed | Where-Object Index -gt $startEvent.Index)
            $cancel=$tail | Where-Object Name -eq 'SELECTION_CANCELLED' | Select-Object -First 1
            $complete=$tail | Where-Object Name -eq 'SELECTION_COMPLETED' | Select-Object -First 1
            $failure=$tail | Where-Object Name -eq 'PROCESSING_FAILED' | Select-Object -First 1
            if(-not $cancel -and -not $complete -and -not $failure){$flowOk=$false}
            if($complete){
                $capture=$tail | Where-Object { $_.Name -eq 'CAPTURE_COMPLETED' -and $_.Index -gt $complete.Index } | Select-Object -First 1
                $created=$tail | Where-Object { $_.Name -eq 'CONTEXT_CREATED' -and $_.Index -gt $complete.Index } | Select-Object -First 1
                if(( -not $capture -or -not $created) -and -not $failure){$flowOk=$false}
                if($capture -and $created -and $capture.Index -gt $created.Index){$flowOk=$false}
            }
        }
        Add-Result 'lifecycle events/order' $flowOk "observed=$(@($observed.Name | Select-Object -Unique) -join ','); completed sessions=$(@($observed | Where-Object Name -eq 'SELECTION_COMPLETED').Count)"
        $leak=($logText -match '(?i)(ocrText|windowTitle)\s*[=:]\s*[^,}\s]{4,}')
        Add-Result 'log privacy scan' (-not $leak) 'Scanned for labeled OCR/title payloads; inspect logs manually as well'
    }

    if($PngPath) {
        Add-Type -AssemblyName System.Drawing
        try { $image=[System.Drawing.Image]::FromFile((Resolve-Path $PngPath)); $matches=($ExpectedWidth -gt 0 -and $ExpectedHeight -gt 0 -and $image.Width -eq $ExpectedWidth -and $image.Height -eq $ExpectedHeight); Add-Result 'PNG dimensions' $matches "actual=$($image.Width)x$($image.Height), expected=${ExpectedWidth}x${ExpectedHeight}"; $image.Dispose() }
        catch { Add-Result 'PNG dimensions' $false $_.Exception.Message }
    }
} finally {
    Pop-Location
}
$results | Format-Table -AutoSize
$failed=@($results | Where-Object Result -eq 'FAIL').Count
$passed=@($results | Where-Object Result -eq 'PASS').Count
Write-Host "Summary: PASS=$passed FAIL=$failed"
if($failed -gt 0){ exit 1 }
