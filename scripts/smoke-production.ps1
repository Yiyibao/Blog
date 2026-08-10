param([Parameter(Mandatory = $true)][uri]$BaseUrl)
$ErrorActionPreference = 'Stop'
if ($BaseUrl.Scheme -ne 'https') { throw 'Production smoke tests require HTTPS' }
$paths = @('/', '/login', '/admin/login', '/api/v1/posts?page=0&size=1', '/api/v1/dishes?page=0&size=1', '/sitemap.xml', '/rss.xml', '/robots.txt', '/actuator/health')
foreach ($path in $paths) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri ([uri]::new($BaseUrl, $path)) -MaximumRedirection 2 -TimeoutSec 20
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) { throw "$path returned $($response.StatusCode)" }
    Write-Host "PASS $path $($response.StatusCode)"
}
