package com.example.dns.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import org.orienteering.datastandard._3.PersonStart;
import org.orienteering.datastandard._3.StartList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Year;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

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
    private final Map<String, String> createTimeCache = new ConcurrentHashMap<>();
    private final Map<String, FileTime> diskWriteTimeCache = new ConcurrentHashMap<>();
    private final Set<String> locallyOverridden = ConcurrentHashMap.newKeySet();
    private final List<BiConsumer<String, Integer>> startListUpdateListeners = new CopyOnWriteArrayList<>();
    private volatile List<CompetitionInfo> eventsCache;
    private volatile Instant eventsCacheTime;

    public TulospalveluService() throws JAXBException {
        this.jaxbContext = JAXBContext.newInstance(StartList.class);
    }

    @SuppressWarnings("unchecked")
    public List<CompetitionInfo> getOrienteeringEvents() {
        if (eventsCache != null && eventsCacheTime != null
                && Instant.now().isBefore(eventsCacheTime.plusSeconds(5 * 60))) {
            return eventsCache;
        }
        try {
            String url = EVENTS_URL + Year.now().getValue();
            log.info("Downloading events from {}", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            Map<String, Object> root = objectMapper.readValue(body, Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");

            eventsCache = data.stream()
                    .map(CompetitionInfo::fromJson)
                    .filter(c -> "Orienteering".equals(c.discipline()))
//                    .filter(c -> c.startListUrl() != null)
                    .toList();
            eventsCacheTime = Instant.now();
            return eventsCache;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to fetch events", e);
            if (eventsCache != null) {
                return eventsCache;
            }
            return Collections.emptyList();
        }
    }

    public StartList getStartList(String competitionId) {
        return getStartList(competitionId, 1);
    }

    public StartList getStartList(String competitionId, int stage) {
        return startListCache.computeIfAbsent(cacheKey(competitionId, stage),
                k -> loadStartList(competitionId, stage));
    }

    /**
     * Scheduled task: refreshes all cached start lists every 10 minutes.
     * Downloads fresh XML and compares createTime — if changed, updates
     * cache and notifies listeners.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void refreshAllStartLists() {
        if (startListCache.isEmpty()) {
            return;
        }
        log.info("Scheduled start list refresh for {} competitions", startListCache.size());
        for (String key : startListCache.keySet()) {
            int idx = key.lastIndexOf('_');
            String competitionId = key.substring(0, idx);
            int stage = Integer.parseInt(key.substring(idx + 1));
            refreshStartList(competitionId, stage);
        }
    }

    private void refreshStartList(String competitionId, int stage) {
        String key = cacheKey(competitionId, stage);
        String currentCreateTime = createTimeCache.get(key);
        log.info("Checking start list updates for {} stage {} (current createTime={})",
                competitionId, stage, currentCreateTime);

        // Check local disk cache first — if file was modified externally, use it
        // and skip the HTTP call (useful for testing: edit XML by hand)
        if (checkLocalDiskCache(competitionId, stage, currentCreateTime)) {
            return;
        }

        // If locally overridden (manual edit active), skip server entirely
        if (locallyOverridden.contains(key)) {
            log.info("[TEST CHECK] Skipping server refresh for {} stage {} — local override active",
                    competitionId, stage);
            return;
        }

        // Disk unchanged — check remote server
        try {
            StartList fresh = downloadStartList(competitionId, stage);
            if (fresh == null) {
                return;
            }
            String freshCreateTime = extractCreateTime(fresh);

            if (!Objects.equals(freshCreateTime, currentCreateTime)) {
                log.info("Start list updated from server for {} stage {} (createTime {} -> {})",
                        competitionId, stage, currentCreateTime, freshCreateTime);
                logStartListDiff(competitionId, startListCache.get(key), fresh);
                startListCache.put(key, fresh);
                createTimeCache.put(key, freshCreateTime);
                writeDiskCache(competitionId, stage, fresh);
                notifyStartListUpdated(competitionId, stage);
            } else {
                log.info("Start list unchanged for {} stage {} (createTime={})",
                        competitionId, stage, currentCreateTime);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh start list for {} stage {}, using cached version",
                    competitionId, stage, e);
        }
    }

    private static String cacheKey(String competitionId, int stage) {
        return competitionId + "_" + stage;
    }

    private static String cacheFileName(String competitionId, int stage) {
        return "startlist_" + competitionId + "_" + stage + ".xml";
    }

    /**
     * Checks if the local disk cache file was modified externally (e.g. by hand
     * for testing). Compares the file's last modified time against when we last
     * wrote it. If modified externally, loads the file and updates in-memory cache.
     * Returns true if the local file was updated (skipping the remote HTTP call).
     */
    private boolean checkLocalDiskCache(String competitionId, int stage, String currentCreateTime) {
        String key = cacheKey(competitionId, stage);
        Path cached = CACHE_DIR.resolve(cacheFileName(competitionId, stage));
        if (!Files.exists(cached)) {
            return false;
        }
        try {
            FileTime fileModified = Files.getLastModifiedTime(cached);
            FileTime lastWritten = diskWriteTimeCache.get(key);

            if (lastWritten != null && fileModified.compareTo(lastWritten) <= 0) {
                // File hasn't been touched since we wrote it
                return false;
            }

            // File was modified externally — parse and check createTime
            log.info("[TEST CHECK] Local disk file modified externally for {} stage {} (fileTime={}, lastWritten={})",
                    competitionId, stage, fileModified, lastWritten);

            try (InputStream is = Files.newInputStream(cached)) {
                var diskStartList = (StartList) jaxbContext.createUnmarshaller().unmarshal(is);
                String diskCreateTime = extractCreateTime(diskStartList);

                if (!Objects.equals(diskCreateTime, currentCreateTime)) {
                    log.info("[TEST CHECK] Start list updated from local disk for {} stage {} (createTime {} -> {}), local override active",
                            competitionId, stage, currentCreateTime, diskCreateTime);
                    logStartListDiff(competitionId, startListCache.get(key), diskStartList);
                    startListCache.put(key, diskStartList);
                    createTimeCache.put(key, diskCreateTime);
                    diskWriteTimeCache.put(key, fileModified);
                    locallyOverridden.add(key);
                    notifyStartListUpdated(competitionId, stage);
                    return true;
                } else {
                    log.info("[TEST CHECK] Local disk file touched but createTime unchanged for {} stage {} (createTime={})",
                            competitionId, stage, diskCreateTime);
                    diskWriteTimeCache.put(key, fileModified);
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read local disk cache for {} stage {}", competitionId, stage, e);
        }
        return false;
    }

    private StartList loadStartList(String competitionId, int stage) {
        String key = cacheKey(competitionId, stage);
        String fileName = cacheFileName(competitionId, stage);
        try {
            Path cached = CACHE_DIR.resolve(fileName);
            if (Files.exists(cached)) {
                log.info("Loading start list from disk cache: {}", cached);
                try (InputStream is = Files.newInputStream(cached)) {
                    var startList = (StartList) jaxbContext.createUnmarshaller().unmarshal(is);
                    createTimeCache.put(key, extractCreateTime(startList));
                    diskWriteTimeCache.put(key, Files.getLastModifiedTime(cached));
                    return startList;
                }
            }

            StartList startList = downloadStartList(competitionId, stage);
            if (startList == null) {
                return null;
            }
            createTimeCache.put(key, extractCreateTime(startList));
            writeDiskCache(competitionId, stage, startList);
            return startList;
        } catch (IOException | JAXBException e) {
            log.error("Failed to fetch start list for {} stage {}", competitionId, stage, e);
            return null;
        }
    }

    private StartList downloadStartList(String competitionId, int stage) {
        CompetitionInfo competition = getOrienteeringEvents().stream()
                .filter(c -> c.eventId().equals(competitionId))
                .findFirst()
                .orElse(null);

        // Stage 1 may have a server-provided URL in the events JSON; for stage > 1
        // we always derive the URL from the IOF naming convention.
        String url;
        if (stage == 1 && competition != null && competition.startListUrl() != null) {
            url = BASE_URL + competition.startListUrl();
        } else {
            // https://online.tulospalvelu.fi/tulokset-new/xml/startlist_2026_viking_1_iof.xml
            url = BASE_URL + "/tulokset-new/xml/startlist_" + competitionId + "_" + stage + "_iof.xml";
        }

        try {
            log.info("Downloading start list from {}", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            try (InputStream is = new ByteArrayInputStream(response.body())) {
                return (StartList) jaxbContext.createUnmarshaller().unmarshal(is);
            }
        } catch (IOException | InterruptedException | JAXBException e) {
            log.error("Failed to download start list for {} stage {}", competitionId, stage, e);
            return null;
        }
    }

    private void logStartListDiff(String competitionId, StartList oldList, StartList newList) {
        var oldRunners = extractRunnerSnapshot(oldList);
        var newRunners = extractRunnerSnapshot(newList);

        int changedStartTime = 0;
        int changedEmit = 0;
        int added = 0;
        int removed = 0;

        for (var entry : newRunners.entrySet()) {
            int bib = entry.getKey();
            var newInfo = entry.getValue();
            var oldInfo = oldRunners.get(bib);
            if (oldInfo == null) {
                added++;
            } else {
                if (!Objects.equals(oldInfo.startTime(), newInfo.startTime())) {
                    changedStartTime++;
                    log.info("  bib {}: lähtöaika {} -> {}", bib, oldInfo.startTime(), newInfo.startTime());
                }
                if (!Objects.equals(oldInfo.emit(), newInfo.emit())) {
                    changedEmit++;
                    log.info("  bib {}: emit {} -> {}", bib, oldInfo.emit(), newInfo.emit());
                }
            }
        }
        for (int bib : oldRunners.keySet()) {
            if (!newRunners.containsKey(bib)) {
                removed++;
            }
        }

        log.info("Start list diff for {}: {} muuttunutta lähtöaikaa, {} muuttunutta emittiä, {} lisätty, {} poistettu (yhteensä {} kilpailijaa)",
                competitionId, changedStartTime, changedEmit, added, removed, newRunners.size());
    }

    private record RunnerSnapshot(String startTime, String emit) {
    }

    private static Map<Integer, RunnerSnapshot> extractRunnerSnapshot(StartList startList) {
        if (startList == null) {
            return Map.of();
        }
        Map<Integer, RunnerSnapshot> map = new LinkedHashMap<>();
        for (var classStart : startList.getClassStart()) {
            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    int bib = parseBibSafe(raceStart.getBibNumber());
                    if (bib <= 0) continue;
                    String st = raceStart.getStartTime() != null
                            ? raceStart.getStartTime().toXMLFormat() : "";
                    String emit = "";
                    if (raceStart.getControlCard() != null && !raceStart.getControlCard().isEmpty()) {
                        emit = raceStart.getControlCard().getFirst().getValue();
                    }
                    map.put(bib, new RunnerSnapshot(st, emit));
                }
            }
        }
        return map;
    }

    private static int parseBibSafe(String bib) {
        if (bib == null || bib.isBlank()) return 0;
        try { return Integer.parseInt(bib.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String extractCreateTime(StartList startList) {
        if (startList == null || startList.getCreateTime() == null) {
            return null;
        }
        return startList.getCreateTime().toXMLFormat();
    }

    private void writeDiskCache(String competitionId, int stage, StartList startList) {
        String fileName = cacheFileName(competitionId, stage);
        try {
            Files.createDirectories(CACHE_DIR);
            Path path = CACHE_DIR.resolve(fileName);
            jaxbContext.createMarshaller().marshal(startList, path.toFile());
            diskWriteTimeCache.put(cacheKey(competitionId, stage), Files.getLastModifiedTime(path));
            log.info("Cached start list to {}", path);
        } catch (JAXBException | IOException e) {
            log.warn("Failed to write start list disk cache: {}", fileName, e);
        }
    }

    private void notifyStartListUpdated(String competitionId, int stage) {
        for (var listener : startListUpdateListeners) {
            try {
                listener.accept(competitionId, stage);
            } catch (Exception e) {
                log.warn("Start list update listener failed for {} stage {}", competitionId, stage, e);
            }
        }
    }

    /**
     * Registers a listener that is called with (competitionId, stage)
     * whenever a start list is refreshed with new data.
     */
    public void addStartListUpdateListener(BiConsumer<String, Integer> listener) {
        startListUpdateListeners.add(listener);
    }

    public void removeStartListUpdateListener(BiConsumer<String, Integer> listener) {
        startListUpdateListeners.remove(listener);
    }

    public void clearCache() {
        eventsCache = null;
        eventsCacheTime = null;
        startListCache.clear();
        createTimeCache.clear();
        diskWriteTimeCache.clear();
        locallyOverridden.clear();
    }
}
