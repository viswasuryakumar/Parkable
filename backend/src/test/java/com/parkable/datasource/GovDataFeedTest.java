package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GovDataFeedTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void socrataFeedFetchesEveryLimitOffsetPageAndUsesConfiguredToken() throws IOException {
        List<String> queries = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        String baseUrl = start("/resource/abcd-1234.json", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            tokens.add(exchange.getRequestHeaders().getFirst("X-App-Token"));
            write(exchange, exchange.getRequestURI().getQuery().contains("$offset=0")
                    ? "[{\"id\":1},{\"id\":2}]" : "[{\"id\":3}]");
        });

        GovDataFeed feed = new SocrataGovDataFeed(baseUrl, "abcd-1234", 2, HttpClient.newHttpClient(), "fixture-token");

        assertThat(drain(feed.fetchAll()).stream().map(node -> node.path("id").asInt()).toList())
                .containsExactly(1, 2, 3);
        assertThat(queries).containsExactly("$limit=2&$offset=0", "$limit=2&$offset=2");
        assertThat(tokens).containsOnly("fixture-token");
    }

    @Test
    void arcGisFeedFetchesEveryResultOffsetPageAndRetainsFeatureGeometry() throws IOException {
        List<String> queries = new ArrayList<>();
        String baseUrl = start("/query", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            write(exchange, exchange.getRequestURI().getQuery().contains("resultOffset=0")
                    ? "{\"features\":[{\"attributes\":{\"id\":1},\"geometry\":{\"x\":1}},"
                    + "{\"attributes\":{\"id\":2},\"geometry\":{\"x\":2}}],\"exceededTransferLimit\":true}"
                    : "{\"features\":[{\"attributes\":{\"id\":3},\"geometry\":{\"x\":3}}],\"exceededTransferLimit\":false}");
        }) + "/query";

        GovDataFeed feed = new ArcGisGovDataFeed(baseUrl + "?custom=value", 2, HttpClient.newHttpClient());

        List<JsonNode> features = drain(feed.fetchAll());
        assertThat(features.stream().map(node -> node.path("attributes").path("id").asInt()).toList())
                .containsExactly(1, 2, 3);
        assertThat(features.getFirst().path("geometry").path("x").asInt()).isEqualTo(1);
        assertThat(queries).allSatisfy(query -> assertThat(query)
                .contains("custom=value", "where=1=1", "outFields=*", "returnGeometry=true", "outSR=4326", "f=json"));
        assertThat(queries).anySatisfy(query -> assertThat(query).contains("resultOffset=0", "resultRecordCount=2"));
        assertThat(queries).anySatisfy(query -> assertThat(query).contains("resultOffset=2", "resultRecordCount=2"));
    }

    private String start(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, handler);
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static List<JsonNode> drain(java.util.Iterator<JsonNode> iterator) {
        List<JsonNode> values = new ArrayList<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
