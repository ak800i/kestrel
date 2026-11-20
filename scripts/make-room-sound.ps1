# Makes the room-sound track for battery measure 3 of scripts\a71-session.ps1 (plan 4D): about 30 minutes of everyday
# talk in this computer's Windows voices, which differ from Kestrel's own, from the sentences of corpus\room-sound.txt,
# in which no word of Kestrel's commands appears, in a shuffled order, with each sentence's voice and speed chosen at
# random and pauses of 0.5 to 3 s between them. It writes a 16 kHz 16-bit mono WAV file, corpus\generated\room-sound.wav,
# which git ignores, and needs no device: Windows' speech API (SAPI) speaks through COM in Windows PowerShell 5.1 and in
# PowerShell 7. The same seed gives the same track.
# Example: scripts\make-room-sound.ps1
param(
    [double]$Minutes = 30
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Speaker.ps1"

$sentences = @([IO.File]::ReadAllLines((Join-Path $PSScriptRoot '..\corpus\room-sound.txt'), [Text.UTF8Encoding]::new($false)) |
        Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith('#') })
$folder = Join-Path $PSScriptRoot '..\corpus\generated'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$part = Join-Path $folder 'room-sound-part.wav'
$speaker = New-Object -ComObject SAPI.SpVoice
$voices = @($speaker.GetVoices() | ForEach-Object { $_ })
if ($voices.Count -eq 0) { throw 'This computer has no Windows voice; add one in Settings > Time and language > Speech.' }
$random = New-Object Random 4
$rate = 16000
# 16 kHz, 2 bytes per sample.
$limit = [long]($Minutes * 60 * $rate * 2)
$track = New-Object IO.MemoryStream
try {
    while ($track.Length -lt $limit) {
        foreach ($sentence in @($sentences | Sort-Object { $random.Next() })) {
            $stream = New-Object -ComObject SAPI.SpFileStream
            # SAFT16kHz16BitMono, and SSFMCreateForWrite, which writes a WAV file.
            $stream.Format.Type = 18
            $stream.Open($part, 3)
            try {
                $speaker.Voice = $voices[$random.Next($voices.Count)]
                $speaker.Rate = $random.Next(-1, 2)
                $speaker.AudioOutputStream = $stream
                $speaker.Speak($sentence) | Out-Null
            } finally {
                $stream.Close()
            }
            $samples = (Read-WavFile $part).Samples
            $track.Write($samples, 0, $samples.Length)
            $pause = New-Object byte[] ($random.Next(500, 3001) * 32)
            $track.Write($pause, 0, $pause.Length)
            if ($track.Length -ge $limit) { break }
        }
    }
    $file = Join-Path $folder 'room-sound.wav'
    Write-WavFile -Path $file -Samples $track.ToArray() -SampleRate $rate
    Write-Host "Made a room-sound track of $([math]::Round($track.Length / 2 / $rate / 60, 1)) minutes with $($voices.Count) Windows voices in $((Resolve-Path -LiteralPath $file).Path)."
} finally {
    $track.Dispose()
    Remove-Item -LiteralPath $part -ErrorAction SilentlyContinue
}
