package com.dns.raspireader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sends card numbers to the REST endpoint.
 * Uses JDK built-in HttpClient.
 */
public class RestSender {

    private static final Logger LOG = Logger.getLogger(RestSender.class.getName());

    private final String baseUrl;
    private final String password;
    private final String machineId;
    private final HttpClient httpClient;

    public enum SendResult { RUNNER_FOUND, CARD_NOT_REGISTERED, RUNNER_NOT_FOUND, FAILED }

    public record SendResponse(SendResult result, LocalTime startTime) {
        static SendResponse of(SendResult result) {
            return new SendResponse(result, null);
        }
    }

    private static final Pattern START_TIME_PATTERN = Pattern.compile("\"startTime\"\\s*:\\s*\"([^\"]+)\"");

    public RestSender(String baseUrl, String password, String machineId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.password = password;
        this.machineId = machineId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Send a list of card numbers to the server.
     */
    public SendResponse send(List<Integer> cardNumbers) {
        if (cardNumbers.isEmpty()) {
            return SendResponse.of(SendResult.RUNNER_NOT_FOUND);
        }

        String json = cardNumbers.stream()
                .map(cc -> "{\"cc\":" + cc + "}")
                .collect(Collectors.joining(",", "[", "]"));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/machine-reading"))
                    .header("Content-Type", "application/json")
                    .header("X-Competition-Password", password)
                    .header("X-Machine-Id", machineId)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            LOG.info("Response " + response.statusCode() + ": " + response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                if (body != null && body.contains("\"found\":false")) {
                    return SendResponse.of(SendResult.CARD_NOT_REGISTERED);
                }
                if (body != null && body.contains("\"bib\"")) {
                    LocalTime startTime = parseStartTime(body);
                    return new SendResponse(SendResult.RUNNER_FOUND, startTime);
                }
                return SendResponse.of(SendResult.RUNNER_NOT_FOUND);
            } else {
                LOG.warning("Server returned error " + response.statusCode());
                return SendResponse.of(SendResult.FAILED);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send card numbers: " + e.getMessage());
            return SendResponse.of(SendResult.FAILED);
        }
    }

    private LocalTime parseStartTime(String body) {
        Matcher matcher = START_TIME_PATTERN.matcher(body);
        if (matcher.find()) {
            String timeStr = matcher.group(1).trim();
            if (!timeStr.isEmpty()) {
                try {
                    return LocalTime.parse(timeStr);
                } catch (Exception e) {
                    LOG.warning("Failed to parse startTime: " + timeStr);
                }
            }
        }
        return null;
    }
}
