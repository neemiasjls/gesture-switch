param([ValidateSet('general', 'all', 'buttons')][string]$Mode = 'general')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
mvn -q package
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o projeto Java.' }
java -cp target\classes local.gestureswitch.Main --mode $Mode
