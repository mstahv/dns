package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.DnsEntryRepository;
import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.TulospalveluService;
import com.example.dns.service.UserSession;
import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DnsViewTest {

    private static final String COMPETITION_ID = "2026_viking";
    private static final String PASSWORD = "test123";

    @Autowired
    ApplicationContext springContext;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    DnsService dnsService;

    @Autowired
    DnsEntryRepository dnsEntryRepository;

    @Autowired
    MachineReadingRepository machineReadingRepository;

    @Autowired
    TulospalveluService tulospalveluService;

    VaadinTestApplicationContext app;
    VaadinTestUiContext ui;

    @BeforeEach
    void setUp() {
        machineReadingRepository.deleteAll();
        dnsEntryRepository.deleteAll();
        dnsService.clearCache();
        var routes = new Routes().autoDiscoverViews(MainView.class.getPackageName());
        app = VaadinTestApplicationContext.forSpring(routes, springContext);

        var competition = new com.example.dns.domain.Competition();
        competition.setCompetitionId(COMPETITION_ID);
        competition.setPassword(PASSWORD);
        if (competitionRepository.findById(PASSWORD).isEmpty()) {
            competitionRepository.save(competition);
        }

        ui = app.newUser().newWindow();

        var userSession = springContext.getBean(UserSession.class);
        userSession.setName("Testikäyttäjä");
        userSession.setPassword(PASSWORD);
    }

    @AfterEach
    void tearDown() {
        app.close();
        dnsService.clearCache();
    }

    private DnsView navigateToDns() {
        DnsView view = ui.navigate(DnsView.class);
        view.setCompetition(PASSWORD);
        return view;
    }

    // --- Perusrakenne ---

    @Test
    void nayttaaLahtoajat() {
        DnsView view = navigateToDns();

        assertFalse(view.getSortedTimes().isEmpty(),
                "Lähtöaikoja pitäisi löytyä tulospalvelusta");
    }

    @Test
    void nayttaaJuoksijakortitNykyiselleAjalle() {
        DnsView view = navigateToDns();

        assertTrue(view.getCurrentIndex() >= 0,
                "Pitäisi navigoida lähimpään lähtöaikaan");
        assertFalse(view.getCardsByBib().isEmpty(),
                "Nykyisellä lähtöajalla pitäisi olla juoksijakortteja");
    }

    // --- Merkintä ---

    @Test
    void kortinKlikkausMerkitseeJuoksijanLahteneeksi() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getCardsByBib().values().iterator().next();
        int bib = card.getBibNumber();

        assertFalse(card.isStarted(), "Kortti ei saa olla aluksi lähtenyt");

        view.onCardClicked(card);

        assertTrue(card.isStarted(), "Kortti pitäisi olla lähtenyt klikkauksen jälkeen");
        assertTrue(dnsService.isStarted(PASSWORD, bib),
                "DnsService:n pitäisi heijastaa lähtenyttä tilaa");

        var entry = dnsService.getEntry(PASSWORD, bib);
        assertTrue(entry.isPresent(), "DnsEntry pitäisi olla olemassa");
        assertEquals("Testikäyttäjä", entry.get().getRegisteredBy());
    }

    @Test
    void lahteneenKortinKlikkausNayttaaVahvistusdialoginJaPeruuVahvistaessa() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getCardsByBib().values().iterator().next();
        int bib = card.getBibNumber();

        view.onCardClicked(card);
        assertTrue(card.isStarted());

        // Klikkaa uudelleen — dialogin pitäisi aueta
        view.onCardClicked(card);

        List<ConfirmDialog> dialogs = ui.get(ConfirmDialog.class).all();
        assertFalse(dialogs.isEmpty(), "Vahvistusdialogi pitäisi näkyä");

        // Vahvista peruminen
        ConfirmDialog dialog = dialogs.getFirst();
        ComponentUtil.fireEvent(dialog, new ConfirmDialog.ConfirmEvent(dialog, false));

        assertFalse(card.isStarted(), "Merkintä pitäisi olla peruttu");
        assertFalse(dnsService.isStarted(PASSWORD, bib));
    }

    @Test
    void dialoginPeruutusJattaaMerkinnanVoimaan() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getCardsByBib().values().iterator().next();
        int bib = card.getBibNumber();

        view.onCardClicked(card);
        assertTrue(card.isStarted());

        view.onCardClicked(card);

        List<ConfirmDialog> dialogs = ui.get(ConfirmDialog.class).all();
        assertFalse(dialogs.isEmpty());
        ComponentUtil.fireEvent(dialogs.getFirst(),
                new ConfirmDialog.CancelEvent(dialogs.getFirst(), false));

        assertTrue(card.isStarted(), "Merkinnän pitäisi pysyä voimassa peruutuksen jälkeen");
        assertTrue(dnsService.isStarted(PASSWORD, bib));
    }

    // --- Monikäyttäjäsynkronointi ---

    @Test
    void merkintaValittuuToiselleKayttajalle() {
        DnsView view1 = navigateToDns();

        VaadinTestUiContext ui2 = app.newUser().newWindow();
        springContext.getBean(UserSession.class).setPassword(PASSWORD);
        DnsView view2 = ui2.navigate(DnsView.class);
        view2.setCompetition(PASSWORD);

        // Valitaan sama juoksija molemmista näkymistä
        ui.activate();
        RunnerCard card1 = view1.getCardsByBib().values().iterator().next();
        int bib = card1.getBibNumber();

        ui2.activate();
        RunnerCard card2 = view2.getCardsByBib().get(bib);

        // Jos card2 on null, juoksija on eri lähtöajassa — ohitetaan
        if (card2 == null) return;

        assertFalse(card1.isStarted());
        assertFalse(card2.isStarted());

        ui.activate();
        view1.onCardClicked(card1);
        assertTrue(card1.isStarted());

        ui2.activate();
        view2.syncCardsFromSignal();
        assertTrue(card2.isStarted(),
                "Toisen käyttäjän kortin pitäisi päivittyä signaalin kautta");
    }

    // --- Kommentti ---

    @Test
    void kirjausKommentillaTallentaaKommentin() {
        navigateToDns();

        // Kirjataan juoksija lähteneeksi kommentilla (kuten info-popoverin kautta)
        int bib = dnsService.getStartedBibs(PASSWORD).isEmpty()
                ? 1 : dnsService.getStartedBibs(PASSWORD).iterator().next();
        // Käytetään uutta bibiä joka ei ole vielä kirjattu
        bib = 1;
        assertFalse(dnsService.isStarted(PASSWORD, bib));

        dnsService.markStarted(PASSWORD, bib, "Testikäyttäjä", "Annettu emit 12345 rikkoutuneen tilalle");

        assertTrue(dnsService.isStarted(PASSWORD, bib));
        var entry = dnsService.getEntry(PASSWORD, bib);
        assertTrue(entry.isPresent(), "Kirjauksen pitäisi löytyä");
        assertEquals("Annettu emit 12345 rikkoutuneen tilalle", entry.get().getComment(),
                "Kommentti pitäisi tallentua kirjauksen yhteydessä");
    }

    @Test
    void kommentinMuokkausJoLahteneelleJuoksijalle() {
        navigateToDns();

        int bib = 1;
        dnsService.markStarted(PASSWORD, bib, "Testikäyttäjä");
        assertTrue(dnsService.isStarted(PASSWORD, bib));

        // Kommentin pitäisi olla tyhjä aluksi
        var entry = dnsService.getEntry(PASSWORD, bib);
        assertTrue(entry.isPresent());
        assertNull(entry.get().getComment(), "Kommentti pitäisi olla aluksi tyhjä");

        // Päivitetään kommentti (kuten info-popoverin Tallenna-nappi tekee)
        dnsService.updateComment(PASSWORD, bib, "Juoksija tuli takaisin hakemaan karttaa");

        entry = dnsService.getEntry(PASSWORD, bib);
        assertTrue(entry.isPresent());
        assertEquals("Juoksija tuli takaisin hakemaan karttaa", entry.get().getComment(),
                "Kommentin pitäisi päivittyä");
    }

    @Test
    void uusiKayttajaNakeeAiemmanMerkinnan() {
        DnsView view1 = navigateToDns();
        RunnerCard card1 = view1.getCardsByBib().values().iterator().next();
        int bib = card1.getBibNumber();
        view1.onCardClicked(card1);

        // Toinen käyttäjä avaa saman kisan
        VaadinTestUiContext ui2 = app.newUser().newWindow();
        springContext.getBean(UserSession.class).setPassword(PASSWORD);
        DnsView view2 = ui2.navigate(DnsView.class);
        view2.setCompetition(PASSWORD);

        RunnerCard card2 = view2.getCardsByBib().get(bib);
        if (card2 == null) return; // eri lähtöaika

        assertTrue(card2.isStarted(),
                "Uuden käyttäjän pitäisi nähdä aiemmin merkitty juoksija lähteneenä");
    }

    // --- WebSerial Emit-kortinluku ---

    /**
     * Etsii lähtölistasta kontrollikortin numeron juoksijalle, joka kuuluu
     * annettuun bib-numeroon.
     */
    private Optional<Integer> findControlCardForBib(int bib) {
        var startList = tulospalveluService.getStartList(COMPETITION_ID);
        if (startList == null) return Optional.empty();
        for (var cs : startList.getClassStart()) {
            for (var ps : cs.getPersonStart()) {
                for (var rs : ps.getStart()) {
                    int parsed = rs.getBibNumber() != null && !rs.getBibNumber().isBlank()
                            ? Integer.parseInt(rs.getBibNumber().trim()) : 0;
                    if (parsed == bib && rs.getControlCard() != null) {
                        for (var cc : rs.getControlCard()) {
                            if (cc.getValue() != null && !cc.getValue().isBlank()) {
                                try {
                                    return Optional.of(Integer.parseInt(cc.getValue().trim()));
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Test
    void tunnetunKortinLukuMerkitseeNykyisenLahtoajanJuoksijanLahteneeksi() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getCardsByBib().values().iterator().next();
        int bib = card.getBibNumber();
        Optional<Integer> ccOpt = findControlCardForBib(bib);
        if (ccOpt.isEmpty()) return; // Skip jos testidatassa ei ole kontrollikorttia

        assertFalse(card.isStarted());

        view.handleCardReading(ccOpt.get());

        assertTrue(card.isStarted(),
                "Nykyisen lähtöajan juoksijan pitäisi merkitä lähteneeksi ilman dialogia");
        assertTrue(dnsService.isStarted(PASSWORD, bib));

        // Nykyisellä lähtöajalla olevasta kortista ei näytetä dialogia
        assertTrue(ui.get(ConfirmDialog.class).all().isEmpty(),
                "Nykyiselle lähtöajalle kuuluvasta kortista ei pidä näyttää dialogia");

        // Lukutapahtuma kirjattu palvelimelle
        List<MachineReading> readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertFalse(readings.isEmpty(), "Lukutapahtuma pitäisi tallentua kantaan");
        assertTrue(readings.getFirst().isFound());
        assertEquals(bib, readings.getFirst().getBib());
    }

    @Test
    void tuntemattomanKortinLukuNayttaaDialoginJaKirjautuuPalvelimelle() {
        DnsView view = navigateToDns();

        int unknownCc = 999_999;
        view.handleCardReading(unknownCc);

        List<ConfirmDialog> dialogs = ui.get(ConfirmDialog.class).all();
        assertFalse(dialogs.isEmpty(), "Tuntemattomalle kortille pitäisi avautua dialogi");

        // Lukutapahtuma kirjattu palvelimelle löytymättömänä (kuten API:n kautta)
        List<MachineReading> readings = machineReadingRepository.findByPasswordAndFoundFalseOrderByReadAtDesc(PASSWORD);
        assertFalse(readings.isEmpty(), "Tuntematon luku pitäisi tallentaa palvelimelle");
        assertEquals(String.valueOf(unknownCc), readings.getFirst().getCc());
        assertFalse(readings.getFirst().isFound());
    }

    @Test
    void samanKortinPerakkainenLukuJatetaanHuomiotta() {
        DnsView view = navigateToDns();
        long[] now = {1_000_000};
        view.setClock(() -> now[0]);

        int unknownCc = 888_888;

        view.handleCardReading(unknownCc);
        long firstReadingCount = machineReadingRepository.count();
        int firstDialogCount = ui.get(ConfirmDialog.class).all().size();

        // 1 sekunnin päästä — saman kortin pitäisi jäädä huomiotta
        now[0] += 1_000;
        view.handleCardReading(unknownCc);
        assertEquals(firstReadingCount, machineReadingRepository.count(),
                "Saman kortin perakkäisestä luvusta ei saa syntyä uutta backend-kirjausta");
        assertEquals(firstDialogCount, ui.get(ConfirmDialog.class).all().size(),
                "Saman kortin perakkäisestä luvusta ei saa avautua uutta dialogia");

        // 5 sekunnin tauon jälkeen sama kortti pitää käsitellä uudelleen
        now[0] += DnsView.CARD_READ_DEBOUNCE_MS + 1;
        view.handleCardReading(unknownCc);
        assertEquals(firstReadingCount + 1, machineReadingRepository.count(),
                "5 sekunnin tauon jälkeen saman kortin luku pitää käsitellä uudelleen");

        // Eri kortti rikkoo debounce-ikkunan välittömästi
        now[0] += 100;
        view.handleCardReading(777_777);
        assertEquals(firstReadingCount + 2, machineReadingRepository.count(),
                "Eri kortin luku käsitellään välittömästi");
    }

    @Test
    void eriLahtoajanKortinLukuNayttaaDialoginMuttaMerkitseeLahteneeksi() {
        DnsView view = navigateToDns();

        // Etsi juoksija, joka EI ole nykyisessä lähtöajassa
        int currentBib = view.getCardsByBib().keySet().iterator().next();
        var currentCard = view.getCardsByBib().get(currentBib);

        Integer offSlotBib = null;
        Integer offSlotCc = null;
        var startList = tulospalveluService.getStartList(COMPETITION_ID);
        outer:
        for (var cs : startList.getClassStart()) {
            for (var ps : cs.getPersonStart()) {
                for (var rs : ps.getStart()) {
                    if (rs.getBibNumber() == null || rs.getBibNumber().isBlank()) continue;
                    int bib = Integer.parseInt(rs.getBibNumber().trim());
                    if (view.getCardsByBib().containsKey(bib)) continue;
                    if (rs.getControlCard() == null || rs.getControlCard().isEmpty()) continue;
                    String ccVal = rs.getControlCard().getFirst().getValue();
                    if (ccVal == null || ccVal.isBlank()) continue;
                    try {
                        offSlotCc = Integer.parseInt(ccVal.trim());
                        offSlotBib = bib;
                        break outer;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (offSlotBib == null) return; // Ei sopivaa testidataa

        assertNotNull(currentCard);
        view.handleCardReading(offSlotCc);

        List<ConfirmDialog> dialogs = ui.get(ConfirmDialog.class).all();
        assertFalse(dialogs.isEmpty(),
                "Eri lähtöajassa olevasta juoksijasta pitäisi näyttää dialogi");
        assertTrue(dnsService.isStarted(PASSWORD, offSlotBib),
                "Juoksija pitäisi merkitä lähteneeksi vaikka eri lähtöajassa");
    }
}
