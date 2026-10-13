package net.firedevops.firemud.entitymanagement.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
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
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, exact run-owned grant for one immutable PRESEEDED actor-assignment intent. */
public final class RunOwnedPreseededActorAssignmentCapability {
  static final String CAPABILITY_SCHEMA = "firemud.run-owned-preseeded-actor-assignment.v3";
  static final String ACTION = "PRESEEDED_ACTOR_ASSIGNMENT";
  private static final String GAME_SESSION_SERVICE = "game-session-service";
  private static final String URI_PREFIX = "spiffe://firemud/ns/";
  private static final String URI_SUFFIX = "/sa/game-session-service";
  private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
  private static final String SAFE_ID_PATTERN = "[a-z0-9][a-z0-9-]{0,127}";
  private static final int MAX_CAPABILITY_BYTES = 16 * 1024;
  private static final int MAX_TRUST_ROOT_BYTES = 1024 * 1024;
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "runId",
          "composeProjectName",
          "action",
          "trustedGameSessionUriSan",
          "trustedGameSessionNamespace",
          "assignmentUuid",
          "canonicalAccountUuid",
          "expectedTarget",
          "corePayload",
          "intentDigest",
          "entityServerTrustRootSha256");
  private static final Set<String> TARGET_FIELDS =
      Set.of(
          "canonicalTenantUuid",
          "realmUuid",
          "worldSlug",
          "realmSlug",
          "gameInstanceId",
          "catalogRevision",
          "canonicalVersionUuid",
          "frozenPolicyDigest",
          "playableStateNamespaceUuid",
          "publishedReleaseBundleRef",
          "playableStateScope");
  private static final Set<String> CORE_FIELDS = Set.of("actorKind", "displayName");
  private static final ObjectMapper STRICT_JSON_MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final String runId;
  private final String composeProjectName;
  private final String trustedGameSessionUriSan;
  private final String trustedGameSessionNamespace;
  private final PreseededActorAssignmentRequest request;
  private final String intentDigest;
  private final String entityServerTrustRootSha256;

  private RunOwnedPreseededActorAssignmentCapability(
      String runId,
      String composeProjectName,
      String trustedGameSessionUriSan,
      String trustedGameSessionNamespace,
      PreseededActorAssignmentRequest request,
      String intentDigest,
      String entityServerTrustRootSha256) {
    this.runId = runId;
    this.composeProjectName = composeProjectName;
    this.trustedGameSessionUriSan = trustedGameSessionUriSan;
    this.trustedGameSessionNamespace = trustedGameSessionNamespace;
    this.request = request;
    this.intentDigest = intentDigest;
    this.entityServerTrustRootSha256 = entityServerTrustRootSha256;
  }

  static RunOwnedPreseededActorAssignmentCapability load(
      String capabilityPath,
      String expectedRunId,
      String expectedComposeProjectName,
      String expectedGameSessionNamespace,
      String expectedEntityServerTrustRootSha256) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    requireGameSessionNamespace(expectedGameSessionNamespace);
    if (capabilityPath == null || capabilityPath.isBlank()) {
      throw invalidCapability();
    }
    String expectedGameSessionUri = URI_PREFIX + expectedGameSessionNamespace + URI_SUFFIX;
    byte[] capabilityBytes;
    try {
      capabilityBytes = readCapability(Path.of(capabilityPath));
    } catch (RuntimeException exception) {
      throw invalidCapability();
    }
    return parseAndValidate(
        capabilityBytes,
        expectedRunId,
        expectedComposeProjectName,
        expectedGameSessionUri,
        expectedGameSessionNamespace,
        expectedEntityServerTrustRootSha256);
  }

  static RunOwnedPreseededActorAssignmentCapability parseAndValidate(
      byte[] capabilityBytes,
      String expectedRunId,
      String expectedComposeProjectName,
      String expectedGameSessionUri,
      String expectedGameSessionNamespace,
      String expectedEntityServerTrustRootSha256) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    requireGameSessionNamespace(expectedGameSessionNamespace);
    if (capabilityBytes == null
        || capabilityBytes.length == 0
        || capabilityBytes.length > MAX_CAPABILITY_BYTES
        || !isExpectedGameSessionUri(expectedGameSessionUri, expectedGameSessionNamespace)
        || expectedEntityServerTrustRootSha256 == null
        || !expectedEntityServerTrustRootSha256.matches(DIGEST_PATTERN)) {
      throw invalidCapability();
    }

    JsonNode root;
    try {
      root = STRICT_JSON_MAPPER.readTree(decodeUtf8(capabilityBytes));
    } catch (CharacterCodingException | JacksonException exception) {
      throw invalidCapability();
    }
    if (root == null || !root.isObject() || !hasExactFields(root, ROOT_FIELDS)) {
      throw invalidCapability();
    }

    String schema = requiredText(root, "schema");
    String runId = requiredText(root, "runId");
    String composeProjectName = requiredText(root, "composeProjectName");
    String action = requiredText(root, "action");
    String trustedUri = requiredText(root, "trustedGameSessionUriSan");
    String trustedNamespace = requiredText(root, "trustedGameSessionNamespace");
    UUID assignmentUuid = requiredCanonicalUuid(root, "assignmentUuid");
    UUID accountUuid = requiredCanonicalUuid(root, "canonicalAccountUuid");
    PreseededActorAssignmentExpectedTarget target = parseTarget(root.get("expectedTarget"));
    PreseededActorCorePayload corePayload = parseCorePayload(root.get("corePayload"));
    String intentDigest = requiredText(root, "intentDigest");
    String entityTrustRoot = requiredText(root, "entityServerTrustRootSha256");

    if (!CAPABILITY_SCHEMA.equals(schema)
        || !runId.equals(expectedRunId)
        || !composeProjectName.equals(expectedComposeProjectName)
        || !ACTION.equals(action)
        || !trustedUri.equals(expectedGameSessionUri)
        || !trustedNamespace.equals(expectedGameSessionNamespace)
        || !entityTrustRoot.equals(expectedEntityServerTrustRootSha256)
        || !intentDigest.matches(DIGEST_PATTERN)) {
      throw invalidCapability();
    }

    PreseededActorAssignmentRequest request =
        new PreseededActorAssignmentRequest(assignmentUuid, accountUuid, corePayload, target);
    if (!intentDigest.equals(PreseededActorAssignmentGrantIntentDigest.compute(request))) {
      throw invalidCapability();
    }
    return new RunOwnedPreseededActorAssignmentCapability(
        runId,
        composeProjectName,
        trustedUri,
        trustedNamespace,
        request,
        intentDigest,
        entityTrustRoot);
  }

  private static PreseededActorAssignmentExpectedTarget parseTarget(JsonNode value) {
    if (value == null || !value.isObject() || !hasExactFields(value, TARGET_FIELDS)) {
      throw invalidCapability();
    }
    PlayableStateScope scope;
    try {
      scope = PlayableStateScope.valueOf(requiredText(value, "playableStateScope"));
    } catch (IllegalArgumentException exception) {
      throw invalidCapability();
    }
    try {
      return new PreseededActorAssignmentExpectedTarget(
          requiredCanonicalUuid(value, "canonicalTenantUuid"),
          requiredCanonicalUuid(value, "realmUuid"),
          requiredText(value, "worldSlug"),
          requiredText(value, "realmSlug"),
          requiredText(value, "gameInstanceId"),
          requiredPositiveLong(value, "catalogRevision"),
          requiredCanonicalUuid(value, "canonicalVersionUuid"),
          requiredText(value, "frozenPolicyDigest"),
          requiredCanonicalUuid(value, "playableStateNamespaceUuid"),
          requiredText(value, "publishedReleaseBundleRef"),
          scope);
    } catch (IllegalArgumentException exception) {
      throw invalidCapability();
    }
  }

  private static PreseededActorCorePayload parseCorePayload(JsonNode value) {
    if (value == null || !value.isObject() || !hasExactFields(value, CORE_FIELDS)) {
      throw invalidCapability();
    }
    if (!"PLAYER".equals(requiredText(value, "actorKind"))) {
      throw invalidCapability();
    }
    try {
      return new PreseededActorCorePayload(
          PreseededActorCorePayload.ActorKind.PLAYER, requiredText(value, "displayName"));
    } catch (IllegalArgumentException exception) {
      throw invalidCapability();
    }
  }

  public static String configuredTrustRootFingerprint(String configuredPath) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw invalidCapability();
    }
    byte[] pemBytes = readConfiguredTrustRoot(configuredPath);
    try {
      Collection<? extends Certificate> certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(pemBytes));
      return fingerprintValidatedTrustRoot(certificates);
    } catch (java.security.cert.CertificateException exception) {
      throw invalidCapability();
    }
  }

  /**
   * Pins the exact CA currently installed in the active Entity gRPC server SSL bundle and rejects
   * stale, unsupported, or replaced source configuration before the grant is read.
   */
  public static String activeTrustRootFingerprint(
      SslBundles sslBundles, String activeServerBundleName, String configuredPath) {
    if (sslBundles == null || !"firemud-grpc".equals(activeServerBundleName)) {
      throw invalidCapability();
    }
    try {
      String configuredFingerprint = configuredTrustRootFingerprint(configuredPath);
      TrustManager[] trustManagers =
          sslBundles.getBundle(activeServerBundleName).getManagers().getTrustManagers();
      String activeFingerprint = activeTrustRootFingerprint(trustManagers, configuredFingerprint);
      if (!activeFingerprint.equals(configuredFingerprint)) {
        throw invalidCapability();
      }
      return activeFingerprint;
    } catch (RuntimeException exception) {
      throw invalidCapability();
    }
  }

  static String activeTrustRootFingerprint(
      TrustManager[] trustManagers, String configuredFingerprint) {
    if (configuredFingerprint == null || !configuredFingerprint.matches(DIGEST_PATTERN)) {
      throw invalidCapability();
    }
    if (trustManagers == null
        || trustManagers.length != 1
        || !(trustManagers[0] instanceof X509TrustManager trustManager)) {
      throw invalidCapability();
    }
    X509Certificate[] acceptedIssuers = trustManager.getAcceptedIssuers();
    if (acceptedIssuers == null || acceptedIssuers.length != 1) {
      throw invalidCapability();
    }
    String activeFingerprint = fingerprintValidatedTrustRoot(Arrays.asList(acceptedIssuers));
    if (!activeFingerprint.equals(configuredFingerprint)) {
      throw invalidCapability();
    }
    return activeFingerprint;
  }

  static String fingerprintValidatedTrustRoot(Collection<? extends Certificate> certificates) {
    try {
      List<X509Certificate> roots =
          certificates.stream()
              .filter(X509Certificate.class::isInstance)
              .map(X509Certificate.class::cast)
              .toList();
      if (certificates.size() != 1 || roots.size() != 1) {
        throw invalidCapability();
      }
      X509Certificate root = roots.getFirst();
      root.checkValidity();
      if (root.getBasicConstraints() < 0) {
        throw invalidCapability();
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(root.getEncoded()));
    } catch (java.security.cert.CertificateException | NoSuchAlgorithmException exception) {
      throw invalidCapability();
    }
  }

  private static byte[] readConfiguredTrustRoot(String configuredPath) {
    try {
      if (configuredPath.startsWith("classpath:")) {
        ClassPathResource resource =
            new ClassPathResource(configuredPath.substring("classpath:".length()));
        try (InputStream input = resource.getInputStream()) {
          byte[] bytes = input.readNBytes(MAX_TRUST_ROOT_BYTES + 1);
          if (bytes.length == 0 || bytes.length > MAX_TRUST_ROOT_BYTES) {
            throw invalidCapability();
          }
          return bytes;
        }
      }
      Path configuredFile =
          configuredPath.startsWith("file:")
              ? Path.of(java.net.URI.create(configuredPath))
              : Path.of(configuredPath);
      if (!configuredFile.isAbsolute()) {
        configuredFile = configuredFile.toAbsolutePath().normalize();
      }
      return readBoundedRegularFile(configuredFile, MAX_TRUST_ROOT_BYTES, false);
    } catch (IOException | RuntimeException exception) {
      throw invalidCapability();
    }
  }

  static byte[] readCapability(Path path) {
    return readBoundedRegularFile(path, MAX_CAPABILITY_BYTES, true);
  }

  private static byte[] readBoundedRegularFile(Path path, int maxBytes, boolean strictCapability) {
    if (path == null || !path.isAbsolute()) {
      throw invalidCapability();
    }
    Path normalized = path.normalize();
    Path parent = normalized.getParent();
    if (!normalized.equals(path) || parent == null) {
      throw invalidCapability();
    }
    try {
      requireNoSymlinkComponents(normalized);
      BasicFileAttributes parentBefore =
          Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes before =
          Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      long processUid = processUid();
      long ownerUid = unixUid(normalized);
      if (!parentBefore.isDirectory()
          || Files.isSymbolicLink(parent)
          || !before.isRegularFile()
          || Files.isSymbolicLink(normalized)
          || before.fileKey() == null
          || before.size() <= 0L
          || before.size() > maxBytes
          || (strictCapability && ownerUid != processUid)
          || !safeReadPermissions(normalized, strictCapability)
          || (strictCapability && !safeCapabilityDirectory(parent, processUid))) {
        throw invalidCapability();
      }

      ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) before.size());
      try (SeekableByteChannel channel =
              Files.newByteChannel(
                  normalized, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          InputStream input = Channels.newInputStream(channel)) {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read == 0) {
            continue;
          }
          if (bytes.size() + read > maxBytes) {
            throw invalidCapability();
          }
          bytes.write(buffer, 0, read);
        }
      }

      BasicFileAttributes after =
          Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes parentAfter =
          Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || Files.isSymbolicLink(normalized)
          || after.fileKey() == null
          || !before.fileKey().equals(after.fileKey())
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !sameFileKey(parentBefore.fileKey(), parentAfter.fileKey())
          || ownerUid != unixUid(normalized)
          || !safeReadPermissions(normalized, strictCapability)
          || (strictCapability && !safeCapabilityDirectory(parent, processUid))) {
        throw invalidCapability();
      }
      return bytes.toByteArray();
    } catch (IOException | SecurityException exception) {
      throw invalidCapability();
    }
  }

  private static void requireNoSymlinkComponents(Path path) throws IOException {
    for (Path component = path; component != null; component = component.getParent()) {
      if (Files.isSymbolicLink(component)) {
        throw invalidCapability();
      }
      BasicFileAttributes attributes =
          Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (component.equals(path) ? !attributes.isRegularFile() : !attributes.isDirectory()) {
        throw invalidCapability();
      }
    }
  }

  private static boolean safeReadPermissions(Path path, boolean strictCapability)
      throws IOException {
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    if (strictCapability) {
      return permissions.equals(
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ));
    }
    return permissions.contains(PosixFilePermission.OWNER_READ)
        && !permissions.contains(PosixFilePermission.OWNER_WRITE)
        && !permissions.contains(PosixFilePermission.GROUP_WRITE)
        && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static boolean safeCapabilityDirectory(Path path, long processUid) throws IOException {
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> privateDirectoryPermissions =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.OWNER_WRITE);
    Set<PosixFilePermission> readOnlyPrivateDirectoryPermissions =
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    return unixUid(path) == processUid
        && (permissions.equals(privateDirectoryPermissions)
            || permissions.equals(readOnlyPrivateDirectoryPermissions));
  }

  private static long unixUid(Path path) throws IOException {
    Object uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    if (!(uid instanceof Number number)) {
      throw new IOException("Owner identity is unavailable");
    }
    return number.longValue();
  }

  private static long processUid() throws IOException {
    Path processDirectory = Path.of("/proc", Long.toString(ProcessHandle.current().pid()));
    return unixUid(processDirectory);
  }

  private static boolean sameFileKey(Object before, Object after) {
    return before != null && before.equals(after);
  }

  private static boolean hasExactFields(JsonNode node, Set<String> expectedFields) {
    Set<String> actualFields = new HashSet<>();
    node.properties().forEach(property -> actualFields.add(property.getKey()));
    return actualFields.equals(expectedFields);
  }

  private static long requiredPositiveLong(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalidCapability();
    }
    long parsed = value.longValue();
    if (parsed <= 0L) {
      throw invalidCapability();
    }
    return parsed;
  }

  private static UUID requiredCanonicalUuid(JsonNode root, String field) {
    String value = requiredText(root, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)
          || (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L)) {
        throw invalidCapability();
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw invalidCapability();
    }
  }

  private static String requiredText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw invalidCapability();
    }
    String parsed = value.textValue();
    if (parsed.isBlank()
        || parsed.length() > 256
        || !parsed.equals(parsed.trim())
        || parsed.indexOf('\n') >= 0
        || parsed.indexOf('\r') >= 0) {
      throw invalidCapability();
    }
    return parsed;
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void requireExpectedRunBinding(String runId, String composeProjectName) {
    if (!isSafeId(runId) || !isSafeId(composeProjectName)) {
      throw invalidCapability();
    }
  }

  private static void requireGameSessionNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw invalidCapability();
    }
  }

  private static boolean isSafeId(String value) {
    return value != null && value.matches(SAFE_ID_PATTERN);
  }

  private static boolean isExpectedGameSessionUri(String uri, String namespace) {
    return uri != null
        && uri.equals(URI_PREFIX + namespace + URI_SUFFIX)
        && GrpcPeerIdentity.parseUri(uri)
            .filter(identity -> identity.isInNamespace(namespace))
            .filter(identity -> identity.isService(GAME_SESSION_SERVICE))
            .isPresent();
  }

  boolean matchesRequest(PreseededActorAssignmentRequest candidate) {
    return candidate != null
        && request.assignmentUuid().equals(candidate.assignmentUuid())
        && request.canonicalAccountUuid().equals(candidate.canonicalAccountUuid())
        && request.corePayload().equals(candidate.corePayload())
        && request.expectedTarget().equals(candidate.expectedTarget())
        && intentDigest.equals(PreseededActorAssignmentGrantIntentDigest.compute(candidate));
  }

  boolean matchesOwnerTarget(
      PreseededActorAssignmentRequest candidate,
      PreseededActorAssignmentOwnerEvidence ownerEvidence) {
    return matchesRequest(candidate)
        && ownerEvidence != null
        && candidate.assignmentUuid().equals(ownerEvidence.assignmentUuid())
        && candidate.canonicalAccountUuid().equals(ownerEvidence.canonicalAccountUuid())
        && candidate.expectedTarget().exactlyMatches(ownerEvidence)
        && intentDigest.equals(
            PreseededActorAssignmentIntentDigest.compute(candidate, ownerEvidence));
  }

  private static InvalidCapabilityException invalidCapability() {
    return new InvalidCapabilityException();
  }

  /** Generic content-free denial marker; callers must not log capability contents. */
  static final class InvalidCapabilityException extends IllegalArgumentException {
    private InvalidCapabilityException() {
      super("Run-owned PRESEEDED assignment capability is invalid");
    }
  }
}
