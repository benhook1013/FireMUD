package net.firedevops.firemud.filter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.firedevops.firemud.config.GatewayHeaderTrustProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Canonicalizes and de-spoofs gateway-owned identity headers.
 *
 * <p>Target-state invariants are documented in {@code
 * design/architecture/system-architecture-gateway.md} (Header Trust Model). This filter is the
 * gateway enforcement point for those invariants.
 */
@Component
public class HeaderTrustFilter implements WebFilter, Ordered {
  private static final String HDR_CLIENT_IP = "X-Client-IP";
  private static final String HDR_SESSION_ID = "X-Session-Id";
  private static final String HDR_GAME_INSTANCE_ID = "X-Game-Instance-Id";
  private static final String HDR_TENANT_ID = "X-Tenant-Id";

  private static final String HDR_PROXY_CLIENT_IP = "X-Proxy-Client-IP";
  private static final String HDR_PROXY_CONNECTION_ID = "X-Proxy-Connection-Id";
  private static final String HDR_PROXY_GAME_INSTANCE_ID = "X-Proxy-Game-Instance-Id";
  private static final String HDR_PROXY_TENANT_ID = "X-Proxy-Tenant-Id";

  private static final HexFormat HEX = HexFormat.of();

  private final GatewayHeaderTrustProperties properties;
  private final CidrSet trustedForwardedProxies;
  private final CidrSet insecureTrustedTcpProxyCidrs;
  private final List<String> trustedTcpProxyFingerprints;
  private final List<String> trustedTcpProxyDnsSans;
  private final List<String> trustedTcpProxyUriSans;

  public HeaderTrustFilter(GatewayHeaderTrustProperties properties) {
    this.properties = Objects.requireNonNull(properties);
    this.trustedForwardedProxies =
        new CidrSet(properties.getForwardedClientIp().getTrustedProxyCidrs());
    this.insecureTrustedTcpProxyCidrs =
        new CidrSet(properties.getTcpProxy().getInsecureTrustedCidrs());
    this.trustedTcpProxyFingerprints =
        normalizeFingerprints(properties.getTcpProxy().getTrustedClientCertFingerprintsSha256());
    this.trustedTcpProxyDnsSans =
        normalizeStrings(properties.getTcpProxy().getTrustedClientCertDnsSans());
    this.trustedTcpProxyUriSans =
        normalizeStrings(properties.getTcpProxy().getTrustedClientCertUriSans());
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    boolean isSessionRoute = path.startsWith("/ws/game") || path.startsWith("/api/session/");

    InetAddress remoteAddress = remoteInetAddress(exchange);
    boolean trustedTcpProxy = isTrustedTcpProxy(exchange, remoteAddress);

    if (isSessionRoute
        && !trustedTcpProxy
        && presentsProxyHeaders(exchange.getRequest().getHeaders())) {
      exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
      return exchange.getResponse().setComplete();
    }

    String incomingProxyClientIp = exchange.getRequest().getHeaders().getFirst(HDR_PROXY_CLIENT_IP);
    String canonicalClientIp =
        trustedTcpProxy && isSessionRoute
            ? normalizeIpLiteral(incomingProxyClientIp)
            : deriveClientIpFromForwardedHeaders(exchange.getRequest().getHeaders(), remoteAddress);

    String incomingProxyConnectionId =
        trustedTcpProxy
            ? exchange.getRequest().getHeaders().getFirst(HDR_PROXY_CONNECTION_ID)
            : null;
    String incomingProxyGameInstanceId =
        trustedTcpProxy && isSessionRoute
            ? exchange.getRequest().getHeaders().getFirst(HDR_PROXY_GAME_INSTANCE_ID)
            : null;
    String incomingProxyTenantId =
        trustedTcpProxy && isSessionRoute
            ? exchange.getRequest().getHeaders().getFirst(HDR_PROXY_TENANT_ID)
            : null;

    ServerWebExchange mutated =
        exchange
            .mutate()
            .request(
                request ->
                    request.headers(
                        headers -> {
                          stripGatewayOwnedHeaders(headers);

                          if (canonicalClientIp != null) {
                            headers.set(HDR_CLIENT_IP, canonicalClientIp);
                          }

                          if (trustedTcpProxy) {
                            if (incomingProxyConnectionId != null
                                && !incomingProxyConnectionId.isBlank()) {
                              headers.set(HDR_PROXY_CONNECTION_ID, incomingProxyConnectionId);
                            }
                            if (incomingProxyGameInstanceId != null
                                && !incomingProxyGameInstanceId.isBlank()) {
                              headers.set(HDR_GAME_INSTANCE_ID, incomingProxyGameInstanceId);
                              if (properties.isEmitLegacySessionId()) {
                                headers.set(HDR_SESSION_ID, incomingProxyGameInstanceId);
                              }
                            }
                            if (incomingProxyTenantId != null && !incomingProxyTenantId.isBlank()) {
                              headers.set(HDR_TENANT_ID, incomingProxyTenantId);
                            }
                          }
                        }))
            .build();

    return chain.filter(mutated);
  }

  private static boolean presentsProxyHeaders(HttpHeaders headers) {
    return headers.containsKey(HDR_PROXY_CLIENT_IP)
        || headers.containsKey(HDR_PROXY_CONNECTION_ID)
        || headers.containsKey(HDR_PROXY_GAME_INSTANCE_ID)
        || headers.containsKey(HDR_PROXY_TENANT_ID);
  }

  private void stripGatewayOwnedHeaders(HttpHeaders headers) {
    headers.remove(HDR_CLIENT_IP);
    headers.remove(HDR_SESSION_ID);
    headers.remove(HDR_GAME_INSTANCE_ID);
    headers.remove(HDR_TENANT_ID);

    headers.remove(HDR_PROXY_CLIENT_IP);
    headers.remove(HDR_PROXY_CONNECTION_ID);
    headers.remove(HDR_PROXY_GAME_INSTANCE_ID);
    headers.remove(HDR_PROXY_TENANT_ID);
  }

  private String deriveClientIpFromForwardedHeaders(
      HttpHeaders headers, InetAddress remoteAddress) {
    if (remoteAddress != null && trustedForwardedProxies.contains(remoteAddress)) {
      String forwarded = parseForwardedFor(headers.getFirst("Forwarded"));
      String normalized = normalizeIpLiteral(forwarded);
      if (normalized != null) {
        return normalized;
      }

      String xff = parseXForwardedFor(headers.getFirst("X-Forwarded-For"));
      normalized = normalizeIpLiteral(xff);
      if (normalized != null) {
        return normalized;
      }

      normalized = normalizeIpLiteral(headers.getFirst("X-Real-IP"));
      if (normalized != null) {
        return normalized;
      }
    }
    return remoteAddress != null ? remoteAddress.getHostAddress() : null;
  }

  private static String parseXForwardedFor(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String[] parts = value.split(",");
    if (parts.length == 0) {
      return null;
    }
    return parts[0].trim();
  }

  private static String parseForwardedFor(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String first = value.split(",")[0];
    String[] parameters = first.split(";");
    for (String parameter : parameters) {
      String trimmed = parameter.trim();
      if (!trimmed.toLowerCase(Locale.ROOT).startsWith("for=")) {
        continue;
      }
      String raw = trimmed.substring(4).trim();
      if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
        raw = raw.substring(1, raw.length() - 1);
      }
      if (raw.startsWith("[") && raw.contains("]")) {
        raw = raw.substring(1, raw.indexOf(']'));
      }
      int portIndex = raw.lastIndexOf(':');
      if (portIndex > 0 && raw.indexOf(':') == portIndex) {
        raw = raw.substring(0, portIndex);
      }
      return raw;
    }
    return null;
  }

  private static String normalizeIpLiteral(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      boolean allowed =
          (c >= '0' && c <= '9') || c == '.' || c == ':' || c == '[' || c == ']' || c == '%';
      if (!allowed) {
        return null;
      }
    }
    try {
      if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        trimmed = trimmed.substring(1, trimmed.length() - 1);
      }
      InetAddress address = InetAddress.getByName(trimmed);
      return address.getHostAddress();
    } catch (Exception ignored) {
      return null;
    }
  }

  private InetAddress remoteInetAddress(ServerWebExchange exchange) {
    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
    return remote != null ? remote.getAddress() : null;
  }

  private boolean isTrustedTcpProxy(ServerWebExchange exchange, InetAddress remoteAddress) {
    if (isTrustedTcpProxyViaMtls(exchange.getRequest().getSslInfo())) {
      return true;
    }
    return properties.getTcpProxy().isAllowInsecureHeadersFromTrustedCidrs()
        && remoteAddress != null
        && insecureTrustedTcpProxyCidrs.contains(remoteAddress);
  }

  private boolean isTrustedTcpProxyViaMtls(SslInfo sslInfo) {
    if (sslInfo == null
        || (trustedTcpProxyFingerprints.isEmpty()
            && trustedTcpProxyDnsSans.isEmpty()
            && trustedTcpProxyUriSans.isEmpty())) {
      return false;
    }
    X509Certificate[] peerCerts;
    try {
      peerCerts = sslInfo.getPeerCertificates();
    } catch (Exception ignored) {
      return false;
    }
    if (peerCerts == null || peerCerts.length == 0 || peerCerts[0] == null) {
      return false;
    }
    X509Certificate leaf = peerCerts[0];
    if (!trustedTcpProxyFingerprints.isEmpty() && matchesFingerprint(leaf)) {
      return true;
    }
    if (!trustedTcpProxyDnsSans.isEmpty() || !trustedTcpProxyUriSans.isEmpty()) {
      return matchesSans(leaf);
    }
    return false;
  }

  private boolean matchesFingerprint(X509Certificate cert) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(cert.getEncoded());
      String fingerprint = HEX.formatHex(hash).toLowerCase(Locale.ROOT);
      for (String allowed : trustedTcpProxyFingerprints) {
        if (fingerprint.equals(allowed)) {
          return true;
        }
      }
      return false;
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean matchesSans(X509Certificate cert) {
    try {
      Collection<List<?>> sans = cert.getSubjectAlternativeNames();
      if (sans == null) {
        return false;
      }
      for (List<?> san : sans) {
        if (san == null || san.size() < 2) {
          continue;
        }
        Object typeObj = san.get(0);
        Object valueObj = san.get(1);
        if (!(typeObj instanceof Integer type) || !(valueObj instanceof String value)) {
          continue;
        }
        if (type == 2) { // DNS
          String normalized = value.toLowerCase(Locale.ROOT);
          for (String allowed : trustedTcpProxyDnsSans) {
            if (normalized.equals(allowed)) {
              return true;
            }
          }
        } else if (type == 6) { // URI
          String normalized = value.toLowerCase(Locale.ROOT);
          for (String allowed : trustedTcpProxyUriSans) {
            if (normalized.equals(allowed)) {
              return true;
            }
          }
        }
      }
      return false;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static List<String> normalizeFingerprints(List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    for (String value : raw) {
      if (value == null) {
        continue;
      }
      String normalized = value.trim().toLowerCase(Locale.ROOT).replace(":", "");
      if (!normalized.isEmpty()) {
        out.add(normalized);
      }
    }
    return out;
  }

  private static List<String> normalizeStrings(List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    for (String value : raw) {
      if (value == null) {
        continue;
      }
      String normalized = value.trim().toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        out.add(normalized);
      }
    }
    return out;
  }

  @Override
  public int getOrder() {
    return -4;
  }

  static final class CidrSet {
    private final List<CidrBlock> blocks;

    CidrSet(List<String> cidrs) {
      List<CidrBlock> parsed = new ArrayList<>();
      if (cidrs != null) {
        for (String cidr : cidrs) {
          CidrBlock block = CidrBlock.parse(cidr);
          if (block != null) {
            parsed.add(block);
          }
        }
      }
      this.blocks = List.copyOf(parsed);
    }

    boolean contains(InetAddress address) {
      if (address == null) {
        return false;
      }
      for (CidrBlock block : blocks) {
        if (block.contains(address)) {
          return true;
        }
      }
      return false;
    }
  }

  record CidrBlock(byte[] network, int prefixBits) {
    static CidrBlock parse(String cidr) {
      if (cidr == null) {
        return null;
      }
      String trimmed = cidr.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      String[] parts = trimmed.split("/");
      if (parts.length != 2) {
        return null;
      }
      String ip = normalizeIpLiteral(parts[0]);
      if (ip == null) {
        return null;
      }
      int prefix;
      try {
        prefix = Integer.parseInt(parts[1].trim());
      } catch (Exception ignored) {
        return null;
      }
      try {
        InetAddress address = InetAddress.getByName(ip);
        int max = address.getAddress().length * 8;
        if (prefix < 0 || prefix > max) {
          return null;
        }
        byte[] networkBytes = address.getAddress();
        applyMaskInPlace(networkBytes, prefix);
        return new CidrBlock(networkBytes, prefix);
      } catch (Exception ignored) {
        return null;
      }
    }

    boolean contains(InetAddress address) {
      byte[] bytes = address.getAddress();
      if (bytes.length != network.length) {
        return false;
      }
      int fullBytes = prefixBits / 8;
      int remainingBits = prefixBits % 8;
      for (int i = 0; i < fullBytes; i++) {
        if (bytes[i] != network[i]) {
          return false;
        }
      }
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xFF << (8 - remainingBits);
      return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private static void applyMaskInPlace(byte[] bytes, int prefixBits) {
      int fullBytes = prefixBits / 8;
      int remainingBits = prefixBits % 8;
      for (int i = fullBytes + (remainingBits > 0 ? 1 : 0); i < bytes.length; i++) {
        bytes[i] = 0;
      }
      if (remainingBits == 0 || fullBytes >= bytes.length) {
        return;
      }
      int mask = 0xFF << (8 - remainingBits);
      bytes[fullBytes] = (byte) (bytes[fullBytes] & mask);
    }
  }
}
