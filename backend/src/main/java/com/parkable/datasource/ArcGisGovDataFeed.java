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

/** Generic ArcGIS FeatureServer query feed. Returned records retain attributes and geometry. */
public final class ArcGisGovDataFeed implements GovDataFeed {
    private static final int DEFAULT_PAGE_SIZE = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI queryEndpoint;
    private final int pageSize;
    private final HttpClient client;

    public ArcGisGovDataFeed(String queryUrl) {
        this(queryUrl, DEFAULT_PAGE_SIZE, HttpClient.newHttpClient());
    }

    ArcGisGovDataFeed(String queryUrl, int pageSize, HttpClient client) {
        this.queryEndpoint = URI.create(Objects.requireNonNull(queryUrl, "queryUrl"));
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.pageSize = pageSize;
        this.client = Objects.requireNonNull(client, "client");
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
                    Page fetched = fetchPage(offset);
                    page = fetched.features();
                    index = 0;
                    offset += page.size();
                    complete = page.isEmpty() || (!fetched.exceededTransferLimit() && page.size() < pageSize);
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

    private Page fetchPage(int offset) {
        String separator = queryEndpoint.getQuery() == null || queryEndpoint.getQuery().isEmpty() ? "?" : "&";
        URI uri = URI.create(queryEndpoint + separator
                + "where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=json"
                + "&resultOffset=" + offset + "&resultRecordCount=" + pageSize);
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                    .GET().header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ArcGIS request failed with HTTP " + response.statusCode());
            }
            JsonNode body = JSON.readTree(response.body());
            if (body.has("error") || !body.path("features").isArray()) {
                throw new IllegalStateException("ArcGIS response did not contain a features array");
            }
            return new Page(java.util.stream.StreamSupport.stream(body.path("features").spliterator(), false).toList(),
                    body.path("exceededTransferLimit").asBoolean(false));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read ArcGIS response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading ArcGIS response", e);
        }
    }

    private record Page(List<JsonNode> features, boolean exceededTransferLimit) {}
}
