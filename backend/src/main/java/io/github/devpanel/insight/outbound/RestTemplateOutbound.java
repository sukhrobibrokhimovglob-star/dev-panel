package io.github.devpanel.insight.outbound;

import java.io.IOException;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

/**
 * Times RestTemplate calls. Applies to RestTemplates built with Spring Boot's
 * {@code RestTemplateBuilder}; one created with {@code new RestTemplate()} is not seen.
 */
public class RestTemplateOutbound implements RestTemplateCustomizer, ClientHttpRequestInterceptor {

  private final OutboundStats stats;

  public RestTemplateOutbound(OutboundStats stats) {
    this.stats = stats;
  }

  @Override
  public void customize(RestTemplate restTemplate) {
    restTemplate.getInterceptors().add(this);
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    long start = System.currentTimeMillis();
    try {
      ClientHttpResponse response = execution.execute(request, body);
      stats.success(request.getURI().toString(), response.getRawStatusCode(),
          System.currentTimeMillis() - start);
      return response;
    } catch (IOException | RuntimeException e) {
      stats.failure(request.getURI().toString(), e, System.currentTimeMillis() - start);
      throw e;
    }
  }
}
