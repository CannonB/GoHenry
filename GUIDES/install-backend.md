# Install the GoHenry backend (step-by-step)

This is the **fast, guided** path to standing up the GoHenry cloud backend on Windows.
It uses an **interactive PowerShell installer** that asks you a few questions and does
everything else — create the Azure resources, wire security, set configuration, and
deploy the code. There is **no SQL** anywhere.

> Prefer no terminal at all? Use the browser-only walkthrough:
> [`azure-setup-guide.md`](azure-setup-guide.md).
> On macOS/Linux, or want a Bash version? Use
> [`../backend/scripts/README.md`](../backend/scripts/README.md) (`gohenry.sh`).

---

## What you need first

1. **Azure CLI** — <https://aka.ms/installazurecli>
2. **Azure Functions Core Tools v4** — `npm i -g azure-functions-core-tools@4 --unsafe-perm true`
3. **.NET 8 SDK** (or newer) — <https://dotnet.microsoft.com/download>
4. An **Azure subscription** you can create resources in.
5. Your **Ford developer app** *client id* and *client secret* (reused — GoHenry does
   not create a Ford app). You can also add these later.

The installer checks 1–2 for you and tells you if anything is missing.

---

## Step 1 — Open PowerShell in the scripts folder

```powershell
cd "C:\Users\terrenca\OneDrive - Microsoft\FY26\GHCLI\GoHenry\backend\scripts"
```

If your machine blocks local scripts, allow them for this session only:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

## Step 2 — Run the installer

```powershell
.\Install-GoHenry.ps1
```

It first asks **what you want to do** — pick one of three options (each then asks
**only** for the inputs that option needs):

| Option | What it does | What it asks for |
| --- | --- | --- |
| **1. Full install** | Provisions the whole Azure backend and deploys the code. | Identity, subscription, suffix, region, Ford slug + client id/secret, extra cars. |
| **2. Backend code only** | Rebuilds & redeploys the Functions **code** to an existing Function App. Touches **no** Azure resources or settings. | Just your install **suffix**. |
| **3. Add / remove cars** | Adds or removes an extra vehicle's Ford app (settings + token only). | Your **suffix**, then **add** (slug + client id/secret) or **remove** (slug). |

> Skip the menu with `-Mode Full`, `-Mode BackendOnly`, or `-Mode Cars`. Passing
> `-ExtraFordApps`/`-RemoveFordApps` still implies `-Mode Cars`.

For a **Full install** it then asks, in order:

| Question | What to enter |
| --- | --- |
| Confirm identity | Shows the signed-in **user** and **tenant**; answer `y` only if both are correct. |
| Use this subscription? | Confirms your Azure subscription and lets you choose another. |
| **Unique resource suffix** | A short lowercase tag (e.g. `henry`) used in resource names. Must be globally unique-ish for storage/vault/hub. |
| **Azure region** | e.g. `eastus`, `westus2`. |
| **Ford app slug** | Usually `primary`. |
| **Ford client id** | From your Ford developer app (or leave blank to set later). |
| **Ford client secret** | Hidden input (or leave blank to set later). |
| Confirm the plan | Review the resource list, then `y` to create. |
| Deploy the code now? | `y` builds and publishes the backend. |

> **Type `EXIT` at any prompt** to quit cleanly — nothing is created or changed until
> you confirm the plan.

That's it — the script creates the resource group, Storage, Key Vault, Notification
Hub, and the Function App; grants the app's managed identity the right roles; applies
all settings; and deploys the code.

> **Re-runnable.** Every step is idempotent. If something fails (e.g. a name was
> taken, or you skipped Ford creds), fix it and run the script again — it updates in
> place rather than duplicating resources.

### Wrong account or tenant?

The installer validates your Azure identity up front. If it's not the right user or
tenant, answer `n` (or pin the expected identity, below) and follow the printed steps:

```powershell
az logout
az login --tenant <your-tenant-id-or-domain>
# if a browser won't open:
az login --tenant <your-tenant-id-or-domain> --use-device-code
```

You can pin the expected identity so the script auto-validates and quits on a mismatch
(useful for unattended runs):

```powershell
.\Install-GoHenry.ps1 -ExpectedUser "you@contoso.com" -ExpectedTenant "<tenant-id-or-domain>"
```

### Non-interactive / scripted run

```powershell
.\Install-GoHenry.ps1 -Suffix henry -Location eastus `
    -FordSlug primary -FordClientId "<id>" -FordClientSecret "<secret>" -NonInteractive
```

Add `-SkipDeploy` to provision infrastructure without publishing code.

## Step 3 — Copy the output into the app

When it finishes, the installer prints a block like:

```
Base URL        : https://func-gohenry-henry.azurewebsites.net/api/
OAuth callback  : https://func-gohenry-henry.azurewebsites.net/api/oauth/callback
Function key    : <your default host key>

Paste into app\local.properties:
    backend.baseUrl=https://func-gohenry-henry.azurewebsites.net/api/
    backend.userId=<pick any stable id, e.g. henry>
    backend.functionKey=<your default host key>
```

Put those three `backend.*` values into `GoHenry\app\local.properties` (see
[`../app/README.md`](../app/README.md)).

## Step 4 — Two manual steps the installer can't do

These require websites outside Azure, so you do them by hand (the installer prints the
exact values):

1. **Register the OAuth callback URL** in your Ford developer portal — paste the
   `OAuth callback` URL from Step 3 as a Redirect URI.
2. **Set up your own Firebase project** for push:
   - Create a Firebase project, add an **Android app** with package
     **`com.gohenry.app`**, and download its `google-services.json` into
     `GoHenry\app\app\`.
   - Generate an **FCM v1 service-account key** and upload it to your Notification Hub
     (Azure Portal → your `gohenry` hub → **Google (FCM v1)**).

## Step 5 — Build the app and link Ford

Follow [`../app/README.md`](../app/README.md) to build/install the Android app, then in
the app open **Alerts & re-auth → Ford Re-Authorization → link**, complete Ford
sign-in, and your cars appear within a couple of minutes.

---

## Tracking more than one vehicle (up to 5)

A Ford OAuth token only unlocks **one vehicle**. To track several cars you register a
**separate Ford developer app** (its own *client id* + *client secret*) for each extra
car, all pointing at the **same** OAuth callback URL. GoHenry supports the primary app
plus up to **4 extras = 5 cars total**.

1. In your Ford developer portal, create one extra Ford app per additional car and copy
   each app's client id + secret. Register the **same** callback URL on every app:
   `https://func-gohenry-<suffix>.azurewebsites.net/api/oauth/callback`.
2. Re-run the installer. After the primary Ford questions it asks
   **"Add another vehicle now (separate Ford app)?"** — answer **y** and, for each car,
   give a short slug (e.g. `car2`), its client id, and its client secret. Repeat up to 4
   times. Each extra app is written as `Ford__Apps__<slug>__ClientId` /
   `Ford__Apps__<slug>__ClientSecret` (the base URLs, callback and scope are inherited).
3. In the app's **Ford Re-Authorization** screen each configured app shows as its own
   card. Tap **Link** on each card and sign in to that car's Ford account. The vehicle
   appears in the carousel within a couple of minutes.

Non-interactive equivalent:

```powershell
.\Install-GoHenry.ps1 -FordClientId "<id1>" -FordClientSecret "<secret1>" `
    -ExtraFordApps @(
        @{ Slug='car2'; ClientId='<id2>'; ClientSecret='<secret2>' },
        @{ Slug='car3'; ClientId='<id3>'; ClientSecret='<secret3>' }
    ) -NonInteractive
```

(Linux/macOS `gohenry.sh`: set `FORD_EXTRA_APPS="car2 car3"` plus
`FORD_CAR2_CLIENT_ID` / `FORD_CAR2_CLIENT_SECRET` etc. in `gohenry.env`, then run the
`settings` step.)

---

## Re-run / change things later

| You want to… | Command |
| --- | --- |
| Add or fix Ford credentials | `.\Install-GoHenry.ps1 -FordClientId "<id>" -FordClientSecret "<secret>"` |
| Add / remove a vehicle (interactive) | `.\Install-GoHenry.ps1 -Mode Cars -Suffix <suffix>` |
| Add another vehicle (extra Ford app) | `.\Install-GoHenry.ps1 -ExtraFordApps @(@{ Slug='car2'; ClientId='<id>'; ClientSecret='<secret>' })` |
| Re-deploy code only | `.\Install-GoHenry.ps1 -Mode BackendOnly -Suffix <suffix>` |
| See everything you created | Azure Portal → resource group `rg-gohenry-<suffix>` |
| Delete everything | `az group delete -n rg-gohenry-<suffix> --yes` |

## Troubleshooting

- **"Missing required tool"** — install the Azure CLI and Functions Core Tools (top of
  this page), reopen PowerShell, and re-run.
- **`SubscriptionNotFound` when creating Storage (or another resource)** — this almost
  always means a **resource provider isn't registered** in a new subscription (Azure
  returns a misleading "subscription not found"). The installer now registers all
  required providers automatically and waits for them. To do it by hand:
  ```powershell
  foreach ($p in 'Microsoft.Storage','Microsoft.Web','Microsoft.KeyVault',
                 'Microsoft.NotificationHubs','Microsoft.Insights',
                 'Microsoft.OperationalInsights','Microsoft.ManagedIdentity') {
      az provider register -n $p --wait
  }
  ```
- **Storage/Vault name already taken** — re-run with a different `-Suffix`.
- **Flex Consumption not available in region** — the installer automatically falls
  back to classic Consumption; no action needed.
- **`...FUNCTIONS_WORKER_RUNTIME... is invalid` for Flex Consumption sites** — Flex
  Consumption manages `FUNCTIONS_WORKER_RUNTIME` and `AzureWebJobsStorage` itself, so
  they can't be set as app settings. The installer now detects Flex and skips them
  automatically; just re-run.
- **App shows 401** — `backend.functionKey` or `backend.userId` is wrong in
  `local.properties`.
- **"needs re-auth"** — re-link Ford from the app's Alerts screen (the OAuth callback
  URL must be registered in the Ford portal first).
