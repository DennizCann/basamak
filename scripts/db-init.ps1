# Creates the basamak role plus the basamak and basamak_test databases on a local PostgreSQL.
# Run once. psql will prompt for the *postgres* superuser password (typed by you, never stored).
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\db-init.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { throw ".env not found. Copy .env.example to .env and fill it first." }

$cfg = @{}
Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2] }

$psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
if (-not $psql) { $psql = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe" | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName }
if (-not $psql) { throw "psql not found. Install PostgreSQL 16+ or add its bin folder to PATH." }

$user = $cfg["DB_USER"]; $pass = $cfg["DB_PASSWORD"].Replace("'", "''")
foreach ($n in @($user, $cfg["DB_NAME"], $cfg["TEST_DB_NAME"])) {
  if ($n -notmatch '^[a-z_][a-z0-9_]*$') { throw "Invalid identifier in .env: '$n' (use lowercase letters, digits, _)" }
}
# Single-quoted here-string: PowerShell expands nothing, so $$ stays literal. Placeholders replaced below.
# Only CREATE / ALTER ROLE statements - nothing is dropped; existing databases are left untouched.
$template = @'
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '__USER__') THEN
    CREATE ROLE __USER__ LOGIN PASSWORD '__PASS__';
  ELSE
    ALTER ROLE __USER__ WITH LOGIN PASSWORD '__PASS__';
  END IF;
END $$;
SELECT 'CREATE DATABASE __DB__ OWNER __USER__' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '__DB__')\gexec
SELECT 'CREATE DATABASE __TESTDB__ OWNER __USER__' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '__TESTDB__')\gexec
'@
$sql = $template.Replace("__USER__", $user).Replace("__PASS__", $pass).Replace("__TESTDB__", $cfg["TEST_DB_NAME"]).Replace("__DB__", $cfg["DB_NAME"])
$tmp = New-TemporaryFile
# Windows PowerShell 5.1 "-Encoding utf8" writes a BOM that psql rejects; write UTF-8 without BOM.
[System.IO.File]::WriteAllText($tmp.FullName, $sql, (New-Object System.Text.UTF8Encoding($false)))
try {
  & $psql -U postgres -h $cfg["DB_HOST"] -p $cfg["DB_PORT"] -v ON_ERROR_STOP=1 -f $tmp
  if ($LASTEXITCODE -ne 0) { throw "psql failed" }
  Write-Host "OK: role '$user' and databases ready." -ForegroundColor Green
} finally { Remove-Item $tmp -Force }
