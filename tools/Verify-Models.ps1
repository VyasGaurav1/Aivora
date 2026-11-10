$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$inventory = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/MODELS.lock.json') | ConvertFrom-Json
$files = @($inventory.runtime) + @($inventory.speechModel.files) + @($inventory.ttsModel.files) + @($inventory.speakerModel.files)
$wake = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/WAKE_MODELS.lock.json') | ConvertFrom-Json
$files += @($wake.files)
foreach ($file in $files) {
    $path = Join-Path $root $file.path
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing bundled asset: $($file.path)" }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $file.sha256) { throw "Bundled asset checksum mismatch: $($file.path)" }
}
Write-Output "Verified $($files.Count) pinned speech runtime/model files."
