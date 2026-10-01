[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'artifacts'
New-Item -ItemType Directory -Path $output -Force | Out-Null
if ($env:JAVA_HOME) { $env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path }
$wrapper = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { '.\mvnw.cmd' } else { './mvnw' }
Push-Location (Join-Path $root 'backend')
try {
    & $wrapper '-q' 'dependency:tree' '-Dscope=runtime' '-DoutputType=json' '-DoutputFile=../artifacts/backend-dependencies.json'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve runtime dependencies.' }
} finally { Pop-Location }
$dependencies = [Collections.Generic.Dictionary[string, object]]::new()
function Visit-Dependency($node) {
    $name = "$($node.groupId):$($node.artifactId)"
    $key = "$name@$($node.version)"
    $dependencies[$key] = @{ package = @{ ecosystem = 'Maven'; name = $name }; version = $node.version }
    if ($null -ne $node.PSObject.Properties['children']) { foreach ($child in $node.children) { Visit-Dependency $child } }
}
$tree = Get-Content -LiteralPath (Join-Path $output 'backend-dependencies.json') -Raw | ConvertFrom-Json
foreach ($child in $tree.children) { Visit-Dependency $child }
$queries = @($dependencies.Values)
$findings = [Collections.Generic.List[object]]::new()
$pending = $queries
while ($pending.Count -gt 0) {
    $response = Invoke-RestMethod -Uri 'https://api.osv.dev/v1/querybatch' -Method Post -ContentType 'application/json' -Body (@{ queries = $pending } | ConvertTo-Json -Depth 12) -TimeoutSec 90
    if ($response.results.Count -ne $pending.Count) { throw 'Incomplete OSV response.' }
    $next = [Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $pending.Count; $i++) {
        $result = $response.results[$i]
        if ($null -ne $result.PSObject.Properties['vulns']) {
            foreach ($vulnerability in $result.vulns) {
                $findings.Add(@{ package = $pending[$i].package.name; version = $pending[$i].version; id = $vulnerability.id })
            }
        }
        if ($null -ne $result.PSObject.Properties['next_page_token']) {
            $next.Add(@{ package = $pending[$i].package; version = $pending[$i].version; page_token = $result.next_page_token })
        }
    }
    $pending = @($next.ToArray())
}
$report = @{ checkedAt = [DateTimeOffset]::UtcNow.ToString('o'); source = 'https://api.osv.dev/v1/querybatch'; runtimeDependencies = $queries.Count; findings = @($findings.ToArray()) }
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'backend-audit.json') -Encoding utf8
Write-Host "Checked $($queries.Count) runtime dependencies; $($findings.Count) OSV findings."
if ($findings.Count -gt 0) { $findings | Format-Table; throw 'Dependency findings require review before release.' }
