param(
    [Parameter(Mandatory)][ValidateSet('H2', 'PostgreSQL')][string]$Mode,
    [Parameter(Mandatory)][string]$DataDirectory,
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][switch]$ApplicationStopped,
    [int]$ApplicationPort = 8081,
    [string]$PgBin,
    [string]$Database
)
$ErrorActionPreference = 'Stop'
if (-not $ApplicationStopped) { throw 'Stop every application instance before backup.' }
$listener = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if ($listener.Port -contains $ApplicationPort) { throw 'Application port is still listening. Stop the application first.' }
$source = (Resolve-Path -LiteralPath $DataDirectory).Path
$target = [IO.Path]::GetFullPath($Destination)
if ($target.StartsWith($source.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup destination must be outside the data directory.'
}
if (Test-Path -LiteralPath $target) { throw 'Backup destination must not already exist.' }
$items = @(Get-ChildItem -LiteralPath $source -Recurse -Force)
if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Data root must not be a symbolic link.' }
if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Symbolic links are not supported in backups.' }
if ($Mode -eq 'H2' -and -not ($items | Where-Object { -not $_.PSIsContainer -and $_.Name.EndsWith('.mv.db') })) {
    throw 'No H2 database found. Check DataDirectory before backing up.'
}
New-Item -ItemType Directory -Path (Join-Path $target 'data') | Out-Null
foreach ($file in $items | Where-Object { -not $_.PSIsContainer }) {
    if ($Mode -eq 'PostgreSQL' -and $file.Name -match '\.(mv|trace|lock)\.db$') { continue }
    $relative = [IO.Path]::GetRelativePath($source, $file.FullName)
    $destinationFile = Join-Path (Join-Path $target 'data') $relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destinationFile)) | Out-Null
    # Exclusive access rejects a live H2 database instead of copying changing pages.
    $input = [IO.File]::Open($file.FullName, 'Open', 'Read', 'None')
    try {
        $output = [IO.File]::Open($destinationFile, 'CreateNew', 'Write', 'None')
        try { $input.CopyTo($output) } finally { $output.Dispose() }
    } finally { $input.Dispose() }
}
if ($Mode -eq 'PostgreSQL') {
    if (-not $PgBin -or -not $Database) { throw 'PgBin and Database are required for PostgreSQL.' }
    & (Join-Path $PgBin 'pg_dump') '--no-password' '--format=custom' '--no-owner' '--no-privileges' "--dbname=$Database" "--file=$(Join-Path $target 'database.dump')"
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed; this backup is incomplete.' }
}
$files = @(Get-ChildItem -LiteralPath $target -File -Recurse | ForEach-Object {
    [ordered]@{ path = [IO.Path]::GetRelativePath($target, $_.FullName).Replace('\', '/'); size = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
[ordered]@{ format = 1; mode = $Mode; createdAt = [DateTime]::UtcNow.ToString('o'); files = $files } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $target 'manifest.json') -Encoding utf8
Write-Output "Verified-file manifest written: $target"
