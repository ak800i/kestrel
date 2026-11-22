# Test photos for the agent's photo task (design D28 and D29 of sub-project 5; research r65). Dot-source this file after
# scripts\lib\Kestrel.ps1 and Assert-TestDevice. New-TestPhoto draws a harmless card (a solid colour and a large number)
# with System.Drawing, which Windows PowerShell 5.1 and PowerShell 7 on Windows both have, saves it as JPEG and puts
# Kestrel's own EXIF segment after the JPEG's start: DateTimeOriginal and OffsetTimeOriginal, from which Android's media
# scanner takes DATE_TAKEN (r65). No Python, Pillow, piexif or exiftool. Add-TestPhotos copies the photos into
# Pictures/KestrelTest on a test device and indexes them; Remove-TestPhotos deletes them again and checks that the
# device has as many images as before. The functions print no file name of the owner's and no image: only counts.

# The folder on the device that holds only Kestrel's test photos, so that its removal removes nothing else (r65).
$TestPhotoFolder = '/storage/emulated/0/Pictures/KestrelTest'
$MediaImages = 'content://media/external/images/media'

# The device's UTC offset as 'date +%z' prints it (+0100) in EXIF's form (+01:00).
function ConvertTo-ExifOffset([string]$Zone) {
    if ("$Zone".Trim() -notmatch '^([+-])(\d{2})(\d{2})$') { throw "Not an offset of 'date +%z': '$Zone'" }
    "$($Matches[1])$($Matches[2]):$($Matches[3])"
}

# The offset in EXIF's form as a TimeSpan.
function ConvertFrom-ExifOffset([string]$Offset) {
    if ($Offset -notmatch '^([+-])(\d{2}):(\d{2})$') { throw "Not an EXIF offset: '$Offset'" }
    $span = [TimeSpan]::new([int]$Matches[2], [int]$Matches[3], 0)
    if ($Matches[1] -eq '-') { $span.Negate() } else { $span }
}

# The APP1 segment of Kestrel's EXIF writer: a little-endian TIFF header, IFD0 with only the pointer to the Exif IFD
# (tag 0x8769), and the Exif IFD with DateTimeOriginal (0x9003, 'YYYY:MM:DD HH:MM:SS') and OffsetTimeOriginal (0x9011,
# '+HH:MM'), both ASCII with their final zero byte.
function New-ExifSegment([datetime]$Taken, [string]$Offset) {
    ConvertFrom-ExifOffset $Offset | Out-Null
    $date = [Text.Encoding]::ASCII.GetBytes($Taken.ToString('yyyy:MM:dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture) + [char]0)
    $zone = [Text.Encoding]::ASCII.GetBytes($Offset + [char]0)
    $tiff = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($tiff)
    # The header: 'II', 42, and IFD0 at offset 8.
    $writer.Write([byte[]](0x49, 0x49))
    $writer.Write([uint16]42)
    $writer.Write([uint32]8)
    # IFD0 (8 to 26): one entry, the Exif IFD's offset, and no next IFD.
    $exifIfd = 26
    $writer.Write([uint16]1)
    $writer.Write([uint16]0x8769); $writer.Write([uint16]4); $writer.Write([uint32]1); $writer.Write([uint32]$exifIfd)
    $writer.Write([uint32]0)
    # The Exif IFD (26 to 56): two entries in the order of their tags, whose values follow it.
    $dateAt = $exifIfd + 2 + 2 * 12 + 4
    $zoneAt = $dateAt + $date.Length
    $writer.Write([uint16]2)
    $writer.Write([uint16]0x9003); $writer.Write([uint16]2); $writer.Write([uint32]$date.Length); $writer.Write([uint32]$dateAt)
    $writer.Write([uint16]0x9011); $writer.Write([uint16]2); $writer.Write([uint32]$zone.Length); $writer.Write([uint32]$zoneAt)
    $writer.Write([uint32]0)
    $writer.Write($date)
    $writer.Write($zone)
    # TIFF values start on even offsets.
    if ($tiff.Length % 2 -eq 1) { $writer.Write([byte]0) }
    $writer.Flush()
    $body = [byte[]]([Text.Encoding]::ASCII.GetBytes("Exif") + [byte[]](0, 0) + $tiff.ToArray())
    $length = $body.Length + 2
    [byte[]]((0xFF, 0xE1, [byte]($length -shr 8), [byte]($length -band 0xFF)) + $body)
}

# Draws card number $Number, saves it as JPEG at $Path and puts the EXIF segment right after the JPEG's start (0xFFD8).
function New-TestPhoto([string]$Path, [int]$Number, [datetime]$Taken, [string]$Offset) {
    Add-Type -AssemblyName System.Drawing
    $colours = @([Drawing.Color]::SteelBlue, [Drawing.Color]::DarkOrange, [Drawing.Color]::SeaGreen, [Drawing.Color]::MediumPurple)
    $bitmap = [Drawing.Bitmap]::new(640, 480)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $stream = [IO.MemoryStream]::new()
    try {
        $graphics.Clear($colours[($Number - 1) % $colours.Count])
        $font = [Drawing.Font]::new('Arial', 160, [Drawing.FontStyle]::Bold)
        $format = [Drawing.StringFormat]::new()
        $format.Alignment = [Drawing.StringAlignment]::Center
        $format.LineAlignment = [Drawing.StringAlignment]::Center
        $graphics.DrawString("$Number", $font, [Drawing.Brushes]::White, [Drawing.RectangleF]::new(0, 0, 640, 480), $format)
        $font.Dispose()
        $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Jpeg)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
    $jpeg = $stream.ToArray()
    if ($jpeg.Length -lt 4 -or $jpeg[0] -ne 0xFF -or $jpeg[1] -ne 0xD8) { throw 'System.Drawing did not write a JPEG.' }
    $segment = New-ExifSegment -Taken $Taken -Offset $Offset
    $bytes = [byte[]]($jpeg[0..1] + $segment + $jpeg[2..($jpeg.Length - 1)])
    [IO.File]::WriteAllBytes($Path, $bytes)
}

# Reads DateTimeOriginal and OffsetTimeOriginal from the first EXIF segment of the JPEG at $Path, as Kestrel's writer
# writes them (little-endian), for the tests. Returns an object with DateTimeOriginal and OffsetTimeOriginal, each $null
# when missing, or $null when the file has no EXIF segment.
function Read-ExifDate([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    $at = 2
    while ($at + 4 -le $bytes.Length -and $bytes[$at] -eq 0xFF) {
        $marker = $bytes[$at + 1]
        $length = $bytes[$at + 2] * 256 + $bytes[$at + 3]
        if ($marker -eq 0xE1 -and [Text.Encoding]::ASCII.GetString($bytes, $at + 4, 4) -eq 'Exif') {
            $tiff = $at + 10
            if ($bytes[$tiff] -ne 0x49) { throw 'Read-ExifDate reads only little-endian EXIF.' }
            $u16 = { param($offset) [BitConverter]::ToUInt16($bytes, $tiff + $offset) }
            $u32 = { param($offset) [BitConverter]::ToUInt32($bytes, $tiff + $offset) }
            $found = @{ 0x9003 = $null; 0x9011 = $null }
            $ifds = @(& $u32 4)
            $seen = 0
            while ($ifds.Count -gt 0 -and $seen -lt 4) {
                $ifd = $ifds[0]; $ifds = @($ifds | Select-Object -Skip 1); $seen++
                $count = & $u16 $ifd
                foreach ($entry in 0..($count - 1)) {
                    $start = $ifd + 2 + 12 * $entry
                    $tag = & $u16 $start
                    if ($tag -eq 0x8769) { $ifds += & $u32 ($start + 8) }
                    if ($found.ContainsKey([int]$tag)) {
                        $size = & $u32 ($start + 4)
                        $valueAt = if ($size -le 4) { $start + 8 } else { & $u32 ($start + 8) }
                        $found[[int]$tag] = [Text.Encoding]::ASCII.GetString($bytes, $tiff + $valueAt, $size).TrimEnd([char]0)
                    }
                }
            }
            return [pscustomobject]@{ DateTimeOriginal = $found[0x9003]; OffsetTimeOriginal = $found[0x9011] }
        }
        $at += 2 + $length
    }
    $null
}

# The day that $Day names ('today', 'yesterday' or YYYY-MM-DD), counted from the device's date $Today.
function Resolve-PhotoDay([string]$Day, [datetime]$Today) {
    switch ($Day) {
        'today' { return $Today.Date }
        'yesterday' { return $Today.Date.AddDays(-1) }
        default {
            $parsed = [datetime]::MinValue
            if (-not [datetime]::TryParseExact($Day, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture, 'None', [ref]$parsed)) {
                throw "-Day must be today, yesterday or YYYY-MM-DD, not '$Day'."
            }
            return $parsed.Date
        }
    }
}

# The start and the end of $Day on the device's clock, in milliseconds since 1970, as find_media bounds a day.
function Get-DayBounds([datetime]$Day, [string]$Offset) {
    $start = [DateTimeOffset]::new($Day.Date, (ConvertFrom-ExifOffset $Offset))
    [pscustomobject]@{ Start = $start.ToUnixTimeMilliseconds(); End = $start.AddDays(1).ToUnixTimeMilliseconds() }
}

# Reads the rows of 'content query ... --projection _id:datetaken:date_added', whose lines read
# 'Row: 0 _id=31, datetaken=1795086000000, date_added=1795090000'; NULL becomes $null. 'No result found.' gives none.
function ConvertFrom-MediaRows([string[]]$Lines) {
    @(foreach ($line in $Lines) {
            if ($line -notmatch '^Row: \d+ ') { continue }
            $row = @{}
            foreach ($name in '_id', 'datetaken', 'date_added') {
                $row[$name] = if ($line -match "\b$name=(-?\d+)") { [int64]$Matches[1] } else { $null }
            }
            [pscustomobject]$row
        })
}

# How many rows fall on the day of $Bounds: by datetaken, or by date_added (seconds) when datetaken is null or 0, as the
# agent's find_media counts them.
function Get-ImagesOfDayCount($Rows, $Bounds) {
    @($Rows | Where-Object {
            $time = if ($_.datetaken) { $_.datetaken } elseif ($null -ne $_.date_added) { $_.date_added * 1000 } else { $null }
            $null -ne $time -and $time -ge $Bounds.Start -and $time -lt $Bounds.End
        }).Count
}

# The device's shell, through the guard of Assert-TestDevice. A seam for the tests.
function Invoke-PhotoShell([string]$Serial, [string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Copies a file to the device. A seam for the tests.
function Send-PhotoFile([string]$Serial, [string]$From, [string]$To) {
    $ErrorActionPreference = 'Continue'
    $output = @(& (Get-Adb) -s $Serial push $From $To 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb push failed on ${Serial}: $(($output | ForEach-Object { "$_".Trim() }) -join ' ')" }
}

# The image rows of the device, with the 'where' clause $Where for the device's shell (already quoted) or all of them.
function Get-ImageRows([string]$Serial, [string]$Where = '') {
    $filter = if ($Where) { " --where $Where" } else { '' }
    ConvertFrom-MediaRows @(Invoke-PhotoShell -Serial $Serial -Command "content query --uri $MediaImages --projection _id:datetaken:date_added$filter")
}

# The where clause that selects the test folder's rows, quoted for the device's shell.
function Get-TestPhotoWhere { "'_data LIKE '\''$TestPhotoFolder/%'\'''" }

# Puts $Count test photos for noon of $Day into Pictures/KestrelTest on the device and indexes them, and returns the
# count of images before (Before), which Remove-TestPhotos needs, and the folder (Folder). It stops before it copies
# anything when the device already has an image of that day, because the owner's own photos of that day would join the test (review C I4);
# it fails, after removing what it copied, when Android does not date a photo on that day (r65). $Work is the folder on
# the PC for the JPEG files.
function Add-TestPhotos([string]$Serial, [string]$Day = 'yesterday', [int]$Count = 3, [string]$Work) {
    if ($Count -lt 1 -or $Count -gt 10) { throw '-Count must be 1 to 10.' }
    $offset = ConvertTo-ExifOffset ((Invoke-PhotoShell -Serial $Serial -Command 'date +%z') -join '').Trim()
    $today = [datetime]::ParseExact(((Invoke-PhotoShell -Serial $Serial -Command 'date +%Y-%m-%d') -join '').Trim(), 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $date = Resolve-PhotoDay -Day $Day -Today $today
    $bounds = Get-DayBounds -Day $date -Offset $offset
    $rows = @(Get-ImageRows -Serial $Serial)
    $already = Get-ImagesOfDayCount -Rows $rows -Bounds $bounds
    if ($already -ne 0) { throw "The device already has photos of $($date.ToString('yyyy-MM-dd')); the test would share them too." }
    $state = [pscustomobject]@{ Before = $rows.Count; Folder = $TestPhotoFolder }
    if (-not $Work) { $Work = Join-Path $PSScriptRoot '..\..\build\test-photos' }
    New-Item -ItemType Directory -Force -Path $Work | Out-Null
    $taken = $date.AddHours(12)
    try {
        Invoke-PhotoShell -Serial $Serial -Command "mkdir -p $TestPhotoFolder" | Out-Null
        foreach ($number in 1..$Count) {
            $local = Join-Path ([IO.Path]::GetFullPath($Work)) "kestrel_test_$number.jpg"
            New-TestPhoto -Path $local -Number $number -Taken $taken -Offset $offset
            $remote = "$TestPhotoFolder/kestrel_test_$number.jpg"
            Send-PhotoFile -Serial $Serial -From $local -To $remote
            Invoke-PhotoShell -Serial $Serial -Command "touch -m -d '$($taken.ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture))' $remote" | Out-Null
            Invoke-PhotoShell -Serial $Serial -Command "content call --uri content://media/ --method scan_file --arg $remote" | Out-Null
        }
        $test = @(Get-ImageRows -Serial $Serial -Where (Get-TestPhotoWhere))
        $dated = @($test | Where-Object { $_.datetaken -and $_.datetaken -ge $bounds.Start -and $_.datetaken -lt $bounds.End }).Count
        if ($test.Count -ne $Count -or $dated -ne $Count) {
            throw "Android indexed $($test.Count) of the $Count test photos and dated $dated of them on $($date.ToString('yyyy-MM-dd'))."
        }
    } catch {
        Remove-TestPhotos -Serial $Serial -Before $state.Before | Out-Null
        throw
    }
    $state
}

# Deletes the test folder, which deletes its rows through FUSE, deletes any row that is left, and returns whether the
# device has as many images as before Add-TestPhotos (r65). It warns with what it found when it does not.
function Remove-TestPhotos([string]$Serial, [int]$Before) {
    $where = Get-TestPhotoWhere
    try {
        Invoke-PhotoShell -Serial $Serial -Command "rm -rf $TestPhotoFolder" | Out-Null
        if (@(Get-ImageRows -Serial $Serial -Where $where).Count -gt 0) {
            Invoke-PhotoShell -Serial $Serial -Command "content delete --uri $MediaImages --where $where" | Out-Null
        }
        $left = @(Get-ImageRows -Serial $Serial -Where $where).Count
        $now = @(Get-ImageRows -Serial $Serial).Count
    } catch {
        Write-Warning "The test photos on $Serial could not be removed: $($_.Exception.Message) Delete $TestPhotoFolder on the device by hand."
        return $false
    }
    if ($left -ne 0 -or $now -ne $Before) {
        Write-Warning "After removing the test photos, $Serial has $now images (before: $Before) and $left test rows."
        return $false
    }
    $true
}
