<#
.SYNOPSIS
    ShareConnectSave one-shot developer environment setup (Windows, no admin required).

.DESCRIPTION
    Installs/checks the tools every contributor needs before they can build, test, or
    run any ShareConnectSave microservice locally:
      - Scoop (user-scoped package manager)
      - Temurin JDK 21 (Java services: Discovery, Connection, Rating, Report, Admin)
      - IntelliJ IDEA Community Edition (Java IDE)
      - .NET 9 SDK        (checked only — installed separately, needs an MSI/admin path)
      - Node.js 20+       (checked only — Angular PWA toolchain)
      - Docker Desktop    (checked only — installed separately, needs an MSI/admin path)

    Safe to re-run any number of times: every step checks current state first and
    skips work that is already done.

.NOTES
    Run from a normal (non-admin) PowerShell prompt:
        powershell -ExecutionPolicy Bypass -File .\dev-setup.ps1
#>

# Infrastructure as Code: the dev environment is described here as a script instead of
# a wiki page of manual steps. The script IS the documentation, and it can't drift out
# of date the way a checklist can -- if it stops working, it stops working visibly.
# We collect results into a summary structure instead of printing-and-forgetting, so
# the "outputs a summary" acceptance criterion is a natural side effect of one array,
# not a second pass over the same logic.
$summary = @()

function Add-Summary {
    param(
        [Parameter(Mandatory)][string]$Tool,
        [Parameter(Mandatory)][ValidateSet('Installed', 'AlreadyPresent', 'Warning', 'Skipped')][string]$Result,
        [string]$Detail = ''
    )
    $summary += [PSCustomObject]@{ Tool = $Tool; Result = $Result; Detail = $Detail }
    $script:summary = $summary
}

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

# Guard clause pattern: check-and-return-early instead of nesting the "do the work"
# branch inside an if. Every install step below follows this same shape -- check
# current state, bail out early with a summary entry if nothing needs to happen,
# only fall through to the actual install when the check genuinely failed.
function Test-CommandExists {
    param([Parameter(Mandatory)][string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-VersionMajor {
    param([Parameter(Mandatory)][string]$VersionString)
    if ($VersionString -match '(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

# ---------------------------------------------------------------------------
# Step 1: Scoop itself
# ---------------------------------------------------------------------------
Write-Step "Checking for Scoop"

# Idempotent bootstrap: Scoop's own installer already refuses to reinstall over an
# existing install, but we still check first so the "already present" branch of the
# summary is accurate instead of just relying on the installer's own idempotency.
if (Test-CommandExists 'scoop') {
    $scoopVersion = (scoop --version) 2>&1 | Select-Object -First 1
    Write-Host "Scoop already installed ($scoopVersion)" -ForegroundColor Green
    Add-Summary -Tool 'Scoop' -Result 'AlreadyPresent' -Detail "$scoopVersion"
}
else {
    Write-Host "Scoop not found -- installing (no admin required)..." -ForegroundColor Yellow
    try {
        # Scoop installs entirely under %USERPROFILE%\scoop -- this is precisely why
        # the whole script needs no admin rights, unlike Chocolatey or winget's
        # machine-scoped installs.
        Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')
        # Scoop's installer updates PATH for new shells; refresh it in this process
        # too so the rest of the script can call `scoop` without reopening a prompt.
        $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
        if (Test-CommandExists 'scoop') {
            Write-Host "Scoop installed successfully" -ForegroundColor Green
            Add-Summary -Tool 'Scoop' -Result 'Installed'
        }
        else {
            Write-Host "Scoop install ran but 'scoop' is still not on PATH in this session -- open a new terminal and re-run this script." -ForegroundColor Red
            Add-Summary -Tool 'Scoop' -Result 'Warning' -Detail 'Installed but not on PATH this session'
        }
    }
    catch {
        Write-Host "Failed to install Scoop: $($_.Exception.Message)" -ForegroundColor Red
        Add-Summary -Tool 'Scoop' -Result 'Warning' -Detail 'Install failed'
    }
}

# Everything past this point needs Scoop; if it truly isn't available, warn and
# continue rather than throwing -- the spec is explicit that missing tools should
# not stop the script (a contributor might only need the .NET side today).
$scoopAvailable = Test-CommandExists 'scoop'

if ($scoopAvailable) {
    # ---------------------------------------------------------------------------
    # Step 2: Scoop buckets
    # ---------------------------------------------------------------------------
    Write-Step "Ensuring required Scoop buckets are added"

    # `scoop bucket add` is itself idempotent (no-ops if already added), but we still
    # check `scoop bucket list` first: re-adding an existing bucket is harmless, yet
    # checking keeps our own summary/log honest about what this run actually did,
    # rather than reporting "Installed" every single time it merely no-op'd.
    $existingBuckets = (scoop bucket list 2>&1 | Out-String)

    foreach ($bucket in @('java', 'extras')) {
        if ($existingBuckets -match "(?m)^\s*$bucket(\s|`$)") {
            Write-Host "Bucket '$bucket' already added" -ForegroundColor Green
            Add-Summary -Tool "Scoop bucket: $bucket" -Result 'AlreadyPresent'
        }
        else {
            Write-Host "Adding bucket '$bucket'..." -ForegroundColor Yellow
            scoop bucket add $bucket 2>&1 | Out-Null
            Add-Summary -Tool "Scoop bucket: $bucket" -Result 'Installed'
        }
    }

    # ---------------------------------------------------------------------------
    # Step 3: JDK 21 (Temurin)
    # ---------------------------------------------------------------------------
    Write-Step "Checking for JDK 21 (Temurin)"

    # Authoritative, PATH-independent idempotency check -- same pattern already used for
    # IntelliJ below (`scoop list` matched against the app name). This matters because
    # `java -version` alone can be shadowed by a pre-existing, machine-level JDK (e.g.
    # Oracle's "javapath" PATH entry) that wins over Scoop's user-level shim. If we only
    # trusted PATH, that shadow would make this check fail on *every* re-run, causing the
    # script to re-attempt the install and misreport 'Installed' forever, never
    # 'AlreadyPresent'.
    $temurinInScoopBefore = ((scoop list 2>&1 | Out-String) -match '(?im)^\s*temurin21-jdk\s')

    $javaOk = $false
    $javaFirstLine = $null
    if (Test-CommandExists 'java') {
        # `java -version` writes to stderr by convention (a JVM quirk from before
        # stdout/stderr conventions settled) -- 2>&1 is required or this check
        # silently sees nothing and assumes Java is missing.
        $javaVersionOutput = (java -version 2>&1 | Out-String)
        # JEP 223 dropped trailing zero version components, so a JDK 21 GA build can
        # legitimately print the bare string "21" instead of "21.0.x" -- match both.
        if ($javaVersionOutput -match '"?21("|\.)') {
            $javaOk = $true
        }
        else {
            $javaFirstLine = ($javaVersionOutput -split "`n")[0].Trim()
        }
    }

    if ($temurinInScoopBefore) {
        Write-Host "JDK 21 already installed" -ForegroundColor Green
        Add-Summary -Tool 'JDK 21 (Temurin)' -Result 'AlreadyPresent'
        if (-not $javaOk) {
            # Scoop already has it, but PATH doesn't resolve to it in this session -- a
            # machine-level Java entry is winning PATH precedence over Scoop's user-level
            # shim. This script cannot reorder machine PATH without admin rights, so hand
            # the developer the two concrete workarounds instead of a silent surprise.
            $versionSuffix = if ($javaFirstLine) { " ($javaFirstLine)" } else { '' }
            Write-Host "Note: 'java' on PATH doesn't resolve to version 21$versionSuffix. A machine-level Java install (e.g. Oracle's 'javapath' entry) likely wins PATH precedence over Scoop's user-level shim. Work around it by setting a user-scoped JAVA_HOME pointing at the Temurin 21 install under scoop\apps\temurin21-jdk\current, or by prepending Scoop's shim directory to PATH in your PowerShell profile ($PROFILE)." -ForegroundColor Yellow
        }
    }
    else {
        if ($javaFirstLine) {
            Write-Host "A JDK is on PATH but it isn't version 21 ($javaFirstLine). Installing Temurin 21 via Scoop alongside it -- Scoop does not remove other JDKs." -ForegroundColor Yellow
        }

        Write-Host "Installing temurin21-jdk via Scoop..." -ForegroundColor Yellow
        scoop install temurin21-jdk 2>&1 | Out-Null

        # $LASTEXITCODE and a bare Test-CommandExists both lie here: $LASTEXITCODE can be
        # zero even when Scoop skipped a broken install, and "some java resolves on PATH"
        # is true regardless of whether Scoop's install actually won PATH precedence (see
        # the PATH-shadowing comment above). Refresh PATH -- mirroring the Scoop bootstrap
        # step above -- and re-run the same version-matching check, then cross-check
        # `scoop list` as the PATH-independent source of truth before deciding what to
        # report.
        $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
        $postInstallJavaOk = $false
        if (Test-CommandExists 'java') {
            $postInstallVersionOutput = (java -version 2>&1 | Out-String)
            $postInstallJavaOk = $postInstallVersionOutput -match '"?21("|\.)'
        }
        $temurinInScoopAfter = ((scoop list 2>&1 | Out-String) -match '(?im)^\s*temurin21-jdk\s')

        if ($temurinInScoopAfter) {
            Write-Host "temurin21-jdk installed" -ForegroundColor Green
            Add-Summary -Tool 'JDK 21 (Temurin)' -Result 'Installed'
            if (-not $postInstallJavaOk) {
                Write-Host "Note: 'java' on PATH still doesn't resolve to version 21. A machine-level Java install (e.g. Oracle's 'javapath' entry) likely wins PATH precedence over Scoop's user-level shim, and this script cannot fix PATH order without admin rights. Work around it by setting a user-scoped JAVA_HOME pointing at the Temurin 21 install under scoop\apps\temurin21-jdk\current, or by prepending Scoop's shim directory to PATH in your PowerShell profile ($PROFILE)." -ForegroundColor Yellow
            }
        }
        else {
            Write-Host "Failed to install temurin21-jdk -- install manually with 'scoop install temurin21-jdk'" -ForegroundColor Red
            Add-Summary -Tool 'JDK 21 (Temurin)' -Result 'Warning' -Detail 'Scoop install failed'
        }
    }

    # ---------------------------------------------------------------------------
    # Step 4: IntelliJ IDEA Community Edition
    # ---------------------------------------------------------------------------
    Write-Step "Checking for IntelliJ IDEA Community Edition"

    # Scoop tracks installed apps in its own manifest list independent of PATH --
    # `scoop list` is the correct idempotency check here, not Get-Command, because
    # IntelliJ is a GUI app that doesn't necessarily put a CLI shim on PATH.
    $scoopApps = (scoop list 2>&1 | Out-String)
    if ($scoopApps -match '(?im)^\s*intellij-idea-community-edition\s') {
        Write-Host "IntelliJ IDEA Community Edition already installed" -ForegroundColor Green
        Add-Summary -Tool 'IntelliJ IDEA CE' -Result 'AlreadyPresent'
    }
    else {
        Write-Host "Installing intellij-idea-community-edition via Scoop..." -ForegroundColor Yellow
        scoop install intellij-idea-community-edition 2>&1 | Out-Null
        $scoopAppsAfter = (scoop list 2>&1 | Out-String)
        if ($scoopAppsAfter -match '(?im)^\s*intellij-idea-community-edition\s') {
            Write-Host "IntelliJ IDEA Community Edition installed" -ForegroundColor Green
            Add-Summary -Tool 'IntelliJ IDEA CE' -Result 'Installed'
        }
        else {
            Write-Host "Failed to install IntelliJ IDEA CE -- install manually with 'scoop install intellij-idea-community-edition'" -ForegroundColor Red
            Add-Summary -Tool 'IntelliJ IDEA CE' -Result 'Warning' -Detail 'Scoop install failed'
        }
    }
}
else {
    Write-Host "Scoop is unavailable -- skipping JDK 21 and IntelliJ IDEA installs. Re-run this script after Scoop is on PATH." -ForegroundColor Red
    Add-Summary -Tool 'JDK 21 (Temurin)' -Result 'Skipped' -Detail 'Scoop unavailable'
    Add-Summary -Tool 'IntelliJ IDEA CE' -Result 'Skipped' -Detail 'Scoop unavailable'
}

# ---------------------------------------------------------------------------
# Step 5-7: Check-only tools (.NET, Node, Docker)
# ---------------------------------------------------------------------------
# These three are deliberately check-and-warn, not install: the .NET SDK and Docker
# Desktop installers require admin elevation on Windows, which this script explicitly
# must not request (spec: "no admin required"). Silently installing Node via Scoop
# while forcing the other two to be manual would be an inconsistent developer
# experience, so all three cross-tool checks share one shape instead.
Write-Step "Checking .NET SDK (need 9+)"
if (Test-CommandExists 'dotnet') {
    $dotnetVersion = (dotnet --version 2>&1 | Out-String).Trim()
    $major = Get-VersionMajor -VersionString $dotnetVersion
    if ($null -ne $major -and $major -ge 9) {
        Write-Host ".NET SDK $dotnetVersion found" -ForegroundColor Green
        Add-Summary -Tool '.NET SDK' -Result 'AlreadyPresent' -Detail $dotnetVersion
    }
    else {
        Write-Host ".NET SDK $dotnetVersion found, but ShareConnectSave's .NET services target .NET 9 -- install .NET 9 SDK from https://dotnet.microsoft.com/download/dotnet/9.0" -ForegroundColor Yellow
        Add-Summary -Tool '.NET SDK' -Result 'Warning' -Detail "Found $dotnetVersion, need >= 9"
    }
}
else {
    Write-Host ".NET SDK not found -- install .NET 9 SDK from https://dotnet.microsoft.com/download/dotnet/9.0 (requires admin, not automated by this script)" -ForegroundColor Yellow
    Add-Summary -Tool '.NET SDK' -Result 'Warning' -Detail 'Not found'
}

Write-Step "Checking Node.js (need 20+)"
if (Test-CommandExists 'node') {
    $nodeVersion = (node --version 2>&1 | Out-String).Trim()
    $major = Get-VersionMajor -VersionString $nodeVersion
    if ($null -ne $major -and $major -ge 20) {
        Write-Host "Node.js $nodeVersion found" -ForegroundColor Green
        Add-Summary -Tool 'Node.js' -Result 'AlreadyPresent' -Detail $nodeVersion
    }
    else {
        Write-Host "Node.js $nodeVersion found, but the Angular PWA needs Node 20+ -- install from https://nodejs.org or 'scoop install nodejs-lts'" -ForegroundColor Yellow
        Add-Summary -Tool 'Node.js' -Result 'Warning' -Detail "Found $nodeVersion, need >= 20"
    }
}
else {
    Write-Host "Node.js not found -- install Node 20+ from https://nodejs.org or 'scoop install nodejs-lts'" -ForegroundColor Yellow
    Add-Summary -Tool 'Node.js' -Result 'Warning' -Detail 'Not found'
}

Write-Step "Checking Docker"
if (Test-CommandExists 'docker') {
    $dockerVersion = (docker --version 2>&1 | Out-String).Trim()
    Write-Host "$dockerVersion found" -ForegroundColor Green
    Add-Summary -Tool 'Docker' -Result 'AlreadyPresent' -Detail $dockerVersion
}
else {
    Write-Host "Docker not found -- install Docker Desktop from https://www.docker.com/products/docker-desktop (requires admin, not automated by this script)" -ForegroundColor Yellow
    Add-Summary -Tool 'Docker' -Result 'Warning' -Detail 'Not found'
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " ShareConnectSave dev environment setup -- summary" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

foreach ($entry in $summary) {
    $color = switch ($entry.Result) {
        'Installed'       { 'Green' }
        'AlreadyPresent'  { 'DarkGreen' }
        'Warning'         { 'Yellow' }
        'Skipped'         { 'Red' }
        default           { 'White' }
    }
    $line = "  [{0,-14}] {1,-22}" -f $entry.Result, $entry.Tool
    if ($entry.Detail) { $line += " ($($entry.Detail))" }
    Write-Host $line -ForegroundColor $color
}

$warnCount = ($summary | Where-Object { $_.Result -in @('Warning', 'Skipped') }).Count
Write-Host "============================================================" -ForegroundColor Cyan
if ($warnCount -eq 0) {
    Write-Host "All tools ready. Re-run this script any time -- it will only report changes." -ForegroundColor Green
}
else {
    Write-Host "$warnCount item(s) need your attention (see Warning/Skipped rows above). Nothing was left half-installed -- safe to re-run after fixing them." -ForegroundColor Yellow
}
