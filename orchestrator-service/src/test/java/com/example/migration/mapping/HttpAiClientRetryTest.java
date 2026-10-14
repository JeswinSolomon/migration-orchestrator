package com.example.migration.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.migration.run.RunException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HttpAiClientRetryTest {

  private HttpServer server;
  private final AtomicInteger calls = new AtomicInteger();

  private String start(int failFirst, int failStatus) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mapping/suggest", exchange -> {
      exchange.getRequestBody().readAllBytes();
      boolean fail = calls.incrementAndGet() <= failFirst;
      byte[] body = (fail ? "{}" : "{\"suggestions\":[],\"unmapped_sources\":[],\"unmapped_targets\":[],"
        + "\"llm_used\":false,\"warnings\":[]}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(fail ? failStatus : 200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void retriesTransientFailuresThenSucceeds() throws IOException {
    var client = new HttpAiClient(start(2, 503), 3, 1);
    assertThat(client.suggest(List.of(), List.of()).suggestions()).isEmpty();
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void givesUpAfterMaxAttemptsWith502() throws IOException {
    var client = new HttpAiClient(start(99, 503), 3, 1);
    assertThatThrownBy(() -> client.suggest(List.of(), List.of()))
      .isInstanceOfSatisfying(RunException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY))
      .hasMessageContaining("3 attempt");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void doesNotRetryClientErrors() throws IOException {
    var client = new HttpAiClient(start(99, 422), 3, 1);
    assertThatThrownBy(() -> client.suggest(List.of(), List.of())).isInstanceOf(RunException.class);
    assertThat(calls.get()).isEqualTo(1);
  }
}
