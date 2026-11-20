# Tests of scripts\lib\Speaker.ps1, which the A71 sessions use to play the corpus's clips to the phone. No test plays a
# sound. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\Speaker.ps1"

    # 16-bit samples of $Milliseconds ms at 16 kHz, all with the value $Value.
    function New-Samples([int]$Milliseconds, [int16]$Value) {
        $pair = [BitConverter]::GetBytes($Value)
        $bytes = New-Object byte[] ($Milliseconds * 32)
        for ($i = 0; $i -lt $bytes.Length; $i += 2) {
            $bytes[$i] = $pair[0]
            $bytes[$i + 1] = $pair[1]
        }
        , $bytes
    }
}

Describe 'The speaker''s clips' {
    It 'writes and reads a clip, and finds where its speech ends before the silence' {
        $samples = [byte[]]((New-Samples 200 0) + (New-Samples 500 8000) + (New-Samples 300 -20000) + (New-Samples 1000 100))
        $path = Join-Path $TestDrive 'clip.wav'

        Write-WavFile -Path $path -Samples $samples -SampleRate 16000

        $wav = Read-WavFile $path
        $wav.SampleRate | Should -Be 16000
        $wav.Channels | Should -Be 1
        $wav.Samples.Length | Should -Be $samples.Length
        $end = Get-ClipSpeechEnd $path
        $end.SpeechEndMs | Should -Be 1000
        $end.LengthMs | Should -Be 2000
        (Get-ClipSpeechEnd (Join-Path $TestDrive 'clip.wav') -ThresholdDb -60).SpeechEndMs | Should -Be 2000
    }

    It 'refuses a file that is not a 16-bit PCM WAV file' {
        $path = Join-Path $TestDrive 'text.wav'
        [IO.File]::WriteAllText($path, 'not a clip')

        { Read-WavFile $path } | Should -Throw 'Not a WAV file*'
    }

    It 'multiplies the samples by the gain, within the 16-bit range' {
        $path = Join-Path $TestDrive 'loud.wav'
        Write-WavFile -Path $path -Samples ([byte[]]((New-Samples 1 8000) + (New-Samples 1 -30000))) -SampleRate 16000

        $scaled = Read-WavFile -Path $path
        $half = [KestrelSpeech]::Scale($scaled.Samples, 0.5)
        $double = [KestrelSpeech]::Scale($scaled.Samples, 2)

        [BitConverter]::ToInt16($half, 0) | Should -Be 4000
        [BitConverter]::ToInt16($half, 32) | Should -Be -15000
        [BitConverter]::ToInt16($double, 0) | Should -Be 16000
        [BitConverter]::ToInt16($double, 32) | Should -Be -32768
    }
}

Describe 'The phone''s clock and the loudness' {
    It 'finds the offset between this computer''s clock and the phone''s from the read with the shortest round trip' {
        $read = {
            $phone = (Get-PcMilliseconds) + 5000000
            ($phone / 1000).ToString('0.000', [Globalization.CultureInfo]::InvariantCulture) + ' 100.00'
        }

        $clock = Get-PhoneClock -ReadUptime $read -Reads 5

        [math]::Abs($clock.OffsetMs - 5000000) | Should -BeLessThan 50
        $clock.ErrorMs | Should -BeGreaterOrEqual 10
    }

    It 'finds the gain that brings the phone''s level into the band, and says when this computer is too quiet' {
        # The phone hears the clip at -20 dBFS at gain 1.
        $measure = { param($Gain) -20 + 20 * [math]::Log10($Gain) }

        $level = Set-SpeakerLevel -Measure $measure -TargetDb -30

        $level.InBand | Should -BeTrue
        [math]::Abs($level.LevelDb + 30) | Should -BeLessOrEqual 3
        $level.Gain | Should -BeLessThan 0.5

        $quiet = Set-SpeakerLevel -Measure { param($Gain) -60 + 20 * [math]::Log10($Gain) } -TargetDb -30
        $quiet.InBand | Should -BeFalse
        $quiet.Gain | Should -Be 1

        (Set-SpeakerLevel -Measure { $null } -TargetDb -30).InBand | Should -BeFalse
    }
}
