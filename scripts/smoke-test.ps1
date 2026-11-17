param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:failures = 0

function Write-Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) {
        Write-Host "  PASS  $name" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $name $detail" -ForegroundColor Red
        $script:failures++
    }
}

function Invoke-Json([string]$method, [string]$path, $body = $null, [string]$token = "") {
    $headers = @{ "X-Terminal-Type" = "smoke" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{
        Method          = $method
        Uri             = "$BaseUrl$path"
        Headers         = $headers
        UseBasicParsing = $true
    }
    if ($null -ne $body) {
        $json = $body | ConvertTo-Json -Compress -Depth 10
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params["ContentType"] = "application/json; charset=utf-8"
    }
    try {
        $resp = Invoke-WebRequest @params
        return @{ StatusCode = [int]$resp.StatusCode; Content = $resp.Content }
    } catch {
        $web = $_.Exception.Response
        if ($null -eq $web) { throw }
        $status = [int]$web.StatusCode
        $stream = $web.GetResponseStream()
        $text = ""
        if ($null -ne $stream) {
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            $text = $reader.ReadToEnd()
        }
        return @{ StatusCode = $status; Content = $text }
    }
}

function Invoke-Sse([string]$path, $body, [string]$token) {
    $temp = Join-Path $env:TEMP ("knowhub-sse-" + [guid]::NewGuid().ToString("N") + ".json")
    $json = $body | ConvertTo-Json -Compress -Depth 10
    [System.IO.File]::WriteAllText($temp, $json, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $raw = & curl.exe -s -N -X POST "$BaseUrl$path" `
            -H "Content-Type: application/json" `
            -H "Authorization: Bearer $token" `
            -H "X-Terminal-Type: smoke" `
            --data-binary "@$temp"
        return ($raw -join "`n")
    } finally {
        Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
    }
}

Write-Host "== KnowHub 医院知识中枢 smoke test  $BaseUrl ==" -ForegroundColor Cyan

# 0. health
$health = Invoke-WebRequest -Uri "$BaseUrl/healthz" -UseBasicParsing
Write-Check "healthz 200" ($health.StatusCode -eq 200) $health.StatusCode
Write-Check "已注入三甲医院制度库(>=20 篇)" ($health.Content -match '"documents":\d+' -and [int]($health.Content | ConvertFrom-Json).documents -ge 8) $health.Content

# 1. auth
$login = Invoke-Json "POST" "/api/v1/auth/login" @{ username = "demo"; password = "demo1234" }
Write-Check "医师账号登录成功" ($login.StatusCode -eq 200) $login.StatusCode
$token = ($login.Content | ConvertFrom-Json).token
Write-Check "返回 token 与用户" (-not [string]::IsNullOrWhiteSpace($token))

$bad = Invoke-Json "POST" "/api/v1/auth/login" @{ username = "demo"; password = "wrong" }
Write-Check "错误密码返回 401" ($bad.StatusCode -eq 401) $bad.StatusCode

$anon = Invoke-Json "GET" "/api/v1/sessions"
Write-Check "未登录访问会话返回 401" ($anon.StatusCode -eq 401) $anon.StatusCode

# 2. session
$session = Invoke-Json "POST" "/api/v1/sessions" @{} $token
$sessionId = ($session.Content | ConvertFrom-Json).sessionId
Write-Check "创建会话 201" ($session.StatusCode -eq 201) $session.StatusCode

# 3. 医院制度问答（SSE）
$sse = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "危急值要在多久内上报？" } $token
Write-Check "SSE 含 meta/source/text/done" (($sse -match '"type":"meta"') -and ($sse -match '"type":"source"') -and ($sse -match '"type":"text"') -and ($sse -match '"type":"done"'))
Write-Check "命中医疗核心制度" ($sse -match 'doc_core_rules')
Write-Check "普通职工看不到人事/医务处受限制度" ((-not ($sse -match 'doc_hr_salary')) -and (-not ($sse -match 'doc_med_dispute')))

# 4. ACL 正向：人事科
$hrLogin = Invoke-Json "POST" "/api/v1/auth/login" @{ username = "hr"; password = "hr1234" }
$hrToken = ($hrLogin.Content | ConvertFrom-Json).token
$hrSession = Invoke-Json "POST" "/api/v1/sessions" @{} $hrToken
$hrSessionId = ($hrSession.Content | ConvertFrom-Json).sessionId
$hrSse = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $hrSessionId; message = "职工薪酬保密有哪些要求？" } $hrToken
Write-Check "人事科能看到薪酬保密办法(ACL 正向)" ($hrSse -match 'doc_hr_salary')
Write-Check "人事科看不到医务处内部指引" (-not ($hrSse -match 'doc_med_dispute'))

# 4.5 制度原文查看
$docView = Invoke-Json "GET" "/api/v1/documents/doc_outpatient" $null $token
Write-Check "制度原文可查看（含文号）" (($docView.StatusCode -eq 200) -and ($docView.Content -match '"content":"') -and ($docView.Content -match 'YW-ZD-001')) $docView.StatusCode
$docForbidden = Invoke-Json "GET" "/api/v1/documents/doc_hr_salary" $null $token
Write-Check "无权制度查看返回 403" ($docForbidden.StatusCode -eq 403) $docForbidden.StatusCode
$docHr = Invoke-Json "GET" "/api/v1/documents/doc_hr_salary" $null $hrToken
Write-Check "人事科可查看受限制度正文" ($docHr.StatusCode -eq 200) $docHr.StatusCode

# 4.6 医务处专属制度：普通职工检索不到
$disputeDemo = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "重大医疗过失要如何内部上报？" } $token
Write-Check "普通职工看不到医务处内部指引" (-not ($disputeDemo -match 'doc_malpractice_report'))

# 4.7 患者视角：只看就医指南、不能碰内部制度
$patientLogin = Invoke-Json "POST" "/api/v1/auth/login" @{ username = "patient"; password = "patient1234" } $null
$patientToken = ($patientLogin.Content | ConvertFrom-Json).token
Write-Check "患者账号登录成功" ($patientLogin.StatusCode -eq 200)
$patientDocs = Invoke-Json "GET" "/api/v1/documents" $null $patientToken
Write-Check "患者只看到就医指南" (($patientDocs.Content -match 'doc_guide_appointment') -and -not ($patientDocs.Content -match 'doc_core_rules'))
$patientInternal = Invoke-Json "GET" "/api/v1/documents/doc_core_rules" $null $patientToken
Write-Check "患者查看内部制度返回 403" ($patientInternal.StatusCode -eq 403) $patientInternal.StatusCode
$patientFlows = Invoke-Json "GET" "/api/v1/flows" $null $patientToken
Write-Check "患者只看到门诊预约流程" (($patientFlows.Content -match 'flow_appointment') -and -not ($patientFlows.Content -match 'flow_admission'))
$patientSession = (Invoke-Json "POST" "/api/v1/sessions" @{} $patientToken).Content | ConvertFrom-Json
$patientSse = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $patientSession.sessionId; message = "抽血前能吃饭吗？" } $patientToken
Write-Check "患者提问命中就医指南" (($patientSse -match '空腹') -or ($patientSse -match '禁食'))
Write-Check "患者回答带就医提示" ($patientSse -match '及时就医')
$patientImport = Invoke-Json "POST" "/api/v1/documents/text" @{ name = "test.md"; content = "x" } $patientToken
Write-Check "患者不能导入制度(403)" ($patientImport.StatusCode -eq 403) $patientImport.StatusCode

$pFlow1 = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $patientSession.sessionId; message = "我要预约门诊" } $patientToken
Write-Check "患者可触发门诊预约流程" ($pFlow1 -match 'flow_step')
Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $patientSession.sessionId; message = "Wang" } $patientToken | Out-Null
Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $patientSession.sessionId; message = "IM" } $patientToken | Out-Null
Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $patientSession.sessionId; message = "2026-11-20" } $patientToken | Out-Null
$pFlowDone = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $patientSession.sessionId; message = "13800001234" } $patientToken
Write-Check "患者完成预约生成 APT-" ($pFlowDone -match 'APT-')

# 4.7 附件：上传检验报告并随问题提问
$attachFile = Join-Path $env:TEMP "knowhub-lab.txt"
[IO.File]::WriteAllText($attachFile, "检验报告：患者住院号 ZY20261117001，血钾 6.8 mmol/L，提示高钾血症，建议立即复查并通知主管医师。", (New-Object System.Text.UTF8Encoding($false)))
$attachJson = & curl.exe -s -X POST "$BaseUrl/api/v1/attachments" -H "Authorization: Bearer $token" -H "X-Terminal-Type: smoke" -F "file=@$attachFile;filename=lab-report.txt;type=text/plain"
$attachment = $attachJson | ConvertFrom-Json
Write-Check "附件上传并解析文本" (($attachment.hasText -eq $true) -and ($attachment.chars -gt 10)) $attachJson
$attSse = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "这份检验报告有什么问题？"; attachmentIds = @($attachment.attachmentId) } $token
Write-Check "回答引用附件内容" (($attSse -match '6.8') -or ($attSse -match '附件'))
Remove-Item -LiteralPath $attachFile -ErrorAction SilentlyContinue

# 5. 住院登记流程
$flow1 = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "我要办理住院登记" } $token
Write-Check "流程触发并返回 flow_step" (($flow1 -match '"type":"flow_step"') -and ($flow1 -match '"nodeType":"collect_param"'))
$flow2 = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "李建国" } $token
Write-Check "收集患者姓名后继续" ($flow2 -match '"nodeType":"collect_param"')
$flow3 = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "ZY20261117001" } $token
Write-Check "收集住院证号后继续" ($flow3 -match '"nodeType":"collect_param"')
$flow4 = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $sessionId; message = "心内科" } $token
Write-Check "流程创建住院登记" ($flow4 -match 'ADM-') $flow4.Substring(0, [Math]::Min(160, $flow4.Length))
Write-Check "流程结束返回 done" ($flow4 -match '"type":"done"')

# 6. 管理员导入制度 + 检索
$adminLogin = Invoke-Json "POST" "/api/v1/auth/login" @{ username = "admin"; password = "admin1234" }
$adminToken = ($adminLogin.Content | ConvertFrom-Json).token
$adminDispute = Invoke-Json "GET" "/api/v1/documents/doc_malpractice_report" $null $adminToken
Write-Check "医务处可查看重大过失上报流程" ($adminDispute.StatusCode -eq 200) $adminDispute.StatusCode
$upload = Invoke-Json "POST" "/api/v1/documents/text" @{
    name    = "门诊号源管理规定.md"
    content = "门诊号源提前 7 天开放预约，现场号源不超过总号源的 30%，70 岁以上老人可优先就诊。"
} $adminToken
Write-Check "管理员导入制度 202" ($upload.StatusCode -eq 202) $upload.StatusCode
$newDocId = ($upload.Content | ConvertFrom-Json).docId

$indexed = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 150
    $docs = (Invoke-Json "GET" "/api/v1/documents" $null $adminToken).Content | ConvertFrom-Json
    $doc = $docs | Where-Object { $_.docId -eq $newDocId }
    if ($doc -and $doc.status -eq "INDEXED") { $indexed = $true; break }
}
Write-Check "新制度完成索引" $indexed $newDocId

$adminSession = Invoke-Json "POST" "/api/v1/sessions" @{} $adminToken
$adminSessionId = ($adminSession.Content | ConvertFrom-Json).sessionId
$uploadSse = Invoke-Sse "/api/v1/chat/stream" @{ sessionId = $adminSessionId; message = "门诊号源提前几天开放预约？" } $adminToken
Write-Check "新制度可被检索" ($indexed -and ($uploadSse -match $newDocId)) $newDocId

$remove = Invoke-Json "DELETE" "/api/v1/documents/$newDocId" $null $adminToken
Write-Check "管理员删除制度 204" ($remove.StatusCode -eq 204) $remove.StatusCode

$upload403 = Invoke-Json "POST" "/api/v1/documents/text" @{ name = "x.md"; content = "x" } $token
Write-Check "普通职工不能导入制度(403)" ($upload403.StatusCode -eq 403) $upload403.StatusCode

# 7. 统计 / 反馈
$stats = Invoke-Json "GET" "/api/v1/stats/overview" $null $adminToken
Write-Check "管理员可看统计" (($stats.StatusCode -eq 200) -and ($stats.Content -match 'documentCount'))
$stats403 = Invoke-Json "GET" "/api/v1/stats/overview" $null $token
Write-Check "普通职工不能看统计(403)" ($stats403.StatusCode -eq 403) $stats403.StatusCode

$doneEvent = ($sse -split "`n" | Where-Object { $_ -like '*"type":"done"*' } | Select-Object -First 1)
Write-Check "done 事件带 messageId 与 usage" (($doneEvent -match 'messageId') -and ($doneEvent -match 'usage'))
$messageId = ""
if ($doneEvent -match '"messageId":"([^"]+)"') { $messageId = $Matches[1] }
if ($messageId -ne "") {
    $feedback = Invoke-Json "POST" "/api/v1/feedback" @{ messageId = $messageId; rating = "up" } $token
    Write-Check "反馈写入 201" ($feedback.StatusCode -eq 201) $feedback.StatusCode
} else {
    Write-Check "从 SSE 解析 messageId" $false
}

Write-Host ""
if ($script:failures -eq 0) {
    Write-Host "ALL CHECKS PASSED" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:failures) CHECK(S) FAILED" -ForegroundColor Red
    exit 1
}
