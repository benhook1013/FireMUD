package net.firedevops.firemud.accountjwtmaterializer;

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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the protected, read-only trust and endpoint snapshot for one custodian invocation. */
final class ProtectedBinding {
  static final Path DEFAULT_PATH = Path.of("/etc/firemud/account-jwt-materializer/binding.json");
  static final int MAX_BINDING_BYTES = 8 * 1024;
  private static final int MAX_CREDENTIAL_BYTES = 64 * 1024;
  private static final Pattern ENVIRONMENT =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern CLUSTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "enabled",
          "configRevision",
          "environmentId",
          "clusterId",
          "namespace",
          "expectedClusterIncarnationUid",
          "expectedNamespaceUid",
          "expectedKubeSystemNamespaceUid",
          "expectedServiceAccountName",
          "expectedServiceAccountUid",
          "expectedMaterializerSpiffeUri",
          "materializerSpkiSha256",
          "materializerSpkiSha256Pins",
          "accountTarget",
          "accountServerDnsName",
          "expectedAccountSpiffeUri",
          "accountServerSpkiSha256Pins",
          "accountCaPath",
          "clientCertificatePath",
          "clientPrivateKeyPath",
          "kubernetesApiServerUri",
          "kubernetesApiCaPath",
          "kubernetesTokenPath");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private ProtectedBinding() {}

  static Snapshot loadDefault() throws IOException {
    return load(DEFAULT_PATH);
  }

  static Snapshot load(Path bindingPath) throws IOException {
    byte[] bindingBytes = readProtectedFile(bindingPath, MAX_BINDING_BYTES);
    Binding binding = parse(bindingBytes);
    Map<Path, String> fileDigests = new LinkedHashMap<>();
    fileDigests.put(
        binding.accountCaPath(), digestFile(binding.accountCaPath(), MAX_CREDENTIAL_BYTES));
    fileDigests.put(
        binding.clientCertificatePath(),
        digestFile(binding.clientCertificatePath(), MAX_CREDENTIAL_BYTES));
    fileDigests.put(
        binding.clientPrivateKeyPath(),
        digestFile(binding.clientPrivateKeyPath(), MAX_CREDENTIAL_BYTES));
    fileDigests.put(
        binding.kubernetesApiCaPath(),
        digestFile(binding.kubernetesApiCaPath(), MAX_CREDENTIAL_BYTES));
    byte[] token = readProtectedFile(binding.kubernetesTokenPath(), 16 * 1024);
    try {
      validateServiceAccountToken(token, binding);
      fileDigests.put(binding.kubernetesTokenPath(), digest(token));
    } finally {
      java.util.Arrays.fill(token, (byte) 0);
    }
    validateCertificates(binding);
    byte[] bindingReadback = readProtectedFile(bindingPath, MAX_BINDING_BYTES);
    try {
      if (!MessageDigest.isEqual(bindingBytes, bindingReadback)) {
        throw denied();
      }
    } finally {
      java.util.Arrays.fill(bindingReadback, (byte) 0);
    }
    for (Map.Entry<Path, String> entry : fileDigests.entrySet()) {
      if (!entry.getValue().equals(digestFile(entry.getKey(), MAX_CREDENTIAL_BYTES))) {
        throw denied();
      }
    }
    Map<String, Object> snapshotFields = new LinkedHashMap<>();
    snapshotFields.put("binding", digest(bindingBytes));
    fileDigests.forEach((path, fileDigest) -> snapshotFields.put(path.toString(), fileDigest));
    return new Snapshot(binding, digest(canonicalJson(snapshotFields)));
  }

  static Binding parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BINDING_BYTES) {
      throw denied();
    }
    try {
      JsonNode root = JSON.readTree(bytes);
      requireExactObject(root, FIELDS);
      if (integer(root, "schemaVersion") != 1 || !bool(root, "enabled")) {
        throw denied();
      }
      String revision = text(root, "configRevision", 128);
      String environment = text(root, "environmentId", 63);
      String cluster = text(root, "clusterId", 128);
      String namespace = text(root, "namespace", 63);
      if (!REVISION.matcher(revision).matches()
          || !ENVIRONMENT.matcher(environment).matches()
          || !CLUSTER.matcher(cluster).matches()
          || !ENVIRONMENT.matcher(namespace).matches()) {
        throw denied();
      }
      String clusterUid = uuid(text(root, "expectedClusterIncarnationUid", 36));
      String namespaceUid = uuid(text(root, "expectedNamespaceUid", 36));
      String kubeSystemUid = uuid(text(root, "expectedKubeSystemNamespaceUid", 36));
      if (!clusterUid.equals(kubeSystemUid)) {
        throw denied();
      }
      String serviceAccount = text(root, "expectedServiceAccountName", 63);
      String serviceAccountUid = uuid(text(root, "expectedServiceAccountUid", 36));
      if (!"account-jwt-materializer".equals(serviceAccount)
          || !ENVIRONMENT.matcher(serviceAccount).matches()) {
        throw denied();
      }
      String materializerUri = text(root, "expectedMaterializerSpiffeUri", 256);
      String expectedMaterializerUri = "spiffe://firemud/ns/" + namespace + "/sa/" + serviceAccount;
      if (!expectedMaterializerUri.equals(materializerUri)) {
        throw denied();
      }
      String clientSpki = hex(text(root, "materializerSpkiSha256", 64));
      List<String> clientPins = pins(root.get("materializerSpkiSha256Pins"));
      if (!clientPins.contains(clientSpki)) {
        throw denied();
      }

      String accountTarget = text(root, "accountTarget", 256);
      String accountDns = text(root, "accountServerDnsName", 253);
      String accountUri = text(root, "expectedAccountSpiffeUri", 256);
      if (!accountTarget.matches("[A-Za-z0-9.-]+:[1-9][0-9]{0,4}")
          || !accountTarget.substring(0, accountTarget.lastIndexOf(':')).equals(accountDns)
          || Integer.parseInt(accountTarget.substring(accountTarget.lastIndexOf(':') + 1)) > 65535
          || accountDns.isBlank()
          || !accountUri.equals("spiffe://firemud/ns/" + namespace + "/sa/account-service")) {
        throw denied();
      }
      List<String> accountPins = pins(root.get("accountServerSpkiSha256Pins"));
      Path accountCa = absolutePath(text(root, "accountCaPath", 512));
      Path clientCertificate = absolutePath(text(root, "clientCertificatePath", 512));
      Path clientPrivateKey = absolutePath(text(root, "clientPrivateKeyPath", 512));
      URIValue apiUri = apiUri(text(root, "kubernetesApiServerUri", 512));
      Path apiCa = absolutePath(text(root, "kubernetesApiCaPath", 512));
      Path token = absolutePath(text(root, "kubernetesTokenPath", 512));
      String digest =
          computeAccountTrustDigest(
              revision,
              environment,
              cluster,
              namespace,
              clusterUid,
              namespaceUid,
              materializerUri,
              clientPins);
      if (digest.isBlank()) {
        throw denied();
      }
      return new Binding(
          revision,
          environment,
          cluster,
          namespace,
          clusterUid,
          namespaceUid,
          kubeSystemUid,
          serviceAccount,
          serviceAccountUid,
          materializerUri,
          clientSpki,
          clientPins,
          digest,
          accountTarget,
          accountDns,
          accountUri,
          accountPins,
          accountCa,
          clientCertificate,
          clientPrivateKey,
          apiUri.uri(),
          apiUri.authority(),
          apiCa,
          token);
    } catch (RuntimeException ex) {
      throw denied();
    }
  }

  private static String computeAccountTrustDigest(
      String revision,
      String environment,
      String cluster,
      String namespace,
      String clusterUid,
      String namespaceUid,
      String peerUri,
      List<String> pins) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("digestVersion", "account-jwt-signer-materializer-binding/v1");
    fields.put("configRevision", revision);
    fields.put("environmentId", environment);
    fields.put("clusterId", cluster);
    fields.put("namespace", namespace);
    fields.put("expectedClusterIncarnationUid", clusterUid);
    fields.put("expectedNamespaceUid", namespaceUid);
    fields.put("expectedPeerUri", peerUri);
    fields.put("peerSpkiSha256Pins", List.copyOf(pins));
    return digest(canonicalJson(fields));
  }

  private static void validateCertificates(Binding binding) throws IOException {
    byte[] clientBytes = readProtectedFile(binding.clientCertificatePath(), MAX_CREDENTIAL_BYTES);
    try {
      X509Certificate leaf = firstCertificate(clientBytes);
      if (!leaf.getPublicKey().getAlgorithm().equalsIgnoreCase("RSA")
          && !leaf.getPublicKey().getAlgorithm().equalsIgnoreCase("EC")) {
        throw denied();
      }
      if (!binding.materializerSpkiSha256().equals(digest(leaf.getPublicKey().getEncoded()))) {
        throw denied();
      }
      Set<String> uris = new LinkedHashSet<>();
      for (List<?> name :
          leaf.getSubjectAlternativeNames() == null
              ? List.<List<?>>of()
              : leaf.getSubjectAlternativeNames()) {
        if (name.size() >= 2
            && Integer.valueOf(6).equals(name.get(0))
            && name.get(1) instanceof String uri) {
          uris.add(uri);
        }
      }
      if (!uris.equals(Set.of(binding.expectedMaterializerSpiffeUri()))) {
        throw denied();
      }
    } catch (Exception ex) {
      throw denied();
    } finally {
      java.util.Arrays.fill(clientBytes, (byte) 0);
    }
    validateCaFile(binding.accountCaPath());
    validateCaFile(binding.kubernetesApiCaPath());
  }

  static X509Certificate firstCertificate(byte[] pem) throws Exception {
    var certificates =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(new java.io.ByteArrayInputStream(pem));
    return certificates.stream()
        .filter(X509Certificate.class::isInstance)
        .map(X509Certificate.class::cast)
        .findFirst()
        .orElseThrow(ProtectedBinding::denied);
  }

  private static void validateCaFile(Path path) throws IOException {
    byte[] bytes = readProtectedFile(path, MAX_CREDENTIAL_BYTES);
    try {
      var certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new java.io.ByteArrayInputStream(bytes));
      if (certificates.isEmpty()) {
        throw denied();
      }
    } catch (java.security.cert.CertificateException | RuntimeException ex) {
      throw denied();
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  private static void validateServiceAccountToken(byte[] tokenBytes, Binding binding) {
    try {
      String token = decodeUtf8(tokenBytes).trim();
      String[] segments = token.split("\\.", -1);
      if (segments.length != 3 || segments[1].isEmpty()) {
        throw denied();
      }
      byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
      try {
        JsonNode claims = JSON.readTree(payload);
        String expectedSubject =
            "system:serviceaccount:"
                + binding.namespace()
                + ":"
                + binding.expectedServiceAccountName();
        JsonNode kubernetes = claims.get("kubernetes.io");
        JsonNode serviceAccount = kubernetes == null ? null : kubernetes.get("serviceaccount");
        if (!expectedSubject.equals(text(claims, "sub", 256))
            || !binding.namespace().equals(text(kubernetes, "namespace", 63))
            || !binding.expectedServiceAccountName().equals(text(serviceAccount, "name", 63))
            || !binding.expectedServiceAccountUid().equals(text(serviceAccount, "uid", 36))) {
          throw denied();
        }
      } finally {
        java.util.Arrays.fill(payload, (byte) 0);
      }
    } catch (RuntimeException ex) {
      throw denied();
    }
  }

  static byte[] readProtectedFile(Path path, int maximumBytes) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    if (!absolute.equals(path) || Files.isSymbolicLink(absolute)) {
      throw denied();
    }
    BasicFileAttributes before =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() <= 0 || before.size() > maximumBytes) {
      throw denied();
    }
    requireRootOwnedReadOnly(absolute);
    byte[] bytes = new byte[(int) before.size()];
    try (SeekableByteChannel channel =
        Files.newByteChannel(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw denied();
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw denied();
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || !Objects.equals(before.fileKey(), after.fileKey())
        || before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
      java.util.Arrays.fill(bytes, (byte) 0);
      throw denied();
    }
    requireRootOwnedReadOnly(absolute);
    return bytes;
  }

  static byte[] readCredential(Path path) throws IOException {
    return readProtectedFile(path, MAX_CREDENTIAL_BYTES);
  }

  private static String digestFile(Path path, int maximumBytes) throws IOException {
    byte[] bytes = readProtectedFile(path, maximumBytes);
    try {
      return digest(bytes);
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  private static void requireRootOwnedReadOnly(Path file) throws IOException {
    Path parent = file.getParent();
    if (parent == null || Files.isSymbolicLink(file) || !parent.toRealPath().equals(parent)) {
      throw denied();
    }
    Object fileUid = Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Object parentUid =
        Files.getAttribute(parent.toRealPath(), "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> filePermissions =
        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> parentPermissions = Files.getPosixFilePermissions(parent.toRealPath());
    if (!(fileUid instanceof Number owner)
        || owner.longValue() != 0
        || !(parentUid instanceof Number parentOwner)
        || parentOwner.longValue() != 0
        || writable(filePermissions)
        || writable(parentPermissions)) {
      throw denied();
    }
  }

  private static boolean writable(Set<PosixFilePermission> permissions) {
    return permissions.contains(PosixFilePermission.OWNER_WRITE)
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static URIValue apiUri(String value) {
    try {
      java.net.URI uri = java.net.URI.create(value);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
          || uri.getPort() < 1
          || uri.getPort() > 65535) {
        throw denied();
      }
      return new URIValue(uri, uri.getRawAuthority());
    } catch (RuntimeException ex) {
      throw denied();
    }
  }

  private static Path absolutePath(String value) {
    Path path = Path.of(value);
    if (!path.isAbsolute() || !path.normalize().equals(path) || path.toString().contains("..")) {
      throw denied();
    }
    return path;
  }

  private static List<String> pins(JsonNode node) {
    if (node == null || !node.isArray() || node.size() == 0 || node.size() > 8) {
      throw denied();
    }
    List<String> pins = new ArrayList<>();
    for (JsonNode entry : node) {
      String pin = hex(entry.isTextual() ? entry.textValue() : "");
      if (pins.contains(pin)) {
        throw denied();
      }
      pins.add(pin);
    }
    return List.copyOf(pins);
  }

  private static String uuid(String value) {
    java.util.UUID uuid = java.util.UUID.fromString(value);
    if (!uuid.toString().equals(value) || uuid.equals(new java.util.UUID(0L, 0L))) {
      throw denied();
    }
    return value;
  }

  private static String hex(String value) {
    if (!SHA256.matcher(value).matches()) {
      throw denied();
    }
    return value;
  }

  private static void requireExactObject(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || node.size() != fields.size()) {
      throw denied();
    }
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if (!fields.contains(field.getKey())) {
        throw denied();
      }
    }
  }

  private static String text(JsonNode object, String name, int maxLength) {
    JsonNode value = object == null ? null : object.get(name);
    if (value == null || !value.isTextual()) {
      throw denied();
    }
    String result = value.textValue();
    if (result.isEmpty() || result.length() > maxLength || containsControl(result)) {
      throw denied();
    }
    return result;
  }

  private static int integer(JsonNode object, String name) {
    JsonNode value = object.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw denied();
    }
    return value.intValue();
  }

  private static boolean bool(JsonNode object, String name) {
    JsonNode value = object.get(name);
    if (value == null || !value.isBoolean()) {
      throw denied();
    }
    return value.booleanValue();
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(Character::isISOControl);
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
      throw denied();
    }
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static RuntimeException denied() {
    return new IllegalArgumentException("Protected materializer binding is unavailable");
  }

  record Binding(
      String configRevision,
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String expectedKubeSystemNamespaceUid,
      String expectedServiceAccountName,
      String expectedServiceAccountUid,
      String expectedMaterializerSpiffeUri,
      String materializerSpkiSha256,
      List<String> materializerSpkiSha256Pins,
      String accountTrustBindingDigest,
      String accountTarget,
      String accountServerDnsName,
      String expectedAccountSpiffeUri,
      List<String> accountServerSpkiSha256Pins,
      Path accountCaPath,
      Path clientCertificatePath,
      Path clientPrivateKeyPath,
      java.net.URI kubernetesApiServerUri,
      String kubernetesApiAuthority,
      Path kubernetesApiCaPath,
      Path kubernetesTokenPath) {
    Binding {
      materializerSpkiSha256Pins = List.copyOf(materializerSpkiSha256Pins);
      accountServerSpkiSha256Pins = List.copyOf(accountServerSpkiSha256Pins);
    }
  }

  record Snapshot(Binding binding, String digest) {
    Snapshot {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(digest);
    }

    void requireUnchanged(Path bindingPath) throws IOException {
      Snapshot current = load(bindingPath);
      if (!digest.equals(current.digest())) {
        throw denied();
      }
    }
  }

  private record URIValue(java.net.URI uri, String authority) {}
}
