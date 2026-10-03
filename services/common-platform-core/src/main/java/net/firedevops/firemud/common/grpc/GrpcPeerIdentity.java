package net.firedevops.firemud.common.grpc;

import io.grpc.Context;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * The authenticated workload identity carried by an internal gRPC peer certificate.
 *
 * <p>This type is deliberately backed only by the peer TLS session. Headers, JWT claims, DNS names,
 * common names, and certificate fingerprints are not identity sources for this contract.
 */
public record GrpcPeerIdentity(String uri, String namespace, String service) {
  public static final Context.Key<GrpcPeerIdentity> CONTEXT_KEY =
      Context.key("firemud-grpc-peer-identity");

  /** The service identities that can be represented by a FireMUD workload certificate. */
  public static final Set<String> ALLOWED_SERVICE_NAMES =
      Set.of(
          "account-service",
          "automation-scripting-service",
          "entity-management-service",
          "game-design-baseline-migrator",
          "game-design-service",
          "game-logic-service",
          "game-session-service",
          "logging-admin-service",
          "social-groups-service",
          "spring-cloud-gateway",
          "tcp-proxy-service",
          "world-management-service");

  private static final Pattern IDENTITY_PATTERN =
      Pattern.compile(
          "^spiffe://firemud/ns/"
              + "(?<namespace>(?=[a-z0-9-]{1,63}/sa/)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)"
              + "/sa/"
              + "(?<service>[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)$");

  public GrpcPeerIdentity {
    if (uri == null || namespace == null || service == null) {
      throw new IllegalArgumentException("Peer identity fields are required");
    }
    if (CanonicalUri.parse(uri).filter(canonical -> canonical.value().equals(uri)).isEmpty()) {
      throw new IllegalArgumentException("Peer identity URI must be canonical");
    }
    Matcher matcher = IDENTITY_PATTERN.matcher(uri);
    if (!matcher.matches()
        || !namespace.equals(matcher.group("namespace"))
        || !service.equals(matcher.group("service"))
        || !isValidNamespace(namespace)
        || !ALLOWED_SERVICE_NAMES.contains(service)) {
      throw new IllegalArgumentException("Invalid FireMUD workload identity");
    }
  }

  /** Returns the identity currently attached to the serving gRPC context, if verified. */
  public static GrpcPeerIdentity current() {
    return CONTEXT_KEY.get();
  }

  /** Parses one exact FireMUD SPIFFE workload URI. */
  public static Optional<GrpcPeerIdentity> parseUri(String uri) {
    Optional<CanonicalUri> canonical = CanonicalUri.parse(uri);
    if (canonical.isEmpty()) {
      return Optional.empty();
    }
    Matcher matcher = IDENTITY_PATTERN.matcher(canonical.get().value());
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String namespace = matcher.group("namespace");
    String service = matcher.group("service");
    if (!ALLOWED_SERVICE_NAMES.contains(service)) {
      return Optional.empty();
    }
    return Optional.of(new GrpcPeerIdentity(canonical.get().value(), namespace, service));
  }

  /** Extracts the authenticated leaf identity from a gRPC TLS session. */
  public static Optional<GrpcPeerIdentity> fromSslSession(SSLSession sslSession) {
    if (sslSession == null) {
      return Optional.empty();
    }
    try {
      Certificate[] peerCertificates = sslSession.getPeerCertificates();
      if (peerCertificates == null
          || peerCertificates.length == 0
          || !(peerCertificates[0] instanceof X509Certificate leaf)) {
        return Optional.empty();
      }
      return fromCertificate(leaf);
    } catch (SSLPeerUnverifiedException | RuntimeException ex) {
      return Optional.empty();
    }
  }

  /** Extracts the identity from the leaf certificate's exact URI SAN set. */
  public static Optional<GrpcPeerIdentity> fromCertificate(X509Certificate leaf) {
    if (leaf == null) {
      return Optional.empty();
    }
    try {
      Collection<List<?>> subjectAlternativeNames = leaf.getSubjectAlternativeNames();
      if (subjectAlternativeNames == null) {
        return Optional.empty();
      }

      List<String> uriSans = new ArrayList<>();
      for (List<?> alternativeName : subjectAlternativeNames) {
        if (!isUriSan(alternativeName)) {
          continue;
        }
        if (alternativeName.size() != 2 || !(alternativeName.get(1) instanceof String value)) {
          return Optional.empty();
        }
        uriSans.add(value);
      }
      if (uriSans.size() != 1) {
        return Optional.empty();
      }
      return parseUri(uriSans.get(0));
    } catch (CertificateParsingException | RuntimeException ex) {
      return Optional.empty();
    }
  }

  public boolean isService(String expectedService) {
    return service.equals(expectedService);
  }

  public boolean isInNamespace(String expectedNamespace) {
    return namespace.equals(expectedNamespace);
  }

  public static boolean isValidNamespace(String namespace) {
    return namespace != null
        && Pattern.matches("(?=.{1,63}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", namespace);
  }

  private static boolean isUriSan(List<?> alternativeName) {
    return alternativeName != null
        && !alternativeName.isEmpty()
        && Integer.valueOf(6).equals(alternativeName.get(0));
  }
}
