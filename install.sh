#!/bin/bash
set -e

echo "=== DNS RaspiReader Installer ==="

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as root (sudo ./install.sh)"
    exit 1
fi

# Build if jar doesn't exist
JAR_FILE="target/raspireader-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Building project..."
    mvn clean package -q
fi

# Create installation directory
echo "Installing to /opt/raspireader..."
mkdir -p /opt/raspireader
cp "$JAR_FILE" /opt/raspireader/raspireader.jar

# Create log directory
mkdir -p /var/log/raspireader

# Install systemd service
echo "Installing systemd service..."
cp raspireader.service /etc/systemd/system/
systemctl daemon-reload

echo ""
echo "=== Installation complete ==="
echo ""
echo "Before starting, edit the service file to set your competition password:"
echo "  sudo systemctl edit raspireader"
echo ""
echo "Or edit directly:"
echo "  sudo nano /etc/systemd/system/raspireader.service"
echo ""
echo "Then start the service:"
echo "  sudo systemctl enable raspireader"
echo "  sudo systemctl start raspireader"
echo ""
echo "View logs:"
echo "  sudo journalctl -u raspireader -f"
echo ""
