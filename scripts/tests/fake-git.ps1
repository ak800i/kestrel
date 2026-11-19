# A stand-in for git in the tests of scripts\sync-catalog.ps1; fake-git.cmd runs it in its own process, as git runs. It
# understands 'git -C <folder> <command> ...' and keeps its state in the JSON file whose path is in the environment variable
# KESTREL_FAKE_GIT:
#   fail   the command that fails with exit code 128 and an error line, as git fails; empty for none
#   calls  every call's arguments after the folder, joined with spaces, in order
# 'init' makes the folder's .git folder, so that a second run sees a repository, and 'fetch' writes a progress line to
# standard error, as git does.
$statePath = $env:KESTREL_FAKE_GIT
if (-not $statePath -or -not (Test-Path -LiteralPath $statePath)) {
    [Console]::Error.WriteLine('fake git: KESTREL_FAKE_GIT must name an existing state file')
    exit 1
}
if ($args.Count -lt 3 -or $args[0] -ne '-C') {
    [Console]::Error.WriteLine("fake git: unsupported arguments: $($args -join ' ')")
    exit 1
}
$folder = $args[1]
$command = $args[2]
$state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
$calls += ($args[2..($args.Count - 1)] -join ' ')
$json = ConvertTo-Json -InputObject ([ordered]@{ fail = [string]$state.fail; calls = [string[]]$calls })
[IO.File]::WriteAllText($statePath, $json)
if ($command -eq [string]$state.fail) {
    [Console]::Error.WriteLine("fatal: $command failed")
    exit 128
}
if ($command -eq 'init') { New-Item -ItemType Directory -Force -Path (Join-Path $folder '.git') | Out-Null }
if ($command -eq 'fetch') { [Console]::Error.WriteLine('From https://github.com/openclaw/openclaw') }
exit 0
