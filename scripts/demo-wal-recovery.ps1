$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")

Start-DemoCluster

Show-Step "write on MASTER (autosave off, WAL fsync)"
Write-Host (Invoke-Api POST "$Master/api/v1/collections" (New-CollectionJson)).Body
Write-Host (Invoke-Api PUT "$Master/api/v1/collections/docs/vectors/x" (New-VectorJson 1 0 0)).Body
Write-Host "WAL $((Invoke-Api GET "$Master/api/v1/replication/wal?n=10").Body)"
Write-Host "STATUS $((Invoke-Api GET "$Master/api/v1/replication/status").Body)"

Show-Step "SIGKILL master (no snapshot), start the same volume"
Invoke-DemoCompose @("kill", "master")
Invoke-DemoCompose @("start", "master")
Wait-HttpOk "$Master/api/v1/health"

Show-Step "GET after WAL replay"
Write-Host (Invoke-Api GET "$Master/api/v1/collections/docs/vectors/x").Body
Write-Host "STATUS $((Invoke-Api GET "$Master/api/v1/replication/status").Body)"

Write-Host ""
Write-Host "cluster is still up. Stop: docker compose -f compose.demo.yaml down -v"
