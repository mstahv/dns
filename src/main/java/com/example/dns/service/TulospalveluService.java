package com.example.dns.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import org.orienteering.datastandard._3.StartList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TulospalveluService {

    private static final Logger log = LoggerFactory.getLogger(TulospalveluService.class);
    private static final String BASE_URL = "https://online.tulospalvelu.fi";
    private static final String EVENTS_URL = BASE_URL + "/tulokset-new/online/online_events_dt.json?Year=";
    private static final Path CACHE_DIR = Path.of("onlinecache");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JAXBContext jaxbContext;

    private final Map<String, StartList> startListCache = new ConcurrentHashMap<>();
    private List<CompetitionInfo> eventsCache;

    public TulospalveluService() throws JAXBException {
        this.jaxbContext = JAXBContext.newInstance(StartList.class);
    }

    @SuppressWarnings("unchecked")
    public List<CompetitionInfo> getOrienteeringEvents() {
        if (eventsCache != null) {
            return eventsCache;
        }
        try {
            String url = EVENTS_URL + Year.now().getValue();
            String json = fetchWithDiskCache("events_" + Year.now().getValue() + ".json", url);
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");

            eventsCache = data.stream()
                    .map(CompetitionInfo::fromJson)
                    .filter(c -> "Orienteering".equals(c.discipline()))
                    .filter(c -> c.startListUrl() != null)
                    .toList();
            return eventsCache;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to fetch events", e);
            return Collections.emptyList();
        }
    }

    public StartList getStartList(String competitionId) {
        return startListCache.computeIfAbsent(competitionId, this::fetchStartList);
    }

    private StartList fetchStartList(String competitionId) {
        CompetitionInfo competition = getOrienteeringEvents().stream()
                .filter(c -> c.eventId().equals(competitionId))
                .findFirst()
                .orElse(null);
        if (competition == null || competition.startListUrl() == null) {
            return null;
        }

        String url = BASE_URL + competition.startListUrl();
        String cacheFileName = "startlist_" + competitionId + ".xml";

        try {
            Path cached = CACHE_DIR.resolve(cacheFileName);
            if (Files.exists(cached)) {
                log.info("Loading start list from disk cache: {}", cached);
                try (InputStream is = Files.newInputStream(cached)) {
                    return (StartList) jaxbContext.createUnmarshaller().unmarshal(is);
                }
            }

            log.info("Downloading start list from {}", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();

            Files.createDirectories(CACHE_DIR);
            Files.write(cached, body);
            log.info("Cached start list to {}", cached);

            try (InputStream is = new java.io.ByteArrayInputStream(body)) {
                return (StartList) jaxbContext.createUnmarshaller().unmarshal(is);
            }
        } catch (IOException | InterruptedException | JAXBException e) {
            log.error("Failed to fetch start list for {}", competitionId, e);
            return null;
        }
    }

    private String fetchWithDiskCache(String fileName, String url)
            throws IOException, InterruptedException {
        Path cached = CACHE_DIR.resolve(fileName);
        if (Files.exists(cached)) {
            log.info("Loading from disk cache: {}", cached);
            return Files.readString(cached);
        }

        log.info("Downloading from {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        Files.createDirectories(CACHE_DIR);
        Files.writeString(cached, body);
        log.info("Cached to {}", cached);

        return body;
    }

    public void clearCache() {
        eventsCache = null;
        startListCache.clear();
    }
}
