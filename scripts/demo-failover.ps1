$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")

Start-DemoCluster

Show-Step "seed a collection on MASTER"
[void](Invoke-Api POST "$Master/api/v1/collections" (New-CollectionJson))
[void](Invoke-Api PUT "$Master/api/v1/collections/docs/vectors/a" (New-VectorJson 1 0 0))
Start-Sleep -Seconds 3

Show-Step "write on replica is rejected (expect 403)"
$denied = Invoke-Api PUT "$Replica/api/v1/collections/docs/vectors/x" (New-VectorJson 0 1 0) -AllowError
Write-Host "PUT replica HTTP $($denied.Status)"

Show-Step "kill MASTER, PROMOTE replica"
Invoke-DemoCompose @("kill", "master")
Write-Host (Invoke-Api POST "$Replica/api/v1/replication/promote").Body
Write-Host "STATUS $((Invoke-Api GET "$Replica/api/v1/replication/status").Body)"

Show-Step "writes go to the new MASTER (old replica :2223)"
Write-Host (Invoke-Api PUT "$Replica/api/v1/collections/docs/vectors/b" (New-VectorJson 0 1 0)).Body
Write-Host "GET b $((Invoke-Api GET "$Replica/api/v1/collections/docs/vectors/b").Body)"

Write-Host ""
Write-Host "do not start the old master: two masters are not handled. Clean: docker compose -f compose.demo.yaml down -v"
