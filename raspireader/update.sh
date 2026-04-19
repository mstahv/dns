#!/bin/bash

REPO_DIR="/opt/raspireader/repo"
INSTALL_DIR="/opt/raspireader"
SERVICE="raspireader"
LOG_TAG="raspireader-update"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    logger -t "$LOG_TAG" "$1" 2>/dev/null || true
}

log "OTA update started"

# Remove trigger file so path unit can re-trigger next time
rm -f "$INSTALL_DIR/update-requested"

# Save current commit for rollback
cd "$REPO_DIR"
OLD_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo "none")
log "Current commit: $OLD_COMMIT"

rollback() {
    log "ROLLBACK: reverting to $OLD_COMMIT"
    cd "$REPO_DIR"
    git reset --hard "$OLD_COMMIT" 2>/dev/null || true
    log "Starting $SERVICE with previous version..."
    systemctl start "$SERVICE" || true
    log "Rollback complete"
}

# 1. Pull latest code
if [ ! -d "$REPO_DIR/.git" ]; then
    log "Cloning repository..."
    git clone https://github.com/mstahv/dns.git "$REPO_DIR"
else
    log "Pulling latest changes..."
    git fetch origin
    git reset --hard origin/main
fi

cd "$REPO_DIR"
NEW_COMMIT=$(git rev-parse --short HEAD)
log "New commit: $NEW_COMMIT"

# 2. Build — if it fails, rollback and keep old service running
log "Building raspireader..."
if ! mvn -pl raspireader package -DskipTests -q; then
    log "BUILD FAILED"
    rollback
    exit 1
fi

# 3. Stop the service, install new JAR
log "Stopping $SERVICE service..."
systemctl stop "$SERVICE" || true

log "Installing new JAR..."
cp "$INSTALL_DIR/raspireader.jar" "$INSTALL_DIR/raspireader.jar.bak" 2>/dev/null || true
cp raspireader/target/raspireader-1.0-SNAPSHOT.jar "$INSTALL_DIR/raspireader.jar"

# 4. Start the service and verify it stays up
log "Starting $SERVICE service..."
systemctl start "$SERVICE"
sleep 5

if ! systemctl is-active --quiet "$SERVICE"; then
    log "SERVICE FAILED TO START"
    log "Restoring previous JAR..."
    cp "$INSTALL_DIR/raspireader.jar.bak" "$INSTALL_DIR/raspireader.jar" 2>/dev/null || true
    rollback
    exit 1
fi

rm -f "$INSTALL_DIR/raspireader.jar.bak"
log "OTA update complete ($NEW_COMMIT)"
