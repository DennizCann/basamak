# Starts the local dev stack in separate windows: Mailpit, backend, mock API (:8090), web, CMS.
# Prerequisites: .env filled, scripts\db-init.ps1 run once, npm install done.
$root = Split-Path -Parent $PSScriptRoot
$mailpit = Join-Path $root "tools\mailpit\mailpit.exe"
if (Test-Path $mailpit) { Start-Process $mailpit -WorkingDirectory $root } else { Write-Warning "Mailpit not found in tools\mailpit (see README)." }
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\backend'; .\mvnw.cmd spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root'; java mock-api\MockApi.java"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root'; npm run dev:web"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root'; npm run dev:cms"
Write-Host "Web: http://localhost:5173  CMS: http://localhost:5174  API: http://localhost:8080  Mail: http://localhost:8025"
