package io.github.devpanel.insight.outbound;

import feign.Capability;
import feign.Client;
import feign.Response;
import java.io.IOException;

/**
 * Times every Feign call. Spring Cloud OpenFeign applies {@link Capability} beans to all clients,
 * so the application changes nothing.
 */
public class FeignOutboundCapability implements Capability {

  private final OutboundStats stats;

  public FeignOutboundCapability(OutboundStats stats) {
    this.stats = stats;
  }

  @Override
  public Client enrich(Client client) {
    return (request, options) -> {
      long start = System.currentTimeMillis();
      try {
        Response response = client.execute(request, options);
        stats.success(request.url(), response.status(), System.currentTimeMillis() - start);
        return response;
      } catch (IOException | RuntimeException e) {
        stats.failure(request.url(), e, System.currentTimeMillis() - start);
        throw e;
      }
    };
  }
}
