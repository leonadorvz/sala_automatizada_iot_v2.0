# ==============================================================================
# PUENTE USB-SERIAL A HTTP (ARDUINO UNO R4 WIFI <-> EMULADOR ANDROID & FIREBASE)
# Puerto Serial: COM3 (115200 baudios)
# Servidor HTTP Local: http://127.0.0.1:8085 (En Emulador: http://10.0.2.2:8085)
# ==============================================================================

$portName = "COM3"
$baudRate = 115200
$httpPort = 8085

$latestJson = '{"temperatura":24.8,"humedad":45.0,"luz":"Optimo","personas":2,"ventilador":false}'
$serialBuffer = ""
$serialPort = $null

function Ensure-SerialConnected {
    if ($null -ne $script:serialPort -and $script:serialPort.IsOpen) {
        return $true
    }
    try {
        $ports = [System.IO.Ports.SerialPort]::GetPortNames()
        $target = if ($ports -contains $portName) { $portName } elseif ($ports.Count -gt 0) { $ports[0] } else { $null }
        if ($null -eq $target) { return $false }

        $sp = New-Object System.IO.Ports.SerialPort $target, $baudRate, "None", 8, "One"
        $sp.DtrEnable = $true
        $sp.RtsEnable = $true
        $sp.ReadTimeout = 50
        $sp.WriteTimeout = 500
        $sp.Open()
        $script:serialPort = $sp
        Write-Host "[OK] Conectado al Arduino en $target ($baudRate baudios)"
        return $true
    } catch {
        return $false
    }
}

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $httpPort)
$listener.Start()
Write-Host "[OK] Puente HTTP Arduino escuchando en puerto $httpPort (GET /estado | POST /ventilador)"

Ensure-SerialConnected | Out-Null

while ($true) {
    # 1. Leer telemetria en vivo desde el puerto USB Serial (COM3)
    if (Ensure-SerialConnected) {
        try {
            if ($serialPort.BytesToRead -gt 0) {
                $chunk = $serialPort.ReadExisting()
                $serialBuffer += $chunk
                while ($serialBuffer.Contains("`n")) {
                    $idx = $serialBuffer.IndexOf("`n")
                    $line = $serialBuffer.Substring(0, $idx).Trim()
                    $serialBuffer = $serialBuffer.Substring($idx + 1)
                    if ($line.StartsWith("TELEMETRY:")) {
                        $jsonCandidate = $line.Substring(10).Trim()
                        if ($jsonCandidate.StartsWith("{") -and $jsonCandidate.EndsWith("}")) {
                            $latestJson = $jsonCandidate
                            Write-Host "[SENSOR] $latestJson"
                        }
                    }
                }
            }
        } catch {
            try { $serialPort.Close() } catch {}
            $serialPort = $null
        }
    }

    # 2. Atender peticiones HTTP de la App Android
    if ($listener.Pending()) {
        try {
            $client = $listener.AcceptTcpClient()
            $client.ReceiveTimeout = 1000
            $client.SendTimeout = 1000
            $stream = $client.GetStream()

            $buffer = New-Object byte[] 4096
            $read = 0
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            while (-not $stream.DataAvailable -and $sw.ElapsedMilliseconds -lt 500) {
                Start-Sleep -Milliseconds 5
            }
            if ($stream.DataAvailable) {
                $read = $stream.Read($buffer, 0, $buffer.Length)
            }
            $reqText = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $read)

            if ($reqText.StartsWith("POST /ventilador")) {
                $turnOn = $reqText -match '"ventilador"\s*:\s*true'
                if (Ensure-SerialConnected) {
                    $cmd = if ($turnOn) { "FAN:1" } else { "FAN:0" }
                    $serialPort.WriteLine($cmd)
                    Write-Host "[COMANDO] Enviado a Arduino: $cmd"
                    Start-Sleep -Milliseconds 80
                }
            }

            $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($latestJson)
            $headers = "HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
            $headerBytes = [System.Text.Encoding]::UTF8.GetBytes($headers)

            $stream.Write($headerBytes, 0, $headerBytes.Length)
            $stream.Write($bodyBytes, 0, $bodyBytes.Length)
            $stream.Flush()
            $client.Close()
        } catch {
            # Ignorar error de cliente individual
        }
    } else {
        Start-Sleep -Milliseconds 15
    }
}
