param(
    [Parameter(Mandatory=$true)][string]$ServerJar,
    [Parameter(Mandatory=$true)][string]$VaultJar,
    [Parameter(Mandatory=$true)][string]$EssentialsJar,
    [Parameter(Mandatory=$true)][string]$Java,
    [Parameter(Mandatory=$true)][string]$Name,
    [int]$Port = 25610,
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
if ($Name -notmatch '^[a-z0-9-]+$') { throw 'Name must be a simple directory name' }
$target = Join-Path $workspace "build\qa\$Name"
if (Test-Path -LiteralPath $target) { throw "Use a new disposable QA name: $target" }
$server = (Resolve-Path -LiteralPath $ServerJar).Path
$vault = (Resolve-Path -LiteralPath $VaultJar).Path
$essentials = (Resolve-Path -LiteralPath $EssentialsJar).Path
New-Item -ItemType Directory -Force -Path "$target\plugins\CryptoCraft" | Out-Null
Copy-Item -LiteralPath $server -Destination "$target\server.jar"
Copy-Item -LiteralPath $vault -Destination "$target\plugins\Vault.jar"
Copy-Item -LiteralPath $essentials -Destination "$target\plugins\EssentialsX.jar"
Copy-Item -LiteralPath "$workspace\build\libs\CryptoCraft-1.2.0.jar" -Destination "$target\plugins\CryptoCraft.jar"
Copy-Item -LiteralPath "$workspace\build\libs\CryptoCraft-ServerQA-1.2.0.jar" -Destination "$target\plugins\CryptoCraftServerQA.jar"
@'
language: en
coins:
  BTC: bitcoin
  ETH: ethereum
  SOL: solana
currencies: [EUR]
default-currency: EUR
api:
  url: https://127.0.0.1:1/disabled-for-disposable-qa
prices:
  request-timeout-seconds: 1
integrations:
  worldguard: false
  placeholderapi: false
'@ | Set-Content -LiteralPath "$target\plugins\CryptoCraft\config.yml" -Encoding utf8
@"
server-ip=127.0.0.1
server-port=$Port
online-mode=false
view-distance=2
simulation-distance=2
max-players=1
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
allow-nether=false
enable-query=false
enable-rcon=false
max-tick-time=60000
pause-when-empty-seconds=-1
"@ | Set-Content -LiteralPath "$target\server.properties" -Encoding ascii
'eula=true' | Set-Content -LiteralPath "$target\eula.txt" -Encoding ascii
if ($PrepareOnly) { Write-Output $target; return }
Push-Location $target
try {
    foreach ($phase in 1,2) {
        & $Java '-Xms256M' '-Xmx1G' '--enable-native-access=ALL-UNNAMED' '-Djava.awt.headless=true' '-Dterminal.jline=false' '-Dterminal.ansi=false' '-Dcryptocraft.qa.disposable=true' '-jar' 'server.jar' 'nogui' *> "phase$phase.log"
        if (-not (Test-Path -LiteralPath "plugins\CryptoCraftServerQA\phase$phase-passed.txt")) {
            if (Test-Path 'logs/latest.log') { Get-Content -LiteralPath 'logs/latest.log' -Tail 60 }
            else { Get-Content -LiteralPath "phase$phase.log" -Tail 40 }
            throw "Server QA failed: $Name phase $phase"
        }
        Get-Content -LiteralPath "plugins\CryptoCraftServerQA\phase$phase-passed.txt"
    }
} finally { Pop-Location }
