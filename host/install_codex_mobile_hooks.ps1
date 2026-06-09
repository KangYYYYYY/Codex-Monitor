param(
    [ValidateSet("User", "Project")]
    [string] $Scope = "User"
)

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$startScript = Join-Path $projectRoot "host\start_codex_mobile_server.py"
$eventScript = Join-Path $projectRoot "host\codex_mobile_hook.py"

if (-not (Test-Path -LiteralPath $startScript)) {
    throw "Missing script: $startScript"
}
if (-not (Test-Path -LiteralPath $eventScript)) {
    throw "Missing script: $eventScript"
}

if ($Scope -eq "User") {
    $codexDir = Join-Path $env:USERPROFILE ".codex"
} else {
    $codexDir = Join-Path $projectRoot ".codex"
}

New-Item -ItemType Directory -Force -Path $codexDir | Out-Null
$hooksPath = Join-Path $codexDir "hooks.json"

function ConvertTo-Hashtable($value) {
    if ($null -eq $value) {
        return $null
    }
    if ($value -is [System.Collections.IDictionary]) {
        $table = @{}
        foreach ($key in $value.Keys) {
            $table[$key] = ConvertTo-Hashtable $value[$key]
        }
        return $table
    }
    if ($value -is [System.Collections.IEnumerable] -and -not ($value -is [string])) {
        $items = @()
        foreach ($item in $value) {
            $items += ConvertTo-Hashtable $item
        }
        return $items
    }
    if ($value -is [pscustomobject]) {
        $table = @{}
        foreach ($property in $value.PSObject.Properties) {
            $table[$property.Name] = ConvertTo-Hashtable $property.Value
        }
        return $table
    }
    return $value
}

if (Test-Path -LiteralPath $hooksPath) {
    try {
        $config = ConvertTo-Hashtable (Get-Content -Raw -Encoding UTF8 -LiteralPath $hooksPath | ConvertFrom-Json)
    } catch {
        $backup = "$hooksPath.bak-$(Get-Date -Format yyyyMMddHHmmss)"
        Copy-Item -LiteralPath $hooksPath -Destination $backup
        Write-Host "Existing hooks.json is not valid JSON. Backup written to $backup"
        $config = @{}
    }
} else {
    $config = @{}
}

if (-not $config.ContainsKey("hooks") -or -not ($config["hooks"] -is [hashtable])) {
    $config["hooks"] = @{}
}

$hooks = $config["hooks"]

function New-CommandHook([string] $command, [string] $statusMessage) {
    $hook = [ordered]@{
        type = "command"
        command = $command
        commandWindows = $command
        timeout = 5
    }
    if ($statusMessage) {
        $hook["statusMessage"] = $statusMessage
    }
    return $hook
}

function Remove-MobileHooks([object[]] $entries) {
    $result = @()
    foreach ($entry in $entries) {
        $entryHooks = @($entry.hooks)
        $keptHooks = @()
        foreach ($hook in $entryHooks) {
            $command = [string] $hook.command
            $commandWindows = [string] $hook.commandWindows
            if ($command.Contains("codex_mobile_hook.py") -or
                $command.Contains("start_codex_mobile_server.py") -or
                $commandWindows.Contains("codex_mobile_hook.py") -or
                $commandWindows.Contains("start_codex_mobile_server.py")) {
                continue
            }
            $keptHooks += $hook
        }

        if ($keptHooks.Count -gt 0) {
            $entry.hooks = $keptHooks
            $result += $entry
        }
    }
    return $result
}

function Set-HookEntries([string] $eventName, [object[]] $newEntries) {
    if ($hooks.ContainsKey($eventName)) {
        $existing = Remove-MobileHooks @($hooks[$eventName])
    } else {
        $existing = @()
    }
    $hooks[$eventName] = @($existing + $newEntries)
}

$python = "python"
$startCommand = "$python `"$startScript`""
$eventCommand = "$python `"$eventScript`""

Set-HookEntries "SessionStart" @(
    [ordered]@{
        matcher = "startup|resume|clear"
        hooks = @(
            New-CommandHook $startCommand "Starting Codex LAN Monitor"
            New-CommandHook $eventCommand "Publishing Codex status"
        )
    }
)

Set-HookEntries "UserPromptSubmit" @(
    [ordered]@{
        hooks = @(
            New-CommandHook $eventCommand "Publishing Codex status"
        )
    }
)

foreach ($eventName in @("PreToolUse", "PostToolUse", "PermissionRequest")) {
    Set-HookEntries $eventName @(
        [ordered]@{
            matcher = ".*"
            hooks = @(
                New-CommandHook $eventCommand "Publishing Codex status"
            )
        }
    )
}

Set-HookEntries "Stop" @(
    [ordered]@{
        hooks = @(
            New-CommandHook $eventCommand "Publishing Codex status"
        )
    }
)

$json = $config | ConvertTo-Json -Depth 20
Set-Content -LiteralPath $hooksPath -Value $json -Encoding UTF8

Write-Host "Installed Codex Mobile hooks:"
Write-Host "  Scope: $Scope"
Write-Host "  File:  $hooksPath"
Write-Host ""
Write-Host "Restart Codex. The first run may ask you to review and trust the hook definition."
