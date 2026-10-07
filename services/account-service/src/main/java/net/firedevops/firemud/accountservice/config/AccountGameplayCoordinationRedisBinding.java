package net.firedevops.firemud.accountservice.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import java.io.ByteArrayInputStream;
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
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default-inactive, root-protected Account Coordination Redis endpoint and credential binding.
 *
 * <p>This is deliberately not a Spring component and has no environment-variable fallback. A future
 * explicit owner composition must call {@link #loadProtected()} and close the returned binding. The
 * protected files bind the intended endpoint, Account ACL identity, TLS peer name, trust anchor,
 * and separately protected password; they do not establish Redis cluster incarnation or deployed
 * ACL correctness.
 */
public final class AccountGameplayCoordinationRedisBinding implements AutoCloseable {
  public static final int MAX_BINDING_BYTES = 16 * 1024;
  public static final int MAX_CA_BYTES = 128 * 1024;
  public static final int MAX_CREDENTIAL_BYTES = 4 * 1024;
  public static final String REQUIRED_ACL_IDENTITY = "account_coord_app";
  public static final Path BINDING_ROOT = Path.of("/etc/firemud/account-coordination-redis");
  public static final Path BINDING_PATH = BINDING_ROOT.resolve("binding.json");
  public static final Path CA_PATH = BINDING_ROOT.resolve("coordination-ca.pem");
  public static final Path CREDENTIAL_ROOT = protectedCredentialRoot();
  public static final Path CREDENTIAL_PATH = CREDENTIAL_ROOT.resolve("acl-password");

  private static final String VERSION = "account-coordination-redis-binding/v1";
  private static final Set<String> BINDING_FIELDS =
      Set.of(
          "version",
          "enabled",
          "coordinationHost",
          "coordinationPort",
          "tlsServerName",
          "servingCaPath",
          "servingCaSha256",
          "aclUsername",
          "credentialPath",
          "bindingDigest");
  private static final Pattern DNS_NAME =
      Pattern.compile(
          "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PEM_CERTIFICATE_BUNDLE =
      Pattern.compile(
          "\\A(?:[\\t\\r\\n ]*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----[\\t\\r\\n ]*)+\\z");
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "Account's Redis ACL credential is read only from this fixed root-protected mount; configuration cannot redirect it or add a fallback.")
  private static Path protectedCredentialRoot() {
    return Path.of("/run/secrets/firemud/account-coordination-redis");
  }

  private final ParsedBinding parsed;
  private final FileIdentity bindingIdentity;
  private final FileIdentity caIdentity;
  private final FileIdentity credentialIdentity;
  private final TrustManagerFactory trustManagerFactory;
  private final char[] aclPassword;
  private final AtomicBoolean closed = new AtomicBoolean();

  private AccountGameplayCoordinationRedisBinding(Snapshot snapshot) {
    this.parsed = snapshot.parsed();
    this.bindingIdentity = snapshot.bindingIdentity();
    this.caIdentity = snapshot.caIdentity();
    this.credentialIdentity = snapshot.credentialIdentity();
    this.trustManagerFactory = snapshot.trustManagerFactory();
    this.aclPassword = snapshot.password().clone();
    Arrays.fill(snapshot.password(), '\0');
  }

  /** Reads only the fixed protected files. Missing or changed material rejects with no fallback. */
  public static AccountGameplayCoordinationRedisBinding loadProtected() {
    try {
      return new AccountGameplayCoordinationRedisBinding(readSnapshot());
    } catch (Exception rejected) {
      throw new BindingRejectedException();
    }
  }

  public String coordinationHost() {
    return parsed.coordinationHost();
  }

  public int coordinationPort() {
    return parsed.coordinationPort();
  }

  public String tlsServerName() {
    return parsed.tlsServerName();
  }

  public String aclUsername() {
    return parsed.aclUsername();
  }

  public String bindingDigest() {
    return parsed.bindingDigest();
  }

  /**
   * Rechecks all fixed protected sources and their file identities before a new network connection.
   * A changed, withdrawn, replaced, or unreadable source rejects rather than rotating an existing
   * process's authority implicitly.
   */
  public void requireUnchangedProtectedSources() {
    if (closed.get()) {
      throw new BindingRejectedException();
    }
    Snapshot current = null;
    try {
      current = readSnapshot();
      if (!parsed.equals(current.parsed())
          || !bindingIdentity.equals(current.bindingIdentity())
          || !caIdentity.equals(current.caIdentity())
          || !credentialIdentity.equals(current.credentialIdentity())) {
        throw new BindingRejectedException();
      }
    } catch (BindingRejectedException rejected) {
      throw rejected;
    } catch (Exception rejected) {
      throw new BindingRejectedException();
    } finally {
      if (current != null) Arrays.fill(current.password(), '\0');
    }
  }

  /** Builds the dedicated client without exposing its credential-bearing URI to callers. */
  public RedisClient createDedicatedRedisClient() {
    requireUnchangedProtectedSources();
    return buildClientConfiguration(
        parsed,
        aclPassword,
        trustManagerFactory,
        (endpoint, options) -> {
          RedisClient client = RedisClient.create(endpoint);
          try {
            client.setOptions(options);
            return client;
          } catch (RuntimeException rejected) {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            throw new BindingRejectedException();
          }
        });
  }

  static <T> T buildClientConfiguration(
      ParsedBinding parsed,
      char[] acceptedPassword,
      TrustManagerFactory trustManagerFactory,
      ClientConfigurationFactory<T> factory) {
    char[] password = acceptedPassword.clone();
    try {
      RedisURI endpoint =
          RedisURI.Builder.redis(parsed.coordinationHost(), parsed.coordinationPort())
              .withSsl(true)
              .withStartTls(false)
              .withVerifyPeer(true)
              .withAuthentication(parsed.aclUsername(), password)
              .withTimeout(COMMAND_TIMEOUT)
              .build();
      ClientOptions options =
          ClientOptions.builder()
              .autoReconnect(false)
              .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
              .timeoutOptions(TimeoutOptions.builder().fixedTimeout(COMMAND_TIMEOUT).build())
              .sslOptions(SslOptions.builder().trustManager(trustManagerFactory).build())
              .build();
      return factory.create(endpoint, options);
    } catch (RuntimeException rejected) {
      throw new BindingRejectedException();
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      Arrays.fill(aclPassword, '\0');
    }
  }

  @Override
  public String toString() {
    return "AccountGameplayCoordinationRedisBinding[protected, credentials redacted]";
  }

  static ParsedBinding parseProtectedBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BINDING_BYTES) {
      throw new IllegalArgumentException("Account Coordination Redis binding is unavailable");
    }
    try {
      JsonNode root = JSON.readTree(bytes);
      if (root == null || !root.isObject() || root.size() != BINDING_FIELDS.size()) {
        throw new IllegalArgumentException("Account Coordination Redis binding is malformed");
      }
      Map<String, JsonNode> fields = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> field : root.properties()) {
        if (!BINDING_FIELDS.contains(field.getKey())
            || fields.putIfAbsent(field.getKey(), field.getValue()) != null) {
          throw new IllegalArgumentException("Account Coordination Redis binding is malformed");
        }
      }
      if (!fields.keySet().equals(BINDING_FIELDS)) {
        throw new IllegalArgumentException("Account Coordination Redis binding is incomplete");
      }
      String version = requiredText(fields, "version", 64);
      JsonNode enabled = fields.get("enabled");
      String host = requiredText(fields, "coordinationHost", 253);
      JsonNode portNode = fields.get("coordinationPort");
      String tlsServerName = requiredText(fields, "tlsServerName", 253);
      String caPath = requiredText(fields, "servingCaPath", 512);
      String caSha256 = requiredText(fields, "servingCaSha256", 64);
      String username = requiredText(fields, "aclUsername", 64);
      String credentialPath = requiredText(fields, "credentialPath", 512);
      String digest = requiredText(fields, "bindingDigest", 64);
      if (!VERSION.equals(version)
          || enabled == null
          || !enabled.isBoolean()
          || !enabled.booleanValue()
          || !DNS_NAME.matcher(host).matches()
          || !host.equals(tlsServerName)
          || !DNS_NAME.matcher(tlsServerName).matches()
          || portNode == null
          || !portNode.isIntegralNumber()
          || !portNode.canConvertToInt()
          || portNode.intValue() < 1
          || portNode.intValue() > 65_535
          || !CA_PATH.toString().equals(caPath)
          || !SHA256.matcher(caSha256).matches()
          || !REQUIRED_ACL_IDENTITY.equals(username)
          || !CREDENTIAL_PATH.toString().equals(credentialPath)
          || !SHA256.matcher(digest).matches()) {
        throw new IllegalArgumentException("Account Coordination Redis binding is invalid");
      }
      int port = portNode.intValue();
      String expectedDigest =
          computeBindingDigest(
              host, port, tlsServerName, caPath, caSha256, username, credentialPath);
      if (!expectedDigest.equals(digest)) {
        throw new IllegalArgumentException("Account Coordination Redis binding digest is invalid");
      }
      return new ParsedBinding(
          host,
          port,
          tlsServerName,
          Path.of(caPath),
          caSha256,
          username,
          Path.of(credentialPath),
          digest);
    } catch (RuntimeException rejected) {
      throw rejected;
    } catch (Exception rejected) {
      throw new IllegalArgumentException("Account Coordination Redis binding is malformed");
    }
  }

  static String computeBindingDigest(
      String host,
      int port,
      String tlsServerName,
      String caPath,
      String caSha256,
      String username,
      String credentialPath) {
    try {
      Map<String, Object> binding = new LinkedHashMap<>();
      binding.put("version", VERSION);
      binding.put("enabled", true);
      binding.put("coordinationHost", host);
      binding.put("coordinationPort", port);
      binding.put("tlsServerName", tlsServerName);
      binding.put("servingCaPath", caPath);
      binding.put("servingCaSha256", caSha256);
      binding.put("aclUsername", username);
      binding.put("credentialPath", credentialPath);
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(binding));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (IOException | NoSuchAlgorithmException | RuntimeException rejected) {
      throw new IllegalArgumentException(
          "Account Coordination Redis binding digest is unavailable");
    }
  }

  private static Snapshot readSnapshot() throws Exception {
    ProtectedFile bindingFile =
        readProtectedFile(BINDING_PATH, BINDING_ROOT, MAX_BINDING_BYTES, false);
    ProtectedFile caFile = null;
    ProtectedFile credentialFile = null;
    char[] password = null;
    try {
      ParsedBinding parsed = parseProtectedBytes(bindingFile.bytes());
      caFile = readProtectedFile(CA_PATH, BINDING_ROOT, MAX_CA_BYTES, false);
      if (!sha256(caFile.bytes()).equals(parsed.servingCaSha256())) {
        throw new IOException("Account Coordination Redis CA changed");
      }
      credentialFile =
          readProtectedFile(CREDENTIAL_PATH, CREDENTIAL_ROOT, MAX_CREDENTIAL_BYTES, true);
      password = parsePassword(credentialFile.bytes());
      TrustManagerFactory trustManagerFactory = trustManagerFactory(caFile.bytes());
      return new Snapshot(
          parsed,
          bindingFile.identity(),
          caFile.identity(),
          credentialFile.identity(),
          trustManagerFactory,
          password);
    } catch (Exception rejected) {
      if (password != null) Arrays.fill(password, '\0');
      throw rejected;
    } finally {
      Arrays.fill(bindingFile.bytes(), (byte) 0);
      if (caFile != null) Arrays.fill(caFile.bytes(), (byte) 0);
      if (credentialFile != null) Arrays.fill(credentialFile.bytes(), (byte) 0);
    }
  }

  private static ProtectedFile readProtectedFile(
      Path path, Path expectedRoot, int maximumBytes, boolean credential) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path root = expectedRoot.toAbsolutePath().normalize();
    if (!absolute.equals(path)
        || !absolute.equals(root.resolve(path.getFileName()))
        || !root.equals(absolute.getParent())
        || Files.isSymbolicLink(absolute)) {
      throw new IOException("Protected Account Coordination Redis file is unavailable");
    }
    verifyProtectedDirectoryChain(root);
    BasicFileAttributes before =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    FileSecurity beforeSecurity = fileSecurity(absolute);
    if (!before.isRegularFile()
        || before.size() <= 0
        || before.size() > maximumBytes
        || before.fileKey() == null
        || !rootOwnedNonWritable(beforeSecurity)) {
      throw new IOException("Protected Account Coordination Redis file is invalid");
    }
    if (credential
        && (beforeSecurity.permissions().contains(PosixFilePermission.OTHERS_READ)
            || beforeSecurity.permissions().contains(PosixFilePermission.OWNER_EXECUTE)
            || beforeSecurity.permissions().contains(PosixFilePermission.GROUP_EXECUTE)
            || beforeSecurity.permissions().contains(PosixFilePermission.OTHERS_EXECUTE)
            || (!beforeSecurity.permissions().contains(PosixFilePermission.OWNER_READ)
                && !beforeSecurity.permissions().contains(PosixFilePermission.GROUP_READ))
            || !Files.isReadable(absolute))) {
      throw new IOException("Protected Account Coordination Redis credential is unreadable");
    }
    long links = unixLong(absolute, "unix:nlink");
    if (links != 1L) {
      throw new IOException("Protected Account Coordination Redis file has aliases");
    }
    byte[] bytes = new byte[(int) before.size()];
    boolean returned = false;
    try {
      try (SeekableByteChannel channel =
          Files.newByteChannel(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          if (channel.read(buffer) < 0) {
            throw new IOException(
                "Protected Account Coordination Redis file changed while reading");
          }
        }
        if (channel.read(ByteBuffer.allocate(1)) != -1) {
          throw new IOException("Protected Account Coordination Redis file changed while reading");
        }
      }
      BasicFileAttributes after =
          Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      FileSecurity afterSecurity = fileSecurity(absolute);
      if (!sameAttributes(before, after)
          || !beforeSecurity.equals(afterSecurity)
          || unixLong(absolute, "unix:nlink") != 1L
          || Files.isSymbolicLink(absolute)) {
        throw new IOException("Protected Account Coordination Redis file changed while reading");
      }
      ProtectedFile protectedFile =
          new ProtectedFile(bytes, identity(absolute, before, afterSecurity, bytes));
      returned = true;
      return protectedFile;
    } finally {
      if (!returned) Arrays.fill(bytes, (byte) 0);
    }
  }

  static byte[] readProtectedBytesForTest(
      Path path, Path expectedRoot, int maximumBytes, boolean credential) throws IOException {
    ProtectedFile file = readProtectedFile(path, expectedRoot, maximumBytes, credential);
    try {
      return file.bytes().clone();
    } finally {
      Arrays.fill(file.bytes(), (byte) 0);
    }
  }

  private static void verifyProtectedDirectoryChain(Path directory) throws IOException {
    Path normalized = directory.toAbsolutePath().normalize();
    Path canonical = normalized.toRealPath();
    if (!normalized.equals(canonical)) {
      throw new IOException("Protected Account Coordination Redis directory is aliased");
    }
    for (Path current = canonical; current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)) {
        throw new IOException("Protected Account Coordination Redis directory is aliased");
      }
      BasicFileAttributes attrs =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      FileSecurity security = fileSecurity(current);
      if (!attrs.isDirectory()
          || security.ownerUid() != 0L
          || security.permissions().contains(PosixFilePermission.GROUP_WRITE)
          || security.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
        throw new IOException("Protected Account Coordination Redis directory is unsafe");
      }
    }
  }

  private static boolean rootOwnedNonWritable(FileSecurity security) {
    return security.ownerUid() == 0L
        && !security.permissions().contains(PosixFilePermission.OWNER_WRITE)
        && !security.permissions().contains(PosixFilePermission.GROUP_WRITE)
        && !security.permissions().contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static FileSecurity fileSecurity(Path path) throws IOException {
    long ownerUid = unixLong(path, "unix:uid");
    long groupId = unixLong(path, "unix:gid");
    return new FileSecurity(
        ownerUid,
        groupId,
        Set.copyOf(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)));
  }

  private static long unixLong(Path path, String attribute) throws IOException {
    Object value = Files.getAttribute(path, attribute, LinkOption.NOFOLLOW_LINKS);
    if (!(value instanceof Number number)) {
      throw new IOException("Protected Account Coordination Redis metadata is unavailable");
    }
    return number.longValue();
  }

  private static FileIdentity identity(
      Path path, BasicFileAttributes attrs, FileSecurity security, byte[] bytes) {
    return new FileIdentity(
        path.toString(),
        String.valueOf(attrs.fileKey()),
        attrs.size(),
        attrs.lastModifiedTime().toMillis(),
        security.ownerUid(),
        security.groupId(),
        security.permissions(),
        sha256(bytes));
  }

  private static boolean sameAttributes(BasicFileAttributes before, BasicFileAttributes after) {
    return after.isRegularFile()
        && Objects.equals(before.fileKey(), after.fileKey())
        && before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime());
  }

  private static TrustManagerFactory trustManagerFactory(byte[] pemBytes) throws Exception {
    if (pemBytes.length == 0 || pemBytes.length > MAX_CA_BYTES) {
      throw new IOException("Account Coordination Redis CA is unavailable");
    }
    String pem = decodeUtf8(pemBytes);
    if (!PEM_CERTIFICATE_BUNDLE.matcher(pem).matches()) {
      throw new IOException("Account Coordination Redis CA bundle is malformed");
    }
    Collection<? extends Certificate> certificates =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(new ByteArrayInputStream(pemBytes));
    if (certificates.isEmpty()) {
      throw new IOException("Account Coordination Redis CA bundle is empty");
    }
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    int index = 0;
    for (Certificate certificate : certificates) {
      if (!(certificate instanceof X509Certificate x509) || x509.getBasicConstraints() < 0) {
        throw new IOException("Account Coordination Redis trust anchor is not a CA");
      }
      x509.checkValidity(java.util.Date.from(Instant.now()));
      trustStore.setCertificateEntry("coordination-ca-" + index++, x509);
    }
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    return factory;
  }

  static char[] parsePassword(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CREDENTIAL_BYTES) {
      throw new IOException("Account Coordination Redis credential is unavailable");
    }
    int length = bytes.length;
    if (bytes[length - 1] == '\n') length--;
    if (length < 32 || length > MAX_CREDENTIAL_BYTES) {
      throw new IOException("Account Coordination Redis credential is malformed");
    }
    char[] password = new char[length];
    for (int index = 0; index < length; index++) {
      int value = bytes[index] & 0xff;
      if (value < 0x21 || value > 0x7e) {
        Arrays.fill(password, '\0');
        throw new IOException("Account Coordination Redis credential is malformed");
      }
      password[index] = (char) value;
    }
    return password;
  }

  private static String requiredText(Map<String, JsonNode> fields, String name, int maxLength) {
    JsonNode value = fields.get(name);
    if (value == null
        || !value.isTextual()
        || value.asText().isEmpty()
        || value.asText().length() > maxLength) {
      throw new IllegalArgumentException("Account Coordination Redis binding is malformed");
    }
    return value.asText();
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  @FunctionalInterface
  interface ClientConfigurationFactory<T> {
    T create(RedisURI endpoint, ClientOptions options);
  }

  public record ParsedBinding(
      String coordinationHost,
      int coordinationPort,
      String tlsServerName,
      Path servingCaPath,
      String servingCaSha256,
      String aclUsername,
      Path credentialPath,
      String bindingDigest) {}

  public static final class BindingRejectedException extends RuntimeException {
    public BindingRejectedException() {
      super("Account Coordination Redis binding is unavailable or changed");
    }
  }

  private record ProtectedFile(byte[] bytes, FileIdentity identity) {}

  private record FileSecurity(long ownerUid, long groupId, Set<PosixFilePermission> permissions) {}

  private record FileIdentity(
      String path,
      String fileKey,
      long size,
      long modifiedMillis,
      long ownerUid,
      long groupId,
      Set<PosixFilePermission> permissions,
      String contentSha256) {}

  private record Snapshot(
      ParsedBinding parsed,
      FileIdentity bindingIdentity,
      FileIdentity caIdentity,
      FileIdentity credentialIdentity,
      TrustManagerFactory trustManagerFactory,
      char[] password) {}
}
