package com.familyx.mercbot.bsp;

import com.familyx.mercbot.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Battle Stats Predictor (lol-manager.com) client: the same endpoints the BSP userscript uses.
 * Leaders are told at key entry that BSP-active keys are sent to BSP's server (scope doc 10.3).
 */
@Component
public class BspClient {

    private static final Logger log = LoggerFactory.getLogger(BspClient.class);
    private static final String BASE = "https://www.lol-manager.com/api/battlestats";

    public record Subscription(boolean active, Instant expiresAt) { }

    /** Result codes: 1 success, 2 too weak, 3 too strong, 5 Hall of Fame, 6 shared attacks; 0 and 4 are failures. */
    public record Prediction(long tbs, long score, int result) {
        public boolean usable() {
            return result != 0 && result != 4;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();
    private final ObjectMapper mapper;
    private final String version;

    public BspClient(ObjectMapper mapper, BotProperties props) {
        this.mapper = mapper;
        this.version = props.bspVersion() == null || props.bspVersion().isBlank() ? "9.4.7" : props.bspVersion();
    }

    public Optional<Subscription> subscription(String apiKey) {
        return fetch(BASE + "/user/" + enc(apiKey) + "/" + enc(version)).map(json -> new Subscription(
                json.path("SubscriptionActive").asBoolean(false),
                parseDate(json.path("SubscriptionEnd").asText(null))));
    }

    public Optional<Prediction> predict(String apiKey, long targetId) {
        return fetch(BASE + "/" + enc(apiKey) + "/" + targetId + "/" + enc(version)).map(json -> new Prediction(
                json.path("TBS").asLong(0),
                json.path("Score").asLong(0),
                json.path("Result").asInt(0)));
    }

    private Optional<JsonNode> fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2 || res.body() == null || res.body().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(res.body()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("BSP call failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** BSP returns dates with or without a zone; values without one are UTC. */
    static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (Exception alsoIgnored) {
                return null;
            }
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
