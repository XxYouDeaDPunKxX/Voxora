param([string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug'))

$ErrorActionPreference = 'Stop'
$wrapper = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw 'Gradle Wrapper non trovato: gradlew.bat'
}

Push-Location $PSScriptRoot
try {
    & $wrapper --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Build Gradle fallita: $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
