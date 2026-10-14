package com.example.migration.mapping;

import com.example.migration.run.RunException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpAiClient implements AiClient {

  private final RestClient client;
  private final int maxAttempts;
  private final long backoffMs;

  public HttpAiClient(@Value("${migration.ai-url}") String baseUrl,
                      @Value("${migration.ai-max-attempts:3}") int maxAttempts,
                      @Value("${migration.ai-backoff-ms:200}") long backoffMs) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.maxAttempts = Math.max(1, maxAttempts);
    this.backoffMs = backoffMs;
  }

  @Override
  public MappingResult suggest(List<SourceColumn> sources, List<TargetColumn> targets) {
    RestClientException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return client.post().uri("/mapping/suggest")
          .body(Map.of("sources", sources, "targets", targets))
          .retrieve()
          .body(MappingResult.class);
      } catch (HttpClientErrorException e) {
        // A 4xx means our request is wrong; retrying the same request cannot help.
        throw new RunException(HttpStatus.BAD_GATEWAY, "AI service rejected the request: " + e.getStatusCode());
      } catch (RestClientException e) {
        last = e;
        if (attempt < maxAttempts) {
          pause(backoffMs * (1L << (attempt - 1)));
        }
      }
    }
    // The AI service is an optional helper: report it as 502 instead of failing the migration run.
    throw new RunException(HttpStatus.BAD_GATEWAY,
      "AI service unavailable after " + maxAttempts + " attempt(s): " + last.getMessage());
  }

  private static void pause(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
