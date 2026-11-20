# Helpers for the speech that this computer plays to the Galaxy A71 through its default speakers (plan 4D): reading and
# writing the corpus's WAV clips, where the speech of a clip ends, a gain on the samples that sets the loudness at the
# phone, playing a clip, and the offset between this computer's clock and the phone's. Dot-source scripts\lib\Session.ps1
# first: Get-PhoneClock uses ConvertFrom-Uptime. Nothing here reads or prints what Kestrel heard.

# The loops over the samples, which would take minutes in PowerShell for the 30-minute room-sound track.
if (-not ('KestrelSpeech' -as [type])) {
    Add-Type -TypeDefinition @'
public static class KestrelSpeech {
    // The 16-bit samples multiplied by the gain, kept within the 16-bit range.
    public static byte[] Scale(byte[] samples, double gain) {
        byte[] scaled = new byte[samples.Length];
        for (int i = 0; i + 1 < samples.Length; i += 2) {
            double value = System.Math.Round((short)(samples[i] | (samples[i + 1] << 8)) * gain);
            short clipped = (short)System.Math.Max(-32768, System.Math.Min(32767, value));
            scaled[i] = (byte)(clipped & 0xff);
            scaled[i + 1] = (byte)((clipped >> 8) & 0xff);
        }
        return scaled;
    }

    // The byte offset just after the last 16-bit sample whose size is at least the limit, or 0 without one.
    public static int EndOfLoud(byte[] samples, double limit) {
        for (int i = samples.Length - 2; i >= 0; i -= 2) {
            int value = (short)(samples[i] | (samples[i + 1] << 8));
            if (System.Math.Abs(value) >= limit) return i + 2;
        }
        return 0;
    }
}
'@
}

# This computer's clock for the speech, in milliseconds since an arbitrary start: the stopwatch's counter, which every
# PowerShell process reads the same.
function Get-PcMilliseconds { [double][Diagnostics.Stopwatch]::GetTimestamp() * 1000 / [Diagnostics.Stopwatch]::Frequency }

# Reads a 16-bit PCM WAV file, such as a clip of the corpus (16 kHz, mono): its sample rate, its channels and the bytes of
# its samples. Throws for any other file.
function Read-WavFile([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    $ascii = [Text.Encoding]::ASCII
    if ($bytes.Length -lt 12 -or $ascii.GetString($bytes, 0, 4) -ne 'RIFF' -or $ascii.GetString($bytes, 8, 4) -ne 'WAVE') {
        throw "Not a WAV file: $Path"
    }
    $at = 12
    $format = $null
    while ($at + 8 -le $bytes.Length) {
        $id = $ascii.GetString($bytes, $at, 4)
        $size = [BitConverter]::ToInt32($bytes, $at + 4)
        if ($id -eq 'fmt ') {
            $format = [pscustomobject]@{
                Code = [BitConverter]::ToInt16($bytes, $at + 8)
                Channels = [int][BitConverter]::ToInt16($bytes, $at + 10)
                SampleRate = [BitConverter]::ToInt32($bytes, $at + 12)
                Bits = [BitConverter]::ToInt16($bytes, $at + 22)
            }
        } elseif ($id -eq 'data') {
            if (-not $format -or $format.Code -ne 1 -or $format.Bits -ne 16) { throw "Not a 16-bit PCM WAV file: $Path" }
            $length = [math]::Min($size, $bytes.Length - $at - 8)
            $samples = New-Object byte[] $length
            [Array]::Copy($bytes, $at + 8, $samples, 0, $length)
            return [pscustomobject]@{ SampleRate = $format.SampleRate; Channels = $format.Channels; Samples = $samples }
        }
        $at += 8 + $size + ($size % 2)
    }
    throw "Not a 16-bit PCM WAV file: $Path"
}

# The bytes of a 16-bit PCM WAV file with these samples.
function ConvertTo-WavBytes([byte[]]$Samples, [int]$SampleRate, [int]$Channels = 1) {
    $stream = New-Object IO.MemoryStream
    $writer = New-Object IO.BinaryWriter($stream)
    $ascii = [Text.Encoding]::ASCII
    $writer.Write($ascii.GetBytes('RIFF'))
    $writer.Write([int](36 + $Samples.Length))
    $writer.Write($ascii.GetBytes('WAVEfmt '))
    $writer.Write([int]16)
    $writer.Write([int16]1)
    $writer.Write([int16]$Channels)
    $writer.Write([int]$SampleRate)
    $writer.Write([int]($SampleRate * $Channels * 2))
    $writer.Write([int16]($Channels * 2))
    $writer.Write([int16]16)
    $writer.Write($ascii.GetBytes('data'))
    $writer.Write([int]$Samples.Length)
    $writer.Write($Samples)
    $writer.Flush()
    , $stream.ToArray()
}

# Writes a 16-bit PCM WAV file with these samples.
function Write-WavFile([string]$Path, [byte[]]$Samples, [int]$SampleRate, [int]$Channels = 1) {
    [IO.File]::WriteAllBytes($Path, (ConvertTo-WavBytes -Samples $Samples -SampleRate $SampleRate -Channels $Channels))
}

# Where the speech of a clip ends: the end of its last sample that is at least $ThresholdDb loud (-40 dBFS by default), in
# milliseconds from the clip's start, and the clip's length, so that the silence after the speech is known. A clip
# without such a sample has its speech end at 0.
function Get-ClipSpeechEnd([string]$Path, [double]$ThresholdDb = -40) {
    $wav = Read-WavFile $Path
    $bytesPerMs = $wav.SampleRate * $wav.Channels * 2 / 1000.0
    $end = [KestrelSpeech]::EndOfLoud($wav.Samples, 32768 * [math]::Pow(10, $ThresholdDb / 20))
    [pscustomobject]@{
        SpeechEndMs = [math]::Round($end / $bytesPerMs)
        LengthMs = [math]::Round($wav.Samples.Length / $bytesPerMs)
    }
}

# A clip's file as WAV bytes with its samples multiplied by $Gain, ready for System.Media.SoundPlayer.
function Get-ScaledClip([string]$Path, [double]$Gain) {
    $wav = Read-WavFile $Path
    , (ConvertTo-WavBytes -Samples ([KestrelSpeech]::Scale($wav.Samples, $Gain)) -SampleRate $wav.SampleRate -Channels $wav.Channels)
}

# Plays a clip through this computer's default speakers with the gain, and returns when it started and when it ended on
# the clock of Get-PcMilliseconds. PlaySync returns when the sound has ended; Load reads the whole clip first, so that the
# start is not delayed.
function Invoke-Clip([string]$Path, [double]$Gain = 1) {
    $stream = New-Object IO.MemoryStream(, (Get-ScaledClip -Path $Path -Gain $Gain))
    $player = New-Object System.Media.SoundPlayer($stream)
    try {
        $player.Load()
        $start = Get-PcMilliseconds
        $player.PlaySync()
        $end = Get-PcMilliseconds
    } finally {
        $player.Dispose()
        $stream.Dispose()
    }
    [pscustomobject]@{ StartMs = $start; EndMs = $end }
}

# Starts playing a clip over and over through this computer's default speakers, such as the room-sound track, and returns
# the player for Stop-ClipLoop.
function Start-ClipLoop([string]$Path, [double]$Gain = 1) {
    $player = New-Object System.Media.SoundPlayer((New-Object IO.MemoryStream(, (Get-ScaledClip -Path $Path -Gain $Gain))))
    $player.Load()
    $player.PlayLooping()
    $player
}

# Stops the player of Start-ClipLoop.
function Stop-ClipLoop($Player) {
    if (-not $Player) { return }
    $Player.Stop()
    $Player.Stream.Dispose()
    $Player.Dispose()
}

# The offset between this computer's clock (Get-PcMilliseconds) and the phone's clock of Kestrel's times
# (SystemClock.elapsedRealtime, which /proc/uptime counts too): $ReadUptime reads the phone's answer of 'cat /proc/uptime'
# once. Of $Reads reads, the one with the shortest round trip counts: the offset is the phone's time minus the middle of
# its round trip, and the error is half the round trip plus the 10 ms steps of /proc/uptime. A time T on this computer is
# T + OffsetMs on the phone. Read it again every 10 clips, because the clocks drift apart a little.
function Get-PhoneClock([scriptblock]$ReadUptime, [int]$Reads = 10) {
    $best = $null
    for ($i = 0; $i -lt $Reads; $i++) {
        $before = Get-PcMilliseconds
        $phone = ConvertFrom-Uptime "$(& $ReadUptime)"
        $after = Get-PcMilliseconds
        if (-not $best -or $after - $before -lt $best.Trip) {
            $best = [pscustomobject]@{ Trip = $after - $before; Offset = $phone - ($before + $after) / 2 }
        }
    }
    [pscustomobject]@{ OffsetMs = [long][math]::Round($best.Offset); ErrorMs = [long][math]::Round($best.Trip / 2 + 10) }
}

# Finds the gain at which the phone hears this computer's clips at $TargetDb, the RMS level in dBFS that Kestrel's
# microphone measured, within $Band dB. $Measure plays the calibration clip with a gain and returns the level that the
# phone measured, or $null when it measured nothing. It starts at $Start and corrects the gain by the difference in dB, at
# most $Tries times; the gain stays at most 1, because a larger gain would clip the samples. Returns the gain, the last
# level and whether it was within the band. Below the band at gain 1, this computer's own volume is too low.
function Set-SpeakerLevel([scriptblock]$Measure, [double]$TargetDb, [double]$Band = 3, [double]$Start = 0.5, [int]$Tries = 6) {
    $gain = $Start
    $level = $null
    for ($try = 1; $try -le $Tries; $try++) {
        $level = & $Measure $gain
        if ($null -eq $level) { break }
        $level = [double]$level
        if ([math]::Abs($level - $TargetDb) -le $Band) { return [pscustomobject]@{ Gain = $gain; LevelDb = $level; InBand = $true } }
        if ($level -lt $TargetDb -and $gain -ge 1) { break }
        $gain = [math]::Min([double]1, [math]::Max([double]0.01, $gain * [math]::Pow(10, ($TargetDb - $level) / 20)))
    }
    [pscustomobject]@{ Gain = $gain; LevelDb = $level; InBand = $false }
}
