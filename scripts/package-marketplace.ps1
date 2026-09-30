param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')][string]$Version = '1.0.0-rc.2',
    [string[]]$IdeSandboxPath = @()
)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
$beaconMaterials = Join-Path $beaconRoot 'docs/marketplace'
$beaconMedia = Join-Path $beaconMaterials 'media'
$beaconUtf8 = New-Object Text.UTF8Encoding($false)

function Resolve-Within([string]$Base, [string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or [IO.Path]::IsPathRooted($Relative)) { throw 'Expected a non-empty relative path.' }
    $beaconBasePath = [IO.Path]::GetFullPath($Base).TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
    $beaconResolved = [IO.Path]::GetFullPath((Join-Path $Base $Relative))
    if (-not $beaconResolved.StartsWith($beaconBasePath, [StringComparison]::OrdinalIgnoreCase)) { throw "Path leaves its allowed directory: $Relative" }
    return $beaconResolved
}
function Assert-File([string]$Path) {
    $beaconFile = Get-Item -LiteralPath $Path
    if ($beaconFile.PSIsContainer -or ($beaconFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Expected a regular material file: $Path" }
}
function Write-Utf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, $beaconUtf8)
}
function Copy-Material([string]$Source, [string]$Relative) {
    Assert-File $Source
    $beaconDestination = Resolve-Within $beaconBundle $Relative
    [IO.Directory]::CreateDirectory((Split-Path -Parent $beaconDestination)) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $beaconDestination
}

# Neither gate uploads files or grants permission to publish. Fail before creating
# a material directory if the exact package, screenshots or decisions are pending.
& (Join-Path $PSScriptRoot 'verify-marketplace.ps1') -Version $Version -Ready
$beaconReleaseArguments = @{ Version = $Version }
if ($IdeSandboxPath.Count -gt 0) { $beaconReleaseArguments.IdeSandboxPath = $IdeSandboxPath }
& (Join-Path $PSScriptRoot 'verify-release.ps1') @beaconReleaseArguments

$beaconMarketplaceReport = [IO.File]::ReadAllText((Join-Path $beaconRoot "build/reports/marketplace-checks-$Version-ready.json")) | ConvertFrom-Json
$beaconReleaseReport = [IO.File]::ReadAllText((Join-Path $beaconRoot "build/reports/release-checks-$Version.json")) | ConvertFrom-Json
if ($beaconMarketplaceReport.result -ne 'LOCAL_READY_GATE_PASS' -or $beaconMarketplaceReport.version -ne $Version -or $beaconReleaseReport.version -ne $Version) { throw 'Current preflight reports do not match the requested version.' }
if ($beaconMarketplaceReport.packageSha256 -cne $beaconReleaseReport.packageSha256 -or $beaconMarketplaceReport.packagedJarSha256 -cne $beaconReleaseReport.packagedJarSha256) { throw 'The package changed between material and release verification.' }

$beaconPlugin = Join-Path $beaconRoot "build/distributions/jvm-beacon-$Version.zip"
$beaconManifest = [IO.File]::ReadAllText((Join-Path $beaconMedia 'manifest.json')) | ConvertFrom-Json
$beaconCopies = [Collections.Generic.List[object]]::new()
$beaconCopies.Add(@{ source = $beaconPlugin; destination = "plugin/jvm-beacon-$Version.zip" })
foreach ($beaconCopy in @('listing.html', 'getting-started.html', 'release-notes.html', 'FAQ.html', 'FAQ.md', 'EULA.html', 'EULA.md', 'privacy-policy.html')) {
    $beaconCopies.Add(@{ source = (Join-Path $beaconMaterials $beaconCopy); destination = "listing/$beaconCopy" })
}
foreach ($beaconCopy in @('verification.md', 'publishing-decisions.json')) {
    $beaconCopies.Add(@{ source = (Join-Path $beaconMaterials $beaconCopy); destination = "handoff/$beaconCopy" })
}
foreach ($beaconCopy in @('jvm-beacon.svg', 'jvm-beacon-dark.svg', 'jvm-beacon.png', 'jvm-beacon-dark.png', 'preview.html')) {
    $beaconCopies.Add(@{ source = (Join-Path $beaconMaterials "brand/$beaconCopy"); destination = "brand/$beaconCopy" })
}
foreach ($beaconCopy in @('pluginIcon.svg', 'pluginIcon_dark.svg')) {
    $beaconCopies.Add(@{ source = (Join-Path $beaconRoot "src/main/resources/META-INF/$beaconCopy"); destination = "brand/$beaconCopy" })
}
$beaconCopies.Add(@{ source = (Join-Path $beaconMedia 'README.md'); destination = 'media/README.md' })
$beaconScreenshotSummary = [Collections.Generic.List[object]]::new()
$beaconDestinations = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($beaconScreen in $beaconManifest.screenshots) {
    $beaconImageSource = Resolve-Within $beaconMedia $beaconScreen.file
    if ([IO.Path]::GetExtension($beaconImageSource).ToLowerInvariant() -notin @('.png', '.jpg', '.jpeg')) { throw 'Only verified PNG/JPEG listing images enter the handoff archive.' }
    $beaconImageRelative = 'media/' + $beaconScreen.file.Replace('\', '/')
    $beaconCopies.Add(@{ source = $beaconImageSource; destination = $beaconImageRelative })
    $beaconScreenshotSummary.Add([ordered]@{
        file = $beaconImageRelative; width = $beaconScreen.width; height = $beaconScreen.height
        sha256 = $beaconScreen.sha256; caption = $beaconScreen.caption
    })
}
foreach ($beaconCopy in $beaconCopies) {
    Assert-File $beaconCopy.source
    if (-not $beaconDestinations.Add($beaconCopy.destination)) { throw "Duplicate material destination: $($beaconCopy.destination)" }
}

$beaconStamp = (Get-Date).ToString('yyyyMMdd-HHmmss-fff')
$beaconBundleName = "marketplace-$Version-$beaconStamp"
$beaconDistribution = Join-Path $beaconRoot 'build/distributions'
$beaconBundle = Join-Path $beaconDistribution $beaconBundleName
$beaconArchive = "$beaconBundle.zip"
if ((Test-Path -LiteralPath $beaconBundle) -or (Test-Path -LiteralPath $beaconArchive)) { throw 'Material directory/archive already exists; existing output is never overwritten.' }
[IO.Directory]::CreateDirectory($beaconBundle) | Out-Null
foreach ($beaconCopy in $beaconCopies) { Copy-Material $beaconCopy.source $beaconCopy.destination }
if ((Get-FileHash -LiteralPath (Join-Path $beaconBundle "plugin/jvm-beacon-$Version.zip") -Algorithm SHA256).Hash.ToLowerInvariant() -cne $beaconReleaseReport.packageSha256) { throw 'Copied installation ZIP differs from the verified package.' }
foreach ($beaconScreen in $beaconScreenshotSummary) {
    if ((Get-FileHash -LiteralPath (Resolve-Within $beaconBundle $beaconScreen.file) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $beaconScreen.sha256) { throw "Copied screenshot differs from the verified image: $($beaconScreen.file)" }
}

# Public image metadata deliberately omits original build paths and raw-capture
# references. Full provenance remains in the private checkout for local review.
$beaconPublicManifest = [ordered]@{
    schemaVersion = 1; version = $Version; packageSha256 = $beaconReleaseReport.packageSha256
    screenshots = @($beaconScreenshotSummary.ToArray())
    note = 'Listing images only. Original private capture evidence is not distributed in this material bundle. This file is not the source-provenance manifest used by verify-marketplace.ps1.'
}
Write-Utf8 (Join-Path $beaconBundle 'media/screenshots.json') ($beaconPublicManifest | ConvertTo-Json -Depth 6)
# The source media README can link to private build captures or GUI evidence.
# Keep uploaded images clickable, but turn any absent local provenance target
# into an explicit source-only reference rather than a broken handoff link.
$beaconMediaReadmePath = Join-Path $beaconBundle 'media/README.md'
$beaconMediaReadme = [IO.File]::ReadAllText($beaconMediaReadmePath)
$beaconMediaReadme = [regex]::Replace($beaconMediaReadme, '(?<!!)\[(?<label>[^\]]+)\]\((?<target><[^>]+>|[^\s)]+)(?:\s+"[^"]*")?\)', {
    param($beaconMatch)
    $beaconLabel = $beaconMatch.Groups['label'].Value
    $beaconTarget = $beaconMatch.Groups['target'].Value.Trim([char[]]'<>')
    if ($beaconTarget -match '^(?i)(https?://|mailto:|#)') { return $beaconMatch.Value }
    $beaconTargetFile = ($beaconTarget -split '[?#]', 2)[0]
    if ($beaconTargetFile -eq 'manifest.json') { return "[$beaconLabel](screenshots.json)" }
    try {
        $beaconLinkFile = Resolve-Within $beaconBundle ('media/' + $beaconTargetFile)
        if (Test-Path -LiteralPath $beaconLinkFile -PathType Leaf) { return $beaconMatch.Value }
    } catch { }
    return "$beaconLabel (private-workspace provenance only: ``$beaconTarget``; not included in this bundle)"
})
Write-Utf8 $beaconMediaReadmePath (@'
> Handoff scope: this folder contains the selected upload images and `screenshots.json`.
> The source `manifest.json`, original raw captures, diagnostic files, build logs and
> private GUI evidence are not included. References to that private provenance below
> are source-workspace references only, not files carried by this archive. The public
> screenshot index omits private source paths and is not the ready-gate manifest.

'@ + $beaconMediaReadme)
$beaconPreflight = [ordered]@{
    version = $Version; preparedAt = (Get-Date).ToString('o')
    packageSha256 = $beaconReleaseReport.packageSha256; packagedJarSha256 = $beaconReleaseReport.packagedJarSha256
    materialGate = $beaconMarketplaceReport.result; tests = $beaconReleaseReport.tests; verifier = $beaconReleaseReport.verifier
    checkedClosedIdeSessions = @($beaconReleaseReport.ideSessions).Count
    note = 'Local automated preflight summary. GUI acceptance is documented separately. No upload, account readiness or Marketplace approval is implied.'
}
Write-Utf8 (Join-Path $beaconBundle 'handoff/preflight-summary.json') ($beaconPreflight | ConvertTo-Json -Depth 6)
Write-Utf8 (Join-Path $beaconBundle 'handoff/README.md') @"
# JVM Beacon $Version 发布交付

本文件为解压后的独立材料包编写，所有下表入口指向包内文件。名称使用 **JVM Beacon**，描述第一句为 **JMX & JFR diagnostics in IntelliJ IDEA.**。本地核对通过表示材料齐备，不表示商店批准、正式稳定版、账号就绪或上传授权；用户自行审阅并上传。

| 内容 | 包内入口 |
|---|---|
| 可安装插件、唯一用于 Upload Plugin 的文件 | [jvm-beacon-$Version.zip](../plugin/jvm-beacon-$Version.zip) |
| 英文 Overview 描述 | [listing.html](../listing/listing.html) |
| Getting Started | [getting-started.html](../listing/getting-started.html) |
| What's New | [release-notes.html](../listing/release-notes.html) |
| FAQ | [HTML](../listing/FAQ.html) · [Markdown 源](../listing/FAQ.md) |
| 免费闭源使用许可 | [EULA HTML](../listing/EULA.html) · [Markdown 源](../listing/EULA.md) |
| 隐私说明 | [privacy-policy.html](../listing/privacy-policy.html) |
| 原创图标说明与预览 | [README](../brand/README.txt) · [浅/深主题预览](../brand/preview.html) |
| 真实截图与英文说明 | [截图说明](../media/README.md) · [上传顺序、尺寸与校验值](../media/screenshots.json) |
| 本地自动检查摘要 | [preflight-summary.json](preflight-summary.json) |
| 本地验证方法与边界 | [verification.md](verification.md) |
| 发布者、许可及分发材料决定 | [publishing-decisions.json](publishing-decisions.json) |
| 全部材料文件 SHA-256 | [SHA256SUMS.txt](../SHA256SUMS.txt) |

## 用户手动提交步骤

1. 解压并审核文案、真实截图、EULA、隐私说明及候选版本支持范围。候选包不能冒称稳定版；名称/plugin ID 保留与商标核查仍须在首次提交时核对。
2. 登录自己的 Marketplace 账号，选择已决定使用的 Vendor，并在 Vendor settings 核对有效联系资料、账号角色和 trader/non-trader 声明。现有 [PhilZ Dev Vendor](https://plugins.jetbrains.com/vendor/philz_dev) 是用户提供的公开入口，决定记录不证明账号权限或邮箱已配置。Developer Agreement 由用户阅读并自行决定是否接受。
3. 在 Upload Plugin 选择上表内层安装 ZIP。外层 marketplace 材料 ZIP 不是插件安装包。名称保持 JVM Beacon，价格与免费闭源 EULA 按决定记录填写；源码保持私有，Source Code URL 留空，不用私有仓库作为公开帮助入口。
4. 填写 Overview / Getting Started / What's New 与许可；FAQ 和 EULA 同时提供 HTML 片段及可读源。按账号当前编辑器提供的模式选择 HTML 或纯文本，并检查页面预览，不假设所有 Custom Page 字段都支持同一格式。
5. 从实际可选标签中选择确实相关的 Java、Monitoring、Profiling 或 JVM/JMX 分类。按 screenshots.json 顺序上传 Media 图像及英文说明；隐私声明和 FAQ 按当前支持的 Custom Page/资料位置填写。只上传精选截图，不上传生产诊断文件或原始私有采集记录。
6. 对候选版本可使用 EAP 自定义通道或 Hidden 状态进行小范围验证。最后由用户决定是否提交公开并完成审核；本包准备和脚本运行不代替此决定。
7. 商店审核通过后，再检查实际图标、卡片、截图、版本、安装和支持入口。审核失败时处理真实问题，不把本地 ready 摘要当作批准证据。

## 文件与证据范围

Logo 已在插件内 META-INF/pluginIcon.svg，Marketplace 从安装包自动提取；brand/ 的 512 PNG 是原创品牌预览，不是产品截图，也不代替安装 SVG。

此包不含私有源码仓库、凭据、录制、现场、原始未公开截图或 build 日志。完整截图来源与 GUI 记录留在原工作区；media/screenshots.json 只列选定上传图像。media/README.md 中标注为 private-workspace provenance 的条目不是随包分发文件。克隆源码后重跑来源核对需要真实匹配证据；材料包不伪造缺失原图。

publishing-decisions.json 是已记录的材料决定，preflight-summary.json 是本地自动核对结果；二者都不证明账户状态、法律充分性、GUI 全覆盖或 Marketplace 批准。verification.md 描述源工作区检查方法；本材料包没有携带构建源码和脚本。
"@
Write-Utf8 (Join-Path $beaconBundle 'brand/README.txt') @'
JVM Beacon original brand assets

pluginIcon.svg and pluginIcon_dark.svg are the exact packaged 40 x 40 logos.
Marketplace and the IDE obtain the logo from META-INF/pluginIcon.svg in the
installation package; a separate PNG logo upload is not required by this workflow.
jvm-beacon.svg and jvm-beacon-dark.svg are equivalent 512-pixel vector masters.
jvm-beacon.png and jvm-beacon-dark.png are optional 512 x 512 transparent raster
previews rendered from those same original shapes. They are branding, not product
screenshots. preview.html shows the SVG at actual listing and larger scales.
No fonts, external images, scripts, filters or third-party brand marks are used.
'@
Write-Utf8 (Join-Path $beaconBundle 'READ-ME-FIRST.txt') @"
JVM Beacon $Version - local Marketplace handoff materials

Upload only plugin/jvm-beacon-$Version.zip as the plugin distribution. The outer
$beaconBundleName.zip is a preparation bundle and is NOT an installable plugin.
Use the remaining files separately for the Marketplace listing and owner review:
listing/ contains English copy, the EULA and privacy disclosure; media/ contains
real verified listing images with captions; brand/ contains original logo assets;
handoff/ contains the upload checklist, decision record and local preflight summary.
Open handoff/README.md for clickable entries that work inside this extracted bundle.

The private source checkout, credentials, recordings, snapshots, raw capture
evidence and build logs are not included. Only the verified installation ZIP is
copied from build output. All other files are explicitly selected listing assets.
Full screenshot provenance remains in the original private checkout; the public
media/screenshots.json intentionally omits source paths. Reproducing a fresh
local ready check after cloning requires new package/tests/Verifier outputs and
matching GUI captures; this handoff bundle does not fabricate missing evidence.

SHA256SUMS.txt lists all prepared files except itself. The outer archive has a
separate .sha256 file beside it. Hashes document file identity, not legal approval.
Preparing this bundle does not upload anything or authorize publication. The
recorded publishing decisions are material decisions, not proof of account status.
"@

$beaconHashLines = @()
foreach ($beaconFile in Get-ChildItem -LiteralPath $beaconBundle -File -Recurse | Sort-Object FullName) {
    $beaconRelative = $beaconFile.FullName.Substring($beaconBundle.Length + 1).Replace('\', '/')
    $beaconHashLines += '{0}  {1}' -f (Get-FileHash -LiteralPath $beaconFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $beaconRelative
}
Write-Utf8 (Join-Path $beaconBundle 'SHA256SUMS.txt') (($beaconHashLines -join "`n") + "`n")
Compress-Archive -LiteralPath $beaconBundle -DestinationPath $beaconArchive -CompressionLevel Optimal
$beaconArchiveHash = (Get-FileHash -LiteralPath $beaconArchive -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Utf8 "$beaconArchive.sha256" ("$beaconArchiveHash  $beaconBundleName.zip`n")
Write-Host "MARKETPLACE_HANDOFF_PACKAGE_PASS: $beaconArchive"
Write-Host "SHA256: $beaconArchiveHash"
