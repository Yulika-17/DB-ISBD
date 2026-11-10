$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ComposeFile = Join-Path $Root "compose.demo.yaml"
$Master = "http://localhost:2222"
$Replica = "http://localhost:2223"

# PowerShell 5 strips quotes in arguments to curl.exe, so JSON must not go through -d "...".
Add-Type -AssemblyName System.Net.Http | Out-Null
if (-not $script:DemoHttp) {
    $script:DemoHttp = [System.Net.Http.HttpClient]::new()
    $script:DemoHttp.Timeout = [TimeSpan]::FromSeconds(30)
}

function Invoke-DemoCompose {
    param([Parameter(Mandatory = $true)][string[]]$ComposeArgs)
    & docker compose -f $ComposeFile --project-directory $Root @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($ComposeArgs -join ' ')"
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Url,
        [string]$Body,
        [switch]$AllowError
    )
    $httpMethod = switch ($Method.ToUpperInvariant()) {
        "GET"    { [System.Net.Http.HttpMethod]::Get }
        "POST"   { [System.Net.Http.HttpMethod]::Post }
        "PUT"    { [System.Net.Http.HttpMethod]::Put }
        "DELETE" { [System.Net.Http.HttpMethod]::Delete }
        default  { New-Object System.Net.Http.HttpMethod $Method }
    }
    $request = New-Object System.Net.Http.HttpRequestMessage $httpMethod, $Url
    if ($null -ne $Body -and $Body.Length -gt 0) {
        $request.Content = [System.Net.Http.StringContent]::new(
            $Body,
            [System.Text.Encoding]::UTF8,
            "application/json")
    }
    $response = $script:DemoHttp.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $code = [int]$response.StatusCode
    if (-not $AllowError -and $code -ge 400) {
        throw "HTTP $code $Method $Url : $text"
    }
    return [pscustomobject]@{ Status = $code; Body = $text }
}

function Get-JsonLsn([string]$Json) {
    $match = [regex]::Match($Json, '"lsn"\s*:\s*(\d+)')
    if (-not $match.Success) {
        throw "no lsn in response: $Json"
    }
    return $match.Groups[1].Value
}

function New-CollectionJson {
    return '{"name":"docs","dimension":3,"metric":"COSINE"}'
}

function New-VectorJson([double]$X, [double]$Y, [double]$Z) {
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    return ('{{"vector":[{0},{1},{2}]}}' -f $X.ToString($inv), $Y.ToString($inv), $Z.ToString($inv))
}

function Get-JsonLong([string]$Json, [string]$Name) {
    $match = [regex]::Match($Json, '"' + [regex]::Escape($Name) + '"\s*:\s*(-?\d+)')
    if (-not $match.Success) {
        throw "field $Name missing in $Json"
    }
    return [long]$match.Groups[1].Value
}

function Wait-ReplicaCaughtUp([int]$Seconds = 60) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $status = $null
    while ((Get-Date) -lt $deadline) {
        $masterLsn = Get-JsonLong (Invoke-Api GET "$Master/api/v1/replication/status").Body "lastLsn"
        $status = (Invoke-Api GET "$Replica/api/v1/replication/status").Body
        $applied = Get-JsonLong $status "lastAppliedLsn"
        if ($applied -ge $masterLsn) {
            return $status
        }
        Start-Sleep -Milliseconds 400
    }
    throw "replica did not catch up in ${Seconds}s; last status: $status"
}

function Wait-HttpOk([string]$Url, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-Api -Method GET -Url $Url -AllowError
            if ($r.Status -eq 200) {
                return
            }
        } catch {
            # still starting
        }
        Start-Sleep -Milliseconds 400
    }
    throw "timeout waiting for $Url"
}

function Start-DemoCluster {
    Write-Host "== cluster: recreate from compose.demo.yaml"
    Invoke-DemoCompose @("down", "-v", "--remove-orphans")
    Invoke-DemoCompose @("up", "--build", "-d")
    Wait-HttpOk "$Master/api/v1/health"
    Wait-HttpOk "$Replica/api/v1/health"
    Write-Host "master  $Master  $((Invoke-Api GET "$Master/api/v1/replication/status").Body)"
    Write-Host "replica $Replica $((Invoke-Api GET "$Replica/api/v1/replication/status").Body)"
}

function Show-Step([string]$Title) {
    Write-Host ""
    Write-Host "== $Title"
}
