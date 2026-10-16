# Derive launcher resources without modifying artwork/. Run from the repository root.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile((Join-Path $PWD 'artwork/Alert-kun-Master.png'))
function Save-Icon([string]$Path, [int]$Size, [bool]$Round, [bool]$Adaptive) {
    $bitmap = New-Object System.Drawing.Bitmap($Size, $Size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = 'AntiAlias'
        $graphics.InterpolationMode = 'HighQualityBicubic'
        $graphics.PixelOffsetMode = 'HighQuality'
        $graphics.Clear([System.Drawing.Color]::Transparent)
        if ($Round) {
            $outline = New-Object System.Drawing.Drawing2D.GraphicsPath
            try {
                $outline.AddEllipse(0, 0, $Size, $Size)
                $graphics.SetClip($outline)
            } finally { $outline.Dispose() }
        } elseif (!$Adaptive) {
            $outline = New-Object System.Drawing.Drawing2D.GraphicsPath
            try {
                $corner = [single]($Size * 0.4)
                $far = [single]($Size - $corner)
                $outline.AddArc(0, 0, $corner, $corner, 180, 90)
                $outline.AddArc($far, 0, $corner, $corner, 270, 90)
                $outline.AddArc($far, $far, $corner, $corner, 0, 90)
                $outline.AddArc(0, $far, $corner, $corner, 90, 90)
                $outline.CloseFigure()
                $graphics.SetClip($outline)
            } finally { $outline.Dispose() }
        }
        if (!$Adaptive) { $graphics.FillRectangle([System.Drawing.Brushes]::White, 0, 0, $Size, $Size) }
        # The character occupies about 63% of the original image height.
        # At 96/108 of the layer it stays within the central 66dp safe zone.
        $fraction = if ($Adaptive) { 96.0 / 108.0 } else { 1.0 }
        $edge = [single]($Size * $fraction)
        $offset = [single](($Size - $edge) / 2)
        $rect = New-Object System.Drawing.RectangleF($offset, $offset, $edge, $edge)
        $graphics.DrawImage($source, $rect)
        $bitmap.Save((Join-Path $PWD $Path), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    foreach ($entry in @(@('mdpi',48), @('hdpi',72), @('xhdpi',96), @('xxhdpi',144), @('xxxhdpi',192))) {
        $directory = 'app/src/main/res/mipmap-' + $entry[0]
        Save-Icon "$directory/ic_launcher.png" $entry[1] $false $false
        Save-Icon "$directory/ic_launcher_round.png" $entry[1] $true $false
    }
    $directory = 'app/src/main/res/drawable-xxxhdpi'
    New-Item -ItemType Directory -Force $directory | Out-Null
    Save-Icon "$directory/ic_launcher_art.png" 432 $false $true
} finally { $source.Dispose() }
