param(
    [Parameter(Mandatory = $true)]
    [string]$InputFile,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$SourceId
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$inputPath = (Resolve-Path -LiteralPath $InputFile).Path
$extractedPath = Join-Path $projectRoot "data\extracted\$SourceId.txt"
$cleanedPath = Join-Path $projectRoot "data\cleaned\$SourceId.txt"
$reportPath = Join-Path $projectRoot "data\cleaned\$SourceId.report.json"

New-Item -ItemType Directory -Force (Split-Path -Parent $extractedPath) | Out-Null
New-Item -ItemType Directory -Force (Split-Path -Parent $cleanedPath) | Out-Null

Push-Location $projectRoot
try {
    & mvn compile exec:java "-Dexec.mainClass=com.example.rag.cli.DocumentExtractor" "-Dexec.args='$inputPath' '$extractedPath'"
    if ($LASTEXITCODE -ne 0) {
        throw "Tika 文档提取失败，退出码: $LASTEXITCODE"
    }

    & python .\clean_extracted_text.py --input $extractedPath --output $cleanedPath --report $reportPath
    if ($LASTEXITCODE -ne 0) {
        throw "文本清洗失败，退出码: $LASTEXITCODE"
    }

    Write-Host ""
    Write-Host "处理完成"
    Write-Host "Tika 原始结果: $extractedPath"
    Write-Host "自动清洗结果: $cleanedPath"
    Write-Host "清洗统计报告: $reportPath"
}
finally {
    Pop-Location
}
