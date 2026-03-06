#!/bin/bash
# update-nightly.sh — Downloads the latest LibreShockwave nightly build
# from GitHub and deploys it to an Apache-served directory.
#
# Install as a daily cron job:
#   sudo crontab -e
#   0 5 * * * /opt/libreshockwave/update-nightly.sh >> /var/log/libreshockwave-update.log 2>&1
#
# Configuration: set DEPLOY_DIR to your Apache document root (or subdirectory).

DEPLOY_DIR="/var/www/html/shockwave"
TEMP_DIR=$(mktemp -d)
REPO="Quackster/LibreShockwave"
API_URL="https://api.github.com/repos/${REPO}/releases/tags/nightly"

cleanup() { rm -rf "$TEMP_DIR"; }
trap cleanup EXIT

echo "[$(date)] Checking for nightly update..."

# Fetch the nightly release metadata to find the ZIP asset URL
ASSET_URL=$(curl -sf "$API_URL" \
    | grep -oP '"browser_download_url"\s*:\s*"\K[^"]+\.zip')

if [ -z "$ASSET_URL" ]; then
    echo "[$(date)] ERROR: Could not find nightly ZIP asset"
    exit 1
fi

ASSET_NAME=$(basename "$ASSET_URL")

# Skip if this version is already deployed
VERSION_FILE="${DEPLOY_DIR}/.nightly-version"
if [ -f "$VERSION_FILE" ] && [ "$(cat "$VERSION_FILE")" = "$ASSET_NAME" ]; then
    echo "[$(date)] Already up to date ($ASSET_NAME)"
    exit 0
fi

echo "[$(date)] Downloading $ASSET_NAME..."
if ! curl -sfL -o "${TEMP_DIR}/${ASSET_NAME}" "$ASSET_URL"; then
    echo "[$(date)] ERROR: Download failed"
    exit 1
fi

echo "[$(date)] Extracting..."
if ! unzip -qo "${TEMP_DIR}/${ASSET_NAME}" -d "${TEMP_DIR}/dist"; then
    echo "[$(date)] ERROR: Extraction failed"
    exit 1
fi

# Deploy
mkdir -p "$DEPLOY_DIR"
cp -f "${TEMP_DIR}"/dist/*.wasm "${DEPLOY_DIR}/"
cp -f "${TEMP_DIR}"/dist/*.wasm-runtime.js "${DEPLOY_DIR}/"
cp -f "${TEMP_DIR}"/dist/libreshockwave.js "${DEPLOY_DIR}/"
cp -f "${TEMP_DIR}"/dist/libreshockwave.css "${DEPLOY_DIR}/" 2>/dev/null
cp -f "${TEMP_DIR}"/dist/index.html "${DEPLOY_DIR}/" 2>/dev/null

echo "$ASSET_NAME" > "$VERSION_FILE"
echo "[$(date)] Deployed $ASSET_NAME to $DEPLOY_DIR"
