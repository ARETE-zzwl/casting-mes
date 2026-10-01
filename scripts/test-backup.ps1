param([Parameter(Mandatory)][string]$Java, [Parameter(Mandatory)][string]$H2Jar)
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('mes-backup-test-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
[IO.Directory]::CreateDirectory((Join-Path $data 'uploads')) | Out-Null
$db = (Join-Path $data 'casting-mes').Replace('\', '/')
& $Java -cp $H2Jar org.h2.tools.Shell -url "jdbc:h2:file:$db" -user sa -password '' -sql 'create table recovery_probe(id integer primary key, name varchar(100)); insert into recovery_probe values(1, ''fixture'');'
if ($LASTEXITCODE -ne 0) { throw 'Fixture creation failed.' }
[IO.File]::WriteAllText((Join-Path $data 'uploads/probe.txt'), 'attachment fixture')
$backup = Join-Path $root 'backup'
& "$PSScriptRoot/backup.ps1" -Mode H2 -DataDirectory $data -Destination $backup -ApplicationStopped -ApplicationPort 18081
$restored = Join-Path $root 'restored'
& "$PSScriptRoot/restore.ps1" -BackupDirectory $backup -Destination $restored
$restoredDb = (Join-Path $restored 'casting-mes').Replace('\', '/')
$result = & $Java -cp $H2Jar org.h2.tools.Shell -url "jdbc:h2:file:$restoredDb" -user sa -password '' -sql 'select name from recovery_probe where id = 1;'
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'fixture') { throw 'Restored database content differs.' }
if ([IO.File]::ReadAllText((Join-Path $restored 'uploads/probe.txt')) -ne 'attachment fixture') { throw 'Restored attachment differs.' }
function MustReject([scriptblock]$Action) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected unsafe operation to be rejected.' }
}
MustReject { & "$PSScriptRoot/restore.ps1" -BackupDirectory $backup -Destination $restored }
$lock = [IO.File]::Open((Join-Path $data 'casting-mes.mv.db'), 'Open', 'ReadWrite', 'None')
try {
    MustReject { & "$PSScriptRoot/backup.ps1" -Mode H2 -DataDirectory $data -Destination (Join-Path $root 'locked-backup') -ApplicationStopped -ApplicationPort 18081 }
} finally { $lock.Dispose() }
[IO.File]::AppendAllText((Join-Path $backup 'data/uploads/probe.txt'), 'tampered')
MustReject { & "$PSScriptRoot/restore.ps1" -BackupDirectory $backup -Destination (Join-Path $root 'tampered-restore') }
if (Test-Path -LiteralPath (Join-Path $root 'tampered-restore')) { throw 'Tampered backup created a restore destination.' }
Write-Output "PASS: H2 database and attachments restored; overwrite, live-file lock and tamper rejected. Evidence: $root"
