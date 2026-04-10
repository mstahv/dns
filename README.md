# DNS - Did Not Start

Suunnistuskilpailun lähtijäseuranta. Kilpailutoimitsijat merkitsevät lähtijät reaaliaikaisesti, ja järjestelmä tunnistaa ne kilpailijat jotka eivät lähteneet (DNS).

## Teknologiat

- Vaadin 25.1.1, Spring Boot 4, Spring Data JDBC, PostgreSQL
- Viritin add-on, IOF XML 3 (iofdomain)
- Testcontainers (PostgreSQL), browserless UI-testit

## Käyttö

### Käynnistys (kehitys)

```bash
./mvnw spring-boot:test-run
```

Avaa selain: http://localhost:8080

### Testit

```bash
./mvnw test
```

## REST API

Ulkoiset järjestelmät (esim. Kellokalle) voivat syöttää ja kysyä lähteneitä REST-rajapinnan kautta.

### Merkitse lähteneet

```
POST /api/started
Content-Type: text/plain
X-Competition-Password: <kisasalasana>
X-Registered-By: <järjestelmän nimi, esim. "kellokalle">

100,200,300
```

**Headerit:**
- `X-Competition-Password` — kilpailun salasana (pakollinen)
- `X-Registered-By` — merkitsijän tunnus (pakollinen)

**Body:** pilkulla erotettu lista kilpailijanumeroita (text/plain).

**Vastaus:**
- `200 OK` — `"3 runners marked as started"`
- `401 Unauthorized` — `"Invalid competition password"`

Jo lähteneet numerot ohitetaan.

### Kysy lähteneet tietyillä lähtöajoilla

```
GET /api/started?times=12:00,12:01
X-Competition-Password: <kisasalasana>
```

**Query-parametrit:**
- `times` — pilkulla erotettu lista lähtöaikoja muodossa `HH:mm` tai `HH:mm:ss`

**Vastaus:**
- `200 OK` — pilkulla erotettu lista kilpailijanumeroita (text/plain), jotka on merkitty lähteneeksi kyseisillä lähtöajoilla
- `401 Unauthorized` — väärä salasana

### Koneluenta (Machine Reading)

Konelukijat (esim. emit-lukijat lähtöviivalla) voivat rekisteröidä lähtijöitä automaattisesti. Kone pitää ensin hyväksyä kilpailun hallintanäkymässä (Koneluenta-sivu).

```
POST /api/machine-reading
Content-Type: application/json
X-Competition-Password: <kisasalasana>
X-Machine-Id: <koneen tunniste>

[{"bib": 123}, {"cc": 54321}]
```

**Headerit:**
- `X-Competition-Password` — kilpailun salasana (pakollinen)
- `X-Machine-Id` — koneen tunniste, pitää vastata hyväksyttyä konetta (pakollinen). Esimerkiksi wifi-kortin MAC tms.

**Body (JSON-lista):** objekteja joissa `bib` (kilpailijanumero) tai `cc` (emit-kortin numero). Molemmat ovat numeroita. Listassa voi lähettää useamman lukeman kerralla (esim. puskuroituja lukemia).

**Vastaus:**
- `200 OK` — JSON-lista tuloksia, yksi per lukema:
  ```json
  [{"bib": 123, "startTime": "12:03:00", "name": "Matti Meikäläinen", "className": "H21", "found": true},
   {"bib": 0, "startTime": "", "name": "", "className": "", "found": false}]
  ```
- `401 Unauthorized` — väärä salasana
- `403 Forbidden` — kone ei ole hyväksytty tälle kilpailulle

Kaikki lukemat (myös ne joissa juoksijaa ei löytynyt, `found: false`) kirjautuvat lokiin, jota voi seurata Koneluenta-näkymässä.
