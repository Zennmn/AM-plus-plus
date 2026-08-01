param(
    [string]$Serial = "emulator-5554",
    [int]$StartupDelaySeconds = 5,
    [int]$TransitionDelaySeconds = 3
)

$ErrorActionPreference = "Stop"

$adbCommand = Get-Command adb -ErrorAction Stop
$targetPackage = "com.apple.android.music"
$deviceXml = "/sdcard/am_car39_collapsed.xml"
$localXml = Join-Path $env:TEMP "am_car39_collapsed_$Serial.xml"
$expandedDeviceXml = "/sdcard/am_car39_expanded.xml"
$expandedLocalXml = Join-Path $env:TEMP "am_car39_expanded_$Serial.xml"

function Invoke-Adb {
    $output = & $adbCommand.Source -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): adb -s $Serial $($args -join ' ')"
    }
    return $output
}

function Get-NodeBounds {
    param(
        [xml]$Hierarchy,
        [string]$ResourceName
    )

    $resourceId = "$targetPackage`:id/$ResourceName"
    $node = $Hierarchy.SelectSingleNode("//node[@resource-id='$resourceId']")
    if ($null -eq $node) {
        throw "Required UI node was missing: $resourceId"
    }
    $match = [regex]::Match([string]$node.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) {
        throw "Invalid bounds for ${resourceId}: $($node.bounds)"
    }
    return [pscustomobject]@{
        Left = [int]$match.Groups[1].Value
        Top = [int]$match.Groups[2].Value
        Right = [int]$match.Groups[3].Value
        Bottom = [int]$match.Groups[4].Value
    }
}

$deviceLine = Invoke-Adb devices | Select-String "^$([regex]::Escape($Serial))\s+device\b"
if ($null -eq $deviceLine) {
    throw "ADB device is not online: $Serial"
}

$resolved = Invoke-Adb shell cmd package resolve-activity --brief `
    -a android.intent.action.MAIN `
    -c android.intent.category.LAUNCHER `
    $targetPackage
$component = $resolved | Where-Object { $_ -match '/' } | Select-Object -Last 1
if ([string]::IsNullOrWhiteSpace($component)) {
    throw "Could not resolve the Apple Music launcher activity"
}

Invoke-Adb shell am force-stop $targetPackage | Out-Null
Invoke-Adb shell am start -W -n $component.Trim() | Out-Null
Start-Sleep -Seconds $StartupDelaySeconds
Invoke-Adb shell uiautomator dump $deviceXml | Out-Null
Invoke-Adb pull $deviceXml $localXml | Out-Null

$hierarchy = [System.Xml.XmlDocument]::new()
$hierarchy.Load($localXml)
$miniPlayerNode = $hierarchy.SelectSingleNode(
    "//node[@resource-id='$targetPackage`:id/mini_player']"
)
if ($null -eq $miniPlayerNode) {
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Seconds $TransitionDelaySeconds
    Invoke-Adb shell uiautomator dump $deviceXml | Out-Null
    Invoke-Adb pull $deviceXml $localXml | Out-Null
    $hierarchy.Load($localXml)
}
$miniPlayer = Get-NodeBounds $hierarchy "mini_player"
$tabsFrame = Get-NodeBounds $hierarchy "bottom_navigation_tabs_frame"
$sheet = Get-NodeBounds $hierarchy "player_sheet_container"

Write-Output (
    "CAR39_GEOMETRY sheetTop={0} mini=[{1},{2}] tabsTop={3}" -f `
        $sheet.Top, $miniPlayer.Top, $miniPlayer.Bottom, $tabsFrame.Top
)

if ($miniPlayer.Bottom -gt $tabsFrame.Top) {
    Write-Error (
        "CAR39_RED mini-player overlaps bottom tabs by {0}px" -f `
            ($miniPlayer.Bottom - $tabsFrame.Top)
    )
    exit 1
}

if (($miniPlayer.Bottom - $miniPlayer.Top) -lt 48) {
    Write-Error "CAR39_RED mini-player visible height is below 48px"
    exit 1
}

Write-Output "CAR39_GREEN collapsed mini-player is fully above bottom tabs"

$tapX = [int](($miniPlayer.Left + $miniPlayer.Right) / 2)
$tapY = [int](($miniPlayer.Top + $miniPlayer.Bottom) / 2)
Invoke-Adb shell input tap $tapX $tapY | Out-Null
Start-Sleep -Seconds $TransitionDelaySeconds
Invoke-Adb shell uiautomator dump $expandedDeviceXml | Out-Null
Invoke-Adb pull $expandedDeviceXml $expandedLocalXml | Out-Null

$expandedHierarchy = [System.Xml.XmlDocument]::new()
$expandedHierarchy.Load($expandedLocalXml)
$expandedSheet = Get-NodeBounds $expandedHierarchy "player_sheet_container"
$expandedPlayer = Get-NodeBounds $expandedHierarchy "player_fragments_host"
$expandedLyrics = Get-NodeBounds $expandedHierarchy "lyrics_main_content"
$expandedLyricsNode = $expandedHierarchy.SelectSingleNode(
    "//node[@resource-id='$targetPackage`:id/lyrics_main_content']"
)
$expandedTabsNode = $expandedHierarchy.SelectSingleNode(
    "//node[@resource-id='$targetPackage`:id/bottom_navigation_tabs_frame']"
)

Write-Output (
    "CAR39_EXPANDED sheet=[{0},{1}] player=[{2},{3}] lyrics=[{4},{5}] lyricsScrollable={6} tabsVisible={7}" -f `
        $expandedSheet.Top, $expandedSheet.Bottom,
        $expandedPlayer.Top, $expandedPlayer.Bottom,
        $expandedLyrics.Top, $expandedLyrics.Bottom,
        $expandedLyricsNode.scrollable,
        ($null -ne $expandedTabsNode)
)

if ($expandedSheet.Top -gt 1 -or $expandedSheet.Bottom -lt $tabsFrame.Bottom) {
    Write-Error "CAR39_RED expanded player sheet does not cover the full viewport"
    exit 1
}

if ($null -ne $expandedTabsNode) {
    Write-Error "CAR39_RED bottom tabs remain visible over the expanded player"
    exit 1
}

if (
    ($expandedLyrics.Bottom - $expandedLyrics.Top) -lt 600 -or
    [string]$expandedLyricsNode.scrollable -ne "true"
) {
    Write-Error "CAR39_RED expanded lyrics are not full-height and scrollable"
    exit 1
}

Write-Output "CAR39_GREEN expanded player covers tabs and lyrics are full-height/scrollable"
exit 0
