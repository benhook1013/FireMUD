package net.firedevops.firemud.gamesession.data;

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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Strict, run-bound authority to activate the local initial-admission fixture coordinator. */
record RunOwnedInitialAdmissionFixtureCapability(
    String runId,
    String composeProjectName,
    String operationId,
    long tenantId,
    long gameTemplateId,
    long ownerAccountId,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProductionRealm,
    boolean requiresCharacterSelection,
    String stateScope,
    String characterCreationPolicy,
    String gameSessionLeafSha256,
    String gameSessionUriSan,
    String caCertificateSha256) {
  public static final String CAPABILITY_SCHEMA = "firemud.run-owned-initial-admission-fixture.v1";
  public static final String GAME_SESSION_URI_SAN =
      "spiffe://firemud/ns/dev/sa/game-session-service";
  public static final String WORLD_SLUG = "demo";
  public static final String WORLD_DISPLAY_NAME = "Demo World";
  public static final String REALM_SLUG = "production";
  public static final String REALM_DISPLAY_NAME = "Live Realm";
  public static final String STATE_SCOPE = "SHARED";
  public static final String CHARACTER_CREATION_POLICY = "ALLOW_NEW";

  private static final int MAX_CAPABILITY_BYTES = 16 * 1024;
  private static final int MAX_CERTIFICATE_FILE_BYTES = 1024 * 1024;
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> ALLOWED_FIELDS =
      Set.of(
          "schema",
          "runId",
          "composeProjectName",
          "operationId",
          "tenantId",
          "gameTemplateId",
          "ownerAccountId",
          "worldSlug",
          "worldDisplayName",
          "realmSlug",
          "realmDisplayName",
          "visible",
          "publicProductionRealm",
          "requiresCharacterSelection",
          "stateScope",
          "characterCreationPolicy",
          "gameSessionLeafSha256",
          "gameSessionUriSan",
          "caCertificateSha256");
  private static final ObjectMapper STRICT_JSON_MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  /**
   * Loads and validates the declared local capability against the active run/project and the actual
   * certificate files configured for this Game Session process.
   */
  static RunOwnedInitialAdmissionFixtureCapability load(
      Path capabilityPath,
      String expectedRunId,
      String expectedComposeProjectName,
      CommonGrpcClientProperties grpcProperties) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    byte[] capabilityBytes = readCapability(capabilityPath);
    CertificatePins certificatePins = configuredCertificatePins(grpcProperties);
    return parseAndValidate(
        capabilityBytes, expectedRunId, expectedComposeProjectName, certificatePins);
  }

  static RunOwnedInitialAdmissionFixtureCapability parseAndValidate(
      byte[] capabilityBytes,
      String expectedRunId,
      String expectedComposeProjectName,
      CertificatePins certificatePins) {
    requireExpectedRunBinding(expectedRunId, expectedComposeProjectName);
    if (capabilityBytes == null
        || capabilityBytes.length == 0
        || capabilityBytes.length > MAX_CAPABILITY_BYTES
        || certificatePins == null) {
      throw invalidCapability();
    }

    JsonNode root;
    try {
      String json = decodeUtf8(capabilityBytes);
      root = STRICT_JSON_MAPPER.readTree(json);
    } catch (CharacterCodingException | JacksonException exception) {
      throw invalidCapability();
    }
    if (root == null || !root.isObject() || !exactFields(root)) {
      throw invalidCapability();
    }

    String schema = requiredText(root, "schema");
    String runId = requiredText(root, "runId");
    String composeProjectName = requiredText(root, "composeProjectName");
    String operationId = requiredText(root, "operationId");
    long tenantId = requiredPositiveLong(root, "tenantId");
    long gameTemplateId = requiredPositiveLong(root, "gameTemplateId");
    long ownerAccountId = requiredPositiveLong(root, "ownerAccountId");
    String worldSlug = requiredText(root, "worldSlug");
    String worldDisplayName = requiredText(root, "worldDisplayName");
    String realmSlug = requiredText(root, "realmSlug");
    String realmDisplayName = requiredText(root, "realmDisplayName");
    JsonNode visible = root.get("visible");
    JsonNode publicProductionRealm = root.get("publicProductionRealm");
    JsonNode characterSelection = root.get("requiresCharacterSelection");
    String stateScope = requiredText(root, "stateScope");
    String characterCreationPolicy = requiredText(root, "characterCreationPolicy");
    String leafSha256 = requiredText(root, "gameSessionLeafSha256");
    String uriSan = requiredText(root, "gameSessionUriSan");
    String caSha256 = requiredText(root, "caCertificateSha256");

    if (!CAPABILITY_SCHEMA.equals(schema)
        || !runId.equals(expectedRunId)
        || !composeProjectName.equals(expectedComposeProjectName)
        || !SAFE_ID.matcher(runId).matches()
        || !SAFE_ID.matcher(composeProjectName).matches()
        || !isCanonicalUuid(operationId)
        || !WORLD_SLUG.equals(worldSlug)
        || !WORLD_DISPLAY_NAME.equals(worldDisplayName)
        || !REALM_SLUG.equals(realmSlug)
        || !REALM_DISPLAY_NAME.equals(realmDisplayName)
        || visible == null
        || !visible.isBoolean()
        || !visible.booleanValue()
        || publicProductionRealm == null
        || !publicProductionRealm.isBoolean()
        || !publicProductionRealm.booleanValue()
        || characterSelection == null
        || !characterSelection.isBoolean()
        || characterSelection.booleanValue()
        || !STATE_SCOPE.equals(stateScope)
        || !CHARACTER_CREATION_POLICY.equals(characterCreationPolicy)
        || !SHA_256.matcher(leafSha256).matches()
        || !GAME_SESSION_URI_SAN.equals(uriSan)
        || !SHA_256.matcher(caSha256).matches()
        || !certificatePins.leafSha256().equals(leafSha256)
        || !certificatePins.uriSan().equals(uriSan)
        || !certificatePins.caSha256().equals(caSha256)) {
      throw invalidCapability();
    }

    return new RunOwnedInitialAdmissionFixtureCapability(
        runId,
        composeProjectName,
        operationId,
        tenantId,
        gameTemplateId,
        ownerAccountId,
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        true,
        true,
        false,
        stateScope,
        characterCreationPolicy,
        leafSha256,
        uriSan,
        caSha256);
  }

  static CertificatePins certificatePins(X509Certificate leaf, X509Certificate ca) {
    if (leaf == null || ca == null || ca.getBasicConstraints() < 0) {
      throw invalidCapability();
    }
    String uriSan =
        GrpcPeerIdentity.fromCertificate(leaf)
            .map(GrpcPeerIdentity::uri)
            .filter(GAME_SESSION_URI_SAN::equals)
            .orElseThrow(RunOwnedInitialAdmissionFixtureCapability::invalidCapability);
    try {
      leaf.checkValidity();
      ca.checkValidity();
      leaf.verify(ca.getPublicKey());
      return new CertificatePins(fingerprint(leaf), uriSan, fingerprint(ca));
    } catch (GeneralSecurityException exception) {
      throw invalidCapability();
    }
  }

  private static CertificatePins configuredCertificatePins(
      CommonGrpcClientProperties grpcProperties) {
    if (grpcProperties == null
        || grpcProperties.isPlaintext()
        || !hasText(grpcProperties.getCertChain())
        || !hasText(grpcProperties.getCaCert())) {
      throw invalidCapability();
    }
    try {
      List<X509Certificate> chain =
          certificates(readConfiguredTlsFile(grpcProperties.getCertChain()));
      List<X509Certificate> authorities =
          certificates(readConfiguredTlsFile(grpcProperties.getCaCert()));
      if (chain.isEmpty() || authorities.size() != 1) {
        throw invalidCapability();
      }
      return certificatePins(chain.getFirst(), authorities.getFirst());
    } catch (IOException | RuntimeException exception) {
      throw invalidCapability();
    }
  }

  private static byte[] readConfiguredTlsFile(String configuredPath) throws IOException {
    byte[] bytes;
    if (configuredPath.startsWith("classpath:")) {
      ClassPathResource resource =
          new ClassPathResource(configuredPath.substring("classpath:".length()));
      try (InputStream input = resource.getInputStream()) {
        bytes = input.readNBytes(MAX_CERTIFICATE_FILE_BYTES + 1);
      }
    } else {
      try (InputStream input = Files.newInputStream(Path.of(configuredPath))) {
        bytes = input.readNBytes(MAX_CERTIFICATE_FILE_BYTES + 1);
      }
    }
    if (bytes.length == 0 || bytes.length > MAX_CERTIFICATE_FILE_BYTES) {
      throw new IOException("Configured TLS certificate material is not bounded");
    }
    return bytes;
  }

  private static List<X509Certificate> certificates(byte[] pemBytes) throws IOException {
    try {
      Collection<? extends Certificate> certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(pemBytes));
      return certificates.stream()
          .filter(X509Certificate.class::isInstance)
          .map(X509Certificate.class::cast)
          .toList();
    } catch (java.security.cert.CertificateException exception) {
      throw new IOException("Configured TLS certificate material could not be parsed", exception);
    }
  }

  private static String fingerprint(X509Certificate certificate) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException exception) {
      throw invalidCapability();
    }
  }

  static byte[] readCapability(Path path) {
    if (path == null || !path.isAbsolute()) {
      throw invalidCapability();
    }
    try {
      BasicFileAttributes before =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() <= 0L
          || before.size() > MAX_CAPABILITY_BYTES
          || !isReadOnly(path)) {
        throw invalidCapability();
      }

      ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) before.size());
      try (SeekableByteChannel channel =
              Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
          InputStream input = Channels.newInputStream(channel)) {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read == 0) {
            continue;
          }
          if (bytes.size() + read > MAX_CAPABILITY_BYTES) {
            throw invalidCapability();
          }
          bytes.write(buffer, 0, read);
        }
      }

      BasicFileAttributes after =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !sameFileKey(before.fileKey(), after.fileKey())
          || !isReadOnly(path)) {
        throw invalidCapability();
      }
      return bytes.toByteArray();
    } catch (IOException | SecurityException exception) {
      throw invalidCapability();
    }
  }

  private static boolean isReadOnly(Path path) throws IOException {
    try {
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      return permissions.contains(PosixFilePermission.OWNER_READ)
          && !permissions.contains(PosixFilePermission.OWNER_WRITE)
          && !permissions.contains(PosixFilePermission.GROUP_WRITE)
          && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
    } catch (UnsupportedOperationException exception) {
      Object readOnly = Files.getAttribute(path, "dos:readonly", LinkOption.NOFOLLOW_LINKS);
      return Boolean.TRUE.equals(readOnly) && !Files.isWritable(path);
    }
  }

  private static boolean exactFields(JsonNode root) {
    Set<String> fields = new HashSet<>();
    root.properties().forEach(property -> fields.add(property.getKey()));
    return fields.equals(ALLOWED_FIELDS);
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

  private static void requireExpectedRunBinding(String runId, String projectName) {
    if (runId == null
        || projectName == null
        || !SAFE_ID.matcher(runId).matches()
        || !SAFE_ID.matcher(projectName).matches()) {
      throw invalidCapability();
    }
  }

  private static boolean isCanonicalUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean sameFileKey(Object before, Object after) {
    return before == null || after == null || before.equals(after);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static InvalidCapabilityException invalidCapability() {
    return new InvalidCapabilityException();
  }

  @Override
  public String toString() {
    return "RunOwnedInitialAdmissionFixtureCapability[verified]";
  }

  record CertificatePins(String leafSha256, String uriSan, String caSha256) {}

  /** Generic, content-free failure so callers cannot accidentally log capability contents. */
  public static final class InvalidCapabilityException extends IllegalArgumentException {
    private InvalidCapabilityException() {
      super("Run-owned initial-admission fixture capability is invalid");
    }
  }
}
