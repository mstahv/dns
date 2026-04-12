#!/bin/bash
set -e

REPO_DIR="/opt/raspireader/repo"
INSTALL_DIR="/opt/raspireader"
SERVICE="raspireader"
LOG_TAG="raspireader-update"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    logger -t "$LOG_TAG" "$1" 2>/dev/null || true
}

log "OTA update started"

# 1. Stop the service (frees memory for build)
log "Stopping $SERVICE service..."
systemctl stop "$SERVICE" || true

# 2. Pull latest code
if [ ! -d "$REPO_DIR/.git" ]; then
    log "Cloning repository..."
    git clone https://github.com/mstahv/dns.git "$REPO_DIR"
else
    log "Pulling latest changes..."
    cd "$REPO_DIR"
    git fetch origin
    git reset --hard origin/main
fi

cd "$REPO_DIR"

# 3. Build only the raspireader module
log "Building raspireader..."
mvn -pl raspireader package -DskipTests -q

# 4. Install the new JAR
log "Installing new JAR..."
cp raspireader/target/raspireader-1.0-SNAPSHOT.jar "$INSTALL_DIR/raspireader.jar"

# 5. Restart the service
log "Starting $SERVICE service..."
systemctl start "$SERVICE"

log "OTA update complete ($(cd "$REPO_DIR" && git rev-parse --short HEAD))"
