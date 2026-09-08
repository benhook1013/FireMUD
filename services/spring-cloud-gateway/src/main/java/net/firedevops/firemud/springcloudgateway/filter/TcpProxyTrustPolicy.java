package net.firedevops.firemud.springcloudgateway.filter;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/** Enforces the exclusive, environment-bound TCP Proxy trust profile selected at startup. */
@Component
public final class TcpProxyTrustPolicy {
  static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
  private static final Pattern DNS_NAME =
      Pattern.compile(
          "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*");
  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern WORKLOAD_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");
  private static final Set<String> DEVELOPMENT_ENVIRONMENTS = Set.of("local-dev", "isolated-test");
  private static final Set<String> PLAYER_FACING_ENVIRONMENTS =
      Set.of("hobby-self-hosted", "staging", "production");
  private static final Set<String> KNOWN_ENVIRONMENTS =
      Set.of(
          "local-dev",
          "isolated-test",
          "pr-preview",
          "dev-demo-cluster",
          "hobby-self-hosted",
          "staging",
          "production");

  private final GatewayTcpProxyListenerProperties listener;
  private final GatewayHeaderTrustProperties legacy;
  private final HeaderTrustFilter.CidrSet legacyCidrs;
  private final HeaderTrustFilter.CidrSet developmentCidrs;
  private final Clock clock;
  private final int publicPort;
  private final TrustProfile profile;
  private final String expectedIdentity;
  private final Instant profileExpiresAt;

  @Autowired
  public TcpProxyTrustPolicy(
      GatewayTcpProxyListenerProperties listener,
      GatewayHeaderTrustProperties legacy,
      @Value("${server.port:8080}") int publicPort,
      Environment environment) {
    this(listener, legacy, publicPort, Clock.systemUTC(), Set.of(environment.getActiveProfiles()));
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected configuration is a framework-owned singleton read at runtime.")
  public TcpProxyTrustPolicy(
      GatewayTcpProxyListenerProperties listener,
      GatewayHeaderTrustProperties legacy,
      int publicPort,
      Clock clock,
      Set<String> activeProfiles) {
    this.listener = Objects.requireNonNull(listener);
    this.legacy = Objects.requireNonNull(legacy);
    this.publicPort = publicPort;
    this.clock = Objects.requireNonNull(clock);
    this.legacyCidrs =
        new HeaderTrustFilter.CidrSet(legacy.getTcpProxy().getInsecureTrustedCidrs());

    if (!listener.isEnabled()) {
      rejectLegacyCertificateMatchers();
      validateLegacyDevelopmentMode(activeProfiles);
      this.profile = null;
      this.expectedIdentity = null;
      this.profileExpiresAt = null;
      this.developmentCidrs = new HeaderTrustFilter.CidrSet(List.of());
      return;
    }

    validateListenerBasics();
    rejectLegacyTrustConfiguration();
    this.profile = TrustProfile.parse(listener.getTrustProfile());
    String configuredEnvironment = normalizedToken(listener.getEnvironment(), "environment");
    if (!KNOWN_ENVIRONMENTS.contains(configuredEnvironment)) {
      throw invalid("unknown environment " + listener.getEnvironment());
    }
    this.expectedIdentity = validateSelectedProfile(configuredEnvironment, activeProfiles);
    this.profileExpiresAt = selectedProfileExpiry();
    this.developmentCidrs =
        profile == TrustProfile.DEVELOPMENT_CIDR
            ? new HeaderTrustFilter.CidrSet(
                List.of(listener.getDevelopmentCidr().getTrustedCidr().trim()))
            : new HeaderTrustFilter.CidrSet(List.of());
  }

  boolean isTrusted(ServerWebExchange exchange, InetAddress remoteAddress) {
    if (!listener.isEnabled()) {
      return legacy.getTcpProxy().isAllowInsecureHeadersFromTrustedCidrs()
          && remoteAddress != null
          && legacyCidrs.contains(remoteAddress);
    }
    if (!isDedicatedListenerRequest(exchange)) {
      return false;
    }
    if (profile == TrustProfile.DEVELOPMENT_CIDR) {
      return remoteAddress != null && developmentCidrs.contains(remoteAddress);
    }
    return authenticatePeer(exchange.getRequest().getSslInfo());
  }

  boolean isDedicatedListenerRequest(ServerWebExchange exchange) {
    if (!listener.isEnabled() || exchange.getRequest().getSslInfo() == null) {
      return false;
    }
    InetSocketAddress local = exchange.getRequest().getLocalAddress();
    return local != null && local.getPort() == listener.getPort();
  }

  boolean authenticatePeer(SslInfo sslInfo) {
    if (sslInfo == null || profile == null || profile == TrustProfile.DEVELOPMENT_CIDR) {
      return false;
    }
    if (profileExpiresAt != null && !profileExpiresAt.isAfter(clock.instant())) {
      return false;
    }
    X509Certificate leaf = peerLeaf(sslInfo);
    if (leaf == null || !hasCurrentClientAuthUsage(leaf)) {
      return false;
    }
    return switch (profile) {
      case PRODUCTION_URI -> expectedIdentity.equals(singleNormalizedUriSan(leaf));
      case MIGRATION_DNS -> expectedIdentity.equals(singleNormalizedDnsSan(leaf));
      case BREAKGLASS_FINGERPRINT -> fingerprintMatches(leaf, expectedIdentity);
      case DEVELOPMENT_CIDR -> false;
    };
  }

  public boolean requiresClientCertificate() {
    return listener.isEnabled() && profile != TrustProfile.DEVELOPMENT_CIDR;
  }

  public String profileName() {
    return profile == null ? "disabled" : profile.name().toLowerCase(Locale.ROOT);
  }

  public Duration timeUntilProfileExpiry() {
    return profileExpiresAt == null ? null : Duration.between(clock.instant(), profileExpiresAt);
  }

  private void validateListenerBasics() {
    if (!StringUtils.hasText(listener.getBindAddress())) {
      throw invalid("bind-address is required");
    }
    if (listener.getPort() < 1 || listener.getPort() > 65535) {
      throw invalid("port must be in the range 1..65535");
    }
    if (publicPort > 0 && listener.getPort() == publicPort) {
      throw invalid("internal TLS port must differ from the public server port");
    }
    requireText(listener.getCertificateChainPath(), "certificate-chain-path");
    requireText(listener.getPrivateKeyPath(), "private-key-path");
    requireText(listener.getEnvironment(), "environment");
    requireText(listener.getTrustProfile(), "trust-profile");
  }

  private void rejectLegacyCertificateMatchers() {
    GatewayHeaderTrustProperties.TcpProxy tcpProxy = legacy.getTcpProxy();
    if (!tcpProxy.getTrustedClientCertFingerprintsSha256().isEmpty()
        || !tcpProxy.getTrustedClientCertDnsSans().isEmpty()
        || !tcpProxy.getTrustedClientCertUriSans().isEmpty()) {
      throw invalid(
          "legacy certificate matchers require the dedicated listener and an explicit trust profile");
    }
  }

  private void rejectLegacyTrustConfiguration() {
    rejectLegacyCertificateMatchers();
    if (legacy.getTcpProxy().isAllowInsecureHeadersFromTrustedCidrs()) {
      throw invalid("dedicated listener cannot coexist with legacy insecure header trust");
    }
  }

  private void validateLegacyDevelopmentMode(Set<String> activeProfiles) {
    if (!legacy.getTcpProxy().isAllowInsecureHeadersFromTrustedCidrs()) {
      return;
    }
    boolean developmentProfile =
        activeProfiles.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(
                value -> value.equals("test") || value.equals("dev") || value.equals("local"));
    if (!developmentProfile) {
      throw invalid(
          "legacy insecure header trust is restricted to explicit test/dev/local profiles");
    }
    if (legacy.getTcpProxy().getInsecureTrustedCidrs().isEmpty()) {
      throw invalid("legacy insecure header trust requires at least one source CIDR");
    }
    for (String cidr : legacy.getTcpProxy().getInsecureTrustedCidrs()) {
      if (HeaderTrustFilter.CidrBlock.parse(cidr) == null) {
        throw invalid("legacy insecure header trust contains an invalid source CIDR");
      }
    }
  }

  private String validateSelectedProfile(String configuredEnvironment, Set<String> activeProfiles) {
    boolean productionSpringProfile =
        activeProfiles.stream().anyMatch(value -> value.equalsIgnoreCase("prod"));
    return switch (profile) {
      case PRODUCTION_URI -> {
        requireClientCa();
        rejectOtherProfileSettings(profile);
        yield normalizeTcpProxyWorkloadIdentity(
            requireText(listener.getProductionUri().getUriSan(), "production-uri.uri-san"));
      }
      case MIGRATION_DNS -> {
        requireClientCa();
        rejectOtherProfileSettings(profile);
        requireText(listener.getMigrationDns().getOwner(), "migration-dns.owner");
        requireText(listener.getMigrationDns().getReason(), "migration-dns.reason");
        requireFutureExpiry(listener.getMigrationDns().getExpiresAt(), "migration-dns.expires-at");
        yield normalizeDnsName(
            requireText(listener.getMigrationDns().getDnsSan(), "migration-dns.dns-san"));
      }
      case BREAKGLASS_FINGERPRINT -> {
        requireClientCa();
        rejectOtherProfileSettings(profile);
        requireText(
            listener.getBreakglassFingerprint().getIncidentReference(),
            "breakglass-fingerprint.incident-reference");
        requireFutureExpiry(
            listener.getBreakglassFingerprint().getExpiresAt(),
            "breakglass-fingerprint.expires-at");
        yield normalizeFingerprint(
            requireText(
                listener.getBreakglassFingerprint().getSha256(), "breakglass-fingerprint.sha256"));
      }
      case DEVELOPMENT_CIDR -> {
        rejectOtherProfileSettings(profile);
        if (!DEVELOPMENT_ENVIRONMENTS.contains(configuredEnvironment)
            || PLAYER_FACING_ENVIRONMENTS.contains(configuredEnvironment)
            || productionSpringProfile) {
          throw invalid(
              "development_cidr is restricted to local-dev or isolated-test without the prod profile");
        }
        if (StringUtils.hasText(listener.getTrustedClientCaPath())) {
          throw invalid(
              "development_cidr must not configure a client CA or claim certificate identity");
        }
        String cidr =
            requireText(
                listener.getDevelopmentCidr().getTrustedCidr(), "development-cidr.trusted-cidr");
        HeaderTrustFilter.CidrSet parsed = new HeaderTrustFilter.CidrSet(List.of(cidr));
        if (parsed.isEmpty()) {
          throw invalid("development-cidr.trusted-cidr is invalid");
        }
        yield cidr.trim();
      }
    };
  }

  private void requireClientCa() {
    requireText(listener.getTrustedClientCaPath(), "trusted-client-ca-path");
  }

  private void rejectOtherProfileSettings(TrustProfile selected) {
    boolean production = StringUtils.hasText(listener.getProductionUri().getUriSan());
    boolean migration =
        StringUtils.hasText(listener.getMigrationDns().getDnsSan())
            || StringUtils.hasText(listener.getMigrationDns().getOwner())
            || StringUtils.hasText(listener.getMigrationDns().getReason())
            || StringUtils.hasText(listener.getMigrationDns().getExpiresAt());
    boolean breakglass =
        StringUtils.hasText(listener.getBreakglassFingerprint().getSha256())
            || StringUtils.hasText(listener.getBreakglassFingerprint().getIncidentReference())
            || StringUtils.hasText(listener.getBreakglassFingerprint().getExpiresAt());
    boolean development = StringUtils.hasText(listener.getDevelopmentCidr().getTrustedCidr());

    if ((selected != TrustProfile.PRODUCTION_URI && production)
        || (selected != TrustProfile.MIGRATION_DNS && migration)
        || (selected != TrustProfile.BREAKGLASS_FINGERPRINT && breakglass)
        || (selected != TrustProfile.DEVELOPMENT_CIDR && development)) {
      throw invalid("settings for inactive trust profiles are forbidden");
    }
  }

  private void requireFutureExpiry(String value, String name) {
    String raw = requireText(value, name);
    try {
      if (!Instant.parse(raw).isAfter(clock.instant())) {
        throw invalid(name + " must be in the future");
      }
    } catch (DateTimeParseException ex) {
      throw invalid(name + " must be an RFC 3339 instant");
    }
  }

  private Instant selectedProfileExpiry() {
    return switch (profile) {
      case MIGRATION_DNS -> Instant.parse(listener.getMigrationDns().getExpiresAt());
      case BREAKGLASS_FINGERPRINT ->
          Instant.parse(listener.getBreakglassFingerprint().getExpiresAt());
      case PRODUCTION_URI, DEVELOPMENT_CIDR -> null;
    };
  }

  private boolean hasCurrentClientAuthUsage(X509Certificate leaf) {
    try {
      leaf.checkValidity(Date.from(clock.instant()));
      List<String> usages = leaf.getExtendedKeyUsage();
      return usages != null && usages.contains(CLIENT_AUTH_EKU);
    } catch (CertificateExpiredException
        | CertificateNotYetValidException
        | java.security.cert.CertificateParsingException
        | RuntimeException ex) {
      return false;
    }
  }

  private static X509Certificate peerLeaf(SslInfo sslInfo) {
    try {
      X509Certificate[] certificates = sslInfo.getPeerCertificates();
      return certificates == null || certificates.length == 0 ? null : certificates[0];
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String singleNormalizedUriSan(X509Certificate leaf) {
    List<String> values = sans(leaf, 6);
    if (values.size() != 1) {
      return null;
    }
    try {
      return normalizeTcpProxyWorkloadIdentity(values.get(0));
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String singleNormalizedDnsSan(X509Certificate leaf) {
    List<String> values = sans(leaf, 2);
    if (values.size() != 1) {
      return null;
    }
    try {
      return normalizeDnsName(values.get(0));
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static List<String> sans(X509Certificate leaf, int requestedType) {
    List<String> values = new ArrayList<>();
    try {
      Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
      if (sans == null) {
        return values;
      }
      for (List<?> san : sans) {
        if (san != null
            && san.size() >= 2
            && san.get(0) instanceof Integer type
            && type == requestedType
            && san.get(1) instanceof String value) {
          values.add(value);
        }
      }
      return values;
    } catch (java.security.cert.CertificateParsingException ex) {
      return List.of();
    }
  }

  private static boolean fingerprintMatches(X509Certificate leaf, String expected) {
    try {
      byte[] actual = MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
      byte[] allowed = HexFormat.of().parseHex(expected);
      return MessageDigest.isEqual(actual, allowed);
    } catch (CertificateEncodingException
        | NoSuchAlgorithmException
        | IllegalArgumentException ex) {
      return false;
    }
  }

  static String normalizeTcpProxyWorkloadIdentity(String raw) {
    if (!StandardCharsets.US_ASCII.newEncoder().canEncode(raw)) {
      throw invalid("production URI SAN must be ASCII");
    }
    final URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException ex) {
      throw invalid("production URI SAN is malformed");
    }
    if (uri.isOpaque()
        || !"spiffe".equals(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getPort() != -1
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !"firemud".equalsIgnoreCase(uri.getHost())) {
      throw invalid("production URI SAN must use the canonical FireMUD SPIFFE authority");
    }
    String path = decodeUnreservedPath(uri.getRawPath());
    String[] segments = path.split("/", -1);
    if (segments.length != 5
        || !segments[0].isEmpty()
        || !"ns".equals(segments[1])
        || !WORKLOAD_SEGMENT.matcher(segments[2]).matches()
        || !"sa".equals(segments[3])
        || !"tcp-proxy-service".equals(segments[4])) {
      throw invalid(
          "production URI SAN must be spiffe://firemud/ns/<namespace>/sa/tcp-proxy-service");
    }
    if (segments[2].equals(".") || segments[2].equals("..")) {
      throw invalid("production URI SAN contains a dot segment");
    }
    return "spiffe://firemud" + path;
  }

  private static String decodeUnreservedPath(String rawPath) {
    if (rawPath == null || rawPath.isEmpty()) {
      throw invalid("production URI SAN path is required");
    }
    StringBuilder out = new StringBuilder(rawPath.length());
    for (int index = 0; index < rawPath.length(); index++) {
      char value = rawPath.charAt(index);
      if (value != '%') {
        if (value > 0x7f) {
          throw invalid("production URI SAN path must be ASCII");
        }
        out.append(value);
        continue;
      }
      if (index + 2 >= rawPath.length()) {
        throw invalid("production URI SAN contains a malformed percent escape");
      }
      int high = Character.digit(rawPath.charAt(index + 1), 16);
      int low = Character.digit(rawPath.charAt(index + 2), 16);
      if (high < 0 || low < 0) {
        throw invalid("production URI SAN contains a malformed percent escape");
      }
      char decoded = (char) ((high << 4) | low);
      if (!isUnreserved(decoded)) {
        throw invalid("production URI SAN escapes a reserved or non-ASCII byte");
      }
      out.append(decoded);
      index += 2;
    }
    for (String segment : out.toString().split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        throw invalid("production URI SAN contains a dot segment");
      }
    }
    return out.toString();
  }

  private static boolean isUnreserved(char value) {
    return (value >= 'A' && value <= 'Z')
        || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }

  private static String normalizeDnsName(String raw) {
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    if (!StandardCharsets.US_ASCII.newEncoder().canEncode(normalized)
        || normalized.endsWith(".")
        || normalized.contains("*")
        || !DNS_NAME.matcher(normalized).matches()) {
      throw invalid("migration DNS SAN must be one exact lowercase ASCII DNS name");
    }
    return normalized;
  }

  private static String normalizeFingerprint(String raw) {
    String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(":", "");
    if (!FINGERPRINT.matcher(normalized).matches()) {
      throw invalid("break-glass fingerprint must be exactly one SHA-256 leaf fingerprint");
    }
    return normalized;
  }

  private static String normalizedToken(String raw, String name) {
    return requireText(raw, name).trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static String requireText(String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw invalid(name + " is required");
    }
    return value;
  }

  private static IllegalStateException invalid(String message) {
    return new IllegalStateException("Invalid TCP Proxy listener configuration: " + message);
  }

  enum TrustProfile {
    PRODUCTION_URI,
    MIGRATION_DNS,
    BREAKGLASS_FINGERPRINT,
    DEVELOPMENT_CIDR;

    static TrustProfile parse(String raw) {
      String normalized = normalizedToken(raw, "trust-profile").replace('-', '_');
      try {
        return valueOf(normalized.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        throw invalid("unknown trust-profile " + raw);
      }
    }
  }
}
