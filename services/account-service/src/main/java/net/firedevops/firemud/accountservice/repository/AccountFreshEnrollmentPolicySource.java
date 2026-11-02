package net.firedevops.firemud.accountservice.repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered reader of deployment-protected, category-owner policy/closure publications. The
 * absolute mount is a composition-time trust root, never request data. Its root-owned pin and
 * artifact must stay inside that root. There are no default publications or runtime delivery.
 */
public final class AccountFreshEnrollmentPolicySource {
  private static final int MAX_BYTES = 16384;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "category",
          "sourceIdentity",
          "publicationId",
          "revision",
          "policyId",
          "policyRule",
          "closureId",
          "closureRule");
  private final Path mountRoot;

  public AccountFreshEnrollmentPolicySource(Path deploymentOwnedMountRoot) {
    mountRoot = Objects.requireNonNull(deploymentOwnedMountRoot);
    if (!mountRoot.isAbsolute())
      throw new IllegalArgumentException("Absolute protected mount required");
  }

  /**
   * Reads the fixed owner pin twice around the bounded artifact read; changed publication denies.
   */
  public VerifiedPublication read(Category category) {
    try {
      Path root = mountRoot.toRealPath();
      requireProtected(root, true);
      Path pinPath = contained(root, category.storageValue() + ".pin");
      byte[] pin = readBounded(pinPath);
      String digest = new String(pin, StandardCharsets.US_ASCII);
      if (!digest.matches("sha256:[0-9a-f]{64}\\n")) throw unavailable();
      digest = digest.substring(0, digest.length() - 1);
      Path artifact = contained(root, digest.substring(7) + ".json");
      byte[] bytes = readBounded(artifact);
      if (!digest.equals(digest(bytes))
          || !Arrays.equals(pin, readBounded(contained(root, category.storageValue() + ".pin")))
          || !root.equals(mountRoot.toRealPath())) throw unavailable();
      JsonNode node = JSON.readTree(bytes);
      if (!node.isObject() || node.size() != FIELDS.size()) throw unavailable();
      for (String field : FIELDS) {
        if (node.get(field) == null || !node.get(field).isTextual()) throw unavailable();
      }
      if (!Arrays.equals(
              bytes, Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(node)))
          || !"account-fresh-enrollment-policy/v1".equals(text(node, "schemaVersion"))
          || !category.storageValue().equals(text(node, "category"))
          || !sourceIdentity(category).equals(text(node, "sourceIdentity"))
          || !text(node, "revision").matches("[1-9][0-9]{0,17}")
          || !Set.of("CERTIFY_NEW_ACTIVE_ACCOUNT", "DENY_NEW_ACCOUNT")
              .contains(text(node, "policyRule"))
          || !Set.of("EXACT_FRESH_INSERT_ONLY", "CLOSED").contains(text(node, "closureRule"))) {
        throw unavailable();
      }
      UUID publicationId = uuid(text(node, "publicationId"));
      UUID policyId = uuid(text(node, "policyId"));
      UUID closureId = uuid(text(node, "closureId"));
      // Root/source identity and the pinned bytes, rather than a caller's claimed publisher, grant
      // publication provenance. The closed policy and closure still decide applicability in SQL.
      return new VerifiedPublication(
          this,
          category,
          publicationId,
          Long.parseLong(text(node, "revision")),
          policyId,
          closureId,
          text(node, "policyRule"),
          text(node, "closureRule"),
          sourceIdentity(category),
          root.toString(),
          digest,
          bytes);
    } catch (IOException
        | tools.jackson.core.JacksonException
        | IllegalArgumentException exception) {
      throw unavailable();
    }
  }

  static String sourceIdentity(Category category) {
    return category == Category.ACCOUNT_SECURITY_LOCK
        ? "account-service/account-security-enrollment-policy/v1"
        : "logging-admin-service/platform-access-enrollment-policy/v1";
  }

  private static String text(JsonNode node, String name) {
    return node.get(name).textValue();
  }

  private static UUID uuid(String text) {
    UUID value = UUID.fromString(text);
    if (!value.toString().equals(text) || value.equals(new UUID(0, 0))) throw unavailable();
    return value;
  }

  private static Path contained(Path root, String name) throws IOException {
    Path result = root.resolve(name).toRealPath();
    if (!result.startsWith(root)) throw unavailable();
    requireProtected(result, false);
    return result;
  }

  private static void requireProtected(Path path, boolean directory) throws IOException {
    if (!(directory
            ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue()
            != 0) {
      throw unavailable();
    }
    var mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    if (mode.contains(PosixFilePermission.GROUP_WRITE)
        || mode.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw unavailable();
    }
  }

  private static byte[] readBounded(Path path) throws IOException {
    requireProtected(path, false);
    ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
    try (SeekableByteChannel channel =
        Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
        /* bounded snapshot */
      }
      if (!buffer.hasRemaining() || buffer.position() == 0) throw unavailable();
      return Arrays.copyOf(buffer.array(), buffer.position());
    }
  }

  static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Current protected category enrollment policy/closure unavailable");
  }

  /** Privately constructed only after the actual protected publication bytes have been verified. */
  public static final class VerifiedPublication {
    private final AccountFreshEnrollmentPolicySource protectedSource;
    final Category category;
    final UUID publicationId;
    final long revision;
    final UUID policyId;
    final UUID closureId;
    final String policyRule;
    final String closureRule;
    final String sourceIdentity;
    final String sourceRoot;
    final String digest;
    private final byte[] bytes;

    private VerifiedPublication(
        AccountFreshEnrollmentPolicySource protectedSource,
        Category category,
        UUID publicationId,
        long revision,
        UUID policyId,
        UUID closureId,
        String policyRule,
        String closureRule,
        String sourceIdentity,
        String sourceRoot,
        String digest,
        byte[] bytes) {
      this.protectedSource = protectedSource;
      this.category = category;
      this.publicationId = publicationId;
      this.revision = revision;
      this.policyId = policyId;
      this.closureId = closureId;
      this.policyRule = policyRule;
      this.closureRule = closureRule;
      this.sourceIdentity = sourceIdentity;
      this.sourceRoot = sourceRoot;
      this.digest = digest;
      this.bytes = bytes.clone();
    }

    byte[] bytes() {
      return bytes.clone();
    }

    void requireCurrentProtectedSource() {
      VerifiedPublication current = protectedSource.read(category);
      if (!publicationId.equals(current.publicationId)
          || !digest.equals(current.digest)
          || !sourceRoot.equals(current.sourceRoot)
          || !Arrays.equals(bytes, current.bytes)) {
        throw unavailable();
      }
    }
  }
}
