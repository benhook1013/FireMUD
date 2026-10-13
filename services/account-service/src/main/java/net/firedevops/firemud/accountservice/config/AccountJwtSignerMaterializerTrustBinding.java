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
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the independently protected trust binding for Account's narrow JWT materializer RPCs. The
 * file must be root-owned and non-writable by the Account/materializer workload. It is read again
 * for every RPC so pin rotation or trust withdrawal takes effect without process restart.
 */
@Component
public final class AccountJwtSignerMaterializerTrustBinding {
  public static final int MAX_FILE_BYTES = 8 * 1024;
  private static final String VERSION = "account-jwt-signer-materializer-binding/v1";
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
          "bindingDigest");
  private static final Pattern ENVIRONMENT =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern CLUSTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final boolean enabled;
  private final String protectedBindingPath;

  public AccountJwtSignerMaterializerTrustBinding(
      @Value("${firemud.account.jwt-signer.materialization.enabled:false}") boolean enabled,
      @Value("${firemud.account.jwt-signer.materialization.protected-binding-path:}")
          String protectedBindingPath) {
    this.enabled = enabled;
    this.protectedBindingPath = protectedBindingPath == null ? "" : protectedBindingPath.trim();
  }

  /** Missing, disabled, malformed, or mutable trust configuration always means no access. */
  public Optional<AccountJwtSignerMaterializerTrustBinding.Binding> current() {
    if (!enabled || protectedBindingPath.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(readProtectedFile(Path.of(protectedBindingPath)));
    } catch (Exception ex) {
      // Never expose config contents, file paths, or certificate details through RPC errors.
      return Optional.empty();
    }
  }

  /** Testable parser boundary; production calls still require the protected-file checks above. */
  static AccountJwtSignerMaterializerTrustBinding.Binding parseProtectedBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
      throw new IllegalArgumentException("JWT materializer trust binding is unavailable");
    }
    String text = decodeUtf8(bytes);
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
        throw new IllegalArgumentException("JWT materializer trust binding is malformed");
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!REQUIRED_FIELDS.contains(key) || fields.putIfAbsent(key, value) != null) {
        throw new IllegalArgumentException("JWT materializer trust binding is malformed");
      }
    }
    if (!fields.keySet().equals(REQUIRED_FIELDS) || !"true".equals(fields.get("enabled"))) {
      throw new IllegalArgumentException("JWT materializer trust binding is disabled");
    }

    String environmentId = fields.get("environmentId");
    String clusterId = fields.get("clusterId");
    String namespace = fields.get("namespace");
    String clusterUid = canonicalUuid(fields.get("expectedClusterIncarnationUid"));
    String namespaceUid = canonicalUuid(fields.get("expectedNamespaceUid"));
    String revision = fields.get("configRevision");
    if (!ENVIRONMENT.matcher(environmentId).matches()
        || !CLUSTER.matcher(clusterId).matches()
        || !ENVIRONMENT.matcher(namespace).matches()
        || !REVISION.matcher(revision).matches()) {
      throw new IllegalArgumentException("JWT materializer trust binding is malformed");
    }
    String expectedUri = fields.get("expectedPeerUri");
    if (!validExpectedPeerUri(namespace, expectedUri)) {
      throw new IllegalArgumentException("JWT materializer workload identity is not canonical");
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
            pins);
    if (!digest.equals(fields.get("bindingDigest"))) {
      throw new IllegalArgumentException("JWT materializer trust binding digest is invalid");
    }
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        environmentId,
        clusterId,
        namespace,
        clusterUid,
        namespaceUid,
        expectedUri,
        pins,
        revision,
        digest);
  }

  private static Binding readProtectedFile(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(absolute)) {
      throw new IOException("Protected Account JWT materializer binding is not a regular file");
    }
    BasicFileAttributes before =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()) {
      throw new IOException("Protected Account JWT materializer binding is not a regular file");
    }
    verifyRootOwnedAndNonWritable(absolute);
    if (before.size() <= 0 || before.size() > MAX_FILE_BYTES) {
      throw new IOException("Protected Account JWT materializer binding size is invalid");
    }
    byte[] bytes = new byte[(int) before.size()];
    try (SeekableByteChannel channel =
        Files.newByteChannel(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        int count = channel.read(buffer);
        if (count < 0) {
          throw new IOException("Protected Account JWT materializer binding changed while reading");
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw new IOException("Protected Account JWT materializer binding changed while reading");
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || !Objects.equals(before.fileKey(), after.fileKey())
        || before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
      throw new IOException("Protected Account JWT materializer binding changed while reading");
    }
    verifyRootOwnedAndNonWritable(absolute);
    return parseProtectedBytes(bytes);
  }

  private static void verifyRootOwnedAndNonWritable(Path file) throws IOException {
    Path parent = file.getParent();
    if (parent == null || Files.isSymbolicLink(file)) {
      throw new IOException("Protected Account JWT materializer binding is unavailable");
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
      throw new IOException("Protected Account JWT materializer binding ownership is invalid");
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
      throw new IllegalArgumentException("JWT materializer trust binding is not UTF-8", ex);
    }
  }

  private static List<String> parsePins(String value) {
    String[] parts = value.split(",", -1);
    if (parts.length == 0 || parts.length > 8) {
      throw new IllegalArgumentException("JWT materializer certificate pins are malformed");
    }
    List<String> pins = new ArrayList<>(parts.length);
    for (String pin : parts) {
      if (!SHA256.matcher(pin).matches() || pins.contains(pin)) {
        throw new IllegalArgumentException("JWT materializer certificate pins are malformed");
      }
      pins.add(pin);
    }
    return List.copyOf(pins);
  }

  private static boolean validExpectedPeerUri(String namespace, String uri) {
    String prefix = "spiffe://firemud/ns/" + namespace + "/sa/";
    return uri != null
        && uri.startsWith(prefix)
        && ENVIRONMENT.matcher(uri.substring(prefix.length())).matches();
  }

  private static String canonicalUuid(String value) {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value) || new UUID(0L, 0L).equals(uuid)) {
      throw new IllegalArgumentException("JWT materializer trust UID is malformed");
    }
    return uuid.toString();
  }

  public static String computeBindingDigest(
      String configRevision,
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String expectedPeerUri,
      List<String> pins) {
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
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(binding));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (Exception ex) {
      throw new IllegalStateException("JWT materializer trust binding digest is unavailable", ex);
    }
  }

  public static boolean matchesPeer(
      java.security.cert.Certificate[] peerCertificates, Binding binding) {
    if (peerCertificates == null || peerCertificates.length == 0 || binding == null) {
      return false;
    }
    if (!(peerCertificates[0] instanceof X509Certificate certificate)) {
      return false;
    }
    try {
      certificate.checkValidity();
      Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
      if (subjectAlternativeNames == null) {
        return false;
      }
      String foundUri = null;
      int uriCount = 0;
      for (List<?> entry : subjectAlternativeNames) {
        if (entry.size() < 2 || !(entry.get(0) instanceof Integer type) || type != 6) {
          continue;
        }
        uriCount++;
        if (!(entry.get(1) instanceof String uri)) {
          return false;
        }
        foundUri = uri;
      }
      if (uriCount != 1 || !binding.expectedPeerUri().equals(foundUri)) {
        return false;
      }
      byte[] spkiDigest =
          MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
      byte[] configuredPin = HexFormat.of().parseHex(binding.peerSpkiSha256Pins().get(0));
      boolean matched = MessageDigest.isEqual(spkiDigest, configuredPin);
      for (int index = 1; index < binding.peerSpkiSha256Pins().size(); index++) {
        byte[] pin = HexFormat.of().parseHex(binding.peerSpkiSha256Pins().get(index));
        matched |= MessageDigest.isEqual(spkiDigest, pin);
      }
      return matched;
    } catch (CertificateException | NoSuchAlgorithmException | RuntimeException ex) {
      return false;
    }
  }

  public record Binding(
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String expectedPeerUri,
      List<String> peerSpkiSha256Pins,
      String configRevision,
      String bindingDigest) {
    public Binding {
      Objects.requireNonNull(environmentId, "Environment identity is required");
      Objects.requireNonNull(clusterId, "Cluster identity is required");
      Objects.requireNonNull(namespace, "Namespace identity is required");
      canonicalUuid(expectedClusterIncarnationUid);
      canonicalUuid(expectedNamespaceUid);
      Objects.requireNonNull(expectedPeerUri, "Materializer URI identity is required");
      peerSpkiSha256Pins = List.copyOf(peerSpkiSha256Pins);
      if (peerSpkiSha256Pins.isEmpty() || peerSpkiSha256Pins.size() > 8) {
        throw new IllegalArgumentException("JWT materializer certificate pins are unavailable");
      }
      for (String pin : peerSpkiSha256Pins) {
        if (!SHA256.matcher(pin).matches()) {
          throw new IllegalArgumentException("JWT materializer certificate pins are malformed");
        }
      }
      Objects.requireNonNull(configRevision, "Materializer trust revision is required");
      if (!REVISION.matcher(configRevision).matches() || !SHA256.matcher(bindingDigest).matches()) {
        throw new IllegalArgumentException("JWT materializer trust binding digest is malformed");
      }
      if (peerSpkiSha256Pins.stream().distinct().count() != peerSpkiSha256Pins.size()) {
        throw new IllegalArgumentException("JWT materializer certificate pins are duplicated");
      }
      AccountJwtSignerDesiredStateRepository.Binding stateBinding =
          new AccountJwtSignerDesiredStateRepository.Binding(
              environmentId,
              clusterId,
              namespace,
              AccountJwtSignerDesiredStateRepository.CustodyMode
                  .INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
      if (!validExpectedPeerUri(stateBinding.namespace(), expectedPeerUri)) {
        throw new IllegalArgumentException("JWT materializer workload identity is not canonical");
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
              peerSpkiSha256Pins);
      if (!expectedDigest.equals(bindingDigest)) {
        throw new IllegalArgumentException("JWT materializer trust binding digest is invalid");
      }
    }

    public AccountJwtSignerDesiredStateRepository.Binding accountBinding() {
      return new AccountJwtSignerDesiredStateRepository.Binding(
          environmentId,
          clusterId,
          namespace,
          AccountJwtSignerDesiredStateRepository.CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    }
  }
}
