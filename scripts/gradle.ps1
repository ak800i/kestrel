# Runs the Gradle wrapper with Android Studio's JDK and the shared Android SDK.
# Example: scripts\gradle.ps1 check kestrelAudit
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
& "$PSScriptRoot\..\gradlew.bat" --console=plain @args
exit $LASTEXITCODE
