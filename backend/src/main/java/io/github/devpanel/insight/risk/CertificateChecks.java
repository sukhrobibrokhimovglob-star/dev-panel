package io.github.devpanel.insight.risk;

import io.github.devpanel.insight.deps.DependencyChecks;
import io.github.devpanel.insight.outbound.OutboundStats;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * When the HTTPS certificates of the services this application calls expire (the hosts seen by
 * the Feign/RestTemplate statistics plus the configured dependencies). An expired certificate
 * stops every call to that service at once; seeing it weeks ahead is the point. Checked twice a
 * day per host.
 */
public class CertificateChecks {

  private static final Duration RECHECK = Duration.ofHours(12);
  private static final int TIMEOUT_MS = 5_000;
  private static final int MAX_HOSTS = 30;

  private final OutboundStats outbound;
  private final DependencyChecks dependencies;
  private final Map<String, Result> results = new ConcurrentHashMap<>();

  /** Either may be null. */
  public CertificateChecks(OutboundStats outbound, DependencyChecks dependencies) {
    this.outbound = outbound;
    this.dependencies = dependencies;
  }

  public List<Result> check() {
    Set<String> hosts = new LinkedHashSet<>();
    if (outbound != null) {
      outbound.list().forEach(target -> add(hosts, target.host()));
    }
    if (dependencies != null) {
      dependencies.list().forEach(target -> add(hosts, target.address()));
    }
    List<Result> current = new ArrayList<>();
    for (String host : hosts) {
      if (current.size() >= MAX_HOSTS) {
        break;
      }
      Result cached = results.get(host);
      if (cached == null || cached.checkedAt().isBefore(Instant.now().minus(RECHECK))) {
        cached = fetch(host);
        results.put(host, cached);
      }
      current.add(cached);
    }
    return current;
  }

  private static void add(Set<String> hosts, String url) {
    if (url == null || !url.toLowerCase().startsWith("https://")) {
      return;
    }
    try {
      URI uri = URI.create(url);
      if (uri.getHost() != null) {
        hosts.add(uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 443));
      }
    } catch (IllegalArgumentException ignored) {
      // not a URL
    }
  }

  static Result fetch(String hostAndPort) {
    int colon = hostAndPort.lastIndexOf(':');
    String host = hostAndPort.substring(0, colon);
    int port = Integer.parseInt(hostAndPort.substring(colon + 1));
    try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
      socket.connect(new java.net.InetSocketAddress(host, port), TIMEOUT_MS);
      socket.setSoTimeout(TIMEOUT_MS);
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setServerNames(List.of(new SNIHostName(host)));
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      Certificate[] chain = socket.getSession().getPeerCertificates();
      if (chain.length > 0 && chain[0] instanceof X509Certificate leaf) {
        Instant notAfter = leaf.getNotAfter().toInstant();
        return new Result(hostAndPort, notAfter,
            Duration.between(Instant.now(), notAfter).toHours() / 24.0,
            leaf.getSubjectX500Principal().getName(), leaf.getIssuerX500Principal().getName(),
            null, Instant.now());
      }
      return new Result(hostAndPort, null, -1, null, null, "No certificate", Instant.now());
    } catch (Exception e) {
      // An untrusted or expired certificate fails the handshake: that is a finding too
      return new Result(hostAndPort, null, -1, null, null,
          e.getClass().getSimpleName() + ": " + e.getMessage(), Instant.now());
    }
  }

  /** {@code daysLeft}: until {@code expires}; -1 when the certificate could not be read. */
  public record Result(String host, Instant expires, double daysLeft, String subject,
                       String issuer, String error, Instant checkedAt) {
  }
}
