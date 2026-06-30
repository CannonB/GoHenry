#!/usr/bin/env bash
# =============================================================================
#  gohenry.sh — one-shot, idempotent provisioner for the GoHenry backend.
#
#  GoHenry is a small hobby vehicle tracker. Its backend is a single .NET 8
#  isolated Azure Functions app whose ONLY datastore is Azure Table Storage —
#  there is NO SQL anywhere. Ford refresh tokens live in Key Vault; push goes
#  out through Azure Notification Hubs (FCM v1).
#
#  This script shares NO resources with FleetFoot / SashaSync / QoastQurrent.
#  Everything lands in its own resource group: rg-gohenry-<suffix>.
#
#  Usage:
#     ./gohenry.sh                 # interactive menu
#     ./gohenry.sh full            # infra + deploy + settings, end to end
#     ./gohenry.sh infra           # create Azure resources only
#     ./gohenry.sh settings        # (re)apply app settings + RBAC
#     ./gohenry.sh deploy          # build + publish the Functions code
#     ./gohenry.sh status          # show resource + endpoint summary
#     ./gohenry.sh reauth          # print the Ford OAuth callback URL
#     ./gohenry.sh add-ford-app <slug> [clientId] [clientSecret]
#                                  # add ONE extra car's Ford app (settings only)
#     ./gohenry.sh remove-ford-app <slug>
#                                  # remove ONE extra car's Ford app (settings + token)
#     ./gohenry.sh teardown        # delete the whole resource group
#     ./gohenry.sh rebuild         # soft: redeploy code + reapply settings
#
#  Configure by editing the CONFIG block below, or via env vars / gohenry.env.
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# CONFIG — override any of these via environment or a sibling gohenry.env file
# ---------------------------------------------------------------------------
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$HERE/gohenry.env" ]] && source "$HERE/gohenry.env"

SUFFIX="${GOHENRY_SUFFIX:-demo}"                 # unique-ish tag for global names
LOCATION="${GOHENRY_LOCATION:-eastus}"
RG="${GOHENRY_RG:-rg-gohenry-$SUFFIX}"

# Globally-unique names (lowercase, no dashes for storage)
STORAGE="${GOHENRY_STORAGE:-stgohenry${SUFFIX}}"
KEYVAULT="${GOHENRY_KEYVAULT:-kv-gohenry-$SUFFIX}"
NH_NAMESPACE="${GOHENRY_NH_NS:-nh-gohenry-$SUFFIX}"
NH_HUB="${GOHENRY_NH_HUB:-gohenry}"
FUNCAPP="${GOHENRY_FUNCAPP:-func-gohenry-$SUFFIX}"

# Ford developer app (REUSED from the existing Ford developer application).
# Slugs are stored lowercase by the OAuth handshake; normalize here so config
# matches and the account shows as primary on the re-auth screen.
FORD_SLUG="$(printf '%s' "${FORD_SLUG:-primary}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
FORD_CLIENT_ID="${FORD_CLIENT_ID:-}"
FORD_CLIENT_SECRET="${FORD_CLIENT_SECRET:-}"
FORD_OAUTH_BASE="${FORD_OAUTH_BASE:-https://api.vehicle.ford.com/}"
FORD_API_BASE="${FORD_API_BASE:-https://api.vehicle.ford.com/fcon-query/}"
FORD_SCOPE="${FORD_SCOPE:-access}"
# Extra Ford apps (one per additional vehicle, up to 4 more = 5 cars total).
# A Ford token only unlocks ONE vehicle, so each car needs its own Ford app.
# Set FORD_EXTRA_APPS to a space/comma separated list of slugs, e.g. "car2 car3",
# then provide FORD_<SLUG>_CLIENT_ID and FORD_<SLUG>_CLIENT_SECRET for each
# (slug uppercased, non-alphanumerics -> _). They share the SAME callback URL.
FORD_EXTRA_APPS="${FORD_EXTRA_APPS:-}"

API_PROJECT="${API_PROJECT:-$HERE/../src/GoHenry.Api/GoHenry.Api.csproj}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
c_blue() { printf "\033[1;34m%s\033[0m\n" "$*"; }
c_green() { printf "\033[1;32m%s\033[0m\n" "$*"; }
c_yellow() { printf "\033[1;33m%s\033[0m\n" "$*"; }
c_red() { printf "\033[1;31m%s\033[0m\n" "$*" >&2; }
step() { c_blue "==> $*"; }

require_cli() {
  command -v az >/dev/null 2>&1 || { c_red "Azure CLI (az) is required."; exit 1; }
  az account show >/dev/null 2>&1 || { c_red "Run 'az login' first."; exit 1; }
}

callback_url() { echo "https://${FUNCAPP}.azurewebsites.net/api/oauth/callback"; }

# Register resource providers required by GoHenry. New subscriptions have NONE
# registered, and Azure returns a misleading "SubscriptionNotFound" when you try
# to create a resource whose provider isn't registered. Idempotent + waits.
register_providers() {
  step "Ensuring required resource providers are registered"
  local providers=(
    Microsoft.Storage Microsoft.Web Microsoft.KeyVault
    Microsoft.NotificationHubs Microsoft.Insights
    Microsoft.OperationalInsights Microsoft.ManagedIdentity
  )
  local p state tries
  for p in "${providers[@]}"; do
    state="$(az provider show -n "$p" --query registrationState -o tsv 2>/dev/null)"
    if [ "$state" != "Registered" ]; then
      az provider register -n "$p" --only-show-errors >/dev/null 2>&1 || true
      tries=0
      until [ "$(az provider show -n "$p" --query registrationState -o tsv 2>/dev/null)" = "Registered" ]; do
        sleep 5; tries=$((tries+1))
        if [ "$tries" -ge 72 ]; then
          c_red "Provider $p did not register in time. Run: az provider register -n $p --wait"
          exit 1
        fi
      done
    fi
  done
}

# ---------------------------------------------------------------------------
# Resource creation (idempotent — every step is safe to re-run)
# ---------------------------------------------------------------------------
create_rg() {
  step "Resource group: $RG ($LOCATION)"
  az group create -n "$RG" -l "$LOCATION" -o none
}

create_storage() {
  step "Storage account (Table + Queue, Standard_LRS): $STORAGE"
  az storage account create -n "$STORAGE" -g "$RG" -l "$LOCATION" \
    --sku Standard_LRS --kind StorageV2 --min-tls-version TLS1_2 -o none
}

create_keyvault() {
  step "Key Vault (RBAC, for Ford refresh tokens): $KEYVAULT"
  az keyvault create -n "$KEYVAULT" -g "$RG" -l "$LOCATION" \
    --enable-rbac-authorization true -o none
}

create_notificationhub() {
  step "Notification Hubs namespace + hub (Free): $NH_NAMESPACE / $NH_HUB"
  az notification-hub namespace create -g "$RG" -l "$LOCATION" \
    --name "$NH_NAMESPACE" --sku Free -o none 2>/dev/null || \
  az notification-hub namespace create -g "$RG" --location "$LOCATION" \
    --namespace-name "$NH_NAMESPACE" --sku Free -o none
  az notification-hub create -g "$RG" --namespace-name "$NH_NAMESPACE" \
    --name "$NH_HUB" --location "$LOCATION" -o none 2>/dev/null || true
}

create_functionapp() {
  step "Function App (Flex Consumption, .NET 8 isolated, Linux): $FUNCAPP"
  if az functionapp show -n "$FUNCAPP" -g "$RG" >/dev/null 2>&1; then
    c_yellow "  Function App already exists — skipping create."
  else
    az functionapp create -n "$FUNCAPP" -g "$RG" \
      --flexconsumption-location "$LOCATION" \
      --runtime dotnet-isolated --runtime-version 8.0 \
      --storage-account "$STORAGE" -o none
  fi
  step "Enable system-assigned managed identity"
  az functionapp identity assign -n "$FUNCAPP" -g "$RG" -o none
}

# ---------------------------------------------------------------------------
# RBAC — managed identity gets Key Vault + Storage data-plane roles (no secrets)
# ---------------------------------------------------------------------------
assign_rbac() {
  step "Granting the Function App's managed identity data-plane roles"
  local mi sub kvId stId
  mi="$(az functionapp identity show -n "$FUNCAPP" -g "$RG" --query principalId -o tsv)"
  sub="$(az account show --query id -o tsv)"
  kvId="/subscriptions/$sub/resourceGroups/$RG/providers/Microsoft.KeyVault/vaults/$KEYVAULT"
  stId="/subscriptions/$sub/resourceGroups/$RG/providers/Microsoft.Storage/storageAccounts/$STORAGE"

  az role assignment create --assignee-object-id "$mi" --assignee-principal-type ServicePrincipal \
    --role "Key Vault Secrets Officer" --scope "$kvId" -o none 2>/dev/null || true
  az role assignment create --assignee-object-id "$mi" --assignee-principal-type ServicePrincipal \
    --role "Storage Table Data Contributor" --scope "$stId" -o none 2>/dev/null || true
  az role assignment create --assignee-object-id "$mi" --assignee-principal-type ServicePrincipal \
    --role "Storage Queue Data Contributor" --scope "$stId" -o none 2>/dev/null || true
  c_green "  RBAC applied (propagation can take a minute)."
}

# ---------------------------------------------------------------------------
# App settings (config keys use '__' which the host maps to ':')
# ---------------------------------------------------------------------------
apply_settings() {
  step "Applying app settings"
  local storageConn nhConn kvUri redirect skuTier
  storageConn="$(az storage account show-connection-string -n "$STORAGE" -g "$RG" --query connectionString -o tsv)"
  nhConn="$(az notification-hub authorization-rule list-keys -g "$RG" \
    --namespace-name "$NH_NAMESPACE" --notification-hub-name "$NH_HUB" \
    --name DefaultFullSharedAccessSignature --query primaryConnectionString -o tsv 2>/dev/null || echo '')"
  kvUri="https://${KEYVAULT}.vault.azure.net/"
  redirect="$(callback_url)"

  # Flex Consumption configures FUNCTIONS_WORKER_RUNTIME and AzureWebJobsStorage
  # itself (at create time) and REJECTS them as app settings. Only set them on
  # classic Consumption plans.
  skuTier="$(az functionapp show -n "$FUNCAPP" -g "$RG" --query 'properties.sku' -o tsv 2>/dev/null)"
  local settings=()
  if [ "$skuTier" != "FlexConsumption" ]; then
    settings+=( "FUNCTIONS_WORKER_RUNTIME=dotnet-isolated" "AzureWebJobsStorage=$storageConn" )
  else
    c_yellow "  Flex Consumption detected — skipping platform-managed FUNCTIONS_WORKER_RUNTIME / AzureWebJobsStorage."
  fi
  settings+=(
    "KeyVaultUri=$kvUri"
    "Ford__Slug=$FORD_SLUG"
    "Ford__ClientId=$FORD_CLIENT_ID"
    "Ford__ClientSecret=$FORD_CLIENT_SECRET"
    "Ford__OAuthBaseUrl=$FORD_OAUTH_BASE"
    "Ford__ApiBaseUrl=$FORD_API_BASE"
    "Ford__RedirectUri=$redirect"
    "Ford__Scope=$FORD_SCOPE"
    "NotificationHub__ConnectionString=$nhConn"
    "NotificationHub__Name=$NH_HUB"
  )

  # Append extra Ford apps. The backend inherits OAuthBaseUrl/ApiBaseUrl/
  # RedirectUri/Scope from the primary, so each extra needs only id + secret.
  for raw in $(printf '%s' "$FORD_EXTRA_APPS" | tr ',' ' '); do
    slug="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
    [ -z "$slug" ] && continue
    envkey="$(printf '%s' "$slug" | tr '[:lower:]' '[:upper:]' | tr -c 'A-Z0-9' '_')"
    eval "cid=\${FORD_${envkey}_CLIENT_ID:-}"
    eval "sec=\${FORD_${envkey}_CLIENT_SECRET:-}"
    settings+=( "Ford__Apps__${slug}__ClientId=$cid" "Ford__Apps__${slug}__ClientSecret=$sec" )
  done

  az functionapp config appsettings set -n "$FUNCAPP" -g "$RG" -o none --settings "${settings[@]}"

  c_green "  Settings applied. OAuth callback URL: $redirect"
  [[ -z "$FORD_CLIENT_ID" ]] && c_yellow "  NOTE: FORD_CLIENT_ID is empty — set Ford creds in gohenry.env and re-run 'settings'."
}

# ---------------------------------------------------------------------------
# Deploy the Functions code
# ---------------------------------------------------------------------------
deploy_code() {
  step "Publishing GoHenry.Api to $FUNCAPP"
  command -v func >/dev/null 2>&1 || { c_red "Azure Functions Core Tools (func) is required to deploy."; exit 1; }
  local proj_dir
  proj_dir="$(cd "$(dirname "$API_PROJECT")" && pwd)"
  ( cd "$proj_dir" && func azure functionapp publish "$FUNCAPP" --dotnet-isolated )
}

# ---------------------------------------------------------------------------
# Status / reauth helpers
# ---------------------------------------------------------------------------
show_status() {
  require_cli
  step "GoHenry resource summary (RG: $RG)"
  az resource list -g "$RG" --query "[].{name:name,type:type}" -o table 2>/dev/null || c_yellow "  Resource group not found."
  echo
  c_green "Base URL:     https://${FUNCAPP}.azurewebsites.net/api/"
  c_green "OAuth start:  POST https://${FUNCAPP}.azurewebsites.net/api/oauth/start?app=$FORD_SLUG"
  c_green "OAuth callback (register in Ford portal): $(callback_url)"
}

print_reauth() {
  echo
  c_yellow "Register THIS exact redirect URI in the Ford developer portal:"
  c_green "    $(callback_url)"
  echo
  echo "Then from the app (or curl) start the handshake:"
  echo "    POST https://${FUNCAPP}.azurewebsites.net/api/oauth/start?app=$FORD_SLUG"
  echo "        header  x-user-id: <your-user-id>"
  echo "        query   code=<function key>"
}

teardown() {
  require_cli
  c_red "This DELETES the entire resource group: $RG"
  read -r -p "Type the resource group name to confirm: " confirm
  [[ "$confirm" == "$RG" ]] || { c_yellow "Aborted."; return; }
  az group delete -n "$RG" --yes --no-wait
  c_green "Delete requested (running in background)."
}

# ---------------------------------------------------------------------------
# Add / remove a single extra Ford app WITHOUT touching any other resource.
# A Ford token unlocks ONE vehicle, so each extra car is its own Ford developer
# app whose client id + secret live in Ford__Apps__<slug>__* on the Function App.
# These commands edit ONLY those settings (and, on removal, the matching Key
# Vault refresh-token secrets) — never storage, KV, the hub, the Function App,
# RBAC, or a code deploy.
# ---------------------------------------------------------------------------
add_ford_app() {
  require_cli
  local slug="${1:-}" cid="${2:-}" sec="${3:-}"
  slug="$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
  [[ -z "$slug" ]] && { read -r -p "Extra Ford app slug (e.g. car2): " slug; slug="$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"; }
  [[ -z "$slug" ]] && { c_red "A slug is required."; exit 1; }
  [[ "$slug" == "$FORD_SLUG" ]] && { c_red "'$slug' is the PRIMARY slug; set FORD_CLIENT_ID/SECRET and run 'settings' instead."; exit 1; }
  az functionapp show -n "$FUNCAPP" -g "$RG" >/dev/null 2>&1 || { c_red "Function App $FUNCAPP not found in $RG (check SUFFIX)."; exit 1; }
  [[ -z "$cid" ]] && read -r -p "  Ford client id for '$slug': " cid
  [[ -z "$sec" ]] && { read -r -s -p "  Ford client secret for '$slug' (hidden): " sec; echo; }
  step "Adding Ford app '$slug' to $FUNCAPP (no other resource changed)"
  az functionapp config appsettings set -n "$FUNCAPP" -g "$RG" -o none \
    --settings "Ford__Apps__${slug}__ClientId=$cid" "Ford__Apps__${slug}__ClientSecret=$sec"
  c_green "  Added. In the app: Settings (cog) -> Ford authorization -> expand '$slug' -> Link."
  [[ -z "$cid" ]] && c_yellow "  NOTE: client id is empty — set Ford__Apps__${slug}__ClientId / __ClientSecret later."
}

remove_ford_app() {
  require_cli
  local slug="${1:-}"
  slug="$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
  [[ -z "$slug" ]] && { read -r -p "Extra Ford app slug to remove: " slug; slug="$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"; }
  [[ -z "$slug" ]] && { c_red "A slug is required."; exit 1; }
  [[ "$slug" == "$FORD_SLUG" ]] && { c_red "Refusing to remove the PRIMARY slug '$slug'."; exit 1; }
  az functionapp show -n "$FUNCAPP" -g "$RG" >/dev/null 2>&1 || { c_red "Function App $FUNCAPP not found in $RG (check SUFFIX)."; exit 1; }
  step "Removing Ford app '$slug' from $FUNCAPP (no other resource changed)"
  if az functionapp config appsettings delete -n "$FUNCAPP" -g "$RG" -o none \
       --setting-names "Ford__Apps__${slug}__ClientId" "Ford__Apps__${slug}__ClientSecret" >/dev/null 2>&1; then
    c_green "  Settings removed."
  else
    c_yellow "  No matching settings (already removed?)."
  fi
  # Best-effort: delete the per-user Ford refresh token(s) for this slug.
  local names n
  names="$(az keyvault secret list --vault-name "$KEYVAULT" --query "[].name" -o tsv 2>/dev/null || true)"
  if [[ -n "$names" ]]; then
    while IFS= read -r n; do
      [[ "$n" =~ ^ford-refresh-.+-${slug}$ ]] || continue
      az keyvault secret delete --vault-name "$KEYVAULT" --name "$n" -o none 2>/dev/null \
        && c_green "  Deleted Key Vault token: $n"
    done <<< "$names"
  else
    c_yellow "  No Key Vault refresh token found for '$slug' (or no access)."
  fi
  c_green "  Done. The removed car drops off after the Function App restarts."
}

# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------
do_infra() {
  require_cli
  register_providers
  create_rg
  create_storage
  create_keyvault
  create_notificationhub
  create_functionapp
  assign_rbac
}

do_full() {
  do_infra
  apply_settings
  deploy_code
  show_status
  print_reauth
  c_green "Done. GoHenry backend is provisioned."
  c_yellow "Manual steps still required (the script can't do these for you):"
  echo "  1. Register the callback URL above in the Ford developer portal."
  echo "  2. Upload your Firebase FCM v1 service-account JSON to the Notification Hub"
  echo "     (Portal -> $NH_HUB -> Google (FCM v1))."
}

do_rebuild() {
  require_cli
  apply_settings
  deploy_code
  show_status
}

menu() {
  cat <<EOF

  GoHenry provisioner
  -------------------
  1) full        infra + settings + deploy (recommended first run)
  2) infra       create Azure resources only
  3) settings    (re)apply app settings + RBAC
  4) deploy      build + publish the Functions code
  5) status      show resources + endpoints
  6) reauth      print the Ford OAuth callback URL
  7) add-app     add an extra Ford app (one more car)
  8) remove-app  remove an extra Ford app
  9) teardown    delete the whole resource group
  10) quit
EOF
  read -r -p "Choose: " choice
  case "$choice" in
    1) do_full ;;
    2) do_infra ;;
    3) require_cli; assign_rbac; apply_settings ;;
    4) require_cli; deploy_code ;;
    5) show_status ;;
    6) print_reauth ;;
    7) add_ford_app ;;
    8) remove_ford_app ;;
    9) teardown ;;
    10|q|quit) exit 0 ;;
    *) c_yellow "Unknown choice." ;;
  esac
}

main() {
  case "${1:-menu}" in
    full)            do_full ;;
    infra)           do_infra ;;
    settings)        require_cli; assign_rbac; apply_settings ;;
    deploy)          require_cli; deploy_code ;;
    rebuild)         do_rebuild ;;
    status)          show_status ;;
    reauth)          print_reauth ;;
    add-ford-app)    add_ford_app "${2:-}" "${3:-}" "${4:-}" ;;
    remove-ford-app) remove_ford_app "${2:-}" ;;
    teardown)        teardown ;;
    menu)            menu ;;
    *) c_red "Unknown command: $1"; exit 1 ;;
  esac
}

main "$@"
