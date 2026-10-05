$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
mvn -q package
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o Java.' }
& .\.venv\Scripts\python.exe -m unittest discover -s tests -v
if ($LASTEXITCODE -ne 0) { throw 'Falha nos testes Python ou nos testes de integracao Java.' }
New-Item -ItemType Directory -Force .secrets\testclasses | Out-Null
$probes = @('ButtonGateProbe', 'ToggleGateProbe', 'ExclusiveLightsProbe', 'TrackedLightsProbe')
$sources = $probes | ForEach-Object { "tests\$_.java" }
javac -cp target\classes -d .secrets\testclasses $sources
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar os testes Java.' }
foreach ($probe in $probes) {
    java -cp 'target\classes;.secrets\testclasses' "local.gestureswitch.$probe"
    if ($LASTEXITCODE -ne 0) { throw "Falha em $probe." }
}
& .\.venv\Scripts\python.exe diagnostics\check_public_files.py
if ($LASTEXITCODE -ne 0) { throw 'Falha na verificacao de privacidade.' }
Write-Host 'Verificacao concluida: compilacao, testes e privacidade.'
