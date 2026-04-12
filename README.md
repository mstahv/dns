# DNS - Did Not Start

Suunnistuskilpailun lähtijäseurantajärjestelmä. Toimitsijat merkitsevät lähtijät reaaliaikaisesti selaimella tai emit-lukijoilla, ja järjestelmä tunnistaa kilpailijat jotka eivät lähteneet (DNS).

Ensikäytössä VikingLine Rasteilla 2026.

## Rakenne

Projekti on Maven multi-module -projekti:

| Moduuli | Kuvaus |
|---------|--------|
| [server](server/) | Palvelin — Vaadin 25 + Spring Boot 4, REST & WebSocket API, PostgreSQL |
| [raspireader](raspireader/) | Konelukija — Emit 250 -lukijasovellus Raspberry Pi:lle, GPIO-ledit, OTA-päivitykset |

## Teknologiat

- **Server:** Vaadin 25.1.1, Spring Boot 4, Spring Data JPA, PostgreSQL, WebSocket
- **RaspiReader:** Java 25, Pi4J 4, jSerialComm, Emit 250 -protokolla
- **Testit:** Testcontainers, browserless UI-testit

## Pikastart (kehitys)

```bash
# Palvelin
cd server
mvn spring-boot:test-run

# Konelukija (Raspberry Pi:llä)
cd raspireader
sudo ./install.sh
```

## Lisenssi

[GPL-3.0](LICENSE)
