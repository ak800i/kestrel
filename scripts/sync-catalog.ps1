# Makes Kestrel's provider catalog from OpenClaw's manifests (design spec, section 10.2): a shallow, sparse checkout of
# OpenClaw's Git repository at one commit in build\openclaw\<commit>, then the Gradle task :tools:catalog-sync:syncCatalog,
# which writes catalog\providers.json and prints its counts, and last the commit in catalog\openclaw.lock. Without -Commit it
# uses the commit in catalog\openclaw.lock. A second run in the same folder reuses the checkout. It fetches only OpenClaw's
# repository and sends no request to any provider. -Git, -Gradle and -Root exist for its tests (scripts\tests).
# Example: scripts\sync-catalog.ps1 -Commit e6113549fe8800b4c736c5e8a131b47f2e015143
param(
    [string]$Commit,
    [string]$Git = 'git',
    [string]$Gradle = (Join-Path $PSScriptRoot 'gradle.ps1'),
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)
$ErrorActionPreference = 'Stop'
$repository = 'https://github.com/openclaw/openclaw.git'
$lock = Join-Path $Root 'catalog\openclaw.lock'
if (-not $Commit) {
    if (-not (Test-Path -LiteralPath $lock)) { throw "Give -Commit, because $lock does not exist." }
    $Commit = [IO.File]::ReadAllText($lock).Trim()
}
if ($Commit -cnotmatch '^[0-9a-f]{40}$') { throw "The commit must be a 40-character hash, not '$Commit'." }
$checkout = Join-Path $Root "build\openclaw\$Commit"
$gitProgram = $Git

function Invoke-Git {
    # git writes its progress to standard error, which Windows PowerShell 5.1 would turn into errors under 'Stop'.
    $ErrorActionPreference = 'Continue'
    $output = @(& $gitProgram -C $checkout @args 2>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    foreach ($line in $output) { Write-Host $line }
    if ($code -ne 0) { throw "git $($args[0]) failed with exit code $code." }
}

New-Item -ItemType Directory -Force -Path $checkout | Out-Null
$gitFolder = Join-Path $checkout '.git'
if (Test-Path -LiteralPath $gitFolder) {
    try {
        Invoke-Git remote set-url origin $repository
    } catch {
        throw "git remote set-url failed in $checkout. If an earlier run stopped before the checkout was set up, delete that folder and run the script again."
    }
} else {
    try {
        Invoke-Git init --quiet
        Invoke-Git remote add origin $repository
    } catch {
        # Without this, every later run would find the half-made repository and fail at 'remote set-url'.
        Remove-Item -LiteralPath $gitFolder -Recurse -Force -ErrorAction SilentlyContinue
        throw
    }
}
Invoke-Git sparse-checkout set --no-cone 'extensions/*/openclaw.plugin.json'
Invoke-Git fetch --depth 1 --filter=blob:none origin $Commit
Invoke-Git checkout --force FETCH_HEAD

& $Gradle ':tools:catalog-sync:syncCatalog' "-Pcheckout=$checkout" "-Pcommit=$Commit"
if ($LASTEXITCODE -ne 0) { throw "The sync failed with exit code $LASTEXITCODE, so $lock keeps its commit." }

# One line with the hash and a line feed, in UTF-8 without a byte order mark, also in Windows PowerShell 5.1.
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lock) | Out-Null
[IO.File]::WriteAllText($lock, "$Commit`n", (New-Object System.Text.UTF8Encoding $false))
"Wrote $lock."
