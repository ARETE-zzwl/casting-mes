param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [Parameter(Mandatory)][string]$Destination,
    [string]$PgBin,
    [string]$NewDatabase
)
$ErrorActionPreference = 'Stop'
$backup = (Resolve-Path -LiteralPath $BackupDirectory).Path
$target = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $target) { throw 'Restore target must not exist; never overwrite live data.' }
$manifest = Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.format -ne 1 -or $manifest.mode -notin @('H2', 'PostgreSQL')) { throw 'Unsupported backup format.' }
$items = @(Get-ChildItem -LiteralPath $backup -Recurse -Force)
if ((Get-Item -LiteralPath $backup).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup root must not be a symbolic link.' }
if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Backup contains symbolic links.' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $manifest.files) {
    if (-not $entry.path -or [IO.Path]::IsPathRooted($entry.path) -or $entry.path.Contains(':') -or
        ($entry.path -split '[\\/]' | Where-Object { $_ -in @('.', '..', '') })) { throw 'Invalid backup path.' }
    $file = [IO.Path]::GetFullPath((Join-Path $backup $entry.path))
    if (-not $file.StartsWith($backup.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        -not $seen.Add($file) -or $entry.path -notmatch '^(data/|database\.dump$)') { throw 'Invalid or duplicate backup path.' }
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing backup file: $($entry.path)" }
    if ((Get-Item -LiteralPath $file).Length -ne $entry.size -or
        (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Backup integrity failure: $($entry.path)" }
}
$actual = @($items | Where-Object { -not $_.PSIsContainer -and $_.FullName -ne (Join-Path $backup 'manifest.json') })
if ($actual.Count -ne $seen.Count) { throw 'Unlisted files in backup.' }
if ($manifest.mode -eq 'H2' -and -not ($manifest.files | Where-Object { $_.path -match '^data/.+\.mv\.db$' })) { throw 'H2 database is missing from the backup.' }
if ($manifest.mode -eq 'PostgreSQL') {
    if (-not $PgBin -or $NewDatabase -notmatch '^[a-z][a-z0-9_]{0,62}$' -or -not $seen.Contains((Join-Path $backup 'database.dump'))) {
        throw 'PgBin, a new database name and database.dump are required.'
    }
    # createdb fails if the database exists. No DROP or --clean is ever issued.
    & (Join-Path $PgBin 'createdb') '--no-password' '--' $NewDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a NEW restore database; nothing will be overwritten.' }
    & (Join-Path $PgBin 'pg_restore') '--no-password' '--exit-on-error' '--single-transaction' '--no-owner' '--no-privileges' "--dbname=$NewDatabase" (Join-Path $backup 'database.dump')
    if ($LASTEXITCODE -ne 0) { throw 'Database restore failed. The new database is reserved for inspection; do not switch applications.' }
}
New-Item -ItemType Directory -Path $target | Out-Null
foreach ($entry in $manifest.files | Where-Object { $_.path.StartsWith('data/') }) {
    $relative = $entry.path.Substring(5)
    $destinationFile = [IO.Path]::GetFullPath((Join-Path $target $relative))
    if (-not $destinationFile.StartsWith($target.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid restore path.' }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destinationFile)) | Out-Null
    Copy-Item -LiteralPath (Join-Path $backup $entry.path) -Destination $destinationFile
}
Write-Output "Restored to new directory: $target. Verify database counts and attachments before switching."
