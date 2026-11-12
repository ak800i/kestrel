# Rebuilds the ELF libraries that ElfReaderTest reads. Needs the Android NDK.
param([string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\AndroidVMs\SDK' }))
$ErrorActionPreference = 'Stop'
$ndk = Get-ChildItem (Join-Path $Sdk 'ndk') -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $ndk) { throw "No NDK found under $Sdk\ndk" }
$clang = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$out = Join-Path $PSScriptRoot '..\resources\elf'
New-Item -ItemType Directory -Force $out | Out-Null
$builds = @(
    @{ Source = 'net.c'; Target = 'aarch64-linux-android33'; Output = 'libnet-arm64.so' },
    @{ Source = 'net.c'; Target = 'armv7a-linux-androideabi33'; Output = 'libnet-arm32.so' },
    @{ Source = 'quiet.c'; Target = 'aarch64-linux-android33'; Output = 'libquiet-arm64.so' }
)
foreach ($build in $builds) {
    & $clang "--target=$($build.Target)" -shared -fPIC -O2 -o (Join-Path $out $build.Output) (Join-Path $PSScriptRoot $build.Source)
    if ($LASTEXITCODE -ne 0) { throw "clang failed for $($build.Output)" }
}
Get-ChildItem $out | Select-Object Name, Length
