# Puts something moving on the screen (a window cycling through colours, with
# a bar sweeping across it) and, when there's a sound device, plays a quiet
# tone on a loop, so a screen-and-sound capture has something to show.
#   powershell -File moving.ps1 [-Seconds 600]
param([int]$Seconds = 600)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# A 440 Hz tone, one second, 48 kHz 16-bit stereo, written as a WAV file.
try {
    $rate = 48000
    $frames = $rate
    $data = New-Object byte[] ($frames * 4)
    for ($i = 0; $i -lt $frames; $i++) {
        $v = [int16](3000 * [Math]::Sin(2 * [Math]::PI * 440 * $i / $rate))
        $b = [BitConverter]::GetBytes($v)
        $data[$i * 4] = $b[0]; $data[$i * 4 + 1] = $b[1]
        $data[$i * 4 + 2] = $b[0]; $data[$i * 4 + 3] = $b[1]
    }
    $ms = New-Object System.IO.MemoryStream
    $w = New-Object System.IO.BinaryWriter($ms)
    $w.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $w.Write([int](36 + $data.Length))
    $w.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt ')); $w.Write([int]16); $w.Write([int16]1); $w.Write([int16]2)
    $w.Write([int]$rate); $w.Write([int]($rate * 4)); $w.Write([int16]4); $w.Write([int16]16)
    $w.Write([Text.Encoding]::ASCII.GetBytes('data')); $w.Write([int]$data.Length); $w.Write($data)
    $w.Flush()
    $ms.Position = 0
    $player = New-Object System.Media.SoundPlayer($ms)
    $player.PlayLooping()
    Write-Host "moving.ps1: playing a tone"
} catch {
    Write-Host "moving.ps1: no tone ($($_.Exception.Message))"
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Mango Cast test pattern'
$form.FormBorderStyle = 'None'
$form.StartPosition = 'Manual'
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$form.Left = $screen.Left + [int]($screen.Width * 0.1)
$form.Top = $screen.Top + [int]($screen.Height * 0.1)
$form.Width = [int]($screen.Width * 0.8)
$form.Height = [int]($screen.Height * 0.8)
$form.TopMost = $true
$form.ShowInTaskbar = $false
$bar = New-Object System.Windows.Forms.Panel
$bar.Width = [int]($form.Width / 8)
$bar.Height = $form.Height
$bar.BackColor = [System.Drawing.Color]::White
$form.Controls.Add($bar)

$script:t = 0
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 15
$timer.Add_Tick({
    $script:t++
    $h = ($script:t * 3) % 360
    $c = [System.Drawing.Color]::FromArgb(255, [int](127 + 127 * [Math]::Sin($h * [Math]::PI / 180)),
        [int](127 + 127 * [Math]::Sin(($h + 120) * [Math]::PI / 180)),
        [int](127 + 127 * [Math]::Sin(($h + 240) * [Math]::PI / 180)))
    $form.BackColor = $c
    $bar.Left = ($script:t * 9) % [Math]::Max(1, $form.Width - $bar.Width)
})
$timer.Start()
$stop = New-Object System.Windows.Forms.Timer
$stop.Interval = $Seconds * 1000
$stop.Add_Tick({ $form.Close() })
$stop.Start()
Write-Host "moving.ps1: window shown at $($form.Left),$($form.Top) $($form.Width)x$($form.Height)"
[System.Windows.Forms.Application]::Run($form)
