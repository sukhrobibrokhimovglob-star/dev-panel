package io.github.devpanel.insight.outbound;

import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

/**
 * Times calls made through any RestTemplate bean of the application, however it was built
 * ({@code new RestTemplateBuilder()}, {@code new RestTemplate()}, Boot's builder). A RestTemplate
 * created inside a method on every call, never as a bean, is not seen.
 */
public class RestTemplateOutbound implements BeanPostProcessor {

  private final ObjectProvider<OutboundStats> stats;

  /** Lazy: a post-processor is created before most beans, the statistics included. */
  public RestTemplateOutbound(ObjectProvider<OutboundStats> stats) {
    this.stats = stats;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof RestTemplate restTemplate && restTemplate.getInterceptors().stream()
        .noneMatch(Interceptor.class::isInstance)) {
      restTemplate.getInterceptors().add(new Interceptor(stats));
    }
    return bean;
  }

  private record Interceptor(ObjectProvider<OutboundStats> stats)
      implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
        ClientHttpRequestExecution execution) throws IOException {
      long start = System.currentTimeMillis();
      OutboundStats target = stats.getIfAvailable();
      try {
        ClientHttpResponse response = execution.execute(request, body);
        if (target != null) {
          target.success(request.getURI().toString(), response.getRawStatusCode(),
              System.currentTimeMillis() - start);
        }
        return response;
      } catch (IOException | RuntimeException e) {
        if (target != null) {
          target.failure(request.getURI().toString(), e, System.currentTimeMillis() - start);
        }
        throw e;
      }
    }
  }
}
