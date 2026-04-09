# DNS - Did Not Start

Suunnistuskilpailun DNS-kirjaussovellus. Vaadin 25.1.1, Spring Boot 4.0.4, Spring Data JDBC, PostgreSQL.

## Kieli

- UI-tekstit suomeksi
- Koodi ja kommentit englanniksi

## Vaadin-koodityyli

- Kun komponenttia konfiguroidaan >2 rivillä, luo sisäluokka (named inner class) tai top-level-luokka jos uudelleenkäytettävä. Triviaalissa tapauksessa inline-luokka (double curly braces) käy.
- Käytä Style-olion spesifejä metodeja: `setBorder("1px solid")`, ei `set("border", "1px solid")`
- Suosi komponenttikoostamista (Composite-pattern) pitkien konfiguraatiolohkojen sijaan
- Käytössä on Aura teema, joten on turha käyttää esim. seuraavia tyylimäärittelyjä: getStyle().setPadding("var(--lumo-space-m)"). Paras on pyrkiä tekemään esim. paddin asetus suoraan Java API:n kautta ja jos tarvitaan CSS propretyjä, käytetään primääristi VaadinCssProps Java luokan kautta (tai sekundäärisesti AuraProps).
- Kaikki käyttötapaukset dokumentoidaan ohjelmoinnin ohessa UI testein. Pyritään käyttämään mahdollisimman paljon browserless-test moduulia, sekundäärisesti Playwright testejä. Testikoodit jäsennellään siten ja niin selkein metodinimin/luokkarakentein, että sitä voi lukea myös eitekninen käyttäjä.
- **browserless-test projektista on käytössä paranneltu versio. Hyödynnetään sen ominaisuuksia: kuvaavampi methodi komponettien etsimiseen (get vs $) ja VaadinTestApplicationContext, jonka kautta voi luoda useampia käyttäjiä ja testin ei ole pakko periä yläluokasta. Käytetään näitä hyödyksi.**
- Muutoksia ja parannuksia tehdessä, pyritään parantamaan/ylläpitämään testejä, jotta ne kattavat tarkentuneet vaatimukset.
- getElement() ja executeJs metodit ovat merkkejä että tehdään "componentti/webbitason" säätöjä. Niitä ei juuri saisi näkyä varsinaisessa ohjelmistokoodissa. Ennen niiden käyttä tulisi puntaroida onko selain/DOM muutoksen tekemiseen jotain valmista paremmin tyypitettyä Java APIa tai add-onia Directoryssä. Esim ei näin: nameField.getElement().executeJs("window.localStorage.setItem($0, $1)", LOCAL_STORAGE_KEY, name) vaan käytä WebStorage luokkaa.


## Arkkitehtuuri

- `domain/` — entiteetit ja Spring Data JDBC -repositoryt
- `service/` — palvelukerros, ulkoiset integraatiot
- `ui/` — Vaadin-näkymät ja UI-komponentit
- Kilpailijatiedot haetaan tulospalvelu.fi:stä IOF XML 3 -muodossa, ei tallenneta omaan kantaan
- DnsEntry on tarkoituksella minimaalinen: competition_id, competitor_number, registered_at, comment

## Ulkoinen palvelu

- online.tulospalvelu.fi — lähtölistat ja kilpailutiedot
- Lähtölistat ovat isoja → cachetetaan muistiin ja levylle
- Events JSON suodatetaan Discipline="Orienteering"
