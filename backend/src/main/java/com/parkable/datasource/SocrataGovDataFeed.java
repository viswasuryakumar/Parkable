package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Generic Socrata SODA feed. Pages are fetched lazily as the caller iterates. */
public final class SocrataGovDataFeed implements GovDataFeed {
    private static final int DEFAULT_PAGE_SIZE = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final int pageSize;
    private final HttpClient client;
    private final String appToken;

    public SocrataGovDataFeed(String domain, String datasetId) {
        this(domain, datasetId, DEFAULT_PAGE_SIZE, HttpClient.newHttpClient(), System.getenv("SOCRATA_APP_TOKEN"));
    }

    SocrataGovDataFeed(String domain, String datasetId, int pageSize, HttpClient client, String appToken) {
        this.endpoint = URI.create(normalizeDomain(domain) + "/resource/" + requireDatasetId(datasetId) + ".json");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.pageSize = pageSize;
        this.client = Objects.requireNonNull(client, "client");
        this.appToken = appToken == null || appToken.isBlank() ? null : appToken;
    }

    @Override
    public Iterator<JsonNode> fetchAll() {
        return new Iterator<>() {
            private List<JsonNode> page = List.of();
            private int index;
            private int offset;
            private boolean complete;

            @Override
            public boolean hasNext() {
                while (index == page.size() && !complete) {
                    page = fetchPage(offset);
                    index = 0;
                    offset += page.size();
                    if (page.size() < pageSize) {
                        complete = true;
                    }
                }
                return index < page.size();
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return page.get(index++);
            }
        };
    }

    private List<JsonNode> fetchPage(int offset) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        endpoint + "?$limit=" + pageSize + "&$offset=" + offset))
                .GET()
                .header("Accept", "application/json");
        if (appToken != null) {
            request.header("X-App-Token", appToken);
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Socrata request failed with HTTP " + response.statusCode());
            }
            JsonNode body = JSON.readTree(response.body());
            if (!body.isArray()) {
                throw new IllegalStateException("Socrata response was not a JSON array");
            }
            return java.util.stream.StreamSupport.stream(body.spliterator(), false).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read Socrata response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading Socrata response", e);
        }
    }

    private static String normalizeDomain(String domain) {
        Objects.requireNonNull(domain, "domain");
        String value = domain.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String requireDatasetId(String datasetId) {
        Objects.requireNonNull(datasetId, "datasetId");
        if (!datasetId.matches("[a-z0-9]{4}-[a-z0-9]{4}")) {
            throw new IllegalArgumentException("datasetId must be a Socrata 4x4 identifier");
        }
        return datasetId;
    }
}
