package net.firedevops.firemud.gamesession.service;

import java.net.URI;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.ProtectedPodUidProjection;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds Game Session's local readiness identity from the protected Pod UID, its active TLS leaf,
 * and Account's authenticated current receiver-metadata read.
 */
public final class GameSessionJwtReadinessProtectedLocalIdentityProvider
    implements GameSessionJwtReadinessLocalIdentityProvider {
  private static final String TLS_BUNDLE_NAME = "firemud-grpc";
  private static final String GAME_SESSION_VALIDATOR = "game-session-service";
  private static final Pattern KUBERNETES_UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern INVENTORY_REVISION =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern IMMUTABLE_IMAGE = Pattern.compile("[^\\s@]+@sha256:[0-9a-f]{64}");

  private final GameSessionJwtReadinessReceiverMetadataReadPort metadataReadPort;
  private final SslBundles sslBundles;
  private final Clock clock;
  private final String workloadNamespace;
  private final Supplier<String> projectedPodUidSource;

  public GameSessionJwtReadinessProtectedLocalIdentityProvider(
      GameSessionJwtReadinessReceiverMetadataReadPort metadataReadPort,
      SslBundles sslBundles,
      String workloadNamespace) {
    this(
        metadataReadPort,
        sslBundles,
        workloadNamespace,
        Clock.systemUTC(),
        ProtectedPodUidProjection::read);
  }

  GameSessionJwtReadinessProtectedLocalIdentityProvider(
      GameSessionJwtReadinessReceiverMetadataReadPort metadataReadPort,
      SslBundles sslBundles,
      String workloadNamespace,
      Clock clock,
      Supplier<String> projectedPodUidSource) {
    this.metadataReadPort = Objects.requireNonNull(metadataReadPort);
    this.sslBundles = Objects.requireNonNull(sslBundles);
    this.clock = Objects.requireNonNull(clock);
    this.projectedPodUidSource = Objects.requireNonNull(projectedPodUidSource);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Protected Game Session workload namespace is required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public LocalObservation observe() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable();
    }
    try {
      String podUidBefore = readProjectedPodUid();
      LocalTlsLeaf tlsLeafBefore = readLocalTlsLeaf();
      GetCurrentReadinessReceiverMetadataRequest request =
          GetCurrentReadinessReceiverMetadataRequest.newBuilder()
              .setSchemaVersion(1)
              .setProjectedPodUid(podUidBefore)
              .setServerLeafSpkiSha256(tlsLeafBefore.spkiSha256())
              .build();
      GetCurrentReadinessReceiverMetadataResponse response = metadataReadPort.readCurrent(request);
      ReadinessReceiverLocalIdentity identity =
          requireCurrentIdentity(response, podUidBefore, tlsLeafBefore);

      String podUidAfter = readProjectedPodUid();
      LocalTlsLeaf tlsLeafAfter = readLocalTlsLeaf();
      if (!podUidBefore.equals(podUidAfter)
          || !tlsLeafBefore.equals(tlsLeafAfter)
          || !identity.getPodUid().equals(podUidAfter)
          || !identity.getServerLeafSpkiSha256().equals(tlsLeafAfter.spkiSha256())) {
        throw unavailable();
      }
      return new LocalObservation(
          identity, sourceIdentity(identity.getAccountJwksSourceIdentity()));
    } catch (IdentityUnavailableException failure) {
      throw failure;
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private ReadinessReceiverLocalIdentity requireCurrentIdentity(
      GetCurrentReadinessReceiverMetadataResponse response,
      String projectedPodUid,
      LocalTlsLeaf tlsLeaf) {
    if (response == null
        || response.getSchemaVersion() != 1
        || !response.getUnknownFields().asMap().isEmpty()
        || !canonicalUuid(response.getRotationOperationId())
        || !isSha256(response.getOperationDigest())
        || !isSha256(response.getPlanDigest())
        || !response.hasCurrentIdentity()) {
      throw unavailable();
    }
    ReadinessReceiverLocalIdentity identity = response.getCurrentIdentity();
    if (!identity.getUnknownFields().asMap().isEmpty()
        || !identity.hasAccountJwksSourceIdentity()
        || !identity.getAccountJwksSourceIdentity().getUnknownFields().asMap().isEmpty()
        || !GAME_SESSION_VALIDATOR.equals(identity.getValidatorId())
        || !isCanonicalUid(identity.getDeploymentUid())
        || !projectedPodUid.equals(identity.getPodUid())
        || !isCanonicalUid(identity.getPodUid())
        || !isImmutableImage(identity.getImage())
        || !INVENTORY_REVISION.matcher(identity.getSourceInventoryRevision()).matches()
        || !isCanonicalPodEndpoint(identity.getPodIp(), identity.getDirectPodEndpoint())
        || !tlsLeaf.spkiSha256().equals(identity.getServerLeafSpkiSha256())
        || !expectedServiceUri().equals(identity.getCanonicalServiceUri())
        || !isSha256(identity.getVerifierConfigSha256())
        || !isSha256(identity.getApplicabilityMatrixDigest())
        || !isSha256(identity.getSourceInventoryDigest())
        || !isSha256(identity.getAccountPublicJwksSha256())
        || !identity
            .getAccountJwksTrustBindingRevision()
            .equals(identity.getAccountJwksSourceIdentity().getBindingRevision())) {
      throw unavailable();
    }
    SourceIdentity source = sourceIdentity(identity.getAccountJwksSourceIdentity());
    if (!workloadNamespace.equals(source.namespace())) {
      throw unavailable();
    }
    return identity;
  }

  private String readProjectedPodUid() {
    String uid = projectedPodUidSource.get();
    if (!isCanonicalUid(uid)) {
      throw unavailable();
    }
    return uid;
  }

  private static boolean isCanonicalUid(String value) {
    return value != null && KUBERNETES_UID.matcher(value).matches();
  }

  private static boolean isImmutableImage(String value) {
    return value != null && value.length() <= 1024 && IMMUTABLE_IMAGE.matcher(value).matches();
  }

  private static boolean isCanonicalPodEndpoint(String podIp, String endpointValue) {
    String canonicalIp = canonicalPodIp(podIp);
    if (canonicalIp == null || endpointValue == null || endpointValue.length() > 256) {
      return false;
    }
    try {
      URI endpoint = URI.create(endpointValue);
      String endpointHost = endpoint.getHost();
      String bracketedIp = canonicalIp.indexOf(':') >= 0 ? "[" + canonicalIp + "]" : canonicalIp;
      return endpoint.toString().equals(endpointValue)
          && "grpcs".equals(endpoint.getScheme())
          && endpoint.getRawUserInfo() == null
          && endpoint.getRawQuery() == null
          && endpoint.getRawFragment() == null
          && (endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty())
          && endpoint.getPort() > 0
          && endpoint.getPort() <= 65535
          && (canonicalIp.equals(endpointHost) || bracketedIp.equals(endpointHost))
          && endpointValue.equals("grpcs://" + bracketedIp + ":" + endpoint.getPort());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static String canonicalPodIp(String value) {
    if (value == null || value.isBlank() || value.indexOf('%') >= 0) {
      return null;
    }
    try {
      if (value.indexOf(':') < 0) {
        String[] components = value.split("\\.", -1);
        if (components.length != 4) {
          return null;
        }
        for (String component : components) {
          if (component.isEmpty()
              || (component.length() > 1 && component.charAt(0) == '0')
              || !component.matches("[0-9]{1,3}")
              || Integer.parseInt(component) > 255) {
            return null;
          }
        }
        return "0.0.0.0".equals(value) || "255.255.255.255".equals(value) ? null : value;
      }
      String canonical = canonicalIpv6Literal(value);
      return value.equals(canonical) ? value : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static String canonicalIpv6Literal(String value) {
    if (!value.matches("[0-9A-Fa-f:]+")) {
      return null;
    }
    int compressionIndex = value.indexOf("::");
    if (compressionIndex != value.lastIndexOf("::")) {
      return null;
    }
    List<Integer> left;
    List<Integer> right;
    if (compressionIndex < 0) {
      left = parseIpv6Groups(value);
      right = List.of();
      if (left == null || left.size() != 8) {
        return null;
      }
    } else {
      left = parseIpv6Groups(value.substring(0, compressionIndex));
      right = parseIpv6Groups(value.substring(compressionIndex + 2));
      if (left == null || right == null || left.size() + right.size() >= 8) {
        return null;
      }
    }
    int[] groups = new int[8];
    int index = 0;
    for (int group : left) {
      groups[index++] = group;
    }
    if (compressionIndex >= 0) {
      index = 8 - right.size();
    }
    for (int group : right) {
      groups[index++] = group;
    }
    byte[] address = new byte[16];
    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
      address[groupIndex * 2] = (byte) (groups[groupIndex] >>> 8);
      address[groupIndex * 2 + 1] = (byte) groups[groupIndex];
    }
    return canonicalIpv6(address);
  }

  private static List<Integer> parseIpv6Groups(String value) {
    if (value.isEmpty()) {
      return List.of();
    }
    List<Integer> groups = new ArrayList<>();
    for (String group : value.split(":", -1)) {
      if (group.isEmpty() || group.length() > 4 || !group.matches("[0-9A-Fa-f]{1,4}")) {
        return null;
      }
      groups.add(Integer.parseInt(group, 16));
    }
    return groups;
  }

  private static String canonicalIpv6(byte[] bytes) {
    if (bytes.length != 16) {
      return "";
    }
    int[] groups = new int[8];
    for (int index = 0; index < groups.length; index++) {
      groups[index] =
          Byte.toUnsignedInt(bytes[index * 2]) << 8 | Byte.toUnsignedInt(bytes[index * 2 + 1]);
    }
    int bestStart = -1;
    int bestLength = 1;
    for (int index = 0; index < groups.length; ) {
      if (groups[index] != 0) {
        index++;
        continue;
      }
      int end = index;
      while (end < groups.length && groups[end] == 0) {
        end++;
      }
      if (end - index > bestLength) {
        bestStart = index;
        bestLength = end - index;
      }
      index = end;
    }
    StringBuilder canonical = new StringBuilder();
    for (int index = 0; index < groups.length; ) {
      if (index == bestStart) {
        canonical.append("::");
        index += bestLength;
        continue;
      }
      if (canonical.length() > 0 && canonical.charAt(canonical.length() - 1) != ':') {
        canonical.append(':');
      }
      canonical.append(Integer.toHexString(groups[index]));
      index++;
    }
    return canonical.toString();
  }

  private LocalTlsLeaf readLocalTlsLeaf() {
    try {
      SslBundle bundle = Objects.requireNonNull(sslBundles.getBundle(TLS_BUNDLE_NAME));
      KeyStore keyStore = Objects.requireNonNull(bundle.getStores().getKeyStore());
      List<X509Certificate> leaves = new ArrayList<>();
      var aliases = keyStore.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (!keyStore.isKeyEntry(alias)) {
          continue;
        }
        Certificate certificate = keyStore.getCertificate(alias);
        if (!(certificate instanceof X509Certificate x509)) {
          throw unavailable();
        }
        leaves.add(x509);
      }
      if (leaves.size() != 1) {
        throw unavailable();
      }
      X509Certificate leaf = leaves.getFirst();
      leaf.checkValidity(java.util.Date.from(clock.instant()));
      GrpcPeerIdentity peer =
          GrpcPeerIdentity.fromCertificate(leaf).orElseThrow(IdentityUnavailableException::new);
      if (!expectedServiceUri().equals(peer.uri())) {
        throw unavailable();
      }
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(leaf.getPublicKey().getEncoded()));
      return new LocalTlsLeaf(peer.uri(), digest);
    } catch (IdentityUnavailableException failure) {
      throw failure;
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private SourceIdentity sourceIdentity(AccountSourceIdentity value) {
    try {
      return new SourceIdentity(
          value.getEnvironmentId(),
          value.getClusterId(),
          value.getClusterIncarnationUid(),
          value.getNamespace(),
          value.getNamespaceUid(),
          value.getConfigMapUid(),
          value.getBindingRevision(),
          value.getApiServerOrigin(),
          value.getServingCaSha256());
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private String expectedServiceUri() {
    return "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
  }

  private static boolean canonicalUuid(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    try {
      UUID operationId = UUID.fromString(value);
      return operationId.toString().equals(value)
          && operationId.version() == 4
          && operationId.variant() == 2;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static boolean isSha256(String value) {
    return value != null && SHA256.matcher(value).matches();
  }

  private static IdentityUnavailableException unavailable() {
    return new IdentityUnavailableException();
  }

  private record LocalTlsLeaf(String serviceUri, String spkiSha256) {}

  /** Redacted denial for unavailable or inconsistent protected Game Session identity evidence. */
  public static final class IdentityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IdentityUnavailableException() {
      super("Protected Game Session receiver identity is unavailable");
    }
  }
}
