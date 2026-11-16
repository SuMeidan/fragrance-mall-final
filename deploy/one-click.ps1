[CmdletBinding()]
param(
    [ValidateSet('Start','Stop','Status')][string]$Action = 'Start',
    [string]$RuntimeDirectory = '',
    [string]$DownloadCache = '',
    [string]$MavenRepository = '',
    [switch]$NoBrowser
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$utf8 = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$packageRoot = $source
$lock = $null

function Write-Json($Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), $utf8)
}
function Read-Json($Path) { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
function Hash-Text([string]$Text) {
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hasher.ComputeHash($utf8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
    finally { $hasher.Dispose() }
}
function New-Password {
    $bytes = New-Object byte[] 16
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return 'Scent' + ([BitConverter]::ToString($bytes)).Replace('-','') + '!'
}
function Get-FreePort([int]$Start) {
    for ($port=$Start; $port -lt $Start+100; $port++) {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$port)
        try { $listener.Server.ExclusiveAddressUse=$true; $listener.Start(); return $port }
        catch { }
        finally { $listener.Stop() }
    }
    throw "没有可用的本地端口：$Start 附近的端口均被占用。"
}
function Download-Tool($Spec) {
    $zip = Join-Path $cache $Spec.archive
    if (Test-Path -LiteralPath $zip) {
        if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Spec.sha256) {
            throw "下载缓存校验失败，请移走此文件后重试：$zip"
        }
        return $zip
    }
    $part = $zip + '.' + [Guid]::NewGuid().ToString('N') + '.part'
    Write-Host "正在下载 $($Spec.archive)（首次运行需要联网）"
    $client = New-Object Net.WebClient
    try {
        $download = $client.DownloadFileTaskAsync([Uri]$Spec.url,$part)
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $nextUpdate = 30
        while (-not $download.IsCompleted) {
            Start-Sleep -Milliseconds 500
            if ($timer.Elapsed.TotalSeconds -gt 900) { $client.CancelAsync(); throw '下载超时，请检查网络后重新双击。' }
            if ($timer.Elapsed.TotalSeconds -ge $nextUpdate) {
                $size = if (Test-Path -LiteralPath $part) { [Math]::Round((Get-Item -LiteralPath $part).Length/1MB,1) } else { 0 }
                Write-Host "  已下载 $size MB，请保持窗口打开。"
                $nextUpdate += 30
            }
        }
        $download.GetAwaiter().GetResult()
        if ((Get-FileHash -LiteralPath $part -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Spec.sha256) {
            throw '下载内容校验失败，未执行或解压。'
        }
        Move-Item -LiteralPath $part -Destination $zip
        return $zip
    } finally { $client.Dispose() }
}
function Install-Tool($Name,$Spec) {
    $directory = Join-Path $toolsDirectory $Spec.directory
    $toolHome = if ($Spec.folder -eq '.') { $directory } else { Join-Path $directory $Spec.folder }
    $executable = Join-Path $toolHome $Spec.executable
    $receipt = Join-Path $directory '.scent-tool.json'
    if (Test-Path -LiteralPath $receipt) {
        $saved = Read-Json $receipt
        if ($saved.archiveSha256 -eq $Spec.sha256 -and (Test-Path -LiteralPath $executable) -and
            (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash -eq $saved.executableSha256) { return $toolHome }
        throw "工具文件已变化，请检查运行目录：$directory"
    }
    if (Test-Path -LiteralPath $directory) { throw "上次工具解压未完成，请检查目录：$directory" }
    $zip = Download-Tool $Spec
    Write-Host "正在安装本地工具：$Name"
    $staging = $directory + '.extract-' + [Guid]::NewGuid().ToString('N')
    [IO.Directory]::CreateDirectory($staging) | Out-Null
    $archive = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($entry in $archive.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $staging $entry.FullName))
            if (-not $target.StartsWith($staging+'\',[StringComparison]::OrdinalIgnoreCase)) { throw '压缩文件包含越界路径。' }
        }
    } finally { $archive.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($zip,$staging)
    Move-Item -LiteralPath $staging -Destination $directory
    if (-not (Test-Path -LiteralPath $executable)) { throw "工具文件缺失：$executable" }
    Write-Json $receipt @{archiveSha256=$Spec.sha256;executableSha256=(Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash}
    return $toolHome
}
function Invoke-Runner([string]$Step) {
    Write-Host "执行：$Step"
    & $python $runner $Step --config $configPath
    if ($LASTEXITCODE -ne 0) { throw "步骤 $Step 失败，详细日志位于：$(Join-Path $instance 'data\logs')" }
}
function Invoke-Local([string]$Url,[string]$Method='GET',$Body=$null,[string]$Token='') {
    $uri = [Uri]$Url
    if ($uri.Host -ne '127.0.0.1' -or $uri.Scheme -ne 'http') { throw '部署检查只访问本地服务。' }
    $request = [Net.HttpWebRequest]::Create($uri)
    $request.Proxy = $null
    $request.Method = $Method
    $request.Timeout = 10000
    if ($Token) { $request.Headers['Authorization'] = 'Bearer '+$Token }
    if ($null -ne $Body) {
        $bytes = $utf8.GetBytes(($Body | ConvertTo-Json -Compress -Depth 6))
        $request.ContentType = 'application/json; charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
    } elseif ($Method -eq 'POST') {
        $request.ContentLength = 0
    }
    try { $response=$request.GetResponse() }
    catch [Net.WebException] {
        $errorResponse=$_.Exception.Response
        if ($null -eq $errorResponse) { throw }
        $errorCode=[int]$errorResponse.StatusCode
        $errorResponse.Dispose()
        $failure=New-Object InvalidOperationException("本地接口返回 HTTP $errorCode：$($uri.AbsolutePath)")
        $failure.Data['StatusCode']=$errorCode
        throw $failure
    }
    try {
        $reader=New-Object IO.StreamReader($response.GetResponseStream(),$utf8)
        try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
        return $text
    } finally { $response.Dispose() }
}
function Wait-Health([string]$Url) {
    for ($attempt=0;$attempt -lt 90;$attempt++) {
        try { if (((Invoke-Local $Url) | ConvertFrom-Json).status -eq 'UP') { return } } catch { }
        Start-Sleep -Seconds 2
    }
    throw '后端未在预期时间内就绪，请查看 data/logs/api.log。'
}

try {
    if (-not [Environment]::Is64BitOperatingSystem -or -not [Environment]::Is64BitProcess) { throw '请使用 Windows 10/11 的 64 位环境。' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $identity=Hash-Text ($source.TrimEnd('\').ToLowerInvariant())
    if (-not $RuntimeDirectory) {
        $RuntimeDirectory=Join-Path $env:LOCALAPPDATA ('SCENT\class-demo\'+$identity.Substring(0,12))
        if ($RuntimeDirectory -match '[^\x00-\x7f]') {
            $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            $RuntimeDirectory=Join-Path $env:PUBLIC ('SCENT-local\'+$sid+'\'+$identity.Substring(0,12))
        }
    }
    $instance=[IO.Path]::GetFullPath($RuntimeDirectory)
    if ($instance -match '[^\x00-\x7f]' -or $instance.StartsWith($packageRoot+'\',[StringComparison]::OrdinalIgnoreCase)) {
        throw '运行目录须位于项目之外，且路径不能包含中文。'
    }
    $ownerFile=Join-Path $instance 'owner.json'
    if (-not (Test-Path -LiteralPath $ownerFile)) {
        if ($Action -ne 'Start') { Write-Host '此项目尚未部署，无需停止。'; exit 0 }
        if ((Test-Path -LiteralPath $instance) -and @(Get-ChildItem -LiteralPath $instance -Force).Count -gt 0) { throw '运行目录已有其他文件，未作覆盖。' }
        [IO.Directory]::CreateDirectory($instance) | Out-Null
        $acl=New-Object Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true,$false)
        $current=[Security.Principal.WindowsIdentity]::GetCurrent().User
        foreach ($sid in @($current,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')),(New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')))) {
            $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $instance -AclObject $acl
        Write-Json $ownerFile @{application='SCENT';source=$source;identity=$identity}
    }
    $owner=Read-Json $ownerFile
    if ($owner.application -ne 'SCENT' -or $owner.identity -ne $identity) { throw '运行目录不属于这份项目，未作修改。' }
    $lock=[IO.File]::Open((Join-Path $instance 'deployment.lock'),'OpenOrCreate','ReadWrite','None')
    $project=Join-Path $instance 'project'
    $runner=Join-Path $project 'deploy\v1.py'
    $configPath=Join-Path $instance 'config.json'
    $manifest=Read-Json (Join-Path $PSScriptRoot 'one-click-tools.json')
    $toolsDirectory=Join-Path $instance 'tools'
    $python=Join-Path (Join-Path $toolsDirectory $manifest.python.directory) 'python.exe'
    if ($Action -ne 'Start') {
        if (-not (Test-Path -LiteralPath $configPath)) { Write-Host '尚未初始化服务。'; exit 0 }
        Invoke-Runner $Action.ToLowerInvariant()
        Write-Host '操作完成；已有数据库和账号保留。'
        exit 0
    }
    Write-Host 'SCENT 本地一键部署：准备环境、构建项目、初始化数据库、启动网页。'
    Write-Host '首次需要联网下载，请保持窗口打开。运行数据只保存在独立本地目录。'
    $cache=if ($DownloadCache) { [IO.Path]::GetFullPath($DownloadCache) } else { Join-Path $instance 'downloads' }
    [IO.Directory]::CreateDirectory($cache) | Out-Null
    [IO.Directory]::CreateDirectory($toolsDirectory) | Out-Null
    $homes=@{}
    foreach ($name in @('python','java','maven','mysql','node','nginx')) { $homes[$name]=Install-Tool $name $manifest.$name }
    $python=Join-Path $homes.python 'python.exe'
    # The JDK includes Microsoft's app-local runtime DLLs, also required by MySQL.
    foreach ($dll in @('msvcp140.dll','vcruntime140.dll','vcruntime140_1.dll')) {
        $from=Join-Path $homes.java ('bin\'+$dll)
        $to=Join-Path $homes.mysql ('bin\'+$dll)
        if (-not (Test-Path -LiteralPath $to)) { Copy-Item -LiteralPath $from -Destination $to }
    }
    $files=@(Get-ChildItem -LiteralPath $source -File -Recurse -Force | Where-Object {
        $_.FullName -notmatch '[\\/](node_modules|target|dist|runtime|\.git|__pycache__)[\\/]' -and
        $_.Name -notlike '.env*' -and $_.Extension -notin @('.pem','.key','.p12','.pfx')
    } | Sort-Object FullName)
    $sourceMap=[ordered]@{}
    foreach ($file in $files) { $sourceMap[$file.FullName.Substring($source.Length+1).Replace('\','/')]=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
    $fingerprint=Hash-Text ($sourceMap | ConvertTo-Json -Compress)
    $builtPath=Join-Path $instance 'built.json'
    $needsBuild= -not (Test-Path -LiteralPath $builtPath)
    if (-not $needsBuild) {
        $previous=Read-Json $builtPath
        if (-not $previous.PSObject.Properties['sourceFiles']) { $needsBuild=$true }
        else {
            $oldMap=@{}
            foreach ($entry in $previous.sourceFiles.PSObject.Properties) { $oldMap[$entry.Name]=$entry.Value }
            $buildInputs=@(@($sourceMap.Keys)+@($oldMap.Keys) | Where-Object {
                $_ -match '^apps/' -or $_ -in @('package.json','package-lock.json','.npmrc','.prettierrc.json')
            } | Select-Object -Unique)
            foreach ($name in $buildInputs) {
                if ($sourceMap[$name] -ne $oldMap[$name]) { $needsBuild=$true; break }
            }
        }
    }
    foreach ($relative in @('apps\api\target\scent-api-0.1.0-SNAPSHOT.jar','apps\storefront\dist\index.html','apps\admin\dist\index.html')) {
        if (-not (Test-Path -LiteralPath (Join-Path $project $relative))) { $needsBuild=$true }
    }
    if ($needsBuild -and (Test-Path -LiteralPath $configPath) -and (Test-Path -LiteralPath $runner)) { Invoke-Runner 'stop' }
    [IO.Directory]::CreateDirectory($project) | Out-Null
    foreach ($file in $files) {
        $relative=$file.FullName.Substring($source.Length+1)
        $target=Join-Path $project $relative
        [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
        if (-not (Test-Path -LiteralPath $target) -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $sourceMap[$relative.Replace('\','/')]) {
            Copy-Item -LiteralPath $file.FullName -Destination $target
        }
    }
    if (-not (Test-Path -LiteralPath $configPath)) {
        $dbPort=Get-FreePort 13359
        $apiPort=Get-FreePort 28089
        $webPort=Get-FreePort 15195
        $config=Get-Content -LiteralPath (Join-Path $project 'deploy\v1.example.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $config.javaHome=$homes.java; $config.mysqlDir=$homes.mysql; $config.nodeHome=$homes.node; $config.nginxHome=$homes.nginx
        $config.runtimeDir=Join-Path $instance 'data'
        $config.adminLogin='scent-admin'; $config.adminPassword=New-Password
        $config.publicOrigin="http://127.0.0.1:$webPort"
        $extra=@{mysqlPort=$dbPort;apiPort=$apiPort;previewPort=$webPort;corsOrigins=$config.publicOrigin;
                 buyerLogin='19900000001';buyerPassword=(New-Password);buyerCreated=$false;
                 mavenHome=(Join-Path $instance 'maven-home');mavenRepository=(Join-Path $instance 'maven-repository')}
        if ($MavenRepository) { $extra.mavenRepository=[IO.Path]::GetFullPath($MavenRepository) }
        foreach ($key in $extra.Keys) { $config | Add-Member -NotePropertyName $key -NotePropertyValue $extra[$key] }
        Write-Json $configPath $config
    }
    $config=Read-Json $configPath
    $config | Add-Member -NotePropertyName 'mavenCommand' -NotePropertyValue (Join-Path $homes.maven 'bin\mvn.cmd') -Force
    Write-Json $configPath $config
    $env:npm_config_cache=Join-Path $instance 'npm-cache'
    if ($needsBuild) {
        Invoke-Runner 'build'
        Invoke-Runner 'web-install'
        Invoke-Runner 'web-build'
        Write-Json $builtPath @{fingerprint=$fingerprint;sourceFiles=$sourceMap}
    } else { Write-Host '源码未变化，复用已构建的程序。' }
    if (-not (Test-Path -LiteralPath (Join-Path $config.runtimeDir 'database-owner.json'))) { Invoke-Runner 'init-db' }
    Invoke-Runner 'start'
    Wait-Health "http://127.0.0.1:$($config.apiPort)/actuator/health"
    Invoke-Runner 'preview-stop'
    Invoke-Runner 'preview-start'
    $origin=$config.publicOrigin
    if ((Invoke-Local ($origin+'/')).Length -lt 100 -or (Invoke-Local ($origin+'/admin/')).Length -lt 100) { throw '前端页面检查未通过。' }
    if (-not $config.buyerCreated) {
        try { Invoke-Local ($origin+'/api/v1/auth/register') 'POST' @{phone=$config.buyerLogin;password=$config.buyerPassword;displayName='演示买家'} | Out-Null }
        catch { if ($_.Exception.Data['StatusCode'] -ne 409) { throw } }
    }
    foreach ($role in @('admin','buyer')) {
        $login=if ($role -eq 'admin') { $config.adminLogin } else { $config.buyerLogin }
        $password=if ($role -eq 'admin') { $config.adminPassword } else { $config.buyerPassword }
        $route=if ($role -eq 'admin') { '/api/v1/auth/admin-login' } else { '/api/v1/auth/login' }
        $session=(Invoke-Local ($origin+$route) 'POST' @{loginName=$login;password=$password}) | ConvertFrom-Json
        Invoke-Local ($origin+'/api/v1/auth/me') 'GET' $null $session.data.token | Out-Null
        Invoke-Local ($origin+'/api/v1/auth/logout') 'POST' $null $session.data.token | Out-Null
    }
    $config=Read-Json $configPath
    $config.buyerCreated=$true
    Write-Json $configPath $config
    $welcome=Join-Path $instance 'SCENT-access.html'
    $buyerPass=[Net.WebUtility]::HtmlEncode($config.buyerPassword)
    $adminPass=[Net.WebUtility]::HtmlEncode($config.adminPassword)
    $html=@"
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SCENT 已启动</title>
<style>body{margin:0;background:#f6f4ef;color:#252824;font:17px/1.7 'Microsoft YaHei',sans-serif}main{max-width:850px;margin:65px auto;padding:30px}h1{font-size:42px;margin-bottom:0}section{background:white;border:1px solid #ddd9cd;border-radius:16px;padding:24px;margin:22px 0}a{display:inline-block;background:#253c36;color:white;padding:10px 22px;border-radius:7px;text-decoration:none}code{background:#f0f0e9;padding:4px 7px;word-break:break-all}.note{color:#62685e;font-size:15px}</style>
<main><h1>SCENT 已启动</h1><p>本地环境已配置完成。可以先浏览商城，再登录商家后台。</p>
<section><h2>买家端</h2><p>手机号：<code>$($config.buyerLogin)</code><br>密码：<code>$buyerPass</code></p><a href="$origin/" target="_blank">打开商城</a></section>
<section><h2>商家端</h2><p>管理员：<code>$($config.adminLogin)</code><br>密码：<code>$adminPass</code></p><a href="$origin/admin/" target="_blank">打开商家后台</a></section>
<p class="note">以上是本机自动创建的演示账号。再次启动会保留已有数据。结束后双击项目中的“stop.cmd”。</p>
<p class="note">开发示例包含 37 件商品。支付宝沙箱与 AI 顾问需要另行提供对应服务凭据，本地启动不会携带项目作者的私人密钥。</p></main></html>
"@
    [IO.File]::WriteAllText($welcome,$html,$utf8)
    Write-Json (Join-Path $instance 'ready.json') @{buyerUrl=$origin+'/';adminUrl=$origin+'/admin/';welcome=$welcome;sourceFingerprint=$fingerprint;checks='database, API health, both frontends, admin login, buyer login'}
    Write-Host "部署完成。买家端：$origin/"
    Write-Host "商家端：$origin/admin/"
    Write-Host "账号页面：$welcome"
    Write-Host "运行目录：$instance"
    if (-not $NoBrowser) { Start-Process -FilePath $welcome -WindowStyle Hidden | Out-Null }
} catch {
    Write-Host ('部署未完成：'+$_.Exception.Message) -ForegroundColor Red
    Write-Host '已下载的环境与已有数据库会保留。可检查网络和运行目录中的 data/logs 后重试。'
    exit 1
} finally { if ($null -ne $lock) { $lock.Dispose() } }
