# GoHenry — first-time Azure setup (browser-only, no terminal)

This guide walks a **first-time** hobbyist through standing up the GoHenry backend
**entirely in the Azure Portal and the Ford/Firebase websites** — no command line
required. Budget **45–60 minutes**. If you're comfortable with a terminal, the
one-command path in [`../backend/scripts/README.md`](../backend/scripts/README.md) is
much faster.

Everything you create here is **brand new and independent** — it shares nothing with
any other app. Expected cost at hobby scale: **~$1–3/month**.

---

## What you'll create

1. A **Resource Group** (a folder for everything)
2. A **Storage account** (the only datastore — no SQL)
3. A **Key Vault** (holds your Ford refresh tokens)
4. A **Notification Hub** (sends push to your phones)
5. A **Function App** (the backend itself)
6. Two external setups: a **Ford developer app** redirect URL, and a **Firebase**
   project for push.

---

## Step 0 — Sign in

Go to <https://portal.azure.com> and sign in. Make sure the subscription you'll use is
selected (top-right account menu → *Switch directory* if needed).

---

## Step 1 — Resource group

1. Search bar → **Resource groups** → **+ Create**.
2. Name: `rg-gohenry-demo` (pick your own suffix), Region: pick one near you (e.g.
   *East US*). → **Review + create** → **Create**.

> Keep everything below in this **same region** where possible.

---

## Step 2 — Storage account (the datastore)

1. Search → **Storage accounts** → **+ Create**.
2. Resource group: `rg-gohenry-demo`. Name: `stgohenrydemo` (lowercase, globally
   unique, no dashes). Performance: **Standard**. Redundancy: **LRS** (cheapest).
3. **Review + create** → **Create**.

That's it — GoHenry creates its tables automatically on first run. There is **no
database to configure**.

---

## Step 3 — Key Vault (Ford tokens)

1. Search → **Key vaults** → **+ Create**.
2. Resource group + a globally-unique name like `kv-gohenry-demo`.
3. On **Access configuration**, choose **Azure role-based access control (RBAC)**.
4. **Review + create** → **Create**.

You don't add any secrets by hand — the backend writes the Ford refresh token here
when you link your account.

---

## Step 4 — Notification Hub (push)

1. Search → **Notification Hub Namespaces** → **+ Create**.
2. Resource group, a unique **Namespace** name like `nh-gohenry-demo`, a **Hub** name
   `gohenry`, Pricing tier **Free**. → **Create**.
3. After it deploys, open the **namespace → your `gohenry` hub**. You'll come back here
   in Step 7 to add Firebase, and you'll need a connection string in Step 6:
   - Namespace → **Access Policies** → `DefaultFullSharedAccessSignature` → copy the
     **Connection string**. Keep it handy.

---

## Step 5 — Function App (the backend)

1. Search → **Function App** → **+ Create** → **Consumption** (or **Flex
   Consumption** if offered).
2. Resource group; a globally-unique name like `func-gohenry-demo`.
3. Runtime stack: **.NET**, Version: **8 (Isolated)**. Operating System: **Linux**.
   Region: same as the rest. Storage account: pick the `stgohenrydemo` you made.
4. **Review + create** → **Create**.
5. When it's done, open the Function App → **Settings → Identity** → **System
   assigned** → toggle **On** → **Save**. This gives the backend an identity you'll
   grant access to Key Vault and Storage next.

### Grant the backend access (RBAC)

Do this twice — once on the Storage account, once on the Key Vault:

1. Open the **Storage account** → **Access control (IAM)** → **+ Add → Add role
   assignment**.
   - Role: **Storage Table Data Contributor** → Next → **Managed identity** → select
     your Function App → **Review + assign**.
   - Repeat for role **Storage Queue Data Contributor**.
2. Open the **Key Vault** → **Access control (IAM)** → **+ Add → Add role
   assignment**.
   - Role: **Key Vault Secrets Officer** → Next → **Managed identity** → select your
     Function App → **Review + assign**.

---

## Step 6 — Tell the backend its settings

Open the Function App → **Settings → Environment variables → App settings**. Add each
of these (**+ Add** for each), then **Apply**. Note the **double underscores** `__`:

| Name | Value |
| --- | --- |
| `Ford__Slug` | `primary` |
| `Ford__ClientId` | *your Ford developer app client id* |
| `Ford__ClientSecret` | *your Ford developer app client secret* |
| `Ford__OAuthBaseUrl` | `https://api.vehicle.ford.com/` |
| `Ford__ApiBaseUrl` | `https://api.vehicle.ford.com/` |
| `Ford__RedirectUri` | `https://func-gohenry-demo.azurewebsites.net/api/oauth/callback` (use **your** app name) |
| `Ford__Scope` | `access` |
| `KeyVaultUri` | `https://kv-gohenry-demo.vault.azure.net/` (use **your** vault name) |
| `NotificationHub__ConnectionString` | *the connection string from Step 4* |
| `NotificationHub__Name` | `gohenry` |

> The default `AzureWebJobsStorage` setting (created with the app) already points at
> your storage account — leave it.

---

## Step 7 — Firebase (your own push project)

GoHenry uses **its own** Firebase project — never another app's.

1. Go to <https://console.firebase.google.com> → **Add project** → name it (e.g.
   *GoHenry*) → create.
2. **Add app → Android.** Package name: **`com.gohenry.app`**. Register.
3. Download the **`google-services.json`** — you'll drop this into the Android app
   (see [`../app/README.md`](../app/README.md)).
4. In Firebase, open **Project settings → Service accounts → Generate new private
   key**. This downloads a **service-account JSON** for FCM v1.
5. Back in the Azure Portal: Notification Hub namespace → your `gohenry` hub →
   **Settings → Google (FCM v1)** → paste/upload the **service-account JSON** →
   **Save**.

---

## Step 8 — Register the callback in the Ford portal

In the Ford developer portal for your app, add this exact **Redirect URI**:

```
https://func-gohenry-demo.azurewebsites.net/api/oauth/callback
```

(Use **your** Function App name.) This must match `Ford__RedirectUri` from Step 6.

---

## Step 9 — Deploy the backend code

The Portal can't compile the code for you. Easiest options:

- **VS Code** with the *Azure Functions* extension → open `backend/src/GoHenry.Api`
  → right-click → **Deploy to Function App** → pick `func-gohenry-demo`.
- Or the terminal path (one line): see
  [`../backend/scripts/README.md`](../backend/scripts/README.md) →
  `./gohenry.sh deploy`.

---

## Step 10 — Get your function key + finish in the app

1. Function App → **Functions → App keys** (or any function → **Function Keys**) →
   copy the **default** host key. This is your `backend.functionKey`.
2. Decide an `x-user-id` for yourself — any stable string, e.g. `henry`. Each phone
   that should see the **same** cars uses the **same** `x-user-id`.
3. Follow [`../app/README.md`](../app/README.md) to build the app, drop in your
   `google-services.json`, set `backend.baseUrl` / `backend.userId` /
   `backend.functionKey` in `local.properties`, install it, and **link Ford in-app**.
   Your cars appear within a couple of minutes.

---

## Troubleshooting

- **No cars after linking** — wait ~2 min for the first poll, then pull-to-refresh.
  Check the Function App is running (**Overview → Running**).
- **401 in the app** — `x-user-id` is missing or the function key is wrong. Re-check
  `local.properties`.
- **"needs re-auth"** — re-link Ford from the app's Notifications screen.
- **No push** — confirm the FCM v1 service-account JSON is uploaded to the hub (Step
  7.5) and the app's `google-services.json` is the one for `com.gohenry.app`.
- **Tear it all down** — delete the **resource group**; everything goes with it.
