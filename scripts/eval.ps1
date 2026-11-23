# Runs :tools:eval, the agent's task suite on the PC (design spec, section 16.5), with this script's own arguments, and
# ends with the tool's exit code: 0 when every run passed or was skipped with a reason, 1 when a run failed, and 2 for a
# refusal before any run (a device, the live gate, a missing configuration, or a build of the tool that failed).
# It rebuilds the tool's installed copy (tools\eval\build\install\eval) with scripts\gradle.ps1 :tools:eval:installDist
# only when a file of tools\eval (outside its build folder), of build-logic (outside its build output), or
# gradle\libs.versions.toml or settings.gradle.kts is newer than the stamp tools\eval\build\eval-built.stamp, which it
# writes after each build that succeeded, and then stops the Gradle daemon, as AGENTS.md asks. The tool runs in the
# repository's folder, where it finds corpus\owner, eval-results and
# scripts\install.ps1. Ctrl+C reaches the tool, whose shutdown hook puts the device back; Windows then asks "Terminate
# batch job (Y/N)?", and either answer is safe. Commands: run, suite, injection, rehearse and list (scripts\eval.ps1
# without arguments prints them). In PowerShell, quote a list, as in --tasks 'stand-in-hotspot,stand-in-read', because
# PowerShell would pass a,b on as two arguments.
# Example: scripts\eval.ps1 run --device emulator-5554 --task stand-in-hotspot --dry-run
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"

# The newest write time of the files whose change needs a new build of the tool: its own files outside its build folder,
# the build logic outside its build output, the version catalog and the settings. build-logic's sources hold a package
# folder named "build" (dev\kestrel\build), so only build-logic's own output folders are left out.
function Get-NewestSource([string]$Root) {
    $tool = Join-Path $Root 'tools\eval'
    $logic = Join-Path $Root 'build-logic'
    $files = @(Get-ChildItem -LiteralPath $tool -Recurse -File | Where-Object { $_.FullName.Substring($tool.Length + 1) -notmatch '^(build|\.gradle|\.kotlin)\\' })
    $files += @(Get-ChildItem -LiteralPath $logic -Recurse -File | Where-Object { $_.FullName.Substring($logic.Length + 1) -notmatch '^((convention\\)?build|\.gradle|\.kotlin)\\' })
    $files += @(Get-Item -LiteralPath (Join-Path $Root 'gradle\libs.versions.toml'), (Join-Path $Root 'settings.gradle.kts'))
    ($files | Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum
}

# Whether the installed copy is missing or older than a source of the tool. The sources are compared with the stamp that
# Update-EvalTool writes after a build, not with eval.jar, because installDist leaves eval.jar as it was when a change (a
# test, or a file saved without a change) does not change the jar, and the copy would then be out of date for ever. The
# stamp is in the build folder, which Get-NewestSource leaves out, and outside install\eval, which installDist empties.
function Test-EvalStale([string]$Root) {
    $jar = Join-Path $Root 'tools\eval\build\install\eval\lib\eval.jar'
    $stamp = Join-Path $Root 'tools\eval\build\eval-built.stamp'
    if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $stamp)) { return $true }
    (Get-NewestSource $Root) -gt (Get-Item -LiteralPath $stamp).LastWriteTimeUtc
}

# Runs scripts\gradle.ps1 with the arguments, its output on the console, and returns its exit code.
function Invoke-Gradle([string[]]$Arguments) {
    & "$PSScriptRoot\gradle.ps1" @Arguments | Out-Host
    $LASTEXITCODE
}

# Builds the installed copy when it is out of date, and stops the Gradle daemon afterwards, also after a failed build.
# After a build that succeeded, it writes the stamp that Test-EvalStale reads. Returns whether the installed copy is current.
function Update-EvalTool([string]$Root) {
    if (-not (Test-EvalStale $Root)) { return $true }
    $built = Invoke-Gradle @(':tools:eval:installDist')
    Invoke-Gradle @('--stop') | Out-Null
    if ($built -ne 0) { return $false }
    [IO.File]::WriteAllText((Join-Path $Root 'tools\eval\build\eval-built.stamp'), '')
    $true
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Update-EvalTool $root)) {
    Write-Warning 'scripts\gradle.ps1 :tools:eval:installDist failed, so :tools:eval did not run.'
    exit 2
}
# The tool finds adb through ANDROID_HOME and runs on JAVA_HOME, which Use-KestrelToolchain sets.
Use-KestrelToolchain
Push-Location -LiteralPath $root
try {
    & (Join-Path $root 'tools\eval\build\install\eval\bin\eval.bat') @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
