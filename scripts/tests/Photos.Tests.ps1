# Tests of scripts\lib\Photos.ps1: Kestrel's EXIF writer and reader, the day's bounds, the media rows, and the test
# photos on a simulated device (Invoke-PhotoShell and Send-PhotoFile are mocked). scripts\test-scripts.ps1 runs these
# tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll and BeforeEach with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Photos.ps1"

    # A device whose media store holds $Rows; a scan dates a test photo at noon of 2026-11-19 (+01:00), or leaves it
    # undated with $Undated, as Android does without the EXIF offset (r65).
    function New-FakeDevice([object[]]$Rows, [switch]$Undated) {
        $script:device = @{ Rows = [Collections.ArrayList]::new(@($Rows)); Undated = [bool]$Undated; NextId = 100; Pushed = 0 }
        Mock Send-PhotoFile { $script:device.Pushed++ }
        Mock Invoke-PhotoShell {
            $rows = $script:device.Rows
            switch -Regex ($Command) {
                '^date \+%z$' { return '+0100' }
                '^date \+%Y-%m-%d$' { return '2026-11-20' }
                '^mkdir -p ' { return @() }
                '^touch ' { return @() }
                'scan_file --arg (\S+)$' {
                    $taken = if ($script:device.Undated) { 'NULL' } else { '1795086000000' }
                    [void]$rows.Add([pscustomobject]@{ id = $script:device.NextId++; path = $Matches[1]; line = $taken })
                    return @('Result: Bundle[...]')
                }
                '^rm -rf ' {
                    foreach ($row in @($rows | Where-Object { "$($_.path)" -like '*/KestrelTest/*' })) { $rows.Remove($row) }
                    return @()
                }
                '^content delete ' { return @() }
                '^content query ' {
                    $selected = if ($Command -like '*KestrelTest*') { @($rows | Where-Object { "$($_.path)" -like '*/KestrelTest/*' }) } else { @($rows) }
                    if ($selected.Count -eq 0) { return @('No result found.') }
                    $index = 0
                    return @(foreach ($row in $selected) { "Row: $(($index++)) _id=$($row.id), datetaken=$($row.line), date_added=1795170000" })
                }
                default { throw "unexpected command: $Command" }
            }
        }
    }
}

Describe 'Kestrel''s test photos' {
    It 'writes an EXIF segment right after the JPEG''s start that the reader reads back' {
        $path = Join-Path $TestDrive 'photo.jpg'

        New-TestPhoto -Path $path -Number 2 -Taken ([datetime]::new(2026, 11, 19, 12, 0, 0)) -Offset '+01:00'

        $bytes = [IO.File]::ReadAllBytes($path)
        @($bytes[0..3]) | Should -Be @(0xFF, 0xD8, 0xFF, 0xE1)
        $exif = Read-ExifDate $path
        $exif.DateTimeOriginal | Should -BeExactly '2026:11:19 12:00:00'
        $exif.OffsetTimeOriginal | Should -BeExactly '+01:00'
    }

    It 'turns the device''s offset into EXIF''s form, and refuses anything else' {
        ConvertTo-ExifOffset '+0100' | Should -BeExactly '+01:00'
        ConvertTo-ExifOffset '-0530' | Should -BeExactly '-05:30'
        { ConvertTo-ExifOffset 'CET' } | Should -Throw 'Not an offset*'
        { New-ExifSegment -Taken (Get-Date) -Offset '+1:00' } | Should -Throw 'Not an EXIF offset*'
    }

    It 'bounds a day on the device''s clock, and counts its images by datetaken or else date_added' {
        $day = Resolve-PhotoDay -Day 'yesterday' -Today ([datetime]::new(2026, 11, 20))
        $bounds = Get-DayBounds -Day $day -Offset '+01:00'

        $bounds.Start | Should -Be 1795042800000
        $bounds.End | Should -Be ($bounds.Start + 86400000)
        { Resolve-PhotoDay -Day 'last week' -Today (Get-Date) } | Should -Throw '-Day must be*'
        $rows = ConvertFrom-MediaRows @(
            'Row: 0 _id=1, datetaken=1795086000000, date_added=1795170000',
            'Row: 1 _id=2, datetaken=NULL, date_added=1795090000',
            'Row: 2 _id=3, datetaken=0, date_added=1795170000',
            'Row: 3 _id=4, datetaken=1795170000000, date_added=1795090000')
        @($rows).Count | Should -Be 4
        $rows[1].datetaken | Should -BeNullOrEmpty
        Get-ImagesOfDayCount -Rows $rows -Bounds $bounds | Should -Be 2
        @(ConvertFrom-MediaRows @('No result found.')).Count | Should -Be 0
    }

    It 'refuses to add photos to a day that already has an image, before it copies anything' {
        New-FakeDevice -Rows @([pscustomobject]@{ id = 1; path = '/storage/emulated/0/DCIM/a.jpg'; line = '1795050000000' })

        { Add-TestPhotos -Serial 'emulator-5554' -Day 'yesterday' -Count 3 -Work $TestDrive } | Should -Throw 'The device already has photos of 2026-11-19; the test would share them too.'
        Should -Invoke Send-PhotoFile -Times 0 -Exactly
    }

    It 'adds dated photos and removes them, leaving as many images as before' {
        New-FakeDevice -Rows @([pscustomobject]@{ id = 1; path = '/storage/emulated/0/DCIM/a.jpg'; line = '1794000000000' })

        $state = Add-TestPhotos -Serial 'emulator-5554' -Day 'yesterday' -Count 3 -Work $TestDrive

        $state.Before | Should -Be 1
        $state.Folder | Should -BeExactly '/storage/emulated/0/Pictures/KestrelTest'
        $script:device.Pushed | Should -Be 3
        $script:device.Rows.Count | Should -Be 4
        Remove-TestPhotos -Serial 'emulator-5554' -Before $state.Before | Should -BeTrue
        $script:device.Rows.Count | Should -Be 1
    }

    It 'removes what it copied when Android does not date a photo on the day' {
        New-FakeDevice -Rows @() -Undated

        { Add-TestPhotos -Serial 'emulator-5554' -Day 'yesterday' -Count 2 -Work $TestDrive } | Should -Throw 'Android indexed 2 of the 2 test photos and dated 0*'
        $script:device.Rows.Count | Should -Be 0
    }
}
