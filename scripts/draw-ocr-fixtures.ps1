# Draws the five screens that the OCR golden test reads (design D20 of sub-project 5): English, Serbian Latin with its
# five letters with diacritics, Serbian Cyrillic with its six letters of its own, small text, and a dark theme, each
# 720 x 1280 pixels, as PNG files in app\src\androidTest\assets\ocr. They are drawn on this computer with System.Drawing,
# which Windows PowerShell 5.1 and PowerShell 7 on Windows both have, not captured from a phone, so they hold no one's
# data. The same computer draws the same files; OcrDeviceTest compares Kestrel's OCR of them with golden.txt, which
# RapidOCR's Python reference made once from these files.
# Example: scripts\draw-ocr-fixtures.ps1
param(
    [string]$Folder = (Join-Path $PSScriptRoot '..\app\src\androidTest\assets\ocr')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# The texts are written with \u escapes, because Windows PowerShell 5.1 reads a script without a byte-order mark as
# Windows-1252.
function ConvertFrom-Escaped([string]$Text) { [regex]::Unescape($Text) }

$screens = @(
    @{ Name = 'en'; Dark = $false; Lines = @(
            @(40, 34, 'Settings'), @(150, 26, 'Battery saver'), @(200, 20, 'Turn off when charged'),
            @(320, 26, 'Wi-Fi network'), @(370, 20, 'Connected to Home'), @(490, 26, 'Display brightness'),
            @(610, 26, 'Notifications'), @(730, 26, 'Order 48213 shipped'), @(1100, 28, 'Pay now')) }
    @{ Name = 'sr-latin'; Dark = $false; Lines = @(
            @(40, 34, 'Pode\u0161avanja'), @(150, 26, '\u010ca\u010dak i \u0160abac'),
            @(270, 26, '\u0110or\u0111e \u0161alje poruku'), @(390, 26, '\u017duta ku\u0107a na uglu'),
            @(510, 26, '\u0106evapi za ru\u010dak'), @(630, 26, 'Po\u0161alji sliku'), @(1100, 28, 'Sa\u010duvaj')) }
    @{ Name = 'sr-cyrillic'; Dark = $false; Lines = @(
            @(40, 34, '\u041f\u043e\u0434\u0435\u0448\u0430\u0432\u0430\u045a\u0430'),
            @(150, 26, '\u0409\u0443\u0431\u0430\u0432 \u0438 \u045a\u0435\u0433\u0430'),
            @(270, 26, '\u0402\u043e\u0440\u0452\u0435 \u0448\u0430\u0459\u0435 \u043f\u043e\u0440\u0443\u043a\u0443'),
            @(390, 26, '\u040b\u0443\u043f\u0440\u0438\u0458\u0430 \u0458\u0435 \u043b\u0435\u043f\u0430'),
            @(510, 26, '\u040f\u0435\u043f \u0438 \u0458\u0430\u043a\u043d\u0430'),
            @(630, 26, '\u041f\u043e\u0448\u0430\u0459\u0438 \u0441\u043b\u0438\u043a\u0443'),
            @(1100, 28, '\u0421\u0430\u0447\u0443\u0432\u0430\u0458')) }
    @{ Name = 'small'; Dark = $false; Lines = @(
            @(40, 16, 'Last updated 5 minutes ago'), @(100, 14, 'Tap to see details'), @(160, 14, 'Version 2.4.1'),
            @(220, 13, 'Privacy policy'), @(280, 13, 'Terms of service'), @(340, 13, 'Open source licences'),
            @(1200, 14, 'Help and feedback')) }
    @{ Name = 'dark'; Dark = $true; Lines = @(
            @(40, 34, 'Messages'), @(150, 26, 'Mark all as read'), @(270, 26, 'Archived chats'),
            @(390, 26, 'New group'), @(510, 26, 'Starred messages'), @(1100, 28, 'Search')) }
)

New-Item -ItemType Directory -Force -Path $Folder | Out-Null
foreach ($screen in $screens) {
    $bitmap = New-Object Drawing.Bitmap 720, 1280, ([Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        # Grey-scale anti-aliasing: ClearType's coloured edges would differ from a phone's text.
        $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAlias
        $back = if ($screen.Dark) { [Drawing.Color]::FromArgb(18, 18, 18) } else { [Drawing.Color]::FromArgb(255, 255, 255) }
        $fore = if ($screen.Dark) { [Drawing.Color]::FromArgb(232, 234, 237) } else { [Drawing.Color]::FromArgb(32, 33, 36) }
        $graphics.Clear($back)
        $brush = New-Object Drawing.SolidBrush $fore
        foreach ($line in $screen.Lines) {
            $font = New-Object Drawing.Font 'Segoe UI', ([float]$line[1]), ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
            try {
                $graphics.DrawString((ConvertFrom-Escaped $line[2]), $font, $brush, [float]48, [float]$line[0])
            } finally {
                $font.Dispose()
            }
        }
        $brush.Dispose()
        $path = Join-Path $Folder "$($screen.Name).png"
        $bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
        Write-Host "$($screen.Name).png $((Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant())"
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}
