# GoHenry provisioning (`gohenry.sh`)

> **On Windows?** Prefer the **interactive PowerShell installer**
> [`Install-GoHenry.ps1`](Install-GoHenry.ps1) — it opens with a 3-way menu
> (**1** full install · **2** backend code only · **3** add/remove cars) and asks
> only for the inputs that option needs. See the step-by-step guide
> [`../../GUIDES/install-backend.md`](../../GUIDES/install-backend.md). The `gohenry.sh`
> Bash script documented here is the macOS/Linux equivalent (its menu offers the same
> operations: `full`, `deploy`, `add-ford-app`, `remove-ford-app`).

`gohenry.sh` stands up the **entire** GoHenry backend in Azure with one command.
Everything lands in its own resource group (`rg-gohenry-<suffix>`) and shares **no**
resources with FleetFoot / SashaSync / QoastQurrent. There is **no SQL** — Azure
Table Storage is the only datastore.

## Prerequisites

- [Azure CLI](https://learn.microsoft.com/cli/azure/install-azure-cli) (`az`), logged in: `az login`
- [Azure Functions Core Tools v4](https://learn.microsoft.com/azure/azure-functions/functions-run-local) (`func`) — only needed for `deploy`
- .NET 8 SDK (to build the Functions code)
- A bash shell (Git Bash / WSL on Windows)

## Configure

Copy the example env file, fill in your Ford developer credentials, and pick a
globally-unique suffix:

```bash
cp gohenry.env.example gohenry.env
# edit gohenry.env
```

| Variable | Meaning |
| --- | --- |
| `GOHENRY_SUFFIX` | Short unique tag used in all global resource names |
| `GOHENRY_LOCATION` | Azure region (default `eastus`) |
| `FORD_SLUG` | App slug (default `primary`) |
| `FORD_CLIENT_ID` / `FORD_CLIENT_SECRET` | **Reused** Ford developer app credentials |
| `FORD_OAUTH_BASE` / `FORD_API_BASE` | Ford endpoints (defaults provided) |

## Run

```bash
./gohenry.sh full        # infra + settings + deploy, end to end
```

Or step by step / re-run individual stages (all idempotent):

| Command | What it does |
| --- | --- |
| `./gohenry.sh infra` | Create RG, Storage, Key Vault, Notification Hub, Function App + managed identity |
| `./gohenry.sh settings` | Apply RBAC + all app settings |
| `./gohenry.sh deploy` | Build and publish the Functions code |
| `./gohenry.sh rebuild` | Soft refresh: reapply settings + redeploy |
| `./gohenry.sh status` | Show resources and endpoint URLs |
| `./gohenry.sh reauth` | Print the Ford OAuth callback URL to register |
| `./gohenry.sh add-ford-app <slug> [id] [secret]` | Add ONE extra car's Ford app — writes only `Ford__Apps__<slug>__*`, no other resource touched |
| `./gohenry.sh remove-ford-app <slug>` | Remove ONE extra car — deletes its `Ford__Apps__<slug>__*` settings + matching Key Vault token, nothing else |
| `./gohenry.sh teardown` | Delete the whole resource group |
| `./gohenry.sh` | Interactive menu |

## Resources created

| Resource | SKU / Tier | Purpose |
| --- | --- | --- |
| Resource Group | — | Container for everything |
| Storage Account | Standard_LRS | Table + Queue (the only datastore) |
| Key Vault | Standard, RBAC | Per-user Ford refresh tokens |
| Notification Hubs ns + hub | Free | FCM push to the app |
| Function App | Flex Consumption, .NET 8 isolated, Linux | The backend |

The managed identity is granted **Key Vault Secrets Officer** and **Storage
Table/Queue Data Contributor**.

## What the script does NOT do (manual, one-time)

1. **Register the OAuth callback URL** in the Ford developer portal. The script
   prints the exact URL (`https://<funcapp>.azurewebsites.net/api/oauth/callback`).
2. **Upload your Firebase FCM v1 service-account JSON** to the Notification Hub
   (Portal → your hub → *Google (FCM v1)*). GoHenry uses its **own** Firebase
   project — never FleetFoot's.

After both manual steps, link a Ford account by POSTing to `/api/oauth/start` and
following the returned URL.
