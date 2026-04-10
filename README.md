# DNS - Did Not Start

Suunnistuskilpailun lähtijäseuranta. Kilpailutoimitsijat merkitsevät lähtijät reaaliaikaisesti, ja järjestelmä tunnistaa ne kilpailijat jotka eivät lähteneet (DNS).

## Teknologiat

- Vaadin 25.1.1, Spring Boot 4, Spring Data JPA, PostgreSQL
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
- `403 Forbidden` — kilpailu on päättynyt (enabled=false)

Jo lähteneet numerot ohitetaan.

### Kysy lähteneet tietyillä lähtöajoilla

```
GET /api/started?times=12:00,12:01
X-Competition-Password: <kisasalasana>
```

**Query-parametrit:**
- `times` — pilkulla erotettu lista lähtöaikoja muodossa `HH:mm`, `HH.mm` tai `HH:mm:ss`

**Vastaus:**
- `200 OK` — pilkulla erotettu lista kilpailijanumeroita (text/plain), jotka on merkitty lähteneeksi kyseisillä lähtöajoilla
- `401 Unauthorized` — väärä salasana

### Koneluenta REST (Machine Reading)

Yksinkertainen HTTP-rajapinta konelukijoille. Kone pitää ensin yhdistää kilpailuun ja hyväksyä Koneluenta-sivulla.

```
POST /api/machine-reading
Content-Type: application/json
X-Machine-Id: <koneen tunniste>

[{"bib": 123}, {"cc": 54321}]
```

**Headerit:**
- `X-Machine-Id` — koneen tunniste (pakollinen). Esimerkiksi wifi-kortin MAC tms.

**Body (JSON-lista):** objekteja joissa `bib` (kilpailijanumero) tai `cc` (emit-kortin numero).

**Vastaus:**
- `200 OK` — JSON-lista tuloksia per kilpailu johon kone on yhdistetty ja hyväksytty:
  ```json
  [{"bib": 123, "startTime": "12:03:00", "name": "Matti Meikäläinen", "className": "H21", "found": true}]
  ```
- Kone ilman kisayhteyttä → tyhjä taulukko `[]`
- Päättyneet kisat (enabled=false) ohitetaan

Kaikki lukemat kirjautuvat lokiin, jota voi seurata Koneluenta-näkymässä.

## WebSocket API (koneluenta)

Optimaalinen rajapinta konelukijoille: pysyvä yhteys minimoi latenssin. Autentikoinnin jälkeen palvelin lähettää automaattisesti kilpailukorttien mäppäyksen, jonka avulla lukukone voi näyttää kilpailijan tiedot välittömästi omassa käyttöliittymässään.

### Yhteysosoite

```
ws://<host>:<port>/ws/machine-reading
```

### Protokolla

#### 1. Autentikointi

Ensimmäinen viesti yhdistää lukukoneen:

```json
→ {"type":"auth","machineId":"emit-reader-1"}
```

Palvelin vastaa:

```json
← {"type":"auth","ok":true}
```

#### 2. Lähtölista (automaattinen)

Heti autentikoinnin jälkeen palvelin lähettää kompaktin lähtölistadatan kaikista kilpailuista joihin kone on yhdistetty ja hyväksytty. Mäppäys on kilpailukortin numerosta (emit) kilpailijanumeroon ja lähtöaikaan:

```json
← {"type":"startlist","data":{"12345":{"bib":1,"st":"12:00:00"},"67890":{"bib":2,"st":"12:01:00"},...}}
```

Tämän datan avulla lukukone voi näyttää omassa käyttöliittymässään kilpailijan tiedot heti kun kortti luetaan — ilman odotusta palvelimen vastaukseen.

Palvelin lähettää päivitetyn startlist-viestin automaattisesti myös silloin, kun:
- Kone yhdistetään uuteen kilpailuun hallintanäkymässä
- Koneen hyväksyntä muutetaan
- Kone poistetaan kilpailusta
- Lähtölista päivittyy tulospalvelu.fi:stä (10 min välimuistin timeout, vertaillaan IOF XML:n `createTime`-arvoa — muuttunut emit-numero tai lähtöaika välittyy automaattisesti)

#### 3. Lukema

```json
→ {"bib":123}
```
tai emit-kortin numerolla:
```json
→ {"cc":54321}
```

Palvelin vastaa JSON-taulukolla (yksi tulos per kilpailu johon kone on yhdistetty):

```json
← [{"bib":123,"startTime":"12:03:00","name":"Matti Meikäläinen","className":"H21","found":true}]
```

Jos kilpailijaa ei löydy:
```json
← [{"bib":123,"startTime":"","name":"","className":"","found":false}]
```

#### 4. Virhe (ilman autentikointia)

```json
← {"type":"error","error":"Ei autentikoitu. Lähetä ensin: {\"type\":\"auth\",\"machineId\":\"...\"}"}
```

### Tyypillinen vuorovaikutus

```
Client                              Server
  |-- {"type":"auth","machineId":"X"} -->|
  |<-- {"type":"auth","ok":true} --------|
  |<-- {"type":"startlist","data":{...}} |  ← kone voi nyt näyttää tiedot paikallisesti
  |                                       |
  |-- {"cc":12345} --------------------->|  ← emit-kortti luettu
  |<-- [{"bib":1,...,"found":true}] -----|  ← vahvistus palvelimelta
  |                                       |
  |-- {"cc":67890} --------------------->|
  |<-- [{"bib":2,...,"found":true}] -----|
  |                                       |
  |  (hallintanäkymässä kone yhdistetään  |
  |   toiseen kilpailuun)                 |
  |<-- {"type":"startlist","data":{...}} |  ← päivitetty data automaattisesti
  |                                       |
  |  (tulospalvelu.fi:n lähtölista        |
  |   päivittyy, esim. emit-muutos)       |
  |<-- {"type":"startlist","data":{...}} |  ← uudet tiedot pushataan automaattisesti
```
