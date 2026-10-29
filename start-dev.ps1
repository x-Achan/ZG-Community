param(
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendRoot = "D:\zhiguang\zhiguang_fe-main\zhiguang_fe-main"
$redisRoot = "D:\work_software\Redis-8.10.2-Windows-x64-cygwin-with-Service\Redis-8.10.2-Windows-x64-cygwin-with-Service"
$kafkaRoot = "D:\work_software\kafka_2.13-3.9.2"
$canalRoot = "D:\work_software\canal"
$javaRoot = "D:\work_software\Java\jdk-21"
$logRoot = Join-Path $projectRoot ".dev-logs"

$redisExe = Join-Path $redisRoot "redis-server.exe"
$redisConfig = Join-Path $redisRoot "redis.conf"
$kafkaStart = Join-Path $kafkaRoot "bin\windows\kafka-server-start.bat"
$kafkaConfig = Join-Path $kafkaRoot "config\kraft\server.properties"
$kafkaLogConfig = Join-Path $kafkaRoot "config\log4j.properties"
$kafkaClasspath = Join-Path $kafkaRoot "libs\*"
$canalStart = Join-Path $canalRoot "bin\startup.bat"
$cmdExe = Join-Path $env:WINDIR "System32\cmd.exe"

function Assert-Path {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
}

function Test-TcpPort {
    param(
        [string]$Address,
        [int]$Port,
        [int]$TimeoutMilliseconds = 500
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($Address, $Port)
        if (-not $task.Wait($TimeoutMilliseconds)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-TcpPort {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -Address "127.0.0.1" -Port $Port) {
            Write-Host "[OK] $Name is listening on $Port" -ForegroundColor Green
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not become ready on port $Port. Check logs in $logRoot"
}

function Start-HiddenProcess {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory
    )

    $stdout = Join-Path $logRoot "$Name.out.log"
    $stderr = Join-Path $logRoot "$Name.err.log"
    Write-Host "[START] $Name"
    Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr | Out-Null
}

Assert-Path -Path $frontendRoot -Label "Frontend directory"
Assert-Path -Path (Join-Path $frontendRoot "package.json") -Label "Frontend package.json"
Assert-Path -Path $redisExe -Label "Redis executable"
Assert-Path -Path $redisConfig -Label "Redis config"
Assert-Path -Path $kafkaStart -Label "Kafka startup script"
Assert-Path -Path $kafkaConfig -Label "Kafka KRaft config"
Assert-Path -Path $kafkaLogConfig -Label "Kafka logging config"
Assert-Path -Path $canalStart -Label "Canal startup script"
Assert-Path -Path (Join-Path $javaRoot "bin\java.exe") -Label "JDK"

$npmCommand = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
if ($null -eq $npmCommand) {
    throw "npm.cmd was not found in PATH"
}

if ($CheckOnly) {
    Write-Host "Configuration check passed." -ForegroundColor Green
    foreach ($service in @(
        @{ Name = "MySQL"; Port = 3306 },
        @{ Name = "Redis"; Port = 6379 },
        @{ Name = "Kafka"; Port = 9092 },
        # Kafka mode keeps the metrics listener on 11112; the classic 11111
        # client port is not a reliable readiness signal in this setup.
        @{ Name = "Canal"; Port = 11112 },
        @{ Name = "Frontend"; Port = 5173 }
    )) {
        $status = if (Test-TcpPort -Address "127.0.0.1" -Port $service.Port) { "RUNNING" } else { "STOPPED" }
        Write-Host ("[{0}] {1} ({2})" -f $status, $service.Name, $service.Port)
    }
    exit 0
}

New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

# Child Java processes inherit these values; no system-wide environment is changed.
$env:JAVA_HOME = $javaRoot
$env:PATH = "$(Join-Path $javaRoot 'bin');$env:PATH"
# Kafka 3.9's Windows launcher probes the OS with WMIC when this variable is
# absent. WMIC is removed from current Windows versions, so set the heap size
# explicitly and skip that obsolete probe.
$env:KAFKA_HEAP_OPTS = "-Xms512M -Xmx512M"

if (-not (Test-TcpPort -Address "127.0.0.1" -Port 3306)) {
    Write-Warning "MySQL is not listening on 3306. Canal and Spring Boot require MySQL."
}

if (Test-TcpPort -Address "127.0.0.1" -Port 6379) {
    Write-Host "[SKIP] Redis is already running" -ForegroundColor DarkGreen
} else {
    # This Redis build uses Cygwin path handling and corrupts an absolute Windows
    # config path. Run it from the Redis directory and pass the relative filename.
    Start-HiddenProcess -Name "redis" -FilePath $redisExe -ArgumentList @("redis.conf") -WorkingDirectory $redisRoot
    Wait-TcpPort -Name "Redis" -Port 6379 -TimeoutSeconds 20
}

if (Test-TcpPort -Address "127.0.0.1" -Port 9092) {
    Write-Host "[SKIP] Kafka is already running" -ForegroundColor DarkGreen
} else {
    # kafka-run-class.bat expands every jar into one command and exceeds the
    # Windows command-line limit. Java understands libs\* directly.
    $kafkaJava = Join-Path $javaRoot "bin\java.exe"
    $kafkaArgs = @(
        "-Xms512M",
        "-Xmx512M",
        "-server",
        "-XX:+UseG1GC",
        "-Djava.awt.headless=true",
        "-Dlog4j.configuration=file:$kafkaLogConfig",
        "-cp",
        $kafkaClasspath,
        "kafka.Kafka",
        $kafkaConfig
    )
    Start-HiddenProcess -Name "kafka" -FilePath $kafkaJava -ArgumentList $kafkaArgs -WorkingDirectory $kafkaRoot
    Wait-TcpPort -Name "Kafka" -Port 9092 -TimeoutSeconds 60
}

if (Test-TcpPort -Address "127.0.0.1" -Port 11112) {
    Write-Host "[SKIP] Canal is already running" -ForegroundColor DarkGreen
} else {
    $canalCommand = "call `"$canalStart`""
    Start-HiddenProcess -Name "canal" -FilePath $cmdExe -ArgumentList @("/d", "/c", $canalCommand) -WorkingDirectory $canalRoot
    Wait-TcpPort -Name "Canal" -Port 11112 -TimeoutSeconds 60
}

if (Test-TcpPort -Address "127.0.0.1" -Port 5173) {
    Write-Host "[SKIP] Frontend is already running" -ForegroundColor DarkGreen
} else {
    $frontendCommand = "call `"$($npmCommand.Source)`" run dev -- --host 127.0.0.1"
    Start-HiddenProcess -Name "frontend" -FilePath $cmdExe -ArgumentList @("/d", "/c", $frontendCommand) -WorkingDirectory $frontendRoot
    Wait-TcpPort -Name "Frontend" -Port 5173 -TimeoutSeconds 45
}

Write-Host ""
Write-Host "All requested development services are ready." -ForegroundColor Green
Write-Host "Frontend: http://127.0.0.1:5173"
Write-Host "Logs: $logRoot"
Write-Host "Spring Boot was not started; start it from IntelliJ when ready."
