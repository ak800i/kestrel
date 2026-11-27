# Runs :tools:eval, the agent's task suite on the PC (design spec, section 16.5), with this script's own arguments, and
# ends with the tool's exit code: 0 when every run passed or was skipped with a reason, 1 when a run failed, 2 for a
# refusal before any run (a device, the live gate, a missing configuration, or a build of the tool that failed), 3
# when the owner's open conversation with Kestrel stopped the runs (owner-busy), and two of this script's own, for which
# the tool did not run (Get-EmulatorHealthRefusal in scripts\lib\DeviceGuard.ps1): 4 when kestrel-api37 has degraded,
# and the emulator must be cold-booted with scripts\cold-boot.ps1 before a run again, and 5 when it stayed busy, because
# another job runs on it, which a run must wait for and a cold boot would end.
# It rebuilds the tool's installed copy (tools\eval\build\install\eval) with scripts\gradle.ps1 :tools:eval:installDist
# only when a file of tools\eval (outside its build folder), of build-logic (outside its build output), or
# gradle\libs.versions.toml or settings.gradle.kts is newer than the stamp tools\eval\build\eval-built.stamp, which it
# writes after each build that succeeded, and then stops the Gradle daemon, as AGENTS.md asks. It runs the tool with
# JAVA_HOME's java.exe, the installed copy's lib\* and its main class, not through Gradle's eval.bat, so that cmd.exe
# changes none of the arguments; the tool runs in the repository's folder, where it finds corpus\owner, eval-results and
# scripts\install.ps1, and writes to this console directly, so its output is not in this script's pipeline. Ctrl+C
# reaches the tool, whose shutdown hook puts the device back. Commands: run, suite, injection, rehearse and list
# (scripts\eval.ps1 without arguments prints them). In PowerShell, quote a list, as in --tasks
# 'stand-in-hotspot,stand-in-read', because PowerShell would pass a,b on as two arguments.
# Example: scripts\eval.ps1 run --device emulator-5554 --task stand-in-hotspot --dry-run
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\DeviceGuard.ps1"

# The serial that the arguments give with --device, which every command that uses a device takes; $null without one.
function Get-EvalDevice([string[]]$Arguments) {
    $at = [array]::IndexOf($Arguments, '--device')
    if ($at -ge 0 -and $at + 1 -lt $Arguments.Count) { $Arguments[$at + 1] } else { $null }
}
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
# After a build that succeeded, it writes the stamp that Test-EvalStale reads, with the time at which the build started,
# so that a source saved during the build counts as newer than the copy. Returns whether the installed copy is current.
function Update-EvalTool([string]$Root) {
    if (-not (Test-EvalStale $Root)) { return $true }
    $started = [datetime]::UtcNow
    $built = Invoke-Gradle @(':tools:eval:installDist')
    Invoke-Gradle @('--stop') | Out-Null
    if ($built -ne 0) { return $false }
    $stamp = Join-Path $Root 'tools\eval\build\eval-built.stamp'
    [IO.File]::WriteAllText($stamp, '')
    (Get-Item -LiteralPath $stamp).LastWriteTimeUtc = $started
    $true
}

# Runs a program with the arguments in the folder, on this console, and returns its exit code. It starts the program
# itself: a batch file such as Gradle's eval.bat would pass the arguments through cmd.exe, which changes % and &, and
# PowerShell quotes a native command's arguments differently in Windows PowerShell 5.1 and PowerShell 7. Each argument
# is quoted by the rules that java.exe and most programs use to split their command line, so that it arrives as it was
# given; a quoted argument is also never a wildcard, which java.exe would expand.
function Invoke-Java([string]$Java, [string[]]$Arguments, [string]$Directory) {
    $quoted = foreach ($argument in $Arguments) {
        # Backslashes before a quote and at the end are doubled, and each quote gets a backslash.
        '"' + [regex]::Replace($argument, '(\\*)("|\z)', { param($match) $match.Groups[1].Value * 2 + $(if ($match.Groups[2].Value) { '\"' } else { '' }) }) + '"'
    }
    $start = [Diagnostics.ProcessStartInfo]::new($Java, ($quoted -join ' '))
    $start.UseShellExecute = $false
    $start.WorkingDirectory = $Directory
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.WaitForExit()
        $process.ExitCode
    } finally {
        $process.Dispose()
    }
}

# Builds the tool when it is out of date and runs it in the repository's folder with the arguments. Returns its exit
# code, or 2 when the build failed or threw, as for any refusal before a run. When the arguments name a device, it first
# checks it (Assert-TestDevice), whose failure is a refusal too, and on kestrel-api37 that the emulator is healthy
# (Get-EmulatorHealthRefusal), whose refusal ends with 4 for a degraded emulator and 5 for a busy one, so that a caller
# such as scripts\a71-agent.ps1 can tell the cold boot or the wait that it needs from a refusal.
function Invoke-EvalScript([string]$Root, [string[]]$Arguments) {
    try {
        if (-not (Update-EvalTool $Root)) {
            Write-Warning 'scripts\gradle.ps1 :tools:eval:installDist failed, so :tools:eval did not run.'
            return 2
        }
        # The tool finds adb through ANDROID_HOME and runs on JAVA_HOME, which Use-KestrelToolchain sets.
        Use-KestrelToolchain
    } catch {
        Write-Warning "scripts\eval.ps1 could not build :tools:eval, so it did not run: $($_.Exception.Message)"
        return 2
    }
    $device = Get-EvalDevice $Arguments
    if ($device) {
        try {
            Assert-TestDevice -Serial $device
        } catch {
            Write-Warning "scripts\eval.ps1 did not run :tools:eval: $($_.Exception.Message)"
            return 2
        }
        $refusal = Get-EmulatorHealthRefusal -Serial $device
        if ($refusal) {
            Write-Warning "scripts\eval.ps1 did not run :tools:eval: $($refusal.Message)"
            return $(if ($refusal.Kind -eq 'Busy') { 5 } else { 4 })
        }
    }
    $library = Join-Path $Root 'tools\eval\build\install\eval\lib\*'
    Invoke-Java (Join-Path $env:JAVA_HOME 'bin\java.exe') (@('-classpath', $library, 'dev.kestrel.eval.MainKt') + $Arguments) $Root
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
exit (Invoke-EvalScript $root $args)
