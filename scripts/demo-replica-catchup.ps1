$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")

Start-DemoCluster

Show-Step "sync initial record to replica"
[void](Invoke-Api POST "$Master/api/v1/collections" (New-CollectionJson))
[void](Invoke-Api PUT "$Master/api/v1/collections/docs/vectors/a" (New-VectorJson 1 0 0))
Write-Host "replica STATUS $(Wait-ReplicaCaughtUp)"

Show-Step "stop replica, MASTER keeps writing"
Invoke-DemoCompose @("stop", "replica")
[void](Invoke-Api PUT "$Master/api/v1/collections/docs/vectors/b" (New-VectorJson 0 1 0))
[void](Invoke-Api PUT "$Master/api/v1/collections/docs/vectors/c" (New-VectorJson 0 0 1))
Write-Host "master STATUS $((Invoke-Api GET "$Master/api/v1/replication/status").Body)"

Show-Step "start replica — catch-up from WAL"
Invoke-DemoCompose @("start", "replica")
Wait-HttpOk "$Replica/api/v1/health"
Write-Host "replica STATUS $(Wait-ReplicaCaughtUp)"
Write-Host "GET b $((Invoke-Api GET "$Replica/api/v1/collections/docs/vectors/b").Body)"
Write-Host "GET c $((Invoke-Api GET "$Replica/api/v1/collections/docs/vectors/c").Body)"

Write-Host ""
Write-Host "cluster is still up. Stop: docker compose -f compose.demo.yaml down -v"
