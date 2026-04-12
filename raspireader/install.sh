#!/bin/bash
set -e

echo "=== DNS RaspiReader Installer ==="

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as root (sudo ./install.sh)"
    exit 1
fi

REPO_DIR="/opt/raspireader/repo"
INSTALL_DIR="/opt/raspireader"
APT_UPDATED=false

apt_update_once() {
    if [ "$APT_UPDATED" = false ]; then
        echo "Updating package lists..."
        apt-get update -qq
        APT_UPDATED=true
    fi
}

# Check and install required packages
PACKAGES=""
if ! command -v java &> /dev/null; then
    PACKAGES="$PACKAGES openjdk-25-jdk"
fi
if ! command -v mvn &> /dev/null; then
    PACKAGES="$PACKAGES maven"
fi
if ! command -v git &> /dev/null; then
    PACKAGES="$PACKAGES git"
fi

if [ -n "$PACKAGES" ]; then
    apt_update_once
    echo "Installing:$PACKAGES"
    apt-get install -y -qq $PACKAGES
fi

echo "Java: $(java -version 2>&1 | head -1)"
echo "Maven: $(mvn -version 2>&1 | head -1)"

# Create installation directories
echo "Creating directories..."
mkdir -p "$INSTALL_DIR"
mkdir -p /var/log/raspireader

# Clone or update repo for OTA updates
if [ ! -d "$REPO_DIR/.git" ]; then
    echo "Cloning repository..."
    git clone https://github.com/mstahv/dns.git "$REPO_DIR"
else
    echo "Updating repository..."
    cd "$REPO_DIR"
    git fetch origin
    git reset --hard origin/main
fi

# Build if jar doesn't exist
JAR_FILE="$REPO_DIR/raspireader/target/raspireader-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Building project..."
    cd "$REPO_DIR"
    mvn -pl raspireader package -DskipTests -q
fi

echo "Installing JAR..."
cp "$JAR_FILE" "$INSTALL_DIR/raspireader.jar"

# Install systemd service
echo "Installing systemd service..."
cp "$REPO_DIR/raspireader/raspireader.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable raspireader

echo ""
echo "=== Installation complete ==="
echo ""
echo "OTA updates can be triggered from the server admin UI,"
echo "or manually: sudo $REPO_DIR/raspireader/update.sh"
echo ""
echo "View logs after reboot:"
echo "  sudo journalctl -u raspireader -f"
echo ""
echo "Rebooting in 5 seconds..."
sleep 5
reboot
