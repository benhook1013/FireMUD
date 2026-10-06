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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default-inactive, independently protected expected validator applicability and runtime pins.
 *
 * <p>This binding is an operator-installed input, never learned from Kubernetes labels, caller
 * claims, a first read, or the readiness plan's partial applicability matrix. It provides no
 * readiness, promotion, or per-validator acceptance authority.
 */
public final class AccountJwtValidatorInventoryBinding {
  public static final int MAX_BINDING_BYTES = 32 * 1024;
  public static final int MAX_RUNTIME_CONFIG_BYTES = 8 * 1024;
  public static final Path PROTECTED_BINDING_PATH =
      Path.of("/etc/firemud/account-jwt-api/validator-inventory.json");
  private static final Path PROTECTED_ROOT = Path.of("/etc/firemud/account-jwt-api");
  private static final String VERSION = "account-jwt-validator-inventory-binding/v1";
  private static final String RUNTIME_CONFIG_ENV = "FIREMUD_JWT_VERIFIER_CONFIG";
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "version",
          "enabled",
          "configRevision",
          "environmentId",
          "clusterId",
          "namespace",
          "expectedClusterIncarnationUid",
          "expectedNamespaceUid",
          "apiBindingRevision",
          "apiBindingDigest",
          "validators",
          "bindingDigest");
  private static final Set<String> VALIDATOR_FIELDS =
      Set.of(
          "validatorId",
          "deploymentName",
          "deploymentUid",
          "selector",
          "replicas",
          "containerName",
          "image",
          "jwksUri",
          "maxCacheAgeSeconds",
          "profiles");
  private static final Set<String> PROFILE_FIELDS = Set.of("tokenProfile", "audience");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern ENVIRONMENT = DNS_LABEL;
  private static final Pattern CLUSTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern REVISION = CLUSTER;
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern LABEL_KEY =
      Pattern.compile(
          "[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,61}[A-Za-z0-9])?(?:/[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,61}[A-Za-z0-9])?)?");
  private static final Pattern LABEL_VALUE =
      Pattern.compile("(?:[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,61}[A-Za-z0-9])?)?");
  private static final Pattern PROFILE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  private static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");

  private final boolean enabled;
  private final String configuredPath;
  private final AtomicReference<FileIdentity> acceptedFile = new AtomicReference<>();

  /** Creates a disabled binding; production composition must opt in with the one fixed path. */
  public AccountJwtValidatorInventoryBinding() {
    this(false, "");
  }

  public AccountJwtValidatorInventoryBinding(boolean enabled, String protectedBindingPath) {
    this.enabled = enabled;
    String path = protectedBindingPath == null ? "" : protectedBindingPath.trim();
    this.configuredPath = PROTECTED_BINDING_PATH.toString().equals(path) ? path : "";
  }

  /** Returns the exact immutable protected expectations, or empty when disabled/unavailable. */
  public java.util.Optional<ProtectedInventory> current() {
    if (!enabled || configuredPath.isBlank()) {
      return java.util.Optional.empty();
    }
    try {
      ProtectedFile file = readProtectedFile(PROTECTED_BINDING_PATH);
      ProtectedInventory parsed = parseProtectedBytes(file.bytes());
      FileIdentity existing = acceptedFile.get();
      if (existing == null) {
        acceptedFile.compareAndSet(null, file.identity());
        existing = acceptedFile.get();
      }
      if (!file.identity().equals(existing)) {
        throw unavailable();
      }
      return java.util.Optional.of(parsed);
    } catch (RuntimeException | IOException failure) {
      return java.util.Optional.empty();
    }
  }

  static ProtectedInventory parseProtectedBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BINDING_BYTES) {
      throw unavailable();
    }
    try {
      JsonNode root = JSON.readTree(decodeUtf8(bytes));
      requireObjectFields(root, ROOT_FIELDS);
      if (!VERSION.equals(requiredText(root, "version", 96))
          || !"true".equals(requiredText(root, "enabled", 5))) {
        throw unavailable();
      }
      String revision = requiredText(root, "configRevision", 128);
      String environmentId = requiredText(root, "environmentId", 63);
      String clusterId = requiredText(root, "clusterId", 128);
      String namespace = requiredText(root, "namespace", 63);
      String clusterUid = canonicalUid(requiredText(root, "expectedClusterIncarnationUid", 36));
      String namespaceUid = canonicalUid(requiredText(root, "expectedNamespaceUid", 36));
      String apiRevision = requiredText(root, "apiBindingRevision", 128);
      String apiDigest = requiredText(root, "apiBindingDigest", 64);
      String digest = requiredText(root, "bindingDigest", 64);
      if (!REVISION.matcher(revision).matches()
          || !ENVIRONMENT.matcher(environmentId).matches()
          || !CLUSTER.matcher(clusterId).matches()
          || !DNS_LABEL.matcher(namespace).matches()
          || !REVISION.matcher(apiRevision).matches()
          || !SHA256.matcher(apiDigest).matches()
          || !SHA256.matcher(digest).matches()
          || !digest.equals(computeBindingDigest(root))) {
        throw unavailable();
      }
      List<ValidatorExpectation> validators = parseValidators(root.get("validators"));
      if (validators.isEmpty() || validators.size() > 32) {
        throw unavailable();
      }
      return new ProtectedInventory(
          revision,
          environmentId,
          clusterId,
          namespace,
          clusterUid,
          namespaceUid,
          apiRevision,
          apiDigest,
          digest,
          validators);
    } catch (IllegalArgumentException failure) {
      throw unavailable();
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  public static String computeBindingDigest(JsonNode root) {
    try {
      requireObjectFields(root, ROOT_FIELDS);
      Map<String, Object> value = new LinkedHashMap<>();
      for (String field : ROOT_FIELDS) {
        if (!"bindingDigest".equals(field)) {
          JsonNode node = root.get(field);
          value.put(field, JSON.readValue(JSON.writeValueAsString(node), Object.class));
        }
      }
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static List<ValidatorExpectation> parseValidators(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > 32) {
      throw unavailable();
    }
    List<ValidatorExpectation> result = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    Set<String> deployments = new LinkedHashSet<>();
    for (JsonNode validator : node) {
      requireObjectFields(validator, VALIDATOR_FIELDS);
      String id = requiredText(validator, "validatorId", 63);
      String deployment = requiredText(validator, "deploymentName", 63);
      String deploymentUid = canonicalUid(requiredText(validator, "deploymentUid", 36));
      String container = requiredText(validator, "containerName", 63);
      String image = requiredText(validator, "image", 512);
      String jwksUri = canonicalJwksUri(requiredText(validator, "jwksUri", 512));
      int replicas = exactInt(validator.get("replicas"), 1, 128);
      int maximumCacheAge = exactInt(validator.get("maxCacheAgeSeconds"), 1, 300);
      if (!DNS_LABEL.matcher(id).matches()
          || !DNS_LABEL.matcher(deployment).matches()
          || !DNS_LABEL.matcher(container).matches()
          || !image.matches("[^\\s@]+@sha256:[0-9a-f]{64}")
          || !ids.add(id)
          || !deployments.add(deployment)) {
        throw unavailable();
      }
      Map<String, String> selector = parseSelector(validator.get("selector"));
      List<ProfileExpectation> profiles = parseProfiles(validator.get("profiles"));
      if (profiles.isEmpty()) {
        throw unavailable();
      }
      String runtimeConfig = canonicalRuntimeConfig(jwksUri, maximumCacheAge, profiles);
      if (runtimeConfig.getBytes(StandardCharsets.UTF_8).length > MAX_RUNTIME_CONFIG_BYTES) {
        throw unavailable();
      }
      result.add(
          new ValidatorExpectation(
              id,
              deployment,
              deploymentUid,
              selector,
              replicas,
              container,
              image,
              jwksUri,
              maximumCacheAge,
              profiles,
              runtimeConfig));
    }
    return List.copyOf(result);
  }

  private static Map<String, String> parseSelector(JsonNode node) {
    if (node == null || !node.isObject() || node.isEmpty() || node.size() > 16) {
      throw unavailable();
    }
    Map<String, String> selector = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      if (!LABEL_KEY.matcher(entry.getKey()).matches()
          || !entry.getValue().isTextual()
          || !LABEL_VALUE.matcher(entry.getValue().asText()).matches()) {
        throw unavailable();
      }
      selector.put(entry.getKey(), entry.getValue().asText());
    }
    return Map.copyOf(selector);
  }

  private static List<ProfileExpectation> parseProfiles(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > 32) {
      throw unavailable();
    }
    List<ProfileExpectation> profiles = new ArrayList<>();
    Set<String> unique = new LinkedHashSet<>();
    for (JsonNode profile : node) {
      requireObjectFields(profile, PROFILE_FIELDS);
      String name = requiredText(profile, "tokenProfile", 64);
      String audience = requiredText(profile, "audience", 256);
      if (!PROFILE.matcher(name).matches()
          || !AUDIENCE.matcher(audience).matches()
          || !unique.add(name + "\u0000" + audience)) {
        throw unavailable();
      }
      profiles.add(new ProfileExpectation(name, audience));
    }
    profiles.sort(
        Comparator.comparing(ProfileExpectation::tokenProfile)
            .thenComparing(ProfileExpectation::audience));
    return List.copyOf(profiles);
  }

  private static String canonicalRuntimeConfig(
      String jwksUri, int maxCacheAgeSeconds, List<ProfileExpectation> profiles) {
    try {
      List<Map<String, Object>> profileValues =
          profiles.stream()
              .map(
                  profile ->
                      Map.<String, Object>of(
                          "tokenProfile", profile.tokenProfile(), "audience", profile.audience()))
              .toList();
      Map<String, Object> runtime =
          Map.of(
              "algorithm",
              "RS256",
              "jwksUri",
              jwksUri,
              "maxCacheAgeSeconds",
              maxCacheAgeSeconds,
              "profiles",
              profileValues);
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(runtime)),
          StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static String canonicalJwksUri(String value) {
    try {
      java.net.URI uri = java.net.URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !"/.well-known/jwks.json".equals(uri.getRawPath())) {
        throw unavailable();
      }
      return uri.toASCIIString();
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static ProtectedFile readProtectedFile(Path path) throws IOException {
    if (!path.equals(PROTECTED_BINDING_PATH)
        || Files.isSymbolicLink(path)
        || !path.equals(path.toRealPath())) {
      throw unavailable();
    }
    verifyProtectedDirectory(PROTECTED_ROOT);
    BasicFileAttributes before =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Object group = Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()
        || before.size() <= 0
        || before.size() > MAX_BINDING_BYTES
        || !(owner instanceof Number uid)
        || uid.longValue() != 0L
        || !(group instanceof Number gid)
        || writable(mode)
        || !mode.contains(PosixFilePermission.OWNER_READ)
        || mode.contains(PosixFilePermission.OTHERS_READ)) {
      throw unavailable();
    }
    byte[] bytes = new byte[(int) before.size()];
    try (SeekableByteChannel channel =
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw unavailable();
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw unavailable();
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> afterMode =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    Object afterOwner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Object afterGroup = Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS);
    if (!sameAttributes(before, after)
        || !mode.equals(afterMode)
        || !Objects.equals(owner, afterOwner)
        || !Objects.equals(group, afterGroup)) {
      throw unavailable();
    }
    FileIdentity identity =
        new FileIdentity(
            String.valueOf(before.fileKey()),
            before.size(),
            before.lastModifiedTime().toMillis(),
            uid.longValue(),
            gid.longValue(),
            Set.copyOf(mode),
            sha256(bytes));
    return new ProtectedFile(bytes, identity);
  }

  private static void verifyProtectedDirectory(Path directory) throws IOException {
    if (!directory.equals(directory.toRealPath())
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw unavailable();
    }
    Object owner = Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> mode =
        Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
    if (!(owner instanceof Number uid)
        || uid.longValue() != 0L
        || mode.contains(PosixFilePermission.GROUP_WRITE)
        || mode.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw unavailable();
    }
  }

  private static void requireObjectFields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject() || node.size() != expected.size()) {
      throw unavailable();
    }
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if (!expected.contains(field.getKey())
          || field.getValue() == null
          || field.getValue().isNull()) {
        throw unavailable();
      }
    }
  }

  private static String requiredText(JsonNode node, String field, int maximumLength) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > maximumLength) {
      throw unavailable();
    }
    return value.asText();
  }

  private static int exactInt(JsonNode node, int minimum, int maximum) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
      throw unavailable();
    }
    int value = node.intValue();
    if (value < minimum || value > maximum) {
      throw unavailable();
    }
    return value;
  }

  private static String canonicalUid(String uid) {
    if (!UID.matcher(uid).matches() || uid.startsWith("00000000-")) {
      throw unavailable();
    }
    return uid;
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException failure) {
      throw unavailable();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private static boolean sameAttributes(BasicFileAttributes before, BasicFileAttributes after) {
    return after.isRegularFile()
        && Objects.equals(before.fileKey(), after.fileKey())
        && before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime());
  }

  private static boolean writable(Set<PosixFilePermission> mode) {
    return mode.contains(PosixFilePermission.OWNER_WRITE)
        || mode.contains(PosixFilePermission.GROUP_WRITE)
        || mode.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Protected Account validator inventory binding is unavailable");
  }

  private record FileIdentity(
      String fileKey,
      long size,
      long modifiedMillis,
      long ownerUid,
      long groupId,
      Set<PosixFilePermission> permissions,
      String contentSha256) {}

  private record ProtectedFile(byte[] bytes, FileIdentity identity) {}

  /** An immutable, file-backed expected inventory; no public constructor or mutation exists. */
  public static final class ProtectedInventory {
    private final String configRevision;
    private final String environmentId;
    private final String clusterId;
    private final String namespace;
    private final String expectedClusterIncarnationUid;
    private final String expectedNamespaceUid;
    private final String apiBindingRevision;
    private final String apiBindingDigest;
    private final String bindingDigest;
    private final List<ValidatorExpectation> validators;

    private ProtectedInventory(
        String configRevision,
        String environmentId,
        String clusterId,
        String namespace,
        String expectedClusterIncarnationUid,
        String expectedNamespaceUid,
        String apiBindingRevision,
        String apiBindingDigest,
        String bindingDigest,
        List<ValidatorExpectation> validators) {
      this.configRevision = configRevision;
      this.environmentId = environmentId;
      this.clusterId = clusterId;
      this.namespace = namespace;
      this.expectedClusterIncarnationUid = expectedClusterIncarnationUid;
      this.expectedNamespaceUid = expectedNamespaceUid;
      this.apiBindingRevision = apiBindingRevision;
      this.apiBindingDigest = apiBindingDigest;
      this.bindingDigest = bindingDigest;
      this.validators = List.copyOf(validators);
    }

    public boolean matchesApiBinding(AccountJwtJwksApiBinding.ParsedBinding binding) {
      return binding != null
          && configRevision != null
          && environmentId.equals(binding.environmentId())
          && clusterId.equals(binding.clusterId())
          && namespace.equals(binding.namespace())
          && expectedClusterIncarnationUid.equals(binding.expectedClusterIncarnationUid())
          && expectedNamespaceUid.equals(binding.expectedNamespaceUid())
          && apiBindingRevision.equals(binding.configRevision())
          && apiBindingDigest.equals(binding.bindingDigest());
    }

    public String configRevision() {
      return configRevision;
    }

    public String environmentId() {
      return environmentId;
    }

    public String clusterId() {
      return clusterId;
    }

    public String namespace() {
      return namespace;
    }

    public String expectedClusterIncarnationUid() {
      return expectedClusterIncarnationUid;
    }

    public String expectedNamespaceUid() {
      return expectedNamespaceUid;
    }

    public String apiBindingRevision() {
      return apiBindingRevision;
    }

    public String apiBindingDigest() {
      return apiBindingDigest;
    }

    public String bindingDigest() {
      return bindingDigest;
    }

    public List<ValidatorExpectation> validators() {
      return validators;
    }
  }

  public static final class ValidatorExpectation {
    private final String validatorId;
    private final String deploymentName;
    private final String deploymentUid;
    private final Map<String, String> selector;
    private final int replicas;
    private final String containerName;
    private final String image;
    private final String jwksUri;
    private final int maxCacheAgeSeconds;
    private final List<ProfileExpectation> profiles;
    private final String canonicalRuntimeConfig;

    private ValidatorExpectation(
        String validatorId,
        String deploymentName,
        String deploymentUid,
        Map<String, String> selector,
        int replicas,
        String containerName,
        String image,
        String jwksUri,
        int maxCacheAgeSeconds,
        List<ProfileExpectation> profiles,
        String canonicalRuntimeConfig) {
      this.validatorId = validatorId;
      this.deploymentName = deploymentName;
      this.deploymentUid = deploymentUid;
      this.selector = Map.copyOf(selector);
      this.replicas = replicas;
      this.containerName = containerName;
      this.image = image;
      this.jwksUri = jwksUri;
      this.maxCacheAgeSeconds = maxCacheAgeSeconds;
      this.profiles = List.copyOf(profiles);
      this.canonicalRuntimeConfig = canonicalRuntimeConfig;
    }

    public String validatorId() {
      return validatorId;
    }

    public String deploymentName() {
      return deploymentName;
    }

    public String deploymentUid() {
      return deploymentUid;
    }

    public Map<String, String> selector() {
      return selector;
    }

    public int replicas() {
      return replicas;
    }

    public String containerName() {
      return containerName;
    }

    public String image() {
      return image;
    }

    public String jwksUri() {
      return jwksUri;
    }

    public int maxCacheAgeSeconds() {
      return maxCacheAgeSeconds;
    }

    public List<ProfileExpectation> profiles() {
      return profiles;
    }

    public String canonicalRuntimeConfig() {
      return canonicalRuntimeConfig;
    }
  }

  public record ProfileExpectation(String tokenProfile, String audience) {}
}
