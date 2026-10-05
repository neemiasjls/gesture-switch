$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path .venv)) {
    py -3.13 -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao criar o ambiente Python 3.13.' }
}
& .\.venv\Scripts\python.exe -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar as dependencias Python.' }
New-Item -ItemType Directory -Force .secrets | Out-Null
if (-not (Test-Path '.secrets\config.ini')) { Copy-Item 'config.example.ini' '.secrets\config.ini' }
New-Item -ItemType Directory -Force models | Out-Null
$model = Join-Path $PSScriptRoot 'models\gesture_recognizer.task'
if (-not (Test-Path $model)) {
    Invoke-WebRequest 'https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task' -OutFile $model
}
mvn -q package
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o projeto Java.' }
Write-Host 'Pronto. Execute: .\run.ps1'
