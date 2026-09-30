param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')][string]$Version = '1.0.0-rc.2',
    [switch]$Ready
)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
$beaconMedia = Join-Path $beaconRoot 'docs/marketplace/media'
$beaconChecks = [Collections.Generic.List[object]]::new()
$beaconPending = [Collections.Generic.List[string]]::new()
$beaconPackageHash = $null
$beaconJarHash = $null
$beaconEulaHash = $null
$beaconPrivateSourceUrl = '(?i)https?://(?:(?:www\.)?github\.com|raw\.githubusercontent\.com)/rainism0329/JVM-Beacon(?:[/#?\s"''<]|$)'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.Drawing

function Add-Check([string]$Name, [string]$Status, [string]$Detail) {
    $beaconChecks.Add([ordered]@{ name = $Name; status = $Status; detail = $Detail })
}
function Resolve-Within([string]$Base, [string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or [IO.Path]::IsPathRooted($Relative)) { throw 'Expected a non-empty relative path.' }
    $beaconBasePath = [IO.Path]::GetFullPath($Base).TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
    $beaconResolved = [IO.Path]::GetFullPath((Join-Path $Base $Relative))
    if (-not $beaconResolved.StartsWith($beaconBasePath, [StringComparison]::OrdinalIgnoreCase)) { throw "Path leaves its allowed directory: $Relative" }
    return $beaconResolved
}
function Get-StreamHash([IO.Stream]$Stream) {
    $beaconHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($beaconHasher.ComputeHash($Stream)).Replace('-', '').ToLowerInvariant() }
    finally { $beaconHasher.Dispose() }
}
function Get-FileSha([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert-Sha([string]$Value, [string]$Label) {
    if ($Value -cnotmatch '^[a-f0-9]{64}$') { throw "Missing or invalid SHA-256: $Label" }
}
function Read-SafeXml([string]$Text) {
    $beaconSettings = [Xml.XmlReaderSettings]::new()
    $beaconSettings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $beaconSettings.XmlResolver = $null
    $beaconReader = [Xml.XmlReader]::Create([IO.StringReader]::new($Text), $beaconSettings)
    try {
        $beaconDocument = [Xml.XmlDocument]::new()
        $beaconDocument.XmlResolver = $null
        $beaconDocument.Load($beaconReader)
        return ,$beaconDocument
    } finally { $beaconReader.Dispose() }
}
function Get-Number([string]$Value) {
    if ($Value -notmatch '^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$') { throw "Unsupported SVG number: $Value" }
    $beaconNumber = [double]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture)
    if ([double]::IsInfinity($beaconNumber) -or [double]::IsNaN($beaconNumber)) { throw 'Non-finite SVG coordinate.' }
    return $beaconNumber
}
function Get-Inherited([Xml.XmlElement]$Element, [string]$Name, [string]$Default) {
    $beaconNode = $Element
    while ($beaconNode -is [Xml.XmlElement]) {
        if ($beaconNode.HasAttribute($Name)) { return $beaconNode.GetAttribute($Name) }
        $beaconNode = $beaconNode.ParentNode
    }
    return $Default
}
function Get-Coordinates([string]$Text) {
    $beaconTokens = [regex]::Matches($Text, '[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?')
    $beaconRemainder = [regex]::Replace($Text, '[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?', '')
    if ($beaconRemainder -match '[^\s,]') { throw 'Unsupported SVG coordinate syntax.' }
    return @($beaconTokens | ForEach-Object { Get-Number $_.Value })
}
function Get-PathPoints([string]$Data) {
    # Bezier control-point hulls conservatively contain their curves. Arc paths are
    # deliberately unsupported; use circle/ellipse or a reviewed Bezier equivalent.
    $beaconPattern = '[A-Za-z]|[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?'
    $beaconTokens = @([regex]::Matches($Data, $beaconPattern) | ForEach-Object { $_.Value })
    if ([regex]::Replace($Data, $beaconPattern, '') -match '[^\s,]') { throw 'Unsupported SVG path syntax.' }
    $beaconPoints = [Collections.Generic.List[double]]::new()
    $beaconIndex = 0; $beaconCommand = ''; $beaconPrevious = ''
    $beaconX = 0.0; $beaconY = 0.0; $beaconStartX = 0.0; $beaconStartY = 0.0
    $beaconControlX = 0.0; $beaconControlY = 0.0
    while ($beaconIndex -lt $beaconTokens.Count) {
        if ($beaconTokens[$beaconIndex] -cmatch '^[A-Za-z]$') {
            $beaconCommand = $beaconTokens[$beaconIndex]; $beaconIndex++
            if ($beaconCommand -notmatch '^[MmLlHhVvCcSsQqTtZz]$') { throw "Unsupported SVG path command: $beaconCommand" }
            if ($beaconPoints.Count -eq 0 -and $beaconCommand -notmatch '^[Mm]$') { throw 'SVG path must start with moveto.' }
            if ($beaconCommand -match '^[Zz]$') {
                $beaconX = $beaconStartX; $beaconY = $beaconStartY
                $beaconPoints.Add($beaconX); $beaconPoints.Add($beaconY)
                $beaconPrevious = 'Z'; $beaconCommand = ''; continue
            }
        }
        if ($beaconCommand -eq '') { throw 'Missing SVG path command.' }
        $beaconUpper = $beaconCommand.ToUpperInvariant()
        $beaconCount = switch ($beaconUpper) { 'H' {1} 'V' {1} 'C' {6} 'S' {4} 'Q' {4} default {2} }
        if ($beaconIndex + $beaconCount -gt $beaconTokens.Count) { throw 'Incomplete SVG path command.' }
        $beaconArgs = @()
        for ($beaconArg = 0; $beaconArg -lt $beaconCount; $beaconArg++) { $beaconArgs += Get-Number $beaconTokens[$beaconIndex + $beaconArg] }
        $beaconIndex += $beaconCount
        $beaconRelative = $beaconCommand -cmatch '^[a-z]$'
        if ($beaconUpper -eq 'H') {
            $beaconX = $beaconArgs[0] + $(if ($beaconRelative) { $beaconX } else { 0 })
            $beaconPoints.Add($beaconX); $beaconPoints.Add($beaconY)
        } elseif ($beaconUpper -eq 'V') {
            $beaconY = $beaconArgs[0] + $(if ($beaconRelative) { $beaconY } else { 0 })
            $beaconPoints.Add($beaconX); $beaconPoints.Add($beaconY)
        } else {
            if (($beaconUpper -eq 'S' -and $beaconPrevious -in @('C', 'S')) -or ($beaconUpper -eq 'T' -and $beaconPrevious -in @('Q', 'T'))) {
                $beaconReflectedX = 2 * $beaconX - $beaconControlX; $beaconReflectedY = 2 * $beaconY - $beaconControlY
                $beaconPoints.Add($beaconReflectedX); $beaconPoints.Add($beaconReflectedY)
            } else { $beaconReflectedX = $beaconX; $beaconReflectedY = $beaconY }
            for ($beaconArg = 0; $beaconArg -lt $beaconCount; $beaconArg += 2) {
                if ($beaconRelative) { $beaconArgs[$beaconArg] += $beaconX; $beaconArgs[$beaconArg + 1] += $beaconY }
                $beaconPoints.Add($beaconArgs[$beaconArg]); $beaconPoints.Add($beaconArgs[$beaconArg + 1])
            }
            if ($beaconUpper -in @('C', 'S', 'Q')) { $beaconControlX = $beaconArgs[$beaconCount - 4]; $beaconControlY = $beaconArgs[$beaconCount - 3] }
            elseif ($beaconUpper -eq 'T') { $beaconControlX = $beaconReflectedX; $beaconControlY = $beaconReflectedY }
            $beaconX = $beaconArgs[$beaconCount - 2]; $beaconY = $beaconArgs[$beaconCount - 1]
            if ($beaconUpper -eq 'M') {
                $beaconStartX = $beaconX; $beaconStartY = $beaconY
                $beaconCommand = $(if ($beaconRelative) { 'l' } else { 'L' })
            }
        }
        $beaconPrevious = $beaconUpper
    }
    if ($beaconPoints.Count -lt 2) { throw 'Empty SVG path.' }
    return $beaconPoints.ToArray()
}
function Assert-Logo([byte[]]$Bytes, [string]$Name) {
    if ($Bytes.Length -ge 3072) { throw "$Name must be smaller than 3 KiB." }
    $beaconSvg = Read-SafeXml ([Text.Encoding]::UTF8.GetString($Bytes))
    $beaconRootSvg = $beaconSvg.DocumentElement
    if ($beaconRootSvg.LocalName -ne 'svg' -or $beaconRootSvg.NamespaceURI -ne 'http://www.w3.org/2000/svg') { throw "$Name is not an SVG document." }
    if ($beaconRootSvg.GetAttribute('width') -notmatch '^40(?:px)?$' -or $beaconRootSvg.GetAttribute('height') -notmatch '^40(?:px)?$') { throw "$Name must declare 40 x 40 dimensions." }
    $beaconView = @(Get-Coordinates $beaconRootSvg.GetAttribute('viewBox'))
    if ($beaconView.Count -ne 4 -or $beaconView[0] -ne 0 -or $beaconView[1] -ne 0 -or $beaconView[2] -ne 40 -or $beaconView[3] -ne 40) { throw "$Name must use viewBox 0 0 40 40." }
    $beaconShapes = 0
    foreach ($beaconElement in $beaconSvg.SelectNodes('//*')) {
        if ($beaconElement.NamespaceURI -ne 'http://www.w3.org/2000/svg') { throw "$Name contains an unsupported XML namespace." }
        if ($beaconElement.LocalName -notin @('svg', 'g', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'title', 'desc')) { throw "$Name uses an unsupported SVG element: $($beaconElement.LocalName)" }
        if ($beaconElement.LocalName -eq 'svg' -and $beaconElement -ne $beaconRootSvg) { throw 'Nested SVG viewports are not covered by this static check.' }
        foreach ($beaconAttribute in $beaconElement.Attributes) {
            if ($beaconAttribute.LocalName -match '^(on.+|href|src|style|transform|filter|mask|clip-path|marker.*|vector-effect)$' -or $beaconAttribute.Value -match '(?i)url\s*\(') { throw "$Name uses unsupported behavior or resources: $($beaconAttribute.Name)" }
        }
        $beaconKind = $beaconElement.LocalName
        if ($beaconKind -in @('svg', 'g', 'title', 'desc')) { continue }
        $beaconShapes++
        $beaconCoordinates = @()
        switch ($beaconKind) {
            'path' { $beaconCoordinates = @(Get-PathPoints $beaconElement.GetAttribute('d')) }
            { $_ -in @('polygon', 'polyline') } { $beaconCoordinates = @(Get-Coordinates $beaconElement.GetAttribute('points')) }
            'line' { foreach ($beaconAttr in @('x1', 'y1', 'x2', 'y2')) { $beaconCoordinates += Get-Number $beaconElement.GetAttribute($beaconAttr) } }
            'rect' {
                $beaconX = Get-Number $beaconElement.GetAttribute('x'); $beaconY = Get-Number $beaconElement.GetAttribute('y')
                $beaconWidth = Get-Number $beaconElement.GetAttribute('width'); $beaconHeight = Get-Number $beaconElement.GetAttribute('height')
                if ($beaconWidth -le 0 -or $beaconHeight -le 0) { throw 'SVG rectangle must have positive dimensions.' }
                $beaconCoordinates = @($beaconX, $beaconY, ($beaconX + $beaconWidth), ($beaconY + $beaconHeight))
            }
            { $_ -in @('circle', 'ellipse') } {
                $beaconX = Get-Number $beaconElement.GetAttribute('cx'); $beaconY = Get-Number $beaconElement.GetAttribute('cy')
                if ($beaconKind -eq 'circle') { $beaconRx = Get-Number $beaconElement.GetAttribute('r'); $beaconRy = $beaconRx }
                else { $beaconRx = Get-Number $beaconElement.GetAttribute('rx'); $beaconRy = Get-Number $beaconElement.GetAttribute('ry') }
                if ($beaconRx -le 0 -or $beaconRy -le 0) { throw 'SVG radius must be positive.' }
                $beaconCoordinates = @(($beaconX - $beaconRx), ($beaconY - $beaconRy), ($beaconX + $beaconRx), ($beaconY + $beaconRy))
            }
        }
        if ($beaconCoordinates.Count -lt 2 -or $beaconCoordinates.Count % 2 -ne 0) { throw 'Invalid SVG point pairs.' }
        $beaconStroke = Get-Inherited $beaconElement 'stroke' 'none'
        $beaconHalfStroke = 0.0
        if ($beaconStroke -ne 'none') {
            $beaconHalfStroke = (Get-Number (Get-Inherited $beaconElement 'stroke-width' '1')) / 2
            if ($beaconHalfStroke -lt 0) { throw 'Negative SVG stroke width.' }
            if ($beaconKind -in @('path', 'polygon', 'polyline') -and (Get-Inherited $beaconElement 'stroke-linejoin' 'miter') -notin @('round', 'bevel')) { throw 'Stroked paths must use round or bevel joins for conservative padding validation.' }
            if ((Get-Inherited $beaconElement 'stroke-linecap' 'butt') -eq 'square') { $beaconHalfStroke *= [Math]::Sqrt(2) }
        }
        foreach ($beaconCoordinate in $beaconCoordinates) {
            if ($beaconCoordinate - $beaconHalfStroke -lt 2 -or $beaconCoordinate + $beaconHalfStroke -gt 38) { throw "$Name artwork/control hull and stroke leave the 2 px safe padding." }
        }
    }
    if ($beaconShapes -eq 0) { throw "$Name has no artwork." }
}
function Get-ImageDimensions([string]$Path) {
    $beaconImage = [Drawing.Image]::FromFile($Path)
    try { return [pscustomobject]@{ width = $beaconImage.Width; height = $beaconImage.Height } }
    finally { $beaconImage.Dispose() }
}

try {
    $beaconPackagePath = Join-Path $beaconRoot "build/distributions/jvm-beacon-$Version.zip"
    $beaconPackageHash = Get-FileSha $beaconPackagePath
    $beaconZip = [IO.Compression.ZipFile]::OpenRead($beaconPackagePath)
    try {
        $beaconJars = @($beaconZip.Entries | Where-Object { $_.FullName.EndsWith('.jar') })
        if ($beaconJars.Count -ne 1 -or $beaconJars[0].FullName -ne "jvm-beacon/lib/jvm-beacon-$Version.jar") { throw 'Expected exactly the versioned plugin JAR in the distribution.' }
        $beaconMemory = [IO.MemoryStream]::new(); $beaconInput = $beaconJars[0].Open()
        try { $beaconInput.CopyTo($beaconMemory) } finally { $beaconInput.Dispose() }
        try {
            $beaconMemory.Position = 0; $beaconJarHash = Get-StreamHash $beaconMemory; $beaconMemory.Position = 0
            $beaconJar = [IO.Compression.ZipArchive]::new($beaconMemory, [IO.Compression.ZipArchiveMode]::Read, $true)
            try {
                $beaconDescriptorEntry = $beaconJar.GetEntry('META-INF/plugin.xml')
                if ($null -eq $beaconDescriptorEntry) { throw 'Packaged plugin.xml is missing.' }
                $beaconReader = [IO.StreamReader]::new($beaconDescriptorEntry.Open())
                try { $beaconDescriptor = Read-SafeXml $beaconReader.ReadToEnd() } finally { $beaconReader.Dispose() }
                if ($beaconDescriptor.'idea-plugin'.id -ne 'dev.jvmbeacon' -or $beaconDescriptor.'idea-plugin'.version -ne $Version) { throw 'Packaged plugin ID/version mismatch.' }
                $beaconLicenseEntry = $beaconJar.GetEntry('META-INF/LICENSE.txt')
                if ($null -eq $beaconLicenseEntry) { throw 'Packaged META-INF/LICENSE.txt is missing.' }
                $beaconLicenseBuffer = [IO.MemoryStream]::new(); $beaconLicenseStream = $beaconLicenseEntry.Open()
                try { $beaconLicenseStream.CopyTo($beaconLicenseBuffer) } finally { $beaconLicenseStream.Dispose() }
                try {
                    $beaconLicenseBytes = $beaconLicenseBuffer.ToArray()
                    $beaconLicenseBuffer.Position = 0; $beaconEulaHash = Get-StreamHash $beaconLicenseBuffer
                } finally { $beaconLicenseBuffer.Dispose() }
                $beaconEulaPath = Join-Path $beaconRoot 'docs/marketplace/EULA.md'
                if ([Convert]::ToBase64String($beaconLicenseBytes) -cne [Convert]::ToBase64String([IO.File]::ReadAllBytes($beaconEulaPath))) { throw 'Packaged license differs byte-for-byte from docs/marketplace/EULA.md. Rebuild after a license change.' }
                $beaconPackageDecisionPath = Join-Path $beaconRoot 'docs/marketplace/publishing-decisions.json'
                if (Test-Path -LiteralPath $beaconPackageDecisionPath -PathType Leaf) {
                    $beaconPackageDecisions = [IO.File]::ReadAllText($beaconPackageDecisionPath) | ConvertFrom-Json
                    if ($beaconPackageDecisions.publisher.status -eq 'decided') {
                        $beaconVendor = $beaconDescriptor.SelectSingleNode('/idea-plugin/vendor')
                        if ($null -eq $beaconVendor -or $beaconVendor.InnerText.Trim() -cne $beaconPackageDecisions.publisher.name -or $beaconVendor.GetAttribute('url') -cne $beaconPackageDecisions.publisher.vendorProfile) { throw 'Packaged vendor name/URL differs from publishing-decisions.json.' }
                    } else { $beaconPending.Add('Packaged vendor cannot be compared until publisher identity/profile is decided.') }
                    if ($beaconPackageDecisions.license.status -eq 'decided') {
                        $beaconChosenLicensePath = Resolve-Within $beaconRoot $beaconPackageDecisions.license.file
                        if ([IO.Path]::GetFullPath($beaconChosenLicensePath) -ine [IO.Path]::GetFullPath($beaconEulaPath)) { throw 'The decided license file must match the EULA packaged by this release.' }
                    }
                    if ($beaconPackageDecisions.license.kind -eq 'free-closed-source' -and $beaconDescriptor.OuterXml -match $beaconPrivateSourceUrl) { throw 'Closed-source descriptor exposes a private source-repository URL.' }
                } else { $beaconPending.Add('Packaged vendor/license cannot be compared until publishing decisions are recorded.') }
                foreach ($beaconName in @('pluginIcon.svg', 'pluginIcon_dark.svg')) {
                    $beaconEntry = $beaconJar.GetEntry("META-INF/$beaconName")
                    if ($null -eq $beaconEntry) { throw "Packaged Marketplace logo missing: $beaconName" }
                    $beaconLogoBuffer = [IO.MemoryStream]::new(); $beaconLogoStream = $beaconEntry.Open()
                    try { $beaconLogoStream.CopyTo($beaconLogoBuffer) } finally { $beaconLogoStream.Dispose() }
                    try { $beaconBytes = $beaconLogoBuffer.ToArray() } finally { $beaconLogoBuffer.Dispose() }
                    Assert-Logo $beaconBytes $beaconName
                    $beaconSourceBytes = [IO.File]::ReadAllBytes((Join-Path $beaconRoot "src/main/resources/META-INF/$beaconName"))
                    if ([Convert]::ToBase64String($beaconBytes) -cne [Convert]::ToBase64String($beaconSourceBytes)) { throw "Packaged logo differs from source: $beaconName" }
                }
            } finally { $beaconJar.Dispose() }
        } finally { $beaconMemory.Dispose() }
    } finally { $beaconZip.Dispose() }
    Add-Check 'package-and-logos' 'PASS' 'ZIP/JAR contains exact source SVGs (40 x 40, below 3 KiB, no external resources, conservative 2 px padding) and byte-identical EULA; recorded vendor identity/profile matches the descriptor when decided.'
} catch { Add-Check 'package-and-logos' 'FAIL' $_.Exception.Message }

foreach ($beaconText in @('listing.html', 'getting-started.html', 'release-notes.html', 'FAQ.md')) {
    try {
        $beaconBody = [IO.File]::ReadAllText((Join-Path $beaconRoot "docs/marketplace/$beaconText"))
        if ($beaconBody.Trim().Length -lt 80) { throw 'Required local copy is absent or too short.' }
        if ($beaconBody -match '(?i)\b(TODO|TBD|PLACEHOLDER)\b|\{\{[^}]+\}\}') { throw 'Unresolved placeholder in required copy.' }
        if ($beaconBody -match $beaconPrivateSourceUrl) { throw 'Public listing material links to the private source repository.' }
        Add-Check "copy/$beaconText" 'PASS' 'Local copy exists with no recognized placeholder tokens. Editorial accuracy requires human review.'
    } catch { Add-Check "copy/$beaconText" 'FAIL' $_.Exception.Message }
}

try {
    $beaconManifest = [IO.File]::ReadAllText((Join-Path $beaconMedia 'manifest.json')) | ConvertFrom-Json
    if ($beaconManifest.schemaVersion -ne 1 -or $beaconManifest.version -ne $Version) { throw 'Screenshot manifest schema/version mismatch.' }
    Assert-Sha $beaconManifest.packageSha256 'manifest package'
    if ($beaconManifest.packageSha256 -cne $beaconPackageHash) { throw 'Screenshot manifest package hash differs from the installation ZIP.' }
    $beaconScreens = @($beaconManifest.screenshots)
    if ($beaconScreens.Count -lt 1 -or $null -eq $beaconScreens[0]) { throw 'Screenshot upload set is empty.' }
    $beaconRatio = $null; $beaconSeen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($beaconScreen in $beaconScreens) {
        $beaconScreenPath = Resolve-Within $beaconMedia $beaconScreen.file
        if (-not $beaconSeen.Add($beaconScreenPath)) { throw 'Duplicate screenshot in upload set.' }
        Assert-Sha $beaconScreen.sha256 $beaconScreen.file
        if ((Get-FileSha $beaconScreenPath) -cne $beaconScreen.sha256) { throw "Screenshot checksum mismatch: $($beaconScreen.file)" }
        $beaconDimensions = Get-ImageDimensions $beaconScreenPath
        if ($beaconDimensions.width -lt 1200 -or $beaconDimensions.height -lt 760) { throw "Screenshot below 1200 x 760: $($beaconScreen.file)" }
        if ($beaconDimensions.width -ne $beaconScreen.width -or $beaconDimensions.height -ne $beaconScreen.height) { throw 'Screenshot dimensions differ from manifest.' }
        if ($null -eq $beaconRatio) { $beaconRatio = $beaconDimensions }
        elseif ([long]$beaconDimensions.width * $beaconRatio.height -ne [long]$beaconDimensions.height * $beaconRatio.width) { throw 'Upload screenshots do not share one exact aspect ratio.' }
        $beaconSource = $beaconScreen.source
        $beaconSourcePath = Resolve-Within $beaconRoot $beaconSource.file
        Assert-Sha $beaconSource.sha256 'source screenshot'; Assert-Sha $beaconSource.packagedJarSha256 'captured plugin JAR'
        if ($beaconSource.pluginVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$') { throw 'Missing capture plugin version.' }
        if ((Get-FileSha $beaconSourcePath) -cne $beaconSource.sha256) { throw 'Original evidence screenshot missing or checksum mismatch.' }
        $beaconEvidence = Resolve-Within $beaconRoot $beaconSource.evidence
        if (-not (Test-Path -LiteralPath $beaconEvidence -PathType Leaf)) { throw 'Capture evidence document is missing.' }
        $beaconSourceDimensions = Get-ImageDimensions $beaconSourcePath
        if ($beaconDimensions.width -gt $beaconSourceDimensions.width -or $beaconDimensions.height -gt $beaconSourceDimensions.height) { throw 'Upload screenshot exceeds original dimensions: upscaling is not accepted.' }
        $beaconTransforms = @($beaconSource.transforms | Where-Object { $null -ne $_ })
        foreach ($beaconTransform in $beaconTransforms) {
            if ($null -ne $beaconTransform -and $beaconTransform -notin @('crop', 'downscale', 'privacy-redaction')) { throw "Unsupported screenshot transform: $beaconTransform" }
        }
        if ($beaconSource.sha256 -cne $beaconScreen.sha256 -and $beaconTransforms.Count -eq 0) { throw 'Changed screenshot has no declared transform.' }
        if ($beaconSource.pluginVersion -ne $Version -or $beaconSource.packagedJarSha256 -cne $beaconJarHash) {
            $beaconPending.Add("Screenshot $($beaconScreen.file) was not captured from this exact version/JAR.")
        }
    }
    Add-Check 'screenshots' 'PASS' "$($beaconScreens.Count) image(s); matching aspect ratio and minimum dimensions; original local evidence and hashes recorded. This is provenance bookkeeping, not proof of unaltered pixels or authentic application behavior."
} catch { Add-Check 'screenshots' 'FAIL' $_.Exception.Message }

try {
    $beaconDecisionPath = Join-Path $beaconRoot 'docs/marketplace/publishing-decisions.json'
    $beaconDecisionPendingStart = $beaconPending.Count
    if (-not (Test-Path -LiteralPath $beaconDecisionPath)) { $beaconPending.Add('License, privacy, publisher/contact and distribution-material decisions are not recorded.') }
    else {
        $beaconDecisions = [IO.File]::ReadAllText($beaconDecisionPath) | ConvertFrom-Json
        if ($beaconDecisions.license.kind -eq 'free-closed-source' -and ($beaconDecisions.distribution.pricing -ne 'free' -or $beaconDecisions.distribution.sourceRepositoryVisibility -ne 'private' -or $beaconDecisions.license.sourceCodeRightsGranted -ne $false)) { throw 'Free closed-source decisions must keep pricing free, the source repository private and source-code rights ungranted.' }
        if ($beaconDecisions.license.status -ne 'decided') { $beaconPending.Add('Source/distribution license or EULA has not been decided.') }
        else {
            $beaconLicense = Resolve-Within $beaconRoot $beaconDecisions.license.file
            if ([string]::IsNullOrWhiteSpace($beaconDecisions.license.kind) -or -not (Test-Path -LiteralPath $beaconLicense -PathType Leaf)) { throw 'Decided license requires its kind and a local license/EULA file.' }
            $beaconLicenseText = [IO.File]::ReadAllText($beaconLicense)
            if ($beaconLicenseText.Trim().Length -lt 80 -or $beaconLicenseText -match '(?i)\b(TODO|TBD|PLACEHOLDER)\b') { throw 'License/EULA file is empty or contains unresolved placeholders.' }
        }
        if ($beaconDecisions.publisher.status -ne 'decided') { $beaconPending.Add('Publisher identity and public contact have not been decided.') }
        elseif ([string]::IsNullOrWhiteSpace($beaconDecisions.publisher.name) -or $beaconDecisions.publisher.contact -notmatch '^(https://[^\s]+|[^\s@]+@[^\s@]+\.[^\s@]+)$' -or ($beaconDecisions.publisher.name + ' ' + $beaconDecisions.publisher.contact) -match '(?i)\b(TODO|TBD|PLACEHOLDER)\b') { throw 'Decided publisher requires a name and HTTPS support URL or contact email, without placeholder tokens.' }
        if ($beaconDecisions.privacy.status -ne 'decided') { $beaconPending.Add('Privacy disclosure has not been prepared and recorded.') }
        else {
            $beaconPrivacy = Resolve-Within $beaconRoot $beaconDecisions.privacy.file
            $beaconPrivacyText = [IO.File]::ReadAllText($beaconPrivacy)
            if ($beaconPrivacyText.Trim().Length -lt 80 -or $beaconPrivacyText -match '(?i)\b(TODO|TBD|PLACEHOLDER)\b') { throw 'Privacy disclosure is empty or contains unresolved placeholders.' }
        }
        if ($beaconDecisions.distribution.status -notin @('prepared', 'decided', 'approved')) { $beaconPending.Add('Distribution-material scope has not been decided. Preparing materials does not authorize upload.') }
    }
    Add-Check 'publishing-decisions' $(if ($beaconPending.Count -gt $beaconDecisionPendingStart) { 'PENDING' } else { 'PASS' }) 'Checks recorded decisions and local files only; does not verify legal sufficiency, account ownership, public endpoint access or Marketplace approval.'
} catch { Add-Check 'publishing-decisions' 'FAIL' $_.Exception.Message }

$beaconFailures = @($beaconChecks | Where-Object { $_.status -eq 'FAIL' }).Count
if ($Ready -and $beaconPending.Count -gt 0) { $beaconFailures++ }
$beaconResult = [ordered]@{
    version = $Version; checkedAt = (Get-Date).ToString('o'); mode = $(if ($Ready) { 'ready-gate' } else { 'materials-only' })
    result = $(if ($beaconFailures -gt 0) { 'FAIL' } elseif ($Ready) { 'LOCAL_READY_GATE_PASS' } else { 'MATERIALS_PASS' })
    packageSha256 = $beaconPackageHash; packagedJarSha256 = $beaconJarHash; packagedEulaSha256 = $beaconEulaHash
    checks = @($beaconChecks.ToArray()); pending = @($beaconPending.ToArray())
    note = 'Local material validation only. Not Marketplace approval, GUI acceptance, license advice, proof of account readiness, or authorization to upload. Use verify-release.ps1 separately for tests/Verifier/closed-sandbox logs.'
}
$beaconOutputDir = Join-Path $beaconRoot 'build/reports'
[IO.Directory]::CreateDirectory($beaconOutputDir) | Out-Null
$beaconOutput = Join-Path $beaconOutputDir "marketplace-checks-$Version$(if ($Ready) { '-ready' }).json"
$beaconResult | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $beaconOutput -Encoding UTF8
$beaconResult | ConvertTo-Json -Depth 8
if ($beaconFailures -gt 0) { throw "MARKETPLACE_CHECK_FAIL: $beaconOutput" }
Write-Host "MARKETPLACE_$($beaconResult.result): $beaconOutput"
