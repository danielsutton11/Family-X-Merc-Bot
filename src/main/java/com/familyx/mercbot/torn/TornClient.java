package com.familyx.mercbot.torn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thin wrapper over the Torn v2 API. It knows nothing about which key to use;
 * {@link TornKeyPool} picks keys and handles rotation.
 */
@Component
public class TornClient {

    private static final String BASE = "https://api.torn.com/v2";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();
    private final ObjectMapper mapper;

    public TornClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param path   path under /v2, for example "/user/profile" or "/faction/9357/members"
     * @param params extra query parameters (the key is added here)
     */
    public JsonNode get(String path, Map<String, String> params, String apiKey) throws TornApiException {
        Map<String, String> query = new LinkedHashMap<>(params);
        query.put("comment", "FamilyXMercBot");
        query.put("key", apiKey);
        String qs = query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path + "?" + qs))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = mapper.readTree(response.body());
            JsonNode error = body.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new TornApiException(error.path("code").asInt(-1), error.path("error").asText("Unknown Torn error"));
            }
            return body;
        } catch (IOException e) {
            throw new TornApiException(-1, "Torn API unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TornApiException(-1, "Interrupted");
        }
    }
}
