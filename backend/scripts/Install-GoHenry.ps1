<#
.SYNOPSIS
    Interactive, Windows-native installer for the GoHenry backend.

.DESCRIPTION
    Asks you a few questions, then provisions the entire GoHenry cloud backend in
    Azure and deploys the Functions code. Everything lands in its own resource group
    and shares NOTHING with FleetFoot / SashaSync / QoastQurrent.

    There is NO SQL: Azure Table Storage is the only datastore, Ford refresh tokens
    live in Key Vault, and push goes through Azure Notification Hubs (FCM v1).

    Storage tables are created on demand by the Functions host at first run
    (Vehicles, FordAccounts, States, Installs, TripHistory, ChargeHistory,
    RoadTrips, Meta) — the installer never has to pre-create them, so deploying
    new code that adds a table (e.g. RoadTrips for the road-trip feature) needs
    only a "Backend code only" redeploy.

    Resources created (all in rg-gohenry-<suffix>):
      - Storage account (Standard_LRS)   -> the only datastore (Tables + Queues)
      - Key Vault (RBAC)                  -> per-user Ford refresh tokens
      - Notification Hubs namespace + hub -> FCM push
      - Function App (.NET 8 isolated)    -> the backend

    Re-runnable: every step is idempotent, so you can run it again to fix or update.

    THREE START OPTIONS (chosen from a menu at the top, or via -Mode):
      1. Full install        - provision everything + deploy the code (default).
      2. Backend code only    - rebuild & redeploy the Functions code to an
                                EXISTING Function App; touches no Azure resources
                                or settings. Only needs the install -Suffix.
      3. Add / remove cars    - add or remove an extra vehicle's Ford app
                                (settings + token only). Only needs -Suffix and
                                the car's slug (plus client id/secret to add).
    Each option asks ONLY for the inputs that option needs.

.PARAMETER Mode
    Which of the three start options to run, skipping the menu:
      Full        - full install (provision + deploy).
      BackendOnly - redeploy the Functions code only (needs -Suffix).
      Cars        - add/remove an extra car's Ford app only (needs -Suffix).
    Omit to be asked interactively. Passing -ExtraFordApps/-RemoveFordApps
    implies -Mode Cars for backward compatibility.

.PARAMETER Suffix
    Short unique tag woven into global resource names (default: prompted).

.PARAMETER Location
    Azure region (default: eastus or prompted).

.PARAMETER ExtraFordApps
    Add extra Ford apps (one per additional vehicle) WITHOUT a full install.
    Passing this runs the lightweight "manage Ford apps" path: it only writes the
    Ford__Apps__<slug>__ClientId / __ClientSecret settings on the existing Function
    App and touches NOTHING else. Each entry is a hashtable
    @{ Slug='car2'; ClientId='...'; ClientSecret='...' }. Requires -Suffix.

.PARAMETER RemoveFordApps
    Remove extra Ford app(s) by slug WITHOUT a full install. Passing this runs the
    same lightweight path: it deletes that slug's Ford__Apps__<slug>__* settings and
    the matching Key Vault refresh-token secret(s), and changes nothing else.
    Requires -Suffix.

.PARAMETER NonInteractive
    Skip all prompts and use parameter/defaults only (for automation).

.EXAMPLE
    .\Install-GoHenry.ps1
        Full interactive install (recommended).

.EXAMPLE
    .\Install-GoHenry.ps1 -Suffix henry -Location westus2
        Pre-seed a couple of answers; you'll still be asked for the rest.

.EXAMPLE
    .\Install-GoHenry.ps1 -Suffix henry -ExtraFordApps @(@{ Slug='car2'; ClientId='<id>'; ClientSecret='<secret>' })
        Add one extra car. Only the Ford__Apps__car2__* settings are written; no
        existing resources are created or updated.

.EXAMPLE
    .\Install-GoHenry.ps1 -Suffix henry -RemoveFordApps car2
        Remove the 'car2' extra Ford app (its settings + Key Vault token). Nothing
        else is touched.
.EXAMPLE
    .\Install-GoHenry.ps1 -Mode BackendOnly -Suffix henry
        Rebuild and redeploy ONLY the Functions code to func-gohenry-henry.
        No Azure resources or app settings are created or changed.

.EXAMPLE
    .\Install-GoHenry.ps1 -Mode Cars -Suffix henry
        Open the add/remove-cars flow against the existing install. You'll be
        asked whether to add or remove a car and only for that car's details.
#>
[CmdletBinding()]
param(
    # Which start option to run (skips the menu). Full | BackendOnly | Cars.
    [ValidateSet('Full','BackendOnly','Cars')]
    [string]$Mode,
    [string]$Suffix,
    [string]$Location,
    [string]$FordSlug,
    [string]$FordClientId,
    [string]$FordClientSecret,
    # Optional extra Ford apps (one per additional vehicle). Each entry must be a
    # hashtable: @{ Slug='car2'; ClientId='...'; ClientSecret='...' }. A Ford OAuth
    # token only grants access to ONE vehicle, so each car needs its own Ford app.
    # NOTE: passing this switches the script into "manage Ford apps only" mode — it
    # ONLY writes the new Ford__Apps__<slug>__* settings and changes nothing else.
    [hashtable[]]$ExtraFordApps = @(),
    # Optional extra Ford app slug(s) to REMOVE. Also runs "manage Ford apps only"
    # mode: deletes each slug's Ford__Apps__<slug>__* settings and matching Key
    # Vault refresh-token secret(s); no other resource is touched.
    [string[]]$RemoveFordApps = @(),
    [string]$ExpectedUser,
    [string]$ExpectedTenant,
    [switch]$SkipDeploy,
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ApiProject = Join-Path $ScriptDir '..\src\GoHenry.Api'

# Lightweight "manage Ford apps only" mode: triggered by -ExtraFordApps (add) or
# -RemoveFordApps (remove). In this mode we ONLY edit the Function App's
# Ford__Apps__<slug>__* settings (and, on removal, matching Key Vault secrets) and
# create/update NOTHING else.
$ManageFordApps = ($ExtraFordApps.Count -gt 0 -or $RemoveFordApps.Count -gt 0)

# ---------------------------------------------------------------------------
# Pretty output helpers
# ---------------------------------------------------------------------------
function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Write-Note($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Write-Err($m)  { Write-Host "ERROR: $m" -ForegroundColor Red }

# Graceful exit — invoked from any prompt when the user types EXIT, or on cancel.
function Exit-Gracefully([string]$Reason = "Cancelled by user.") {
    Write-Host ""
    Write-Note $Reason
    Write-Note "Nothing further was changed. Re-run .\Install-GoHenry.ps1 any time to continue."
    exit 0
}

# Returns $true when the user typed EXIT (any case) — caller should bail out.
function Test-ExitWord([string]$Value) {
    return ($null -ne $Value -and $Value.Trim() -ieq 'exit')
}

# Tip shown once so users know they can bail at any prompt.
function Write-ExitTip { Write-Note "(Type EXIT at any prompt to quit.)" }

function Read-Default([string]$Prompt, [string]$Default) {
    if ($NonInteractive) { return $Default }
    $suffix = if ($Default) { " [$Default]" } else { "" }
    $answer = Read-Host "$Prompt$suffix"
    if (Test-ExitWord $answer) { Exit-Gracefully }
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim()
}

function Read-YesNo([string]$Prompt, [bool]$Default = $true) {
    if ($NonInteractive) { return $Default }
    $hint = if ($Default) { "[Y/n]" } else { "[y/N]" }
    while ($true) {
        $a = (Read-Host "$Prompt $hint").Trim()
        if (Test-ExitWord $a) { Exit-Gracefully }
        $a = $a.ToLower()
        if ($a -eq '') { return $Default }
        if ($a -in @('y','yes')) { return $true }
        if ($a -in @('n','no'))  { return $false }
    }
}

function Read-Secret([string]$Prompt, [string]$Existing) {
    if ($NonInteractive -or $Existing) { return $Existing }
    $sec = Read-Host $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if (Test-ExitWord $plain) { Exit-Gracefully }
    return $plain
}

# Top-level menu: pick one of the three start options. Returns Full/BackendOnly/Cars.
function Read-InstallMode {
    Write-Host ""
    Write-Step "What would you like to do?"
    Write-Host "    [1] Full install        - provision the Azure backend and deploy the code"
    Write-Host "    [2] Backend code only   - just rebuild & redeploy the Functions code (needs your install suffix)"
    Write-Host "    [3] Add / remove cars   - add or remove an extra vehicle's Ford app (needs your install suffix)"
    Write-Host ""
    while ($true) {
        $a = (Read-Default "Choose 1, 2 or 3" "1")
        switch ($a) {
            '1' { return 'Full' }
            '2' { return 'BackendOnly' }
            '3' { return 'Cars' }
            default { Write-Note "Enter 1, 2 or 3." }
        }
    }
}

# Run an az command, returning trimmed stdout; throw with context on failure.
# Simple (non-advanced) function so tokens like -o/-n/-g pass straight through.
function Invoke-Az {
    $out = & az @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "az $($args -join ' ')`n$out"
    }
    return ($out | Out-String).Trim()
}

# True if an 'az ... show' style probe succeeds (resource exists).
function Test-AzExists {
    & az @args -o none 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# Wait until a freshly-created Function App's deployment (Kudu/SCM) host is
# DNS-resolvable. A just-created app often isn't in DNS yet, so an immediate
# 'func ... publish' fails with "No such host is known (<app>.scm...:443)".
# Returns $true once resolvable, or $false after the timeout (we still try).
function Wait-ScmReady([string]$AppName, [int]$TimeoutSec = 180) {
    $scm = "$AppName.scm.azurewebsites.net"
    $sw  = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        try { [System.Net.Dns]::GetHostEntry($scm) | Out-Null; return $true }
        catch { Start-Sleep -Seconds 5 }
    }
    return $false
}

# Publish the Functions code, waiting for the deploy endpoint first and retrying
# a couple of times. This absorbs the brief window where a new app's SCM host
# isn't yet resolvable / warmed up (the "No such host is known" race).
function Invoke-FuncPublish([string]$AppName) {
    Write-Step "Waiting for $AppName's deployment endpoint to come online"
    if (Wait-ScmReady $AppName) { Write-Ok "Deployment endpoint is reachable." }
    else { Write-Note "Endpoint not resolvable yet - trying anyway (will retry on failure)." }

    Push-Location $ApiProject
    try {
        $maxAttempts = 3
        for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
            & func azure functionapp publish $AppName --dotnet-isolated
            if ($LASTEXITCODE -eq 0) { Write-Ok "Deployed."; return }
            if ($attempt -ge $maxAttempts) { throw "func publish failed (exit $LASTEXITCODE) after $attempt attempts" }
            Write-Note "Deploy attempt $attempt failed (the app may still be warming up). Retrying in 20s..."
            Start-Sleep -Seconds 20
        }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# ADD / REMOVE extra Ford apps without touching any other resource. A Ford token
# only unlocks ONE vehicle, so each extra car is its own Ford developer app whose
# client id + secret live in the Function App settings Ford__Apps__<slug>__*.
# This path edits ONLY those settings (and, on removal, the matching Key Vault
# refresh-token secrets) — it never creates/updates storage, Key Vault, the
# Notification Hub, the Function App, RBAC, or deploys code.
# ---------------------------------------------------------------------------
function Invoke-ManageFordApps {
    if (-not $Suffix) { $Suffix = Read-Default "Unique resource suffix used at install (e.g. henry)" "" }
    $Suffix = ($Suffix -replace '[^a-z0-9]', '').ToLower()
    if (-not $Suffix) { Write-Err "A -Suffix is required to locate your GoHenry Function App."; exit 1 }

    $Rg       = "rg-gohenry-$Suffix"
    $FuncApp  = "func-gohenry-$Suffix"
    $KeyVault = "kv-gohenry-$Suffix"

    Write-Step "Manage Ford apps on $FuncApp (no other resources are changed)"
    if (-not (Test-AzExists functionapp show -n $FuncApp -g $Rg)) {
        Write-Err "Function App '$FuncApp' not found in resource group '$Rg'."
        Write-Note "Check the suffix, or run a full install first: .\Install-GoHenry.ps1 -Suffix $Suffix"
        exit 1
    }

    # Read current settings so we can find the primary slug and report no-ops.
    $existing = @{}
    try {
        $cur = Invoke-Az functionapp config appsettings list -n $FuncApp -g $Rg -o json | ConvertFrom-Json
        foreach ($kv in $cur) { $existing[$kv.name] = $kv.value }
    } catch { Write-Note "Could not read current settings (continuing)." }
    $primarySlug = ([string]$existing['Ford__Slug']).Trim().ToLower()

    # Interactive Cars flow: when nothing was passed on the command line, ask
    # whether to add or remove a car and gather ONLY that action's inputs.
    if ($ExtraFordApps.Count -eq 0 -and $RemoveFordApps.Count -eq 0 -and -not $NonInteractive) {
        $knownExtra = @($existing.Keys |
            Where-Object { $_ -match '^Ford__Apps__([a-z0-9]+)__ClientId$' } |
            ForEach-Object { ($_ -replace '^Ford__Apps__','') -replace '__ClientId$','' } |
            Sort-Object -Unique)
        while ($true) {
            Write-Host ""
            Write-Host "    [1] Add a vehicle"
            Write-Host "    [2] Remove a vehicle"
            Write-Host "    [3] Done"
            if ($primarySlug)         { Write-Note "Primary car: $primarySlug (managed with -FordClientId/-FordClientSecret on a normal run)." }
            if ($knownExtra.Count -gt 0) { Write-Note "Extra cars currently configured: $($knownExtra -join ', ')" }
            $choice = Read-Default "Choose 1, 2 or 3" "3"
            if ($choice -eq '1') {
                $slug = ((Read-Default "  New vehicle slug (e.g. car2)" "") -replace '[^a-z0-9]', '').ToLower()
                if (-not $slug) { Write-Note "  Blank slug - skipping."; continue }
                if ($primarySlug -and $slug -ieq $primarySlug) { Write-Note "  '$slug' is the PRIMARY car; can't add it as an extra."; continue }
                $cid = Read-Default "  Ford client id for '$slug'" ""
                $sec = Read-Secret  "  Ford client secret for '$slug' (input hidden)" ""
                $ExtraFordApps += @{ Slug = $slug; ClientId = $cid; ClientSecret = $sec }
                if (($ExtraFordApps.Count) -ge 4) { Write-Note "  Reached the 5-vehicle limit (1 primary + 4 extra)."; break }
            } elseif ($choice -eq '2') {
                $slug = ((Read-Default "  Slug of the car to remove" "") -replace '[^a-z0-9]', '').ToLower()
                if (-not $slug) { Write-Note "  Blank slug - skipping."; continue }
                $RemoveFordApps += $slug
            } else { break }
        }
    }

    # Normalize requested additions (prompt only for the values we still need).
    $toAdd = @()
    foreach ($a in $ExtraFordApps) {
        $s = (([string]$a.Slug).Trim().ToLower() -replace '[^a-z0-9]', '')
        if (-not $s) { Write-Note "Skipping an add with a blank slug."; continue }
        if ($primarySlug -and $s -ieq $primarySlug) { Write-Note "'$s' is the PRIMARY slug; change it with -FordClientId/-FordClientSecret on a normal run. Skipping."; continue }
        $cid = [string]$a.ClientId
        $sec = [string]$a.ClientSecret
        if (-not $cid) { $cid = Read-Default "  Ford client id for '$s'" "" }
        if (-not $sec) { $sec = Read-Secret  "  Ford client secret for '$s' (input hidden)" "" }
        $toAdd += [pscustomobject]@{ Slug = $s; ClientId = $cid; ClientSecret = $sec }
    }

    # Normalize requested removals.
    $toRemove = @()
    foreach ($r in $RemoveFordApps) {
        $s = (([string]$r).Trim().ToLower() -replace '[^a-z0-9]', '')
        if (-not $s) { continue }
        if ($primarySlug -and $s -ieq $primarySlug) { Write-Note "Refusing to remove the PRIMARY slug '$s' (it is the main car). Skipping."; continue }
        $toRemove += $s
    }

    if ($toAdd.Count -eq 0 -and $toRemove.Count -eq 0) { Write-Note "Nothing to add or remove."; return }

    # --- Removals first: settings + matching Key Vault refresh-token secrets. ---
    foreach ($slug in $toRemove) {
        Write-Step "Removing Ford app '$slug'"
        if (-not ($existing.Keys | Where-Object { $_ -like "Ford__Apps__${slug}__*" })) {
            Write-Note "  No settings found for '$slug' - it may already be removed."
        }
        & az functionapp config appsettings delete -n $FuncApp -g $Rg --setting-names "Ford__Apps__${slug}__ClientId" "Ford__Apps__${slug}__ClientSecret" -o none 2>$null | Out-Null
        Write-Ok "  Settings removed."
        # Best-effort: delete the per-user refresh token(s) named ford-refresh-<user>-<slug>.
        $allSecrets = & az keyvault secret list --vault-name $KeyVault --query "[].name" -o tsv 2>$null
        if ($LASTEXITCODE -eq 0 -and $allSecrets) {
            $hit = $allSecrets -split "`r?`n" | Where-Object { $_ -match "^ford-refresh-.+-$([regex]::Escape($slug))$" }
            if ($hit) {
                foreach ($n in $hit) {
                    $n = $n.Trim(); if (-not $n) { continue }
                    & az keyvault secret delete --vault-name $KeyVault --name $n -o none 2>$null | Out-Null
                    Write-Ok "  Deleted Key Vault token: $n"
                }
            } else { Write-Note "  No Key Vault refresh token found for '$slug'." }
        } else { Write-Note "  Skipped Key Vault cleanup (no access or no secrets)." }
    }

    # --- Additions: set only the two settings per slug. ---
    if ($toAdd.Count -gt 0) {
        $settings = @()
        foreach ($a in $toAdd) {
            $settings += "Ford__Apps__$($a.Slug)__ClientId=$($a.ClientId)"
            $settings += "Ford__Apps__$($a.Slug)__ClientSecret=$($a.ClientSecret)"
        }
        Write-Step "Adding $($toAdd.Count) Ford app(s): $(($toAdd | ForEach-Object { $_.Slug }) -join ', ')"
        Invoke-Az functionapp config appsettings set -n $FuncApp -g $Rg --settings @settings -o none | Out-Null
        foreach ($a in $toAdd) { if (-not $a.ClientId) { Write-Note "  '$($a.Slug)' has no client id yet - set Ford__Apps__$($a.Slug)__ClientId / __ClientSecret in the Portal." } }
        Write-Ok "  Settings applied."
    }

    Write-Host ""
    Write-Ok "Done. The Function App restarts automatically (a few seconds); no other resource changed."
    if ($toAdd.Count -gt 0)    { Write-Note "In the app: Settings (cog) -> Ford authorization -> expand each new slug -> Link to authorize that car." }
    if ($toRemove.Count -gt 0) { Write-Note "Removed cars drop off after the restart. Cached Vehicles/FordAccounts rows (if any) can be deleted per the README." }
}

# ---------------------------------------------------------------------------
# BACKEND CODE ONLY: rebuild and republish the Functions code to an EXISTING
# Function App. Creates/updates NOTHING in Azure (no providers, provisioning,
# RBAC, or settings) — it only needs the install -Suffix to locate the app.
# ---------------------------------------------------------------------------
function Invoke-BackendDeployOnly {
    if (-not $Suffix) { $Suffix = Read-Default "Unique resource suffix used at install (e.g. henry)" "" }
    $Suffix = ($Suffix -replace '[^a-z0-9]', '').ToLower()
    if (-not $Suffix) { Write-Err "A -Suffix is required to locate your GoHenry Function App."; exit 1 }

    $Rg      = "rg-gohenry-$Suffix"
    $FuncApp = "func-gohenry-$Suffix"

    Write-Step "Backend code only -> $FuncApp (no Azure resources or settings are changed)"
    if (-not (Test-AzExists functionapp show -n $FuncApp -g $Rg)) {
        Write-Err "Function App '$FuncApp' not found in resource group '$Rg'."
        Write-Note "Check the suffix, or run a full install first: .\Install-GoHenry.ps1 -Mode Full -Suffix $Suffix"
        exit 1
    }

    Write-Step "Publishing GoHenry.Api to $FuncApp (this can take a few minutes)"
    Invoke-FuncPublish $FuncApp

    Write-Host ""
    Write-Ok "Done. Only the Functions code was redeployed; settings and resources are unchanged."
}

# ===========================================================================
# 0) Prerequisites
# ===========================================================================
Write-Host ""
Write-Host "  GoHenry backend installer" -ForegroundColor Magenta
Write-Host "  -------------------------" -ForegroundColor Magenta
Write-Host ""
if (-not $NonInteractive) { Write-ExitTip }
Write-Host ""

# ---------------------------------------------------------------------------
# Pick the start option (Full / BackendOnly / Cars). -ExtraFordApps/-RemoveFordApps
# imply Cars for backward compatibility; otherwise ask (or default to Full when
# running non-interactively). Each option asks ONLY for the inputs it needs.
# ---------------------------------------------------------------------------
if (-not $Mode) {
    if ($ManageFordApps)      { $Mode = 'Cars' }
    elseif ($NonInteractive)  { $Mode = 'Full' }
    else                      { $Mode = Read-InstallMode }
}

Write-Step "Checking prerequisites"
$missing = @()
# 'func' is only needed when we build/deploy code (Full or BackendOnly); the
# add/remove-cars path uses 'az' alone.
$needTools = if ($Mode -eq 'Cars') { @('az') } else { @('az','func') }
foreach ($tool in $needTools) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { $missing += $tool }
}
if ($missing.Count -gt 0) {
    Write-Err "Missing required tool(s): $($missing -join ', ')"
    Write-Note "Install the Azure CLI:  https://aka.ms/installazurecli"
    Write-Note "Install Functions Core Tools v4:  npm i -g azure-functions-core-tools@4 --unsafe-perm true"
    exit 1
}
Write-Ok "$($needTools -join ' and ') found."

# Notification Hubs needs an az extension; add it quietly if absent. Only the
# full install provisions the hub, so skip this for the lightweight paths.
if ($Mode -eq 'Full') {
    $hasNhExt = (& az extension list --query "[?name=='notification-hub'].name" -o tsv 2>$null)
    if (-not $hasNhExt) {
        Write-Step "Installing the 'notification-hub' az extension"
        & az extension add --name notification-hub --only-show-errors 2>$null | Out-Null
    }
}

# Make sure we're logged in.
try { $acct = Invoke-Az account show -o json | ConvertFrom-Json }
catch {
    Write-Step "Signing in to Azure"
    & az login --only-show-errors | Out-Null
    $acct = Invoke-Az account show -o json | ConvertFrom-Json
}

# ---------------------------------------------------------------------------
# Validate the signed-in user / tenant BEFORE doing anything else.
# ---------------------------------------------------------------------------
$signedInUser   = $acct.user.name
$signedInTenant = $acct.tenantId
# Try to surface the tenant's primary domain for a friendlier display (best effort).
$tenantDomain = $null
try {
    $tenantDomain = Invoke-Az rest --method get `
        --url "https://graph.microsoft.com/v1.0/organization?`$select=verifiedDomains" `
        --query "value[0].verifiedDomains[?isDefault].name | [0]" -o tsv 2>$null
    if ($tenantDomain) { $tenantDomain = $tenantDomain.Trim() }
} catch { $tenantDomain = $null }

Write-Step "Confirming your Azure identity"
Write-Ok "Signed-in user : $signedInUser"
if ($tenantDomain) { Write-Ok "Tenant         : $tenantDomain ($signedInTenant)" }
else               { Write-Ok "Tenant         : $signedInTenant" }
Write-Ok "Subscription   : $($acct.name) ($($acct.id))"

# Reusable "wrong account" guidance + quit.
function Stop-WrongIdentity([string]$Why) {
    Write-Host ""
    Write-Err $Why
    Write-Host ""
    Write-Note "To sign in as the correct user / tenant:"
    Write-Host  "    az logout"
    Write-Host  "    az login --tenant <your-tenant-id-or-domain>"
    Write-Host  "    # if a browser won't open, use device code:"
    Write-Host  "    az login --tenant <your-tenant-id-or-domain> --use-device-code"
    Write-Host ""
    Write-Note "Then re-run this script. You can pin the expected identity to skip this check:"
    Write-Host  "    .\Install-GoHenry.ps1 -ExpectedUser <you@contoso.com> -ExpectedTenant <tenant-id-or-domain>"
    exit 1
}

# True if the CURRENTLY active subscription can actually be used against ARM.
# (az account show reads a local cache and can succeed for stale/inaccessible subs;
#  this hits ARM so SubscriptionNotFound / disabled subs are caught up front.)
function Test-Subscription {
    & az group list -o none 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# Show enabled subscriptions and set the chosen one as active. Returns the new $acct.
function Select-Subscription {
    $subs = Invoke-Az account list --query "[?state=='Enabled'].{name:name,id:id}" -o json | ConvertFrom-Json
    if (-not $subs -or $subs.Count -eq 0) {
        Write-Err "No ENABLED subscriptions are available for '$signedInUser' in this tenant."
        Write-Note "Pick a tenant that has an active subscription, then re-run:"
        Write-Host "    az login --tenant <your-tenant-id-or-domain>"
        exit 1
    }
    Write-Host ""
    for ($i = 0; $i -lt $subs.Count; $i++) { Write-Host ("  [{0}] {1}  ({2})" -f $i, $subs[$i].name, $subs[$i].id) }
    while ($true) {
        $idx = [int](Read-Default "Pick a subscription number" "0")
        if ($idx -ge 0 -and $idx -lt $subs.Count) { break }
        Write-Note "Enter a number between 0 and $($subs.Count - 1)."
    }
    Invoke-Az account set --subscription $subs[$idx].id | Out-Null
    return (Invoke-Az account show -o json | ConvertFrom-Json)
}


if ($ExpectedUser -and ($signedInUser -ine $ExpectedUser)) {
    Stop-WrongIdentity "Signed-in user '$signedInUser' does not match expected '-ExpectedUser $ExpectedUser'."
}
if ($ExpectedTenant) {
    $tenantMatch = ($signedInTenant -ieq $ExpectedTenant) -or
                   ($tenantDomain -and ($tenantDomain -ieq $ExpectedTenant))
    if (-not $tenantMatch) {
        Stop-WrongIdentity "Signed-in tenant '$signedInTenant' does not match expected '-ExpectedTenant $ExpectedTenant'."
    }
}

# 2) Interactive confirmation when no explicit expectation was given.
if (-not $NonInteractive -and -not $ExpectedUser -and -not $ExpectedTenant) {
    if (-not (Read-YesNo "Is this the correct user AND tenant?" $true)) {
        Stop-WrongIdentity "You indicated this is NOT the correct user / tenant."
    }
}

if (-not $NonInteractive) {
    if (-not (Read-YesNo "Use THIS subscription?" $true)) {
        $acct = Select-Subscription
        Write-Ok "Now using: $($acct.name)"
    } else {
        # Make sure the confirmed subscription is the active context for all az calls.
        Invoke-Az account set --subscription $acct.id | Out-Null
    }
}

# ---------------------------------------------------------------------------
# Verify the active subscription actually works against ARM (not just cache).
# This catches SubscriptionNotFound / disabled subs BEFORE we provision.
# ---------------------------------------------------------------------------
Write-Step "Verifying subscription access"
while (-not (Test-Subscription)) {
    Write-Err "Subscription '$($acct.name)' ($($acct.id)) is not usable (not found, disabled, or wrong tenant)."
    if ($NonInteractive) {
        Write-Note "Sign in to a tenant with an active subscription, or pass a valid one, then re-run:"
        Write-Host "    az login --tenant <your-tenant-id-or-domain>"
        Write-Host "    az account set --subscription <subscription-id>"
        exit 1
    }
    Write-Note "Choose a different subscription:"
    $acct = Select-Subscription
}
Write-Ok "Subscription verified: $($acct.name) ($($acct.id))"

# ---------------------------------------------------------------------------
# Lightweight branches that run against an EXISTING install and exit — no
# provider registration, provisioning, RBAC, or full-install questions below:
#   Cars        -> add/remove an extra vehicle's Ford app (settings + token).
#   BackendOnly -> rebuild & redeploy the Functions code only.
# ---------------------------------------------------------------------------
if ($Mode -eq 'Cars')        { Invoke-ManageFordApps;   exit 0 }
if ($Mode -eq 'BackendOnly') { Invoke-BackendDeployOnly; exit 0 }

# ---------------------------------------------------------------------------
# Register required resource providers. Brand-new subscriptions have NONE
# registered, and Azure confusingly returns "SubscriptionNotFound" when you
# try to create a resource whose provider isn't registered. We kick off
# registration now (async) so it overlaps with the questions below, then wait
# for completion right before provisioning.
# ---------------------------------------------------------------------------
$RequiredProviders = @(
    'Microsoft.Storage',
    'Microsoft.Web',
    'Microsoft.KeyVault',
    'Microsoft.NotificationHubs',
    'Microsoft.Insights',
    'Microsoft.OperationalInsights',
    'Microsoft.ManagedIdentity'
)
Write-Step "Ensuring required resource providers are registered"
foreach ($p in $RequiredProviders) {
    $state = (& az provider show -n $p --query registrationState -o tsv 2>$null)
    if ($state -ne 'Registered') {
        & az provider register -n $p --only-show-errors 2>$null | Out-Null
        Write-Note "Registering $p (was: $state) ..."
    }
}
Write-Ok "Registration requested (continues in the background)."

# ===========================================================================
# 1) Gather answers
# ===========================================================================
Write-Host ""
Write-Step "A few questions"

if (-not $Suffix)   { $Suffix   = Read-Default "Unique resource suffix (lowercase, letters/digits)" "demo" }
$Suffix = ($Suffix -replace '[^a-z0-9]', '').ToLower()
if (-not $Location) { $Location = Read-Default "Azure region" "eastus" }
if (-not $FordSlug) { $FordSlug = Read-Default "Ford app slug" "primary" }
# The OAuth handshake stores slugs lowercase; keep config in the same canonical
# form so the re-auth screen matches and the account shows as primary.
$FordSlug = $FordSlug.Trim().ToLower()

Write-Note "Reuse your EXISTING Ford developer app's credentials (GoHenry does not create one)."
if (-not $FordClientId)     { $FordClientId     = Read-Default "Ford client id (can leave blank now, set later)" "" }
if (-not $FordClientSecret) { $FordClientSecret = Read-Secret  "Ford client secret (input hidden; Enter to skip)" $FordClientSecret }

# --- Extra Ford apps (one per additional vehicle) -------------------------
# A Ford OAuth token only unlocks ONE vehicle, so each car you want to track
# needs its OWN Ford developer app (its own client id + secret) registered
# against the SAME callback URL. GoHenry supports up to 5 cars total: the
# primary above plus up to 4 extras here. Each extra is identified by a short
# lowercase "slug" (e.g. car2). Leave the slug blank to stop adding cars.
$ExtraApps = @()
$takenSlugs = New-Object System.Collections.Generic.HashSet[string]
[void]$takenSlugs.Add($FordSlug)
if ($ExtraFordApps) {
    foreach ($a in $ExtraFordApps) {
        $s = ([string]$a.Slug).Trim().ToLower()
        if (-not $s -or -not $takenSlugs.Add($s)) { continue }
        $ExtraApps += [pscustomobject]@{ Slug = $s; ClientId = [string]$a.ClientId; ClientSecret = [string]$a.ClientSecret }
    }
} elseif (-not $NonInteractive) {
    Write-Host ""
    Write-Note "A Ford token only unlocks ONE vehicle. Add a SEPARATE Ford app per extra car."
    if (Read-YesNo "Add another vehicle now (separate Ford app)?" $false) {
        for ($n = 2; $n -le 5; $n++) {
            $defSlug = "car$n"
            $slug = Read-Default "  Vehicle $n slug (blank to stop)" $defSlug
            $slug = ($slug -replace '[^a-z0-9]', '').ToLower()
            if (-not $slug) { break }
            if (-not $takenSlugs.Add($slug)) { Write-Note "  Slug '$slug' already used - skipping."; continue }
            $cid = Read-Default "  Ford client id for '$slug'" ""
            $sec = Read-Secret  "  Ford client secret for '$slug' (input hidden)" ""
            $ExtraApps += [pscustomobject]@{ Slug = $slug; ClientId = $cid; ClientSecret = $sec }
            if ($ExtraApps.Count -ge 4) { Write-Note "  Reached the 5-vehicle limit."; break }
            if (-not (Read-YesNo "  Add another vehicle?" $false)) { break }
        }
    }
}

# Derived, globally-unique names.
$Rg        = "rg-gohenry-$Suffix"
$Storage   = "stgohenry$Suffix"
$KeyVault  = "kv-gohenry-$Suffix"
$NhNs      = "nh-gohenry-$Suffix"
$NhHub     = "gohenry"
$FuncApp   = "func-gohenry-$Suffix"
$FordOAuth = "https://api.vehicle.ford.com/"
$FordApi   = "https://api.vehicle.ford.com/fcon-query/"
$Callback  = "https://$FuncApp.azurewebsites.net/api/oauth/callback"

Write-Host ""
Write-Step "Plan"
Write-Host "    Resource group : $Rg ($Location)"
Write-Host "    Storage        : $Storage   (Tables+Queues, the only datastore)"
Write-Host "    Key Vault      : $KeyVault  (Ford refresh tokens)"
Write-Host "    Notification   : $NhNs / $NhHub  (FCM push)"
Write-Host "    Function App   : $FuncApp  (.NET 8 isolated)"
Write-Host "    Ford slug      : $FordSlug"
if ($ExtraApps.Count -gt 0) {
    Write-Host "    Extra Ford apps: $(($ExtraApps | ForEach-Object { $_.Slug }) -join ', ')  ($($ExtraApps.Count) more vehicle(s))"
}
Write-Host "    OAuth callback : $Callback"
Write-Host ""
if (-not (Read-YesNo "Create/update these resources now?" $true)) { Write-Note "Aborted by user."; exit 0 }

# ===========================================================================
# 2) Provision (idempotent)
# ===========================================================================
# Block until every required provider is fully Registered. This prevents the
# misleading "SubscriptionNotFound" failure on the first create call.
Write-Step "Waiting for resource providers to finish registering"
foreach ($p in $RequiredProviders) {
    $tries = 0
    while ((& az provider show -n $p --query registrationState -o tsv 2>$null) -ne 'Registered') {
        Start-Sleep -Seconds 5
        $tries++
        if ($tries -ge 72) {   # ~6 minutes per provider
            Write-Err "Resource provider '$p' did not reach 'Registered' in time."
            Write-Note "Finish it manually, then re-run this script:"
            Write-Host "    az provider register -n $p --wait"
            exit 1
        }
    }
}
Write-Ok "All required providers are registered."

Write-Step "Resource group"
Invoke-Az group create -n $Rg -l $Location -o none | Out-Null
Write-Ok "ok"

Write-Step "Storage account (Standard_LRS)"
if (-not (Test-AzExists storage account show -n $Storage -g $Rg)) {
    Invoke-Az storage account create -n $Storage -g $Rg -l $Location --sku Standard_LRS --kind StorageV2 --min-tls-version TLS1_2 -o none | Out-Null
}
Write-Ok "ok"

Write-Step "Key Vault (RBAC)"
if (-not (Test-AzExists keyvault show -n $KeyVault -g $Rg)) {
    Invoke-Az keyvault create -n $KeyVault -g $Rg -l $Location --enable-rbac-authorization true -o none | Out-Null
}
Write-Ok "ok"

Write-Step "Notification Hubs namespace + hub (Free)"
& az notification-hub namespace create -g $Rg --name $NhNs --location $Location --sku Free -o none 2>$null | Out-Null
& az notification-hub create -g $Rg --namespace-name $NhNs --name $NhHub --location $Location -o none 2>$null | Out-Null
Write-Ok "ok"

Write-Step "Function App (.NET 8 isolated, Linux)"
if (-not (Test-AzExists functionapp show -n $FuncApp -g $Rg)) {
    # Try Flex Consumption first; fall back to classic Consumption if unavailable in the region.
    $created = $false
    try {
        Invoke-Az functionapp create -n $FuncApp -g $Rg --flexconsumption-location $Location --runtime dotnet-isolated --runtime-version 8.0 --storage-account $Storage -o none | Out-Null
        $created = $true
    } catch {
        Write-Note "Flex Consumption unavailable here; using classic Consumption."
    }
    if (-not $created) {
        Invoke-Az functionapp create -n $FuncApp -g $Rg --consumption-plan-location $Location --os-type Linux --runtime dotnet-isolated --runtime-version 8.0 --functions-version 4 --storage-account $Storage -o none | Out-Null
    }
}
Write-Step "Enabling managed identity"
Invoke-Az functionapp identity assign -n $FuncApp -g $Rg -o none | Out-Null
Write-Ok "ok"

# ===========================================================================
# 3) RBAC for the managed identity (no connection secrets needed for KV)
# ===========================================================================
Write-Step "Granting the Function App's identity data-plane roles"
$mi  = Invoke-Az functionapp identity show -n $FuncApp -g $Rg --query principalId -o tsv
$sub = $acct.id
$kvId = "/subscriptions/$sub/resourceGroups/$Rg/providers/Microsoft.KeyVault/vaults/$KeyVault"
$stId = "/subscriptions/$sub/resourceGroups/$Rg/providers/Microsoft.Storage/storageAccounts/$Storage"
foreach ($role in @(
    @{ name = "Key Vault Secrets Officer";        scope = $kvId },
    @{ name = "Storage Table Data Contributor";   scope = $stId },
    @{ name = "Storage Queue Data Contributor";   scope = $stId }
)) {
    & az role assignment create --assignee-object-id $mi --assignee-principal-type ServicePrincipal --role $role.name --scope $role.scope -o none 2>$null | Out-Null
}
Write-Ok "ok (role propagation can take ~1 minute)"

# ===========================================================================
# 4) App settings (Azure uses '__' which the host maps to ':')
# ===========================================================================
Write-Step "Applying app settings"
$storageConn = Invoke-Az storage account show-connection-string -n $Storage -g $Rg --query connectionString -o tsv
$nhConn = & az notification-hub authorization-rule list-keys -g $Rg --namespace-name $NhNs --notification-hub-name $NhHub --name DefaultFullSharedAccessSignature --query primaryConnectionString -o tsv 2>$null
if (-not $nhConn) { $nhConn = "" }
$kvUri = "https://$KeyVault.vault.azure.net/"

# Flex Consumption configures FUNCTIONS_WORKER_RUNTIME and AzureWebJobsStorage itself
# (at create time) and REJECTS them as app settings. Only set them on classic plans.
$skuTier = Invoke-Az functionapp show -n $FuncApp -g $Rg --query "properties.sku" -o tsv
$isFlex  = ($skuTier -ieq 'FlexConsumption')

$settings = @()
if (-not $isFlex) {
    $settings += "FUNCTIONS_WORKER_RUNTIME=dotnet-isolated"
    $settings += "AzureWebJobsStorage=$storageConn"
} else {
    Write-Note "Flex Consumption detected - skipping platform-managed FUNCTIONS_WORKER_RUNTIME / AzureWebJobsStorage."
}
$settings += @(
    "KeyVaultUri=$kvUri",
    "Ford__Slug=$FordSlug",
    "Ford__ClientId=$FordClientId",
    "Ford__ClientSecret=$FordClientSecret",
    "Ford__OAuthBaseUrl=$FordOAuth",
    "Ford__ApiBaseUrl=$FordApi",
    "Ford__RedirectUri=$Callback",
    "Ford__Scope=access",
    "NotificationHub__ConnectionString=$nhConn",
    "NotificationHub__Name=$NhHub"
)
# Extra Ford apps -> Ford__Apps__{slug}__ClientId / __ClientSecret. The backend
# inherits OAuthBaseUrl/ApiBaseUrl/RedirectUri/Scope from the primary, so each
# extra app only needs its own client id + secret. They share the SAME callback.
foreach ($a in $ExtraApps) {
    $settings += "Ford__Apps__$($a.Slug)__ClientId=$($a.ClientId)"
    $settings += "Ford__Apps__$($a.Slug)__ClientSecret=$($a.ClientSecret)"
}
Invoke-Az functionapp config appsettings set -n $FuncApp -g $Rg --settings @settings -o none | Out-Null
Write-Ok "ok"
if (-not $FordClientId) { Write-Note "Ford client id is blank - re-run later with -FordClientId/-FordClientSecret, or set in the Portal." }
if ($ExtraApps.Count -gt 0) {
    Write-Note "Configured $($ExtraApps.Count) extra Ford app(s): $(($ExtraApps | ForEach-Object { $_.Slug }) -join ', '). Each shows as its own card on the app's Ford Re-Authorization screen - tap Link on each to authorize that vehicle."
    foreach ($a in $ExtraApps) { if (-not $a.ClientId) { Write-Note "  '$($a.Slug)' has no client id yet - set Ford__Apps__$($a.Slug)__ClientId / __ClientSecret in the Portal." } }
}

# ===========================================================================
# 5) Deploy the Functions code
# ===========================================================================
$doDeploy = -not $SkipDeploy
if ($doDeploy -and -not $NonInteractive) { $doDeploy = Read-YesNo "Build and deploy the backend code now?" $true }
if ($doDeploy) {
    Write-Step "Publishing GoHenry.Api to $FuncApp (this can take a few minutes)"
    Invoke-FuncPublish $FuncApp
} else {
    Write-Note "Skipped code deploy. Later: cd backend/src/GoHenry.Api; func azure functionapp publish $FuncApp --dotnet-isolated"
}

# ===========================================================================
# 6) Summary + next steps
# ===========================================================================
$funcKey = & az functionapp keys list -n $FuncApp -g $Rg --query "functionKeys.default" -o tsv 2>$null
if (-not $funcKey) { $funcKey = & az functionapp keys list -n $FuncApp -g $Rg --query "masterKey" -o tsv 2>$null }

Write-Host ""
Write-Host "  ===================== GoHenry is provisioned =====================" -ForegroundColor Green
Write-Host ""
Write-Host "  Base URL        : https://$FuncApp.azurewebsites.net/api/"
Write-Host "  OAuth callback  : $Callback"
Write-Host "  Function key    : $(if ($funcKey) { $funcKey } else { '(open Portal -> Function App -> App keys)' })"
Write-Host ""
Write-Host "  Key endpoints (all under the Base URL, function-key protected):" -ForegroundColor Cyan
Write-Host "      GET  fleet/vehicles                         list the user's cars"
Write-Host "      GET  fleet/telemetry/{vin}                  latest snapshot (+ active road trip)"
Write-Host "      GET  fleet/notifications/{vin}              notify prefs"
Write-Host "      GET  fleet/pollsettings                     Ford poll cadence (1-10 min)"
Write-Host "      GET  fleet/roadtripsettings                 road-trip auto-start + auto-close"
Write-Host "      GET  fleet/roadtrips/{vin}                  list a car's road trips"
Write-Host "      POST fleet/roadtrips/{vin}/start            start a road trip"
Write-Host "      POST fleet/roadtrips/{vin}/stop             stop the active road trip"
Write-Host "      GET  fleet/roadtrips/{vin}/{id}             road-trip detail + timeline"
Write-Host "      POST fleet/roadtrips/{vin}/{id}/rename      rename a road trip"
Write-Host "      DEL  fleet/roadtrips/{vin}/{id}             delete a road trip + timeline"
Write-Host ""
Write-Host "  Paste into app\local.properties:" -ForegroundColor Cyan
Write-Host "      backend.baseUrl=https://$FuncApp.azurewebsites.net/api/"
Write-Host "      backend.userId=<pick any stable id, e.g. henry>"
Write-Host "      backend.functionKey=$funcKey"
Write-Host ""
Write-Host "  MANUAL steps the installer cannot do for you:" -ForegroundColor Yellow
Write-Host "    1. Register this Redirect URI in the Ford developer portal:"
Write-Host "         $Callback"
Write-Host "    2. Create your OWN Firebase project (Android package com.gohenry.app),"
Write-Host "       download google-services.json into app\app\, and upload the FCM v1"
Write-Host "       service-account JSON to the Notification Hub:"
Write-Host "         Portal -> $NhHub -> Google (FCM v1)"
Write-Host ""
Write-Host "  Then build the app (app\README.md), link Ford in-app, and your cars appear."
Write-Host ""
