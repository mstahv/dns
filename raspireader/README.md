# DNS RaspiReader

Emit 250 USB card reader application for Raspberry Pi Zero 2 W. Reads Emit e-card numbers and pushes them to a REST endpoint for competition timing.

## Quick Install

Puhtaalle Raspberry Pi:lle (Raspberry Pi OS Lite, wifi konfiguroitu):

```bash
wget -qO- https://raw.githubusercontent.com/mstahv/dns/main/raspireader/install.sh | sudo bash
```

Asentaa automaattisesti Java 25:n, Mavenin ja gitin, kloonaa repon, buildaa ja asentaa systemd-palvelut (lukija + OTA-päivitys). Laite käynnistyy uudelleen asennuksen lopuksi ja lukija käynnistyy automaattisesti.

## OTA-päivitykset

Laite tukee etäpäivityksiä serverin hallintanäkymästä. Päivitysnappi näkyy online-koneille "Toiminnot"-sarakkeessa.

Päivitys:
1. Serveri lähettää `{"type":"requestUpdate"}` WebSocketin kautta
2. Laite kirjoittaa lipputiedoston `/opt/raspireader/update-requested`
3. systemd path-unit (`raspireader-update.path`) havaitsee tiedoston ja käynnistää `raspireader-update.service`
4. Skripti poistaa lipun, pysäyttää lukijapalvelun, hakee uusimman koodin gitistä, buildaa ja käynnistää uudelleen

Manuaalinen päivitys: `sudo /opt/raspireader/repo/raspireader/update.sh`

Päivityslokit: `sudo journalctl -u raspireader-update`

## Requirements

- Raspberry Pi Zero 2 W (or any Pi with GPIO and USB)
- JDK 25+
- Maven 3.9+
- Emit 250 USB reader (FTDI-based, appears as `/dev/ttyUSB0`)
- Green LED + 330Ω resistor
- Red LED + 330Ω resistor
- Piezo-summeri (esim. Arduino Starter Kit R4)
- SG90 mikroservo (esim. Arduino Starter Kit R4) — lähtöporttia varten

## Kytkennät

### GPIO-pinnikartta

| Komponentti | GPIO (BCM) | Fyysinen pinni | Tarkoitus |
|-------------|-----------|----------------|-----------|
| Vihreä LED | 17 | 11 | Juoksija löytyi / lähtöajan ilmaisin |
| Punainen LED | 27 | 13 | Virhe / varoitus |
| Summeri (piezo) | 22 | 15 | Äänimerkit (myöhässä / virhe) |
| Servo (PWM) | 18 | 12 | Lähtöportti (hardware PWM0) |

### Signaalit kortinluvun jälkeen

| Tilanne | Vihreä | Punainen | Summeri | Portti | Merkitys |
|---------|--------|----------|---------|--------|----------|
| 0–4 min lähtöön | Vilkkuu | Pois | — | Auki | Ohjaa juoksija oikeaan lähtöhetkeen |
| 4–5 min lähtöön | Palaa jatkuvana | Pois | — | Auki | Normaali tilanne lähdössä |
| >5 min lähtöön | Vuorottelee | Vuorottelee | — | Kiinni | Juoksija liian aikaisin |
| Lähtöaika mennyt | Vilkkuu 4× | Pois | 3× lyhyt piip | Auki | Juoksija myöhässä |
| Kortti tuntematon | Palaa | Vilkkuu | 1× pitkä piip | Kiinni | Korttia ei löydy |
| Serverivirhe | Pois | Vilkkuu | 1× pitkä piip | Kiinni | Yhteysongelma |

Lähtöportti pysyy auki 2 sekuntia viimeisestä lukijan viestistä. Jos sama kortti on lukijalla pidempään, portin auki-aikaa jatketaan automaattisesti.

### Idle-tila (ei kortinlukuja 20 s)

Kun kortteja ei ole luettu 20 sekuntiin, laite näyttää yhteystilan tuplavälähdyksellä 5 sekunnin välein:

| Tilanne | LED | Merkitys |
|---------|-----|----------|
| Yhteys palvelimeen OK | Vihreä tuplavälähdys | Laite valmiina, yhteys kunnossa |
| Ei yhteyttä palvelimeen | Punainen tuplavälähdys | Laite valmiina, yhteys poikki |

Tuplavälähdys on kaksi nopeaa välähdystä (80 ms päälle, 120 ms tauko, 80 ms päälle). Välähdys lakkaa heti kun kortti luetaan.

### Onboard ACT LED (yhteystila)

Raspberry Pi:n levyllä oleva ACT LED indikoi yhteystilaa:

| ACT LED | Merkitys |
|---------|----------|
| Kaksoissvälähdys 5s välein | WebSocket-yhteys serveriin OK |
| Yksittäinen välähdys 5s välein | Verkko toimii, mutta WS-yhteys poikki |
| Jatkuva vilkutus | Ei verkkoyhteyttä lainkaan |

### Kytkentäkaavio

```
Raspberry Pi                  Komponentit
-----------                  -----------
GPIO 17 (pin 11) ---[330Ω]---[GREEN LED+]---[GREEN LED-]---+
GPIO 27 (pin 13) ---[330Ω]---[RED LED+]-----[RED LED-]-----+--- GND (pin 14)
GPIO 22 (pin 15) ---[BUZZER+]---[BUZZER-]------------------+
GPIO 18 (pin 12) ---[SERVO signal (oranssi/keltainen)]
         5V (pin 2) ---[SERVO VCC (punainen)]
        GND (pin 6) ---[SERVO GND (ruskea)]
```

Pin layout on the Pi header:

```
                    +-----+
               3V3  | 1  2| 5V  <-- servo VCC (punainen)
             GPIO2  | 3  4| 5V
             GPIO3  | 5  6| GND  <-- servo GND (ruskea)
             GPIO4  | 7  8| GPIO14
               GND  | 9 10| GPIO15
  GREEN --> GPIO17  |11 12| GPIO18  <-- SERVO signal
    RED --> GPIO27  |13 14| GND  <-- shared ground (LEDs + buzzer)
 BUZZER --> GPIO22  |15 16| GPIO23
               3V3  |17 18| GPIO24
            GPIO10  |19 20| GND
             GPIO9  |21 22| GPIO25
            GPIO11  |23 24| GPIO8
               GND  |25 26| GPIO7
                    +-----+
```

### Kytkentäohjeet

**LEDit:**
1. GPIO 17 (pin 11) → 330Ω vastus → vihreä LED anodi (pitkä jalka) → katodi → GND (pin 14)
2. GPIO 27 (pin 13) → 330Ω vastus → punainen LED anodi (pitkä jalka) → katodi → GND (pin 14)

**Summeri (piezo, Arduino Starter Kit R4):**
3. GPIO 22 (pin 15) → summerin + → summerin − → GND (pin 14)

Piezo-summeri ei tarvitse vastusta (minimaalinen virrankulutus).

**Mikroservo (SG90, Arduino Starter Kit R4):**
4. GPIO 18 (pin 12) → servon signaalijohtoon (oranssi/keltainen)
5. Pin 2 (5V) → servon VCC-johtoon (punainen)
6. Pin 6 (GND) → servon GND-johtoon (ruskea)

SG90 servon johtojärjestys: ruskea=GND, punainen=VCC, oranssi=signaali.

### Hardware PWM -konfiguraatio (servo)

Servo vaatii hardware PWM:n GPIO 18:lla. Lisää `/boot/firmware/config.txt` -tiedostoon (vanhemmissa: `/boot/config.txt`):

```
dtoverlay=pwm
```

Käynnistä Pi uudelleen konfiguraation jälkeen. Tämä luo sysfs-rajapinnan `/sys/class/pwm/pwmchip0/pwm0`.

**Huom:** Jos servoa ei ole kytketty tai PWM-overlay puuttuu, lukija käynnistyy normaalisti ilman porttitoimintoa (lokiin tulostuu varoitus).

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
| `--url <url>` | Server URL (WebSocket johdetaan automaattisesti) | `https://dns.virit.in` |
| `--machine-id <id>` | Koneen tunniste | Auto-detect: `<hostname>-<machine-id/MAC>` |
| `--serial <device>` | Sarjaportti | Auto-detect (`/dev/ttyUSB*`) |
| `--log <path>` | Lokitiedoston polku | `/var/log/raspireader/reads.log` |
| `--emitcheck` | Emit-kortin tarkistustila (read-only) | Pois |

### Example

```bash
java -jar raspireader.jar --serial /dev/ttyUSB0
```

### Emitcheck-tila

Itsepalvelupiste kilpailukeskukseen, jossa juoksija voi tarkistaa emit-korttinsa toimivuuden ja löytymisen kilpailijatietokannasta.

```bash
java -jar raspireader.jar --emitcheck
```

Emitcheck-tilassa:
- Lukija yhdistää serveriin WebSocketilla ja vastaanottaa lähtölistan
- Kortteja **ei lähetetä serverille** — juoksijaa ei kirjata lähteneeksi
- Vihreä vilkkuu = kortti löytyy järjestelmästä
- Punainen vilkkuu = korttia ei löydy

Tarkistuspisteen viereen tulostetaan ohjelappu (`docs/emitcheck-ohje.svg`).

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

## Lukijoiden kloonaus ja käyttöönotto

Kun "master"-lukija on konfiguroitu valmiiksi (JDK, raspireader, systemd-palvelu, WiFi-asetukset), uudet lukijat luodaan kloonaamalla SD-kortti ja yksilöimällä kone.

### 1. WiFi-verkkojen lisäys master-lukijaan

Lisää kilpailupaikkojen WiFi-verkot etukäteen. NetworkManager tallentaa ne automaattisesti kaikille klooneille:

```bash
# Lisää verkko
sudo nmcli device wifi connect "KilpailunWiFi" password "salasana123"

# Listaa tallennetut verkot
nmcli connection show

# Poista vanha verkko
nmcli connection delete "VanhaVerkko"

# Aseta prioriteetti (korkeampi yhdistää ensin)
nmcli connection modify "KilpailunWiFi" connection.autoconnect-priority 10
```

Vinkki: lisää yleisimmät kilpailupaikkojen verkot valmiiksi, niin lukijat yhdistävät automaattisesti.

### 2. SD-kortin kloonaus rpi-clone:lla

rpi-clone kopioi käynnissä olevan järjestelmän toiselle SD-kortille USB-adapterilla:

```bash
# Asenna rpi-clone (kerran master-lukijaan)
git clone https://github.com/billw2/rpi-clone.git
sudo cp rpi-clone/rpi-clone /usr/local/sbin/

# Aseta kohde-SD-kortti USB-adapteriin ja etsi laite
lsblk

# Kloonaa (tyypillisesti /dev/sda)
sudo rpi-clone sda
```

Kloonaus kopioi kaiken: käyttöjärjestelmän, JDK:n, raspireader-sovelluksen, WiFi-asetukset ja systemd-palvelun.

### 3. Kloonin yksilöinti

Käynnistä klooni ja tee seuraavat toimenpiteet:

```bash
# Vaihda hostname (esim. ereader1, ereader2, ...)
sudo hostnamectl set-hostname ereader2

# Generoi uudet SSH-avaimet (klooni peri master-lukijan avaimet)
sudo rm /etc/ssh/ssh_host_*
sudo dpkg-reconfigure openssh-server
sudo systemctl restart ssh

# Generoi uusi machine-id
sudo rm /etc/machine-id
sudo systemd-machine-id-setup

# Käynnistä uudelleen jotta kaikki muutokset aktivoituvat
sudo reboot
```

Machine ID muodostuu automaattisesti muotoon `<hostname>-<machine-id>`, esim. `ereader2-a1b2c3...`, joten hostname ja machine-id:n uudelleengenerointi riittävät erottamaan lukijat hallintanäkymässä.

### Pikaohje: uusi lukija 5 minuutissa

1. Aseta tyhjä SD-kortti USB-adapteriin master-lukijaan
2. `sudo rpi-clone sda`
3. Siirrä SD-kortti uuteen lukijaan ja käynnistä
4. `sudo hostnamectl set-hostname ereaderN`
5. `sudo rm /etc/ssh/ssh_host_* && sudo dpkg-reconfigure openssh-server`
6. `sudo rm /etc/machine-id && sudo systemd-machine-id-setup`
7. `sudo reboot`
