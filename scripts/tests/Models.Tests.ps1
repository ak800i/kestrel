# Tests of scripts\lib\Models.ps1, which scripts\push-models.ps1 uses. The downloads come from fixture files instead of
# the internet, and the device is fake-adb-files.cmd, which keeps the fake device's files in a folder of the test drive.
# scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll and BeforeEach with the It blocks.')]
param()

Describe 'Read-ModelManifest' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        $hash = '0123456789abcdef' * 4

        # Writes a manifest with one model that has these files, and returns its path.
        function New-ManifestFile($Files, $Archives = @()) {
            $path = Join-Path $TestDrive 'manifest.json'
            $manifest = [ordered]@{ format = 1; archives = @($Archives); models = @([ordered]@{ id = 'm'; engine = 'asr'; files = @($Files) }) }
            [IO.File]::WriteAllText($path, (ConvertTo-Json -Depth 5 -InputObject $manifest))
            $path
        }
    }

    It 'accepts the repository''s manifest' {
        $manifest = Read-ModelManifest -Path "$PSScriptRoot\..\..\models\manifest.json"

        @($manifest.models).Count | Should -BeGreaterThan 0
    }

    It 'refuses a path that leaves the models folder: <_>' -ForEach @('../a.onnx', 'm/../../a.onnx', '/sdcard/a.onnx', 'm\a.onnx', 'm/./a.onnx', "m/a.onnx`n", 'm/-a.onnx') {
        $path = New-ManifestFile @(@{ path = $_; size = 1; sha256 = $hash; url = 'https://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*leaves the models folder*'
    }

    It 'refuses a path that is listed twice' {
        $file = @{ path = 'm/a.onnx'; size = 1; sha256 = $hash; url = 'https://example.org/a' }

        { Read-ModelManifest -Path (New-ManifestFile @($file, $file)) } | Should -Throw '*listed twice*'
    }

    It 'refuses a file without a size' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 0; sha256 = $hash; url = 'https://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*size*'
    }

    It 'refuses a hash that is not lowercase hex' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash.ToUpperInvariant(); url = 'https://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*SHA-256*'
    }

    It 'refuses a download that is not https' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; url = 'http://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*https*'
    }

    It 'refuses a member of an archive that is not listed' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; archive = 'pack'; member = 'pack/a.onnx' })

        { Read-ModelManifest -Path $path } | Should -Throw '*archive*'
    }

    It 'refuses a member that leaves the folder into which the archive is unpacked: <_>' -ForEach @('../a.onnx', 'pack/../../a.onnx', '/a.onnx', 'pack\a.onnx', "pack/a.onnx`n", '-P') {
        $archive = @{ id = 'pack'; url = 'https://example.org/pack.tar.bz2'; size = 1; sha256 = $hash }
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; archive = 'pack'; member = $_ }) @($archive)

        { Read-ModelManifest -Path $path } | Should -Throw '*member that leaves*'
    }

    It 'refuses an archive whose id is not a plain file name: <_>' -ForEach @('..', 'a/b', '..\a', '') {
        $archive = @{ id = $_; url = 'https://example.org/pack.tar.bz2'; size = 1; sha256 = $hash }
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; url = 'https://example.org/a' }) @($archive)

        { Read-ModelManifest -Path $path } | Should -Throw '*plain file name*'
    }

    It 'refuses an archive that is listed twice' {
        $archive = @{ id = 'pack'; url = 'https://example.org/pack.tar.bz2'; size = 1; sha256 = $hash }
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; url = 'https://example.org/a' }) @($archive, $archive)

        { Read-ModelManifest -Path $path } | Should -Throw '*listed twice*'
    }
}

Describe 'Save-ModelFiles' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        Mock Write-Host {}

        # The upstream files: one that is downloaded on its own, and an archive with two members.
        $sources = Join-Path $TestDrive 'sources'
        New-Item -ItemType Directory -Path (Join-Path $sources 'pack\data') | Out-Null
        Set-Content -LiteralPath (Join-Path $sources 'a.onnx') -Value 'model a' -NoNewline
        Set-Content -LiteralPath (Join-Path $sources 'pack\data\b.txt') -Value 'member b' -NoNewline
        Set-Content -LiteralPath (Join-Path $sources 'pack\c.txt') -Value 'member c' -NoNewline
        $archive = Join-Path $sources 'pack.tar.bz2'
        & "$env:SystemRoot\System32\tar.exe" -c -j -f $archive -C $sources pack

        function Get-TestHash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

        # A manifest entry for an upstream file, downloaded on its own or, with -Member, from the archive.
        function New-TestFile([string]$Name, [string]$Path, [string]$Member) {
            $source = Join-Path $sources ($Name -replace '/', '\')
            $file = [ordered]@{ path = $Path; size = (Get-Item -LiteralPath $source).Length; sha256 = Get-TestHash $source }
            if ($Member) { $file.archive = 'pack'; $file.member = $Member } else { $file.url = "https://example.org/$Name" }
            [pscustomobject]$file
        }

        $manifest = [pscustomobject]@{
            format = 1
            archives = @([pscustomobject]@{ id = 'pack'; url = 'https://example.org/pack.tar.bz2'; size = (Get-Item -LiteralPath $archive).Length; sha256 = Get-TestHash $archive })
            models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = @(
                (New-TestFile 'a.onnx' 'm/a.onnx'),
                (New-TestFile 'pack/data/b.txt' 'm/data/b.txt' 'pack/data/b.txt'),
                (New-TestFile 'pack/c.txt' 'm/c.txt' 'pack/c.txt')
            ) })
        }

        Mock Invoke-ModelDownload { Copy-Item -LiteralPath (Join-Path $sources ($Url -replace '^https://example\.org/', '')) -Destination $Path }
    }

    BeforeEach {
        $cache = Join-Path $TestDrive "cache-$([guid]::NewGuid())"
    }

    It 'downloads each source once and unpacks the members of the archive' {
        Save-ModelFiles -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Join-Path $cache 'files\m\a.onnx') -Raw | Should -BeExactly 'model a'
        Get-Content -LiteralPath (Join-Path $cache 'files\m\data\b.txt') -Raw | Should -BeExactly 'member b'
        Get-Content -LiteralPath (Join-Path $cache 'files\m\c.txt') -Raw | Should -BeExactly 'member c'
        Should -Invoke Invoke-ModelDownload -Times 2 -Exactly
        # The archive's name in the cache is its id, not a part of its address.
        @(Get-ChildItem -LiteralPath (Join-Path $cache 'archives') | ForEach-Object { $_.Name }) | Should -Be @('pack')
    }

    It 'downloads nothing when the cache holds every file' {
        Save-ModelFiles -Manifest $manifest -Cache $cache
        Save-ModelFiles -Manifest $manifest -Cache $cache

        Should -Invoke Invoke-ModelDownload -Times 2 -Exactly
    }

    It 'downloads a damaged file again' {
        Save-ModelFiles -Manifest $manifest -Cache $cache
        Set-Content -LiteralPath (Join-Path $cache 'files\m\a.onnx') -Value 'model x' -NoNewline

        Save-ModelFiles -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Join-Path $cache 'files\m\a.onnx') -Raw | Should -BeExactly 'model a'
        Should -Invoke Invoke-ModelDownload -Times 3 -Exactly
    }

    It 'unpacks a damaged member again from the archive in the cache' {
        Save-ModelFiles -Manifest $manifest -Cache $cache
        Set-Content -LiteralPath (Join-Path $cache 'files\m\c.txt') -Value 'member x' -NoNewline

        Save-ModelFiles -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Join-Path $cache 'files\m\c.txt') -Raw | Should -BeExactly 'member c'
        Should -Invoke Invoke-ModelDownload -Times 2 -Exactly
    }

    It 'refuses a download with the wrong hash and keeps nothing of it' {
        $file = [pscustomobject]@{ path = 'm/a.onnx'; size = 7; sha256 = '0' * 64; url = 'https://example.org/a.onnx' }
        $wrong = [pscustomobject]@{ format = 1; archives = @(); models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = @($file) }) }

        { Save-ModelFiles -Manifest $wrong -Cache $cache } | Should -Throw '*SHA-256*'
        @(Get-ChildItem -LiteralPath $cache -Recurse -File) | Should -BeNullOrEmpty
    }

    It 'deletes a download that fails partway' {
        Mock Invoke-ModelDownload {
            Set-Content -LiteralPath $Path -Value 'part of a' -NoNewline
            throw 'The connection was closed.'
        }

        { Save-ModelFiles -Manifest $manifest -Cache $cache } | Should -Throw '*connection was closed*'
        @(Get-ChildItem -LiteralPath $cache -Recurse -File) | Should -BeNullOrEmpty
    }

    It 'refuses a member with the wrong hash and keeps nothing of it' {
        $files = @($manifest.models[0].files | ForEach-Object { $_.PSObject.Copy() })
        $files[2].sha256 = '0' * 64
        $wrong = [pscustomobject]@{ format = 1; archives = $manifest.archives; models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = $files }) }

        { Save-ModelFiles -Manifest $wrong -Cache $cache } | Should -Throw '*pack/c.txt*SHA-256*'
        Test-Path -LiteralPath (Join-Path $cache 'files\m\c.txt') | Should -BeFalse
    }
}

Describe 'Push-ModelFiles' {
    BeforeAll {
        # The scripts run with 'Stop', under which Windows PowerShell 5.1 stops at adb's first error line unless the
        # helpers protect themselves, so the tests run with it too.
        $ErrorActionPreference = 'Stop'
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        # As Assert-TestDevice remembers kestrel-api37, which the fake device is, so that the helpers run their commands.
        $KestrelTestDevices['fake'] = @{ Property = 'ro.boot.qemu.avd_name'; Pattern = 'kestrel-api37' }
        $fakeAdb = Join-Path $PSScriptRoot 'fake-adb-files.cmd'
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}
        Mock Write-Host {}
        $models = '/sdcard/Android/data/dev.kestrel/files/models'

        # A cache with three files, as Save-ModelFiles leaves it, and a manifest that lists them.
        $cache = Join-Path $TestDrive 'cache'
        $contents = [ordered]@{ 'm/a.onnx' = 'model a'; 'm/data/b.txt' = 'member b'; 'n/c.txt' = 'member c' }
        $files = @(foreach ($path in $contents.Keys) {
                $local = Get-CachedModelFile -Cache $cache -Path $path
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $local) | Out-Null
                Set-Content -LiteralPath $local -Value $contents[$path] -NoNewline
                [pscustomobject]@{
                    path = $path
                    size = (Get-Item -LiteralPath $local).Length
                    sha256 = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
                    url = "https://example.org/$path"
                }
            })
        $manifest = [pscustomobject]@{ format = 1; archives = @(); models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = $files }) }

        # Starts a fake device, with or without Kestrel and with or without the models folder that Kestrel creates, and
        # returns the folder that stands for the device's '/'. -Corrupt names the model files whose push goes wrong.
        function Set-FakeDevice([switch]$NotInstalled, [switch]$WithModelsFolder, [string[]]$Corrupt = @()) {
            $root = Join-Path $TestDrive "device-$([guid]::NewGuid())"
            New-Item -ItemType Directory -Path $root | Out-Null
            if ($WithModelsFolder) {
                New-Item -ItemType Directory -Force -Path (Join-Path $root 'sdcard\Android\data\dev.kestrel\files\models') | Out-Null
            }
            $state = [ordered]@{ root = $root; installed = -not $NotInstalled; modes = @{}; corrupt = [string[]]@($Corrupt | ForEach-Object { "$models/$_" }); calls = @() }
            [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
            $root
        }

        function Get-FakeDevice { [IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json }

        # The file of the fake device that stands for a model file.
        function Get-DeviceCopy([string]$Root, [string]$Path) {
            Join-Path $Root ('sdcard\Android\data\dev.kestrel\files\models\' + ($Path -replace '/', '\'))
        }
    }

    BeforeEach {
        $env:KESTREL_FAKE_ADB = Join-Path $TestDrive 'device.json'
    }

    AfterAll {
        Remove-Item Env:\KESTREL_FAKE_ADB -ErrorAction SilentlyContinue
    }

    It 'refuses a device without Kestrel' {
        Set-FakeDevice -NotInstalled | Out-Null

        { Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache } | Should -Throw '*not installed*'
    }

    It 'starts Kestrel when its models folder is missing, then copies every file' {
        $root = Set-FakeDevice

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        foreach ($path in $contents.Keys) {
            Get-Content -LiteralPath (Get-DeviceCopy $root $path) -Raw | Should -BeExactly $contents[$path]
        }
        (Get-FakeDevice).calls | Should -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo kestrel-end'
    }

    It 'gives every folder below the models folder the mode 2775, each after the folder that holds it' {
        Set-FakeDevice -WithModelsFolder | Out-Null

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        $device = Get-FakeDevice
        @($device.modes.PSObject.Properties | ForEach-Object { $_.Name }) | Should -Be @("$models/m", "$models/n", "$models/m/data")
        @($device.modes.PSObject.Properties | ForEach-Object { $_.Value } | Select-Object -Unique) | Should -Be @('2775')
        $device.calls | Should -Not -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo kestrel-end'
    }

    It 'gives a folder that exists already the mode 2775 too' {
        $root = Set-FakeDevice -WithModelsFolder
        New-Item -ItemType Directory -Path (Get-DeviceCopy $root 'm') | Out-Null

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        (Get-FakeDevice).modes."$models/m" | Should -Be '2775'
    }

    It 'copies nothing that the device already has' {
        Set-FakeDevice -WithModelsFolder | Out-Null

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache
        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        @((Get-FakeDevice).calls | Where-Object { $_ -like 'push *' }).Count | Should -Be 3
    }

    It 'copies a file again when the device holds another version of it' {
        $root = Set-FakeDevice -WithModelsFolder
        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache
        Set-Content -LiteralPath (Get-DeviceCopy $root 'm/a.onnx') -Value 'model x' -NoNewline

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Get-DeviceCopy $root 'm/a.onnx') -Raw | Should -BeExactly 'model a'
        @((Get-FakeDevice).calls | Where-Object { $_ -like 'push */m/a.onnx' }).Count | Should -Be 2
    }

    It 'refuses a copy whose hash on the device is wrong after the push' {
        Set-FakeDevice -WithModelsFolder -Corrupt 'm/data/b.txt' | Out-Null

        { Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache } | Should -Throw '*m/data/b.txt*does not have the hash*'
    }
}

Describe 'Select-ModelManifest' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        $manifest = [pscustomobject]@{
            format = 1
            archives = @([pscustomobject]@{ id = 'pack' })
            models = @(
                [pscustomobject]@{ id = 'voice'; files = @() }
                [pscustomobject]@{ id = 'kokoro'; optional = $true; files = @() }
            )
        }
    }

    It 'leaves out the optional models' {
        $selected = Select-ModelManifest -Manifest $manifest

        @($selected.models | ForEach-Object { $_.id }) | Should -Be @('voice')
        @($selected.archives).Count | Should -Be 1
    }

    It 'keeps them with -IncludeOptional' {
        $selected = Select-ModelManifest -Manifest $manifest -IncludeOptional

        @($selected.models | ForEach-Object { $_.id }) | Should -Be @('voice', 'kokoro')
    }
}