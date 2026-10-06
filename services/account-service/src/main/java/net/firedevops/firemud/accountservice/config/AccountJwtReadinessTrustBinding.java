package net.firedevops.firemud.accountservice.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Independently protected, expiring trust for the non-authorizing readiness RPC only. */
@Component
public final class AccountJwtReadinessTrustBinding {
  public static final int MAX_FILE_BYTES = 8 * 1024;
  private static final String VERSION = "account-jwt-readiness-trust-binding/v1";
  private static final String VALIDATOR_ID = "account-service";
  private static final String HARNESS_SERVICE_ACCOUNT = "account-jwt-readiness-harness";
  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "enabled",
          "configRevision",
          "environmentId",
          "clusterId",
          "namespace",
          "expectedClusterIncarnationUid",
          "expectedNamespaceUid",
          "expectedPeerUri",
          "peerSpkiSha256Pins",
          "validatorId",
          "validatorInstanceId",
          "validUntilEpochSecond",
          "bindingDigest");
  private static final Pattern ENVIRONMENT =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern CLUSTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern INSTANCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final boolean enabled;
  private final String protectedBindingPath;
  private final Clock clock;

  @Autowired
  public AccountJwtReadinessTrustBinding(
      @Value("${firemud.account.jwt-readiness.validation.enabled:false}") boolean enabled,
      @Value("${firemud.account.jwt-readiness.validation.protected-binding-path:}")
          String protectedBindingPath) {
    this(enabled, protectedBindingPath, Clock.systemUTC());
  }

  AccountJwtReadinessTrustBinding(boolean enabled, String protectedBindingPath, Clock clock) {
    this.enabled = enabled;
    this.protectedBindingPath = protectedBindingPath == null ? "" : protectedBindingPath.trim();
    this.clock = Objects.requireNonNull(clock);
  }

  /** Missing, disabled, malformed, expired, or mutable trust configuration denies access. */
  public Optional<Binding> current() {
    if (!enabled || protectedBindingPath.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(readProtectedFile(Path.of(protectedBindingPath), clock.instant()));
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  static Binding parseProtectedBytes(byte[] bytes, long nowEpochSecond) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
      throw unavailable();
    }
    Map<String, String> fields = parseFields(decodeUtf8(bytes));
    if (!fields.keySet().equals(REQUIRED_FIELDS) || !"true".equals(fields.get("enabled"))) {
      throw unavailable();
    }

    String environmentId = fields.get("environmentId");
    String clusterId = fields.get("clusterId");
    String namespace = fields.get("namespace");
    String clusterUid = canonicalUuid(fields.get("expectedClusterIncarnationUid"));
    String namespaceUid = canonicalUuid(fields.get("expectedNamespaceUid"));
    String revision = fields.get("configRevision");
    String instanceId = fields.get("validatorInstanceId");
    long validUntil = parsePositiveLong(fields.get("validUntilEpochSecond"));
    if (!ENVIRONMENT.matcher(environmentId).matches()
        || !CLUSTER.matcher(clusterId).matches()
        || !ENVIRONMENT.matcher(namespace).matches()
        || !REVISION.matcher(revision).matches()
        || !INSTANCE.matcher(instanceId).matches()
        || !VALIDATOR_ID.equals(fields.get("validatorId"))
        || nowEpochSecond <= 0L
        || nowEpochSecond >= validUntil) {
      throw unavailable();
    }

    String expectedUri = fields.get("expectedPeerUri");
    if (!expectedHarnessUri(namespace, expectedUri)) {
      throw unavailable();
    }
    List<String> pins = parsePins(fields.get("peerSpkiSha256Pins"));
    String digest =
        computeBindingDigest(
            revision,
            environmentId,
            clusterId,
            namespace,
            clusterUid,
            namespaceUid,
            expectedUri,
            pins,
            instanceId,
            validUntil);
    if (!digest.equals(fields.get("bindingDigest"))) {
      throw unavailable();
    }
    return new Binding(
        environmentId,
        clusterId,
        namespace,
        clusterUid,
        namespaceUid,
        expectedUri,
        pins,
        revision,
        VALIDATOR_ID,
        instanceId,
        validUntil,
        digest);
  }

  private static Binding readProtectedFile(Path path, java.time.Instant now) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(absolute)) {
      throw new IOException("Protected readiness binding is unavailable");
    }
    BasicFileAttributes before =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() <= 0 || before.size() > MAX_FILE_BYTES) {
      throw new IOException("Protected readiness binding is unavailable");
    }
    verifyRootOwnedAndNonWritable(absolute);
    byte[] bytes = new byte[(int) before.size()];
    try (SeekableByteChannel channel =
        Files.newByteChannel(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw new IOException("Protected readiness binding changed while reading");
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw new IOException("Protected readiness binding changed while reading");
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || !Objects.equals(before.fileKey(), after.fileKey())
        || before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
      throw new IOException("Protected readiness binding changed while reading");
    }
    verifyRootOwnedAndNonWritable(absolute);
    return parseProtectedBytes(bytes, now.getEpochSecond());
  }

  private static Map<String, String> parseFields(String text) {
    String[] lines = text.split("\n", -1);
    int count = lines.length;
    if (count > 0 && lines[count - 1].isEmpty()) {
      count--;
    }
    Map<String, String> fields = new LinkedHashMap<>();
    for (int index = 0; index < count; index++) {
      String line = lines[index];
      int separator = line.indexOf('=');
      if (line.isEmpty() || separator <= 0 || separator == line.length() - 1) {
        throw unavailable();
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!REQUIRED_FIELDS.contains(key) || fields.putIfAbsent(key, value) != null) {
        throw unavailable();
      }
    }
    return fields;
  }

  private static void verifyRootOwnedAndNonWritable(Path file) throws IOException {
    Path parent = file.getParent();
    if (parent == null || Files.isSymbolicLink(file)) {
      throw new IOException("Protected readiness binding is unavailable");
    }
    Path realParent = parent.toRealPath();
    Object fileOwner = Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Object directoryOwner = Files.getAttribute(realParent, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> fileMode =
        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> directoryMode = Files.getPosixFilePermissions(realParent);
    if (!(fileOwner instanceof Number fileUid)
        || fileUid.longValue() != 0L
        || !(directoryOwner instanceof Number directoryUid)
        || directoryUid.longValue() != 0L
        || writable(fileMode)
        || writable(directoryMode)) {
      throw new IOException("Protected readiness binding ownership is invalid");
    }
  }

  private static boolean writable(Set<PosixFilePermission> permissions) {
    return permissions.contains(PosixFilePermission.OWNER_WRITE)
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw unavailable();
    }
  }

  private static List<String> parsePins(String value) {
    String[] parts = value.split(",", -1);
    if (parts.length == 0 || parts.length > 8) {
      throw unavailable();
    }
    List<String> pins = new ArrayList<>(parts.length);
    for (String pin : parts) {
      if (!SHA256.matcher(pin).matches() || pins.contains(pin)) {
        throw unavailable();
      }
      pins.add(pin);
    }
    return List.copyOf(pins);
  }

  private static boolean expectedHarnessUri(String namespace, String uri) {
    return ("spiffe://firemud/ns/" + namespace + "/sa/" + HARNESS_SERVICE_ACCOUNT).equals(uri);
  }

  private static String canonicalUuid(String value) {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value) || new UUID(0L, 0L).equals(uuid)) {
      throw unavailable();
    }
    return uuid.toString();
  }

  private static long parsePositiveLong(String value) {
    try {
      long result = Long.parseLong(value);
      if (result <= 0L || !Long.toString(result).equals(value)) {
        throw unavailable();
      }
      return result;
    } catch (NumberFormatException ex) {
      throw unavailable();
    }
  }

  public static String computeBindingDigest(
      String configRevision,
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String expectedPeerUri,
      List<String> pins,
      String validatorInstanceId,
      long validUntilEpochSecond) {
    try {
      Map<String, Object> binding = new LinkedHashMap<>();
      binding.put("digestVersion", VERSION);
      binding.put("configRevision", configRevision);
      binding.put("environmentId", environmentId);
      binding.put("clusterId", clusterId);
      binding.put("namespace", namespace);
      binding.put("expectedClusterIncarnationUid", expectedClusterIncarnationUid);
      binding.put("expectedNamespaceUid", expectedNamespaceUid);
      binding.put("expectedPeerUri", expectedPeerUri);
      binding.put("peerSpkiSha256Pins", List.copyOf(pins));
      binding.put("validatorId", VALIDATOR_ID);
      binding.put("validatorInstanceId", validatorInstanceId);
      binding.put("validUntilEpochSecond", validUntilEpochSecond);
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(binding));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (IOException | NoSuchAlgorithmException | RuntimeException ex) {
      throw new IllegalStateException("Readiness binding digest is unavailable");
    }
  }

  public static PeerIdentity identifyPeer(java.security.cert.Certificate[] certificates) {
    if (certificates == null || certificates.length == 0) {
      throw new PeerIdentityRejectedException();
    }
    if (!(certificates[0] instanceof X509Certificate certificate)) {
      throw new PeerIdentityRejectedException();
    }
    try {
      certificate.checkValidity();
      Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
      if (subjectAlternativeNames == null) {
        throw new PeerIdentityRejectedException();
      }
      String foundUri = null;
      int uriCount = 0;
      for (List<?> entry : subjectAlternativeNames) {
        if (entry.size() < 2 || !(entry.get(0) instanceof Integer type) || type != 6) {
          continue;
        }
        uriCount++;
        if (!(entry.get(1) instanceof String uri)) {
          throw new PeerIdentityRejectedException();
        }
        foundUri = uri;
      }
      if (uriCount != 1 || foundUri == null) {
        throw new PeerIdentityRejectedException();
      }
      String spki =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(certificate.getPublicKey().getEncoded()));
      return new PeerIdentity(foundUri, spki);
    } catch (CertificateException | NoSuchAlgorithmException ex) {
      throw new PeerIdentityRejectedException();
    }
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Readiness trust binding is unavailable");
  }

  public static boolean matchesPeer(
      java.security.cert.Certificate[] certificates, Binding binding) {
    if (binding == null) {
      return false;
    }
    try {
      PeerIdentity peer = identifyPeer(certificates);
      return binding.matches(peer);
    } catch (RuntimeException rejected) {
      return false;
    }
  }

  public record PeerIdentity(String uri, String spkiSha256) {}

  /**
   * Configured per-validator-instance identity; this is not proof of pod UID or fleet inventory.
   */
  public record Binding(
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String expectedPeerUri,
      List<String> peerSpkiSha256Pins,
      String configRevision,
      String validatorId,
      String validatorInstanceId,
      long validUntilEpochSecond,
      String bindingDigest) {
    public Binding {
      Objects.requireNonNull(environmentId);
      Objects.requireNonNull(clusterId);
      Objects.requireNonNull(namespace);
      Objects.requireNonNull(expectedClusterIncarnationUid);
      Objects.requireNonNull(expectedNamespaceUid);
      Objects.requireNonNull(expectedPeerUri);
      peerSpkiSha256Pins = List.copyOf(peerSpkiSha256Pins);
      Objects.requireNonNull(configRevision);
      Objects.requireNonNull(validatorInstanceId);
      Objects.requireNonNull(bindingDigest);
      if (!ENVIRONMENT.matcher(environmentId).matches()
          || !CLUSTER.matcher(clusterId).matches()
          || !ENVIRONMENT.matcher(namespace).matches()
          || !REVISION.matcher(configRevision).matches()
          || !INSTANCE.matcher(validatorInstanceId).matches()
          || !VALIDATOR_ID.equals(validatorId)
          || peerSpkiSha256Pins.isEmpty()
          || peerSpkiSha256Pins.size() > 8
          || validUntilEpochSecond <= 0L
          || !SHA256.matcher(bindingDigest).matches()
          || !expectedHarnessUri(namespace, expectedPeerUri)
          || !peerSpkiSha256Pins.stream().allMatch(pin -> SHA256.matcher(pin).matches())
          || peerSpkiSha256Pins.stream().distinct().count() != peerSpkiSha256Pins.size()) {
        throw unavailable();
      }
      UUID clusterUid = UUID.fromString(expectedClusterIncarnationUid);
      UUID namespaceUid = UUID.fromString(expectedNamespaceUid);
      if (!clusterUid.toString().equals(expectedClusterIncarnationUid)
          || !namespaceUid.toString().equals(expectedNamespaceUid)
          || clusterUid.equals(new UUID(0L, 0L))
          || namespaceUid.equals(new UUID(0L, 0L))) {
        throw unavailable();
      }
      String expectedDigest =
          computeBindingDigest(
              configRevision,
              environmentId,
              clusterId,
              namespace,
              expectedClusterIncarnationUid,
              expectedNamespaceUid,
              expectedPeerUri,
              peerSpkiSha256Pins,
              validatorInstanceId,
              validUntilEpochSecond);
      if (!expectedDigest.equals(bindingDigest)) {
        throw unavailable();
      }
    }

    public boolean isCurrentAt(long nowEpochSecond) {
      return nowEpochSecond > 0L && nowEpochSecond < validUntilEpochSecond;
    }

    public boolean matches(PeerIdentity peer) {
      if (peer == null || !expectedPeerUri.equals(peer.uri())) {
        return false;
      }
      try {
        byte[] actual = HexFormat.of().parseHex(peer.spkiSha256());
        boolean matched = false;
        for (String pin : peerSpkiSha256Pins) {
          matched |= MessageDigest.isEqual(actual, HexFormat.of().parseHex(pin));
        }
        return matched;
      } catch (IllegalArgumentException malformedPeer) {
        return false;
      }
    }
  }

  public static final class PeerIdentityRejectedException extends RuntimeException {
    public PeerIdentityRejectedException() {
      super("Readiness caller identity is unavailable");
    }
  }
}
