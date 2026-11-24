$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")

Start-DemoCluster

Show-Step "create collection + PUT on MASTER"
Write-Host (Invoke-Api POST "$Master/api/v1/collections" (New-CollectionJson)).Body

$put = Invoke-Api PUT "$Master/api/v1/collections/docs/vectors/7" (New-VectorJson 1 0 0)
Write-Host $put.Body
$lsn = Get-JsonLsn $put.Body
Write-Host "[CLIENT] write ok lsn=$lsn"

Show-Step "anomaly: GET replica immediately (expect 404, lag > 0)"
$stale = Invoke-Api GET "$Replica/api/v1/collections/docs/vectors/7" -AllowError
Write-Host "GET replica id=7 HTTP $($stale.Status)"
Write-Host "replica STATUS $((Invoke-Api GET "$Replica/api/v1/replication/status").Body)"

Show-Step "solution: GET replica with LSN token waitLsn=$lsn"
Write-Host (Invoke-Api GET "$Replica/api/v1/collections/docs/vectors/7?waitLsn=$lsn").Body
Write-Host "replica STATUS $((Invoke-Api GET "$Replica/api/v1/replication/status").Body)"

Write-Host ""
Write-Host "cluster is still up. Stop: docker compose -f compose.demo.yaml down -v"
