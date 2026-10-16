Get-Process | Where-Object { $_.ProcessName -like "*java*" } | Select-Object Id, ProcessName
