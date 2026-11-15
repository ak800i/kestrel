# Helpers for scripts\push-models.ps1 (design spec, section 8.10). They read models\manifest.json, keep the model files
# in a cache on this computer, and copy them into Kestrel's models folder on a device. Dot-source scripts\lib\Kestrel.ps1
# first: the device helpers use Get-Adb and Invoke-SettingsCommand.

# Reads models\manifest.json and checks what the scripts rely on: every path stays inside the models folder and is listed
# once, every archive's id and every member is a plain name that stays inside the cache, and every file and archive has
# an https source, a size and a SHA-256 hash, so that nothing unchecked reaches a device.
function Read-ModelManifest([string]$Path) {
    $manifest = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.format -ne 1) { throw "${Path}: unknown format '$($manifest.format)'." }
    $archives = @{}
    foreach ($archive in @($manifest.archives | Where-Object { $null -ne $_ })) {
        $id = [string]$archive.id
        $what = "${Path}: the archive '$id'"
        # The cache names the archive after its id.
        if (-not (Test-PlainPath $id) -or $id.Contains('/')) { throw "$what needs an id that is a plain file name." }
        if ($archives.ContainsKey($id)) { throw "$what is listed twice." }
        Assert-HttpsSource -What $what -Url $archive.url
        Assert-SizeAndHash -What $what -Size $archive.size -Sha256 $archive.sha256
        $archives[$id] = $true
    }
    $paths = @{}
    foreach ($model in @($manifest.models)) {
        foreach ($file in @($model.files)) {
            $path = [string]$file.path
            $what = "${Path}: the file '$path'"
            if (-not (Test-PlainPath $path)) { throw "$what has a path that leaves the models folder." }
            if ($paths.ContainsKey($path)) { throw "$what is listed twice." }
            $paths[$path] = $true
            if ($file.url) {
                Assert-HttpsSource -What $what -Url $file.url
            } elseif (-not ($file.archive -and $archives.ContainsKey([string]$file.archive) -and $file.member)) {
                throw "$what needs a url, or an archive from the list of archives and a member of it."
            } elseif (-not (Test-PlainPath ([string]$file.member))) {
                throw "$what has a member that leaves the folder into which the archive is unpacked."
            }
            Assert-SizeAndHash -What $what -Size $file.size -Sha256 $file.sha256
        }
    }
    return $manifest
}

# Whether a path from the manifest is relative and stays inside its folder: segments of letters, digits, '.', '_' and
# '-', separated by '/'. Each segment starts with a letter, a digit or '_', so that none is '.' or '..', and tar.exe
# never reads a member as an option. '\z' ends the match at the end of the path; '$' would also allow a final newline.
function Test-PlainPath([string]$Path) {
    return $Path -match '^[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*\z'
}

function Assert-HttpsSource([string]$What, $Url) {
    if ([string]$Url -notlike 'https://*') { throw "$What needs an https address." }
}

function Assert-SizeAndHash([string]$What, $Size, $Sha256) {
    if (-not ($Size -as [long]) -or ($Size -as [long]) -le 0) { throw "$What needs a size in bytes." }
    if ([string]$Sha256 -cnotmatch '^[0-9a-f]{64}$') { throw "$What needs a SHA-256 hash in lowercase hex." }
}

# The file in the cache that holds a model file. The cache keeps the manifest's paths.
function Get-CachedModelFile([string]$Cache, [string]$Path) {
    return Join-Path (Join-Path $Cache 'files') ($Path -replace '/', '\')
}

# Whether a file exists with this size and SHA-256 hash.
function Test-FileHash([string]$Path, $Size, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $Path).Length -ne ($Size -as [long])) { return $false }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $Sha256
}

# Downloads one address into one file. The tests replace it.
function Invoke-ModelDownload([string]$Url, [string]$Path) {
    # Windows PowerShell 5.1 downloads large files much more slowly while it shows its progress bar.
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
}

# Moves a downloaded or unpacked file to $Target if it has this size and hash, and deletes it otherwise.
function Move-CheckedFile([string]$Source, [string]$Target, $Size, [string]$Sha256, [string]$What) {
    if (-not (Test-FileHash -Path $Source -Size $Size -Sha256 $Sha256)) {
        Remove-Item -LiteralPath $Source -Force -ErrorAction SilentlyContinue
        throw "$What does not have the size and SHA-256 hash that models\manifest.json gives."
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
    Move-Item -LiteralPath $Source -Destination $Target -Force
}

# Downloads one address into $Target through a temporary file, which it keeps only with this size and hash. It deletes
# the temporary file when the download fails or is interrupted.
function Save-CheckedDownload([string]$Url, [string]$Target, $Size, [string]$Sha256) {
    Write-Host "Downloading $Url"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
    $temporary = "$Target.download"
    $saved = $false
    # A finally block, because Ctrl+C skips catch blocks.
    try {
        Invoke-ModelDownload -Url $Url -Path $temporary
        Move-CheckedFile -Source $temporary -Target $Target -Size $Size -Sha256 $Sha256 -What "The download of $Url"
        $saved = $true
    } finally {
        if (-not $saved) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
    }
}

# Unpacks the members that $Files name from an archive into the cache, with Windows' own tar.exe.
function Expand-ModelFiles([string]$Archive, $Files, [string]$Cache) {
    $folder = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $folder | Out-Null
    try {
        $members = @($Files | ForEach-Object { [string]$_.member })
        & "$env:SystemRoot\System32\tar.exe" -x -f $Archive -C $folder @members
        if ($LASTEXITCODE -ne 0) { throw "tar.exe could not unpack $Archive." }
        foreach ($file in $Files) {
            $source = Join-Path $folder ([string]$file.member -replace '/', '\')
            $target = Get-CachedModelFile -Cache $Cache -Path $file.path
            Move-CheckedFile -Source $source -Target $target -Size $file.size -Sha256 $file.sha256 -What "$($file.member) in $Archive"
        }
    } finally {
        Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Fills the cache with every model file that it does not already hold with the right hash. An archive is downloaded once
# into $Cache\archives, under its id, and the members that are needed are unpacked from it. A download that fails or is
# interrupted, and a download or a member with the wrong size or hash, stop the function, and nothing of them stays in
# the cache.
function Save-ModelFiles($Manifest, [string]$Cache) {
    $archives = @{}
    foreach ($archive in @($Manifest.archives | Where-Object { $null -ne $_ })) { $archives[[string]$archive.id] = $archive }
    $fromArchives = @{}
    foreach ($file in @($Manifest.models | ForEach-Object { $_.files })) {
        $target = Get-CachedModelFile -Cache $Cache -Path $file.path
        if (Test-FileHash -Path $target -Size $file.size -Sha256 $file.sha256) { continue }
        if ($file.url) {
            Save-CheckedDownload -Url $file.url -Target $target -Size $file.size -Sha256 $file.sha256
        } else {
            $id = [string]$file.archive
            if (-not $fromArchives.ContainsKey($id)) { $fromArchives[$id] = New-Object System.Collections.ArrayList }
            [void]$fromArchives[$id].Add($file)
        }
    }
    foreach ($id in @($fromArchives.Keys)) {
        $archive = $archives[$id]
        $archiveFile = Join-Path (Join-Path $Cache 'archives') $id
        if (-not (Test-FileHash -Path $archiveFile -Size $archive.size -Sha256 $archive.sha256)) {
            Save-CheckedDownload -Url $archive.url -Target $archiveFile -Size $archive.size -Sha256 $archive.sha256
        }
        Expand-ModelFiles -Archive $archiveFile -Files $fromArchives[$id] -Cache $Cache
    }
}

# The folder in which Kestrel looks for its models on a device (design spec, section 8.10).
function Get-DeviceModelsFolder {
    return '/sdcard/Android/data/dev.kestrel/files/models'
}

function Test-DeviceFolder([string]$Serial, [string]$Path) {
    return (@(Invoke-SettingsCommand -Serial $Serial -Command "test -d '$Path' && echo yes || echo no") -join '').Trim() -eq 'yes'
}

# The size of a file on a device, or -1 when the file does not exist.
function Get-DeviceFileSize([string]$Serial, [string]$Path) {
    $answer = (@(Invoke-SettingsCommand -Serial $Serial -Command "stat -c %s '$Path' 2>/dev/null || echo missing") -join '').Trim()
    if ($answer -match '^\d+$') { return [long]$answer }
    return -1
}

# The SHA-256 hash of a file on a device, in lowercase hex.
function Get-DeviceFileHash([string]$Serial, [string]$Path) {
    $answer = (@(Invoke-SettingsCommand -Serial $Serial -Command "sha256sum '$Path'") -join '').Trim()
    return ($answer -split '\s+')[0].ToLowerInvariant()
}

# Stops with an error when Kestrel is not installed on a device.
function Assert-KestrelInstalled([string]$Serial) {
    $installed = @(Invoke-SettingsCommand -Serial $Serial -Command 'pm path dev.kestrel || true') -match '^package:'
    if (-not $installed) { throw "Kestrel is not installed on $Serial. Install it first with scripts\install.ps1 -Serial $Serial." }
}

# Copies the model files from the cache into Kestrel's models folder on a device, except those that the device already
# holds with the right size and hash, and checks the hash of each copy on the device. Kestrel must be installed. It
# creates its models folder when it starts, so the function starts Kestrel when the folder is missing. The folders below
# it get the mode 2775 on every run, also when they exist already, because on Android 17 Kestrel cannot read a folder
# that adb creates otherwise (spike S7).
function Push-ModelFiles([string]$Serial, $Manifest, [string]$Cache) {
    # adb's progress and error lines must not stop the function; its own checks decide.
    $ErrorActionPreference = 'Continue'
    $root = Get-DeviceModelsFolder
    Assert-KestrelInstalled -Serial $Serial
    if (-not (Test-DeviceFolder -Serial $Serial -Path $root)) {
        Invoke-SettingsCommand -Serial $Serial -Command 'am start -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
        $deadline = (Get-Date).AddSeconds(15)
        while (-not (Test-DeviceFolder -Serial $Serial -Path $root)) {
            if ((Get-Date) -gt $deadline) { throw "Kestrel did not create $root on $Serial." }
            Start-Sleep -Milliseconds 500
        }
    }
    $files = @($Manifest.models | ForEach-Object { $_.files })
    $folders = @{}
    foreach ($file in $files) {
        $parts = ([string]$file.path) -split '/'
        for ($count = 1; $count -lt $parts.Count; $count++) { $folders[($parts[0..($count - 1)] -join '/')] = $true }
    }
    # Each folder comes after the folder that holds it. Invoke-SettingsCommand may run a command again, so the command
    # must work on a folder that exists.
    foreach ($folder in @($folders.Keys | Sort-Object { ($_ -split '/').Count }, { $_ })) {
        $path = "$root/$folder"
        Invoke-SettingsCommand -Serial $Serial -Command "mkdir -p '$path' && chmod 2775 '$path'" | Out-Null
    }
    $adb = Get-Adb
    foreach ($file in $files) {
        $remote = "$root/$($file.path)"
        $size = Get-DeviceFileSize -Serial $Serial -Path $remote
        if ($size -eq ($file.size -as [long]) -and (Get-DeviceFileHash -Serial $Serial -Path $remote) -eq $file.sha256) {
            Write-Host "Already on $Serial`: $($file.path)"
            continue
        }
        Write-Host "Copying $($file.path) to $Serial"
        $output = @(& $adb -s $Serial push (Get-CachedModelFile -Cache $Cache -Path $file.path) $remote 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "adb could not copy $($file.path) to ${Serial}: $(($output | ForEach-Object { "$_".Trim() }) -join ' ')" }
        if ((Get-DeviceFileHash -Serial $Serial -Path $remote) -ne $file.sha256) {
            throw "The copy of $($file.path) on $Serial does not have the hash that models\manifest.json gives."
        }
    }
}
