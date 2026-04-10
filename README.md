# DNS RaspiReader

Emit 250 USB card reader application for Raspberry Pi Zero 2 W. Reads Emit e-card numbers and pushes them to a REST endpoint for competition timing.

## Requirements

- Raspberry Pi Zero 2 W (or any Pi with GPIO and USB)
- JDK 25+
- Maven 3.9+
- Emit 250 USB reader (FTDI-based, appears as `/dev/ttyUSB0`)
- Green LED + 330Ω resistor
- Red LED + 330Ω resistor

## LED Wiring

Two LEDs are used to indicate the result of each card read:

| LED | GPIO (BCM) | Physical pin | Meaning |
|-----|-----------|--------------|---------|
| Green | 17 | 11 | Runner found / start time indicator |
| Red | 27 | 13 | Error / warning indicator |

### LED Signals

After each card read, LEDs indicate the result:

| Tilanne | Vihreä | Punainen | Merkitys |
|---------|--------|----------|----------|
| Juoksija löytyi, 0–4 min lähtöön | Vilkkuu | Pois | Ohjaa juoksija oikeaan lähtöhetkeen |
| Juoksija löytyi, 4–5 min lähtöön | Palaa jatkuvana | Pois | Normaali tilanne lähdössä |
| Juoksija löytyi, >5 min lähtöön | Vilkkuu vuorotellen | Vilkkuu vuorotellen | Juoksija liian aikaisin |
| Juoksija löytyi, lähtöaika mennyt | Vilkkuu tuplanopeudella | Pois | Juoksija myöhässä |
| Kortti tuntematon (`found:false`) | Palaa jatkuvana | Vilkkuu | Korttia ei löydy järjestelmästä |
| Serverivirhe / ei vastausta | Pois | Vilkkuu | Yhteysongelma tai muu virhe |

```
Raspberry Pi                  LEDs
-----------                  -----
GPIO 17 (pin 11) ---[330Ω]---[GREEN LED+]---[GREEN LED-]---+
GPIO 27 (pin 13) ---[330Ω]---[RED LED+]-----[RED LED-]-----+--- GND (pin 9 or 14)
```

Pin layout on the Pi header:

```
                    +-----+
               3V3  | 1  2| 5V
             GPIO2  | 3  4| 5V
             GPIO3  | 5  6| GND
             GPIO4  | 7  8| GPIO14
               GND  | 9 10| GPIO15
  GREEN --> GPIO17  |11 12| GPIO18
    RED --> GPIO27  |13 14| GND  <-- shared ground
            GPIO22  |15 16| GPIO23
               3V3  |17 18| GPIO24
            GPIO10  |19 20| GND
             GPIO9  |21 22| GPIO25
            GPIO11  |23 24| GPIO8
               GND  |25 26| GPIO7
                    +-----+
```

**Connect:**
1. GPIO 17 (pin 11) → 330Ω resistor → Green LED anode (long leg) → cathode → GND (pin 14)
2. GPIO 27 (pin 13) → 330Ω resistor → Red LED anode (long leg) → cathode → GND (pin 14)

Both LED cathodes can share the same GND pin (pin 9 or 14).

## Build

```bash
mvn clean package
```

This creates a fat JAR at `target/raspireader-1.0-SNAPSHOT.jar`.

## Usage

```bash
java -jar target/raspireader-1.0-SNAPSHOT.jar [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--url <url>` | Server URL | `http://m4m.local:8080` |
| `--password <pwd>` | Competition password (`X-Competition-Password`) | (empty) |
| `--machine-id <id>` | Machine identifier (`X-Machine-Id`) | Auto-detect from MAC or `/etc/machine-id` |
| `--serial <device>` | Serial port device path | Auto-detect (`/dev/ttyUSB*`) |
| `--log <path>` | Log file path | `/var/log/raspireader/reads.log` |

### Example

```bash
java -jar raspireader.jar \
  --url http://192.168.1.100:8080 \
  --password kilpailusana123 \
  --serial /dev/ttyUSB0
```

## Install as systemd service

```bash
sudo ./install.sh
```

Then configure:
```bash
# Edit the ExecStart line to set your password and URL
sudo nano /etc/systemd/system/raspireader.service

# Enable and start
sudo systemctl enable raspireader
sudo systemctl start raspireader

# View logs
sudo journalctl -u raspireader -f
```

## Serial Port Protocol

The Emit 250 sends 217-byte messages over serial (9600 baud, 8N2) whenever a card is placed on the reader. All bytes are XOR'd with `0xDF`. The card number is a 3-byte little-endian integer at bytes 3-5 (after decoding). Messages are validated using two checksums (head and transfer).

Reference: [emit-punch-cards-communication](https://github.com/mikaello/emit-punch-cards-communication)

## Log File Format

All card reads (including re-reads of the same card) are logged with timestamps:

```
2026-04-10 14:30:15 CARD=208560
2026-04-10 14:30:42 CARD=123456
2026-04-10 14:31:01 CARD=208560
```

## WebSocket API

The reader communicates with the server over a persistent WebSocket connection to `ws://<host>:<port>/ws/machine-reading`. This minimizes latency and enables the server to push startlist updates.

### Protocol flow

1. **Auth**: reader sends `{"type":"auth","machineId":"..."}`, server responds `{"type":"auth","ok":true}`
2. **Startlist**: server automatically pushes `{"type":"startlist","data":{"<cc>":{"bib":<n>,"st":"HH:mm:ss"},...}}` — the reader caches this locally for instant LED feedback
3. **Reading**: reader sends `{"cc":<cardNumber>}`, server responds with `[{"bib":...,"startTime":"...","found":true/false}]`

The local startlist cache allows immediate LED response even if the server connection is temporarily down. Server responses are always respected and may override the cached result (e.g., if an emit card was just reassigned).

Cards read while disconnected are buffered and sent when the connection is restored. Reconnection uses exponential backoff (1s–30s).

## Installing JDK 25 on Raspberry Pi

```bash
# Download and install Eclipse Temurin JDK 25
# (check https://adoptium.net for latest aarch64 builds)
wget https://api.adoptium.net/v3/binary/latest/25/ea/linux/aarch64/jdk/hotspot/normal/eclipse
tar xzf eclipse
sudo mv jdk-25* /opt/jdk-25
echo 'export JAVA_HOME=/opt/jdk-25' | sudo tee /etc/profile.d/java.sh
echo 'export PATH=$JAVA_HOME/bin:$PATH' | sudo tee -a /etc/profile.d/java.sh
source /etc/profile.d/java.sh
```
