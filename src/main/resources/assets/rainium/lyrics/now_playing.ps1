# Rainium: what's playing on Windows.
# Reads the system media session (any player that shows in the volume flyout: Spotify, browsers, Apple Music...)
# and prints one line of JSON a second. Ends by itself when the game (ParentPid) closes.
param([int]$ParentPid = 0, [string]$Filter = '')

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(5000)
    return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

while ($true) {
    if ($ParentPid -gt 0 -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit }
    try {
        $session = $null
        if ($Filter) {
            $session = $manager.GetSessions() | Where-Object { $_.SourceAppUserModelId -like ('*' + $Filter + '*') } | Select-Object -First 1
        } else {
            $session = $manager.GetCurrentSession()
        }
        if ($null -eq $session) {
            [Console]::Out.WriteLine('{"none":true}')
        } else {
            $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $timeline = $session.GetTimelineProperties()
            $playing = $session.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing'
            $position = $timeline.Position.TotalSeconds
            if ($playing) { $position += ([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalSeconds }
            $line = @{
                app = [string]$session.SourceAppUserModelId
                title = [string]$props.Title
                artist = [string]$props.Artist
                album = [string]$props.AlbumTitle
                length = [double]$timeline.EndTime.TotalSeconds
                position = [double]$position
                playing = [bool]$playing
            } | ConvertTo-Json -Compress
            [Console]::Out.WriteLine($line)
        }
    } catch {
        [Console]::Out.WriteLine('{"error":' + (ConvertTo-Json ([string]$_.Exception.Message) -Compress) + '}')
    }
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 1000
}
