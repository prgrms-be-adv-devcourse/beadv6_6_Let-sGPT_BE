param(
    [Parameter(Mandatory = $true)]
    [string] $Question,
    [string] $GatewayBaseUrl = "http://localhost:8000",
    [string] $Email = "admin@test.com",
    [string] $Password = "admin1234"
)

$ErrorActionPreference = "Stop"

$loginBody = @{
    email = $Email
    password = $Password
} | ConvertTo-Json -Compress

$tokenResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$GatewayBaseUrl/api/v1/members/login" `
    -ContentType "application/json" `
    -Body $loginBody

Add-Type -AssemblyName System.Net.Http

$client = [System.Net.Http.HttpClient]::new()
$request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post,
    "$GatewayBaseUrl/api/v1/ai/chats"
)
$request.Headers.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
        "Bearer",
        $tokenResponse.accessToken
    )
$request.Headers.Accept.Add(
    [System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream")
)
$request.Content = [System.Net.Http.StringContent]::new(
    (@{ message = $Question } | ConvertTo-Json -Compress),
    [System.Text.Encoding]::UTF8,
    "application/json"
)

$watch = [System.Diagnostics.Stopwatch]::StartNew()
$response = $null
$reader = $null

try {
    $response = $client.SendAsync(
        $request,
        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
    ).GetAwaiter().GetResult()
    [void] $response.EnsureSuccessStatusCode()

    $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream)
    $eventName = ""
    $dataLines = [System.Collections.Generic.List[string]]::new()
    $answer = [System.Text.StringBuilder]::new()
    $events = [ordered]@{}
    $stages = [System.Collections.Generic.List[string]]::new()
    $terminalDone = $false
    $terminalError = $null
    $firstEventMs = $null
    $firstContentMs = $null

    while (($line = $reader.ReadLineAsync().GetAwaiter().GetResult()) -ne $null) {
        if ($line.StartsWith("event:")) {
            $eventName = $line.Substring(6).Trim()
            continue
        }
        if ($line.StartsWith("data:")) {
            $dataLines.Add($line.Substring(5).Trim())
            continue
        }
        if ($line.Length -ne 0 -or [string]::IsNullOrWhiteSpace($eventName)) {
            continue
        }

        if ($null -eq $firstEventMs) {
            $firstEventMs = $watch.ElapsedMilliseconds
        }
        if (-not $events.Contains($eventName)) {
            $events[$eventName] = 0
        }
        $events[$eventName]++

        $data = $dataLines -join "`n"
        try {
            $payload = $data | ConvertFrom-Json
            switch ($eventName) {
                "status" {
                    $stages.Add([string] $payload.stage)
                }
                "delta" {
                    if ($null -eq $firstContentMs) {
                        $firstContentMs = $watch.ElapsedMilliseconds
                    }
                    [void] $answer.Append([string] $payload.text)
                }
                "error" {
                    if ($null -eq $firstContentMs) {
                        $firstContentMs = $watch.ElapsedMilliseconds
                    }
                    [void] $answer.Append([string] $payload.message)
                    $terminalError = [string] $payload.code
                }
                "done" {
                    $terminalDone = $true
                }
            }
        } finally {
            $eventName = ""
            $dataLines.Clear()
        }
    }

    $watch.Stop()
    [PSCustomObject]@{
        question = $Question
        httpStatus = [int] $response.StatusCode
        firstEventMs = $firstEventMs
        firstContentMs = $firstContentMs
        totalMs = $watch.ElapsedMilliseconds
        events = [PSCustomObject] $events
        stages = @($stages)
        terminalDone = $terminalDone
        terminalError = $terminalError
        answer = $answer.ToString()
    } | ConvertTo-Json -Depth 8
} finally {
    if ($null -ne $reader) {
        $reader.Dispose()
    }
    if ($null -ne $response) {
        $response.Dispose()
    }
    $request.Dispose()
    $client.Dispose()
}
