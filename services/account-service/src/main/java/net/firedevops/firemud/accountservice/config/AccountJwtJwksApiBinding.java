package net.firedevops.firemud.accountservice.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Independently protected, default-inactive trust for Account's fixed public-JWKS Kubernetes
 * ConfigMap calls. This is deliberately not a Spring component: lifecycle wiring must opt in.
 */
public final class AccountJwtJwksApiBinding {
  public static final int MAX_BINDING_BYTES = 16 * 1024;
  private static final int MAX_CA_BYTES = 128 * 1024;
  private static final int MAX_BEARER_BYTES = 16 * 1024;
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int MAX_REQUEST_BYTES = 1024 * 1024;
  private static final String BINDING_VERSION = "account-jwt-jwks-api-binding/v1";
  private static final String ACCOUNT_SERVICE_ACCOUNT = "account-service";
  private static final Path TRUST_ROOT = Path.of("/etc/firemud/account-jwt-api");
  private static final Path TRUST_BINDING_PATH = TRUST_ROOT.resolve("binding.json");
  private static final Path TRUST_CA_PATH = TRUST_ROOT.resolve("serving-ca.pem");
  private static final Path TOKEN_ROOT = Path.of("/var/run/secrets/firemud/account-jwt-api-token");
  private static final Path TOKEN_PATH = TOKEN_ROOT.resolve("token");
  private static final Path CANONICAL_TOKEN_ROOT =
      Path.of("/run/secrets/firemud/account-jwt-api-token");
  private static final Pattern ENVIRONMENT =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern NAMESPACE = ENVIRONMENT;
  private static final Pattern CLUSTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern BEARER = Pattern.compile("[!-~]{1,16384}");
  private static final Set<String> CONFIG_FIELDS =
      Set.of(
          "version",
          "enabled",
          "configRevision",
          "environmentId",
          "clusterId",
          "apiServerUrl",
          "tlsServerName",
          "servingCaPath",
          "servingCaSha256",
          "bearerTokenPath",
          "namespace",
          "expectedClusterIncarnationUid",
          "expectedNamespaceUid",
          "expectedApiUsername",
          "bindingDigest");
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final boolean enabled;
  private final String protectedBindingPath;
  private final Duration requestTimeout;
  private final ProtectedState testProtectedState;
  private final ProtectedPaths protectedPaths;
  private final AtomicReference<TrustIdentity> acceptedIdentity = new AtomicReference<>();

  /** Creates an inert binding. No Spring activation or ambient Kubernetes configuration exists. */
  public AccountJwtJwksApiBinding() {
    this(false, "");
  }

  /**
   * Creates an explicitly selected binding. The selected file still has to authorize the exact
   * Account endpoint, trust anchor, cluster/namespace incarnation, and authenticated principal.
   */
  public AccountJwtJwksApiBinding(boolean enabled, String protectedBindingPath) {
    this.enabled = enabled;
    String configuredPath = protectedBindingPath == null ? "" : protectedBindingPath.trim();
    this.protectedBindingPath =
        TRUST_BINDING_PATH.toString().equals(configuredPath) ? configuredPath : "";
    this.requestTimeout = Duration.ofSeconds(8);
    this.testProtectedState = null;
    this.protectedPaths =
        new ProtectedPaths(
            TRUST_ROOT, TRUST_BINDING_PATH, TRUST_CA_PATH, TOKEN_ROOT, TOKEN_PATH, false);
  }

  private AccountJwtJwksApiBinding(Duration requestTimeout, ProtectedState testProtectedState) {
    this.enabled = true;
    this.protectedBindingPath = "<test-only-trusted-binding>";
    this.requestTimeout = requestTimeout;
    this.testProtectedState = testProtectedState;
    this.protectedPaths = null;
  }

  private AccountJwtJwksApiBinding(String bindingPath, ProtectedPaths protectedPaths) {
    this.enabled = true;
    this.protectedBindingPath = bindingPath;
    this.requestTimeout = Duration.ofSeconds(8);
    this.testProtectedState = null;
    this.protectedPaths = protectedPaths;
  }

  static AccountJwtJwksApiBinding forProtectedFilesTest(
      Path bindingPath, Path trustRoot, Path caPath, Path tokenRoot, Path tokenPath) {
    Objects.requireNonNull(bindingPath, "test binding path is required");
    Objects.requireNonNull(trustRoot, "test trust root is required");
    Objects.requireNonNull(caPath, "test CA path is required");
    Objects.requireNonNull(tokenRoot, "test token root is required");
    Objects.requireNonNull(tokenPath, "test token path is required");
    Path normalizedBinding = bindingPath.toAbsolutePath().normalize();
    Path normalizedTrustRoot = trustRoot.toAbsolutePath().normalize();
    Path normalizedCa = caPath.toAbsolutePath().normalize();
    Path normalizedTokenRoot = tokenRoot.toAbsolutePath().normalize();
    Path normalizedToken = tokenPath.toAbsolutePath().normalize();
    if (!normalizedBinding.equals(normalizedTrustRoot.resolve("binding.json"))
        || !normalizedCa.equals(normalizedTrustRoot.resolve("serving-ca.pem"))
        || !normalizedToken.equals(normalizedTokenRoot.resolve("token"))) {
      throw new IllegalArgumentException("Protected Account JWT test paths are invalid");
    }
    ProtectedPaths paths =
        new ProtectedPaths(
            normalizedTrustRoot,
            normalizedBinding,
            normalizedCa,
            normalizedTokenRoot,
            normalizedToken,
            true);
    return new AccountJwtJwksApiBinding(normalizedBinding.toString(), paths);
  }

  /**
   * Supplies trusted in-memory binding material only to package-local transport tests. It still
   * exercises the production pinned-CA HTTPS sender and cannot install a custom HTTP client.
   */
  static AccountJwtJwksApiBinding forTransportTest(
      ParsedBinding binding, byte[] caBytes, String bearerToken, Duration requestTimeout) {
    Objects.requireNonNull(binding, "transport test binding is required");
    Objects.requireNonNull(caBytes, "transport test CA is required");
    Objects.requireNonNull(requestTimeout, "transport test timeout is required");
    if (caBytes.length == 0
        || caBytes.length > MAX_CA_BYTES
        || !sha256(caBytes).equals(binding.servingCaSha256())
        || !"https".equalsIgnoreCase(binding.apiServer().getScheme())
        || binding.apiServer().getHost() == null
        || !binding.apiServer().getHost().equalsIgnoreCase(binding.tlsServerName())
        || !binding
            .expectedApiUsername()
            .equals("system:serviceaccount:" + binding.namespace() + ":" + ACCOUNT_SERVICE_ACCOUNT)
        || bearerToken == null
        || !BEARER.matcher(bearerToken).matches()
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofSeconds(8)) > 0) {
      throw new IllegalArgumentException("Account JWT JWKS transport test binding is invalid");
    }
    byte[] caSnapshot = caBytes.clone();
    Set<PosixFilePermission> readOnly = Set.of(PosixFilePermission.OWNER_READ);
    FileIdentity bindingIdentity = testFileIdentity("test-binding", new byte[] {1}, readOnly);
    FileIdentity caIdentity = testFileIdentity("test-ca", caSnapshot, readOnly);
    FileIdentity bearerIdentity =
        testFileIdentity(
            binding.bearerTokenPath().toString(),
            bearerToken.getBytes(StandardCharsets.UTF_8),
            readOnly);
    TrustIdentity identity =
        new TrustIdentity(
            bindingIdentity,
            caIdentity,
            credentialPolicy(bearerIdentity),
            binding.bindingDigest(),
            "<transport-test-root>");
    return new AccountJwtJwksApiBinding(
        requestTimeout,
        new ProtectedState(binding, caSnapshot, bearerToken, identity, bearerIdentity));
  }

  /** Returns non-secret pinned endpoint and identity fields after rechecking protected files. */
  public ParsedBinding current() {
    if (!enabled || protectedBindingPath.isBlank()) {
      throw new BindingRejectedException();
    }
    try {
      return acceptCurrentProtectedState().config();
    } catch (BindingRejectedException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BindingRejectedException();
    }
  }

  /** Opens one fixed-endpoint operation with a single bounded bearer snapshot. */
  public ApiOperation beginOperation() {
    if (!enabled || protectedBindingPath.isBlank()) {
      throw new BindingRejectedException();
    }
    try {
      return new ApiOperation(acceptCurrentProtectedState());
    } catch (BindingRejectedException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BindingRejectedException();
    }
  }

  private ApiResponse send(ProtectedState credentialSnapshot, ApiCall call, byte[] requestBody) {
    return send(credentialSnapshot, call, requestBody, null);
  }

  private ApiResponse send(
      ProtectedState credentialSnapshot,
      ApiCall call,
      byte[] requestBody,
      String protectedInventoryPath) {
    Objects.requireNonNull(call, "Kubernetes API call is required");
    if (!enabled || protectedBindingPath.isBlank() || credentialSnapshot == null) {
      throw new BindingRejectedException();
    }
    if (requestBody != null && requestBody.length > MAX_REQUEST_BYTES) {
      throw new BindingRejectedException();
    }
    validateCallBody(call, requestBody);
    ProtectedState state = recheckProtectedState();
    TrustIdentity baseline = acceptedIdentity.get();
    if (!baseline.equals(credentialSnapshot.identity())
        || !credentialSnapshot.bearerIdentity().equals(state.bearerIdentity())) {
      throw new BindingRejectedException();
    }

    HttpClient client;
    try {
      client = createHttpClient(credentialSnapshot.caBytes());
    } catch (Exception ex) {
      throw new BindingRejectedException();
    }
    if ((call == ApiCall.READ_VALIDATOR_DEPLOYMENT
            || call == ApiCall.LIST_VALIDATOR_PODS
            || call == ApiCall.READ_VALIDATOR_REPLICA_SET)
        != (protectedInventoryPath != null)) {
      throw new BindingRejectedException();
    }
    URI uri =
        credentialSnapshot
            .config()
            .apiServer()
            .resolve(
                protectedInventoryPath == null
                    ? call.path(credentialSnapshot.config().namespace())
                    : protectedInventoryPath);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + credentialSnapshot.bearerToken());
    if (call == ApiCall.PATCH_JWKS_CONFIG_MAP) {
      request.header("Content-Type", "application/merge-patch+json");
    } else if (call == ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL) {
      request.header("Content-Type", "application/json");
    }
    if (requestBody == null) {
      request.method(call.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      request.method(call.method(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
    }

    // Catch changed or withdrawn trust after client construction and immediately before send.
    ProtectedState beforeSend = recheckProtectedState();
    if (!baseline.equals(beforeSend.identity())
        || !credentialSnapshot.bearerIdentity().equals(beforeSend.bearerIdentity())) {
      throw new BindingRejectedException();
    }
    long responseDeadlineNanos = System.nanoTime() + requestTimeout.toNanos();
    try {
      HttpResponse<java.io.InputStream> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (java.io.InputStream body = response.body()) {
        ExecutorService bodyReader =
            Executors.newSingleThreadExecutor(
                task -> {
                  Thread thread = new Thread(task, "account-jwks-api-response-reader");
                  thread.setDaemon(true);
                  return thread;
                });
        Future<byte[]> bodyFuture = bodyReader.submit(() -> readBounded(body, MAX_RESPONSE_BYTES));
        try {
          long remainingNanos = responseDeadlineNanos - System.nanoTime();
          if (remainingNanos <= 0L) {
            throw new TimeoutException();
          }
          return new ApiResponse(
              response.statusCode(), bodyFuture.get(remainingNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException ex) {
          bodyFuture.cancel(true);
          throw new IOException("Kubernetes API response exceeded its deadline");
        } finally {
          bodyReader.shutdownNow();
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ApiTransportException();
    } catch (Exception ex) {
      throw new ApiTransportException();
    }
  }

  private ProtectedState recheckProtectedState() {
    try {
      return acceptCurrentProtectedState();
    } catch (BindingRejectedException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BindingRejectedException();
    }
  }

  /** Strict parser is exposed for deterministic format tests, not as an activation path. */
  public static ParsedBinding parseProtectedBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BINDING_BYTES) {
      throw new IllegalArgumentException("Account JWT JWKS API binding is unavailable");
    }
    try {
      String text = decodeUtf8(bytes);
      JsonNode root = STRICT_JSON.readTree(text);
      if (root == null || !root.isObject() || root.size() != CONFIG_FIELDS.size()) {
        throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
      }
      Map<String, JsonNode> supplied = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> field : root.properties()) {
        if (!CONFIG_FIELDS.contains(field.getKey())
            || supplied.putIfAbsent(field.getKey(), field.getValue()) != null) {
          throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
        }
      }
      if (!supplied.keySet().equals(CONFIG_FIELDS)
          || !"true".equals(requiredText(root, "enabled", 5))
          || !BINDING_VERSION.equals(requiredText(root, "version", 80))) {
        throw new IllegalArgumentException("Account JWT JWKS API binding is disabled");
      }

      String revision = requiredText(root, "configRevision", 128);
      String environmentId = requiredText(root, "environmentId", 63);
      String clusterId = requiredText(root, "clusterId", 128);
      String namespace = requiredText(root, "namespace", 63);
      String clusterUid = canonicalUid(requiredText(root, "expectedClusterIncarnationUid", 36));
      String namespaceUid = canonicalUid(requiredText(root, "expectedNamespaceUid", 36));
      String apiUsername = requiredText(root, "expectedApiUsername", 256);
      if (!REVISION.matcher(revision).matches()
          || !ENVIRONMENT.matcher(environmentId).matches()
          || !CLUSTER.matcher(clusterId).matches()
          || !NAMESPACE.matcher(namespace).matches()
          || !apiUsername.equals(
              "system:serviceaccount:" + namespace + ":" + ACCOUNT_SERVICE_ACCOUNT)) {
        throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
      }

      URI apiServer = parseApiServer(requiredText(root, "apiServerUrl", 512));
      String tlsServerName = requiredText(root, "tlsServerName", 253);
      String asciiTlsName = canonicalDnsName(tlsServerName);
      if (!asciiTlsName.equalsIgnoreCase(apiServer.getHost())) {
        throw new IllegalArgumentException("Account JWT JWKS API TLS name is not pinned");
      }
      String caPath = requiredText(root, "servingCaPath", 1024);
      String caSha256 = requiredText(root, "servingCaSha256", 64);
      String tokenPath = requiredText(root, "bearerTokenPath", 1024);
      if (!SHA256.matcher(caSha256).matches()) {
        throw new IllegalArgumentException("Account JWT JWKS API CA pin is malformed");
      }
      Path servingCaPath = absoluteProtectedPath(caPath);
      Path bearerTokenPath = absoluteProtectedPath(tokenPath);
      String suppliedDigest = requiredText(root, "bindingDigest", 64);
      if (!SHA256.matcher(suppliedDigest).matches()
          || !suppliedDigest.equals(computeBindingDigest(root))) {
        throw new IllegalArgumentException("Account JWT JWKS API binding digest is invalid");
      }
      return new ParsedBinding(
          revision,
          environmentId,
          clusterId,
          apiServer,
          asciiTlsName,
          servingCaPath,
          caSha256,
          bearerTokenPath,
          namespace,
          clusterUid,
          namespaceUid,
          apiUsername,
          suppliedDigest);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
    }
  }

  public static String computeBindingDigest(JsonNode protectedBinding) {
    if (protectedBinding == null || !protectedBinding.isObject()) {
      throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
    }
    try {
      Map<String, Object> content = new LinkedHashMap<>();
      for (String field : CONFIG_FIELDS) {
        if (!"bindingDigest".equals(field)) {
          JsonNode value = protectedBinding.get(field);
          if (value == null || !value.isValueNode()) {
            throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
          }
          content.put(field, value.asText());
        }
      }
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(STRICT_JSON.writeValueAsString(content));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Account JWT JWKS API binding digest is unavailable");
    }
  }

  private ProtectedState readCurrentProtectedState() throws Exception {
    if (testProtectedState != null) {
      return testProtectedState;
    }
    Path bindingPath = absoluteProtectedPath(protectedBindingPath);
    if (protectedPaths == null
        || !bindingPath.equals(protectedPaths.bindingPath())
        || (!protectedPaths.testOnly()
            && (!bindingPath.equals(TRUST_BINDING_PATH)
                || !protectedPaths.trustRoot().equals(TRUST_ROOT)
                || !protectedPaths.caPath().equals(TRUST_CA_PATH)
                || !protectedPaths.tokenRoot().equals(TOKEN_ROOT)
                || !protectedPaths.tokenPath().equals(TOKEN_PATH)))) {
      throw new BindingRejectedException();
    }
    Path canonicalTokenRoot = resolveTrustedTokenRoot(protectedPaths);
    if (!protectedPaths.testOnly()) {
      verifyProtectedDirectoryChain(TRUST_ROOT);
      verifyProtectedDirectoryChain(canonicalTokenRoot);
    }
    ProtectedFile bindingFile = readProtectedFile(bindingPath, MAX_BINDING_BYTES);
    ParsedBinding config = parseProtectedBytes(bindingFile.bytes());
    if (!config.servingCaPath().equals(protectedPaths.caPath())
        || !config.bearerTokenPath().equals(protectedPaths.tokenPath())) {
      throw new BindingRejectedException();
    }
    ProtectedFile caFile = readProtectedFile(config.servingCaPath(), MAX_CA_BYTES);
    ProtectedFile tokenFile =
        readBearerProtectedFile(config.bearerTokenPath(), protectedPaths.tokenRoot());
    String caDigest = sha256(caFile.bytes());
    if (!caDigest.equals(config.servingCaSha256())) {
      throw new BindingRejectedException();
    }
    String token = decodeUtf8(tokenFile.bytes());
    if (!BEARER.matcher(token).matches()) {
      throw new BindingRejectedException();
    }
    TrustIdentity identity =
        new TrustIdentity(
            bindingFile.identity(),
            caFile.identity(),
            credentialPolicy(tokenFile.identity()),
            config.bindingDigest(),
            canonicalTokenRoot.toString());
    return new ProtectedState(config, caFile.bytes(), token, identity, tokenFile.identity());
  }

  private static CredentialPolicyIdentity credentialPolicy(FileIdentity identity) {
    return new CredentialPolicyIdentity(
        identity.path(), identity.ownerUid(), identity.groupId(), identity.permissions());
  }

  private ProtectedState acceptCurrentProtectedState() throws Exception {
    ProtectedState state = readCurrentProtectedState();
    TrustIdentity baseline = acceptedIdentity.get();
    if (baseline == null) {
      acceptedIdentity.compareAndSet(null, state.identity());
      baseline = acceptedIdentity.get();
    }
    if (baseline == null || !baseline.equals(state.identity())) {
      throw new BindingRejectedException();
    }
    return state;
  }

  private static HttpClient createHttpClient(byte[] caBytes) throws Exception {
    CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
    List<? extends Certificate> certificates =
        List.copyOf(certificateFactory.generateCertificates(new ByteArrayInputStream(caBytes)));
    if (certificates.isEmpty() || certificates.size() > 16) {
      throw new BindingRejectedException();
    }
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    int index = 0;
    for (Certificate certificate : certificates) {
      if (!(certificate instanceof X509Certificate x509)) {
        throw new BindingRejectedException();
      }
      x509.checkValidity();
      if (x509.getBasicConstraints() < 0) {
        throw new BindingRejectedException();
      }
      trustStore.setCertificateEntry("account-jwks-ca-" + index++, x509);
    }
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustManagers.getTrustManagers(), null);
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(sslContext)
        .sslParameters(sslParameters)
        .proxy(DIRECT_ONLY_PROXY)
        .build();
  }

  private static final ProxySelector DIRECT_ONLY_PROXY =
      new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
          return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
          // No proxy exists; connection failures are handled by the bounded API call.
        }
      };

  private static byte[] readBounded(java.io.InputStream input, int maximumBytes)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
    while (true) {
      int count = input.read(buffer);
      if (count < 0) {
        return output.toByteArray();
      }
      total += count;
      if (total > maximumBytes) {
        throw new IOException("Kubernetes API response exceeded its bound");
      }
      output.write(buffer, 0, count);
    }
  }

  private static void validateCallBody(ApiCall call, byte[] body) {
    if (call == ApiCall.PATCH_JWKS_CONFIG_MAP) {
      if (body == null || body.length == 0) {
        throw new BindingRejectedException();
      }
      try {
        JsonNode patch = STRICT_JSON.readTree(decodeUtf8(body));
        JsonNode metadata = patch == null ? null : patch.get("metadata");
        JsonNode data = patch == null ? null : patch.get("data");
        if (patch == null
            || !patch.isObject()
            || patch.size() != 2
            || metadata == null
            || !metadata.isObject()
            || metadata.size() != 2
            || !validUid(text(metadata.get("uid")))
            || !validResourceVersion(text(metadata.get("resourceVersion")))
            || data == null
            || !data.isObject()
            || data.size() != 2
            || !data.has("jwks.json")
            || !data.has("jwt-generation.json")
            || !data.get("jwks.json").isTextual()
            || !data.get("jwt-generation.json").isTextual()
            || data.get("jwks.json").asText().isBlank()
            || data.get("jwt-generation.json").asText().isBlank()) {
          throw new BindingRejectedException();
        }
        JsonNode jwks = STRICT_JSON.readTree(data.get("jwks.json").asText());
        JsonNode keys = jwks == null ? null : jwks.get("keys");
        if (jwks == null
            || !jwks.isObject()
            || jwks.size() != 1
            || keys == null
            || !keys.isArray()
            || keys.isEmpty()) {
          throw new BindingRejectedException();
        }
        for (JsonNode key : keys) {
          if (!key.isObject()
              || key.has("d")
              || key.has("p")
              || key.has("q")
              || key.has("dp")
              || key.has("dq")
              || key.has("qi")
              || key.has("oth")
              || key.has("k")) {
            throw new BindingRejectedException();
          }
        }
      } catch (BindingRejectedException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new BindingRejectedException();
      }
      return;
    }
    if (call == ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL) {
      String expected =
          "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\",\"spec\":{}}";
      if (body == null || !expected.equals(decodeUtf8(body))) {
        throw new BindingRejectedException();
      }
      return;
    }
    if (body != null) {
      throw new BindingRejectedException();
    }
  }

  private static ProtectedFile readProtectedFile(Path path, int maximumBytes) throws IOException {
    return readProtectedFile(path, maximumBytes, false);
  }

  private static ProtectedFile readBearerProtectedFile(Path configuredPath, Path trustedTokenRoot)
      throws IOException {
    Path absolute = configuredPath.toAbsolutePath().normalize();
    Path tokenRoot = trustedTokenRoot.toAbsolutePath().normalize();
    if (absolute.getParent() == null
        || !absolute.equals(configuredPath)
        || !absolute.equals(tokenRoot.resolve("token"))) {
      throw new IOException("Protected Account JWT bearer file is unavailable");
    }
    Path canonicalTokenRoot = tokenRoot.toRealPath();
    if (!canonicalTokenRoot.equals(tokenRoot) && !canonicalTokenRoot.equals(CANONICAL_TOKEN_ROOT)) {
      throw new IOException("Protected Account JWT bearer projection root is invalid");
    }
    verifyProtectedDirectory(canonicalTokenRoot);

    BasicFileAttributes linkBefore =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!linkBefore.isSymbolicLink()) {
      throw new IOException("Protected Account JWT bearer file is unavailable");
    }
    Object linkOwner = Files.getAttribute(absolute, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    if (!(linkOwner instanceof Number linkUid) || linkUid.longValue() != 0L) {
      throw new IOException("Protected Account JWT bearer file ownership is invalid");
    }

    Path target = absolute.toRealPath();
    Path versionDirectory = target.getParent();
    if (!target.startsWith(canonicalTokenRoot)
        || versionDirectory == null
        || !canonicalTokenRoot.equals(versionDirectory.getParent())
        || target.getFileName() == null
        || !"token".equals(target.getFileName().toString())
        || !versionDirectory.getFileName().toString().matches("\\.\\.[A-Za-z0-9._-]{1,128}")) {
      throw new IOException("Protected Account JWT bearer projection is invalid");
    }
    ProtectedFile targetFile = readProtectedFile(target, MAX_BEARER_BYTES, true);
    Path targetAfter = absolute.toRealPath();
    BasicFileAttributes linkAfter =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Object linkOwnerAfter = Files.getAttribute(absolute, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    if (!target.equals(targetAfter)
        || !sameLinkAttributes(linkBefore, linkAfter)
        || !(linkOwnerAfter instanceof Number linkUidAfter)
        || linkUidAfter.longValue() != 0L) {
      throw new IOException("Protected Account JWT bearer file changed while reading");
    }

    FileIdentity targetIdentity = targetFile.identity();
    return new ProtectedFile(
        targetFile.bytes(),
        new FileIdentity(
            absolute.toString(),
            target + "|link=" + String.valueOf(linkBefore.fileKey()),
            targetIdentity.size(),
            targetIdentity.modifiedMillis(),
            targetIdentity.ownerUid(),
            targetIdentity.groupId(),
            targetIdentity.permissions(),
            targetIdentity.contentSha256()));
  }

  private static ProtectedFile readProtectedFile(
      Path path, int maximumBytes, boolean bearerCredential) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(absolute) || !absolute.equals(absolute.toRealPath())) {
      throw new IOException("Protected Account JWT JWKS file is unavailable");
    }
    BasicFileAttributes before =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() <= 0 || before.size() > maximumBytes) {
      throw new IOException("Protected Account JWT JWKS file is unavailable");
    }
    FileSecurity beforeSecurity = fileSecurity(absolute);
    verifyRootOwnedAndNonWritable(absolute);
    if (bearerCredential) {
      verifyBearerPermissions(absolute, beforeSecurity.permissions());
    }
    byte[] bytes = new byte[(int) before.size()];
    try (SeekableByteChannel channel =
        Files.newByteChannel(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        int count = channel.read(buffer);
        if (count < 0) {
          throw new IOException("Protected Account JWT JWKS file changed while reading");
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw new IOException("Protected Account JWT JWKS file changed while reading");
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    FileSecurity afterSecurity = fileSecurity(absolute);
    if (!sameAttributes(before, after) || !beforeSecurity.equals(afterSecurity)) {
      throw new IOException("Protected Account JWT JWKS file changed while reading");
    }
    verifyRootOwnedAndNonWritable(absolute);
    if (bearerCredential) {
      verifyBearerPermissions(absolute, afterSecurity.permissions());
    }
    return new ProtectedFile(
        bytes,
        new FileIdentity(
            absolute.toString(),
            String.valueOf(before.fileKey()),
            before.size(),
            before.lastModifiedTime().toMillis(),
            afterSecurity.ownerUid(),
            afterSecurity.groupId(),
            afterSecurity.permissions(),
            sha256(bytes)));
  }

  private static FileSecurity fileSecurity(Path file) throws IOException {
    Object owner = Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Object group = Files.getAttribute(file, "unix:gid", LinkOption.NOFOLLOW_LINKS);
    if (!(owner instanceof Number ownerUid) || !(group instanceof Number groupId)) {
      throw new IOException("Protected Account JWT JWKS file ownership is unavailable");
    }
    return new FileSecurity(
        ownerUid.longValue(),
        groupId.longValue(),
        Set.copyOf(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)));
  }

  private static void verifyRootOwnedAndNonWritable(Path file) throws IOException {
    Path parent = file.getParent();
    if (parent == null || Files.isSymbolicLink(file)) {
      throw new IOException("Protected Account JWT JWKS file is unavailable");
    }
    verifyProtectedDirectory(parent);
    Object fileOwner = Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> fileMode =
        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
    if (!(fileOwner instanceof Number fileUid) || fileUid.longValue() != 0L || writable(fileMode)) {
      throw new IOException("Protected Account JWT JWKS file ownership is invalid");
    }
  }

  private static void verifyProtectedDirectory(Path directory) throws IOException {
    Path normalizedDirectory = directory.toAbsolutePath().normalize();
    Path realDirectory = normalizedDirectory.toRealPath();
    Object directoryOwner =
        Files.getAttribute(realDirectory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> directoryMode =
        Files.getPosixFilePermissions(realDirectory, LinkOption.NOFOLLOW_LINKS);
    if (!realDirectory.equals(normalizedDirectory)
        || !Files.isDirectory(realDirectory, LinkOption.NOFOLLOW_LINKS)
        || !(directoryOwner instanceof Number directoryUid)
        || directoryUid.longValue() != 0L
        || writableByUntrusted(directoryMode)) {
      throw new IOException("Protected Account JWT JWKS directory ownership is invalid");
    }
  }

  private static Path resolveTrustedTokenRoot(ProtectedPaths paths) throws IOException {
    Path lexicalRoot = paths.tokenRoot().toAbsolutePath().normalize();
    Path canonicalRoot = lexicalRoot.toRealPath();
    if (paths.testOnly()) {
      if (!canonicalRoot.equals(lexicalRoot)) {
        throw new IOException("Protected Account JWT bearer root is unavailable");
      }
    } else if (!lexicalRoot.equals(TOKEN_ROOT)
        || (!canonicalRoot.equals(TOKEN_ROOT) && !canonicalRoot.equals(CANONICAL_TOKEN_ROOT))) {
      throw new IOException("Protected Account JWT bearer root is unavailable");
    }
    return canonicalRoot;
  }

  private static void verifyProtectedDirectoryChain(Path directory) throws IOException {
    Path normalized = directory.toAbsolutePath().normalize();
    Path canonical = normalized.toRealPath();
    if (!normalized.equals(canonical)) {
      throw new IOException("Protected Account JWT JWKS directory ownership is invalid");
    }
    for (Path current = canonical; current != null; current = current.getParent()) {
      verifyProtectedDirectory(current);
    }
  }

  private static boolean writable(Set<PosixFilePermission> permissions) {
    return permissions.contains(PosixFilePermission.OWNER_WRITE)
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static boolean writableByUntrusted(Set<PosixFilePermission> permissions) {
    return permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static void verifyBearerPermissions(Path file, Set<PosixFilePermission> permissions)
      throws IOException {
    if (!permissions.contains(PosixFilePermission.OWNER_READ)
        || !Files.isReadable(file)
        || permissions.contains(PosixFilePermission.OTHERS_READ)
        || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
        || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
        || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
      throw new IOException("Protected Account JWT bearer file permissions are invalid");
    }
  }

  private static boolean validUid(String value) {
    return value != null && UID.matcher(value).matches();
  }

  private static boolean validResourceVersion(String value) {
    return value != null && value.matches("[!-~]{1,256}");
  }

  private static boolean sameAttributes(BasicFileAttributes before, BasicFileAttributes after) {
    return after.isRegularFile()
        && Objects.equals(before.fileKey(), after.fileKey())
        && before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime());
  }

  private static boolean sameLinkAttributes(BasicFileAttributes before, BasicFileAttributes after) {
    return before.isSymbolicLink() == after.isSymbolicLink()
        && before.isRegularFile() == after.isRegularFile()
        && Objects.equals(before.fileKey(), after.fileKey())
        && before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime());
  }

  private static String requiredText(JsonNode object, String field, int maximumLength) {
    JsonNode value = object.get(field);
    if (value == null
        || !value.isTextual()
        || value.asText().isEmpty()
        || value.asText().length() > maximumLength) {
      throw new IllegalArgumentException("Account JWT JWKS API binding is malformed");
    }
    return value.asText();
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
  }

  private static URI parseApiServer(String value) {
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getPort() < 1
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || (uri.getRawPath() != null
              && !uri.getRawPath().isEmpty()
              && !"/".equals(uri.getRawPath()))) {
        throw new IllegalArgumentException("Account JWT JWKS API endpoint is malformed");
      }
      return URI.create("https://" + uri.getRawAuthority() + "/");
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Account JWT JWKS API endpoint is malformed");
    }
  }

  private static String canonicalDnsName(String value) {
    if (value == null || value.isBlank() || value.endsWith(".")) {
      throw new IllegalArgumentException("Account JWT JWKS API TLS name is malformed");
    }
    try {
      String ascii =
          IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
      if (ascii.length() > 253 || ascii.startsWith("[") || isIpLiteral(ascii)) {
        throw new IllegalArgumentException("Account JWT JWKS API TLS name is malformed");
      }
      return ascii;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Account JWT JWKS API TLS name is malformed");
    }
  }

  private static boolean isIpLiteral(String value) {
    return value.matches("[0-9.]+") || value.indexOf(':') >= 0;
  }

  private static Path absoluteProtectedPath(String value) {
    Path path = Path.of(value);
    if (!path.isAbsolute() || !path.normalize().equals(path)) {
      throw new IllegalArgumentException("Account JWT JWKS protected path is malformed");
    }
    return path;
  }

  private static String canonicalUid(String value) {
    if (!UID.matcher(value).matches() || "00000000-0000-0000-0000-000000000000".equals(value)) {
      throw new IllegalArgumentException("Account JWT JWKS API UID is malformed");
    }
    return value;
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
      throw new IllegalArgumentException("Account JWT JWKS protected file is not UTF-8");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception ex) {
      throw new IllegalStateException("Account JWT JWKS digest is unavailable");
    }
  }

  private static FileIdentity testFileIdentity(
      String path, byte[] bytes, Set<PosixFilePermission> permissions) {
    return new FileIdentity(
        path, "test-file-identity:" + path, bytes.length, 1L, 0L, 0L, permissions, sha256(bytes));
  }

  private record ProtectedFile(byte[] bytes, FileIdentity identity) {}

  private record FileSecurity(long ownerUid, long groupId, Set<PosixFilePermission> permissions) {}

  private record ProtectedState(
      ParsedBinding config,
      byte[] caBytes,
      String bearerToken,
      TrustIdentity identity,
      FileIdentity bearerIdentity) {}

  private record TrustIdentity(
      FileIdentity binding,
      FileIdentity ca,
      CredentialPolicyIdentity bearer,
      String bindingDigest,
      String bearerRootRealPath) {}

  private record CredentialPolicyIdentity(
      String path, long ownerUid, long groupId, Set<PosixFilePermission> permissions) {}

  private record ProtectedPaths(
      Path trustRoot,
      Path bindingPath,
      Path caPath,
      Path tokenRoot,
      Path tokenPath,
      boolean testOnly) {}

  private record FileIdentity(
      String path,
      String fileKey,
      long size,
      long modifiedMillis,
      long ownerUid,
      long groupId,
      Set<PosixFilePermission> permissions,
      String contentSha256) {}

  public record ParsedBinding(
      String configRevision,
      String environmentId,
      String clusterId,
      URI apiServer,
      String tlsServerName,
      Path servingCaPath,
      String servingCaSha256,
      Path bearerTokenPath,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String expectedApiUsername,
      String bindingDigest) {}

  /** One operation-scoped credential lease; all its API calls use the same bearer snapshot. */
  public final class ApiOperation implements AutoCloseable {
    private ProtectedState credentialSnapshot;

    private ApiOperation(ProtectedState credentialSnapshot) {
      this.credentialSnapshot = credentialSnapshot;
    }

    public ParsedBinding binding() {
      if (credentialSnapshot == null) {
        throw new BindingRejectedException();
      }
      return credentialSnapshot.config();
    }

    public ApiResponse send(ApiCall call, byte[] requestBody) {
      return AccountJwtJwksApiBinding.this.send(credentialSnapshot, call, requestBody);
    }

    /** Reads one Deployment named by the independently protected validator inventory only. */
    public ApiResponse readValidatorDeployment(
        AccountJwtValidatorInventoryBinding.ProtectedInventory inventory,
        AccountJwtValidatorInventoryBinding.ValidatorExpectation validator) {
      return AccountJwtJwksApiBinding.this.sendInventoryRead(
          credentialSnapshot, ApiCall.READ_VALIDATOR_DEPLOYMENT, inventory, validator, null);
    }

    /** Lists Pods only by the protected validator selector in the pinned namespace. */
    public ApiResponse listValidatorPods(
        AccountJwtValidatorInventoryBinding.ProtectedInventory inventory,
        AccountJwtValidatorInventoryBinding.ValidatorExpectation validator,
        String continuationToken) {
      return AccountJwtJwksApiBinding.this.sendInventoryRead(
          credentialSnapshot, ApiCall.LIST_VALIDATOR_PODS, inventory, validator, continuationToken);
    }

    /** Reads one ReplicaSet named by an observed Pod owner reference in the pinned namespace. */
    public ApiResponse readValidatorReplicaSet(
        AccountJwtValidatorInventoryBinding.ProtectedInventory inventory,
        AccountJwtValidatorInventoryBinding.ValidatorExpectation validator,
        String replicaSetName) {
      return AccountJwtJwksApiBinding.this.sendInventoryRead(
          credentialSnapshot,
          ApiCall.READ_VALIDATOR_REPLICA_SET,
          inventory,
          validator,
          null,
          replicaSetName);
    }

    @Override
    public void close() {
      credentialSnapshot = null;
    }
  }

  private ApiResponse sendInventoryRead(
      ProtectedState credentialSnapshot,
      ApiCall call,
      AccountJwtValidatorInventoryBinding.ProtectedInventory inventory,
      AccountJwtValidatorInventoryBinding.ValidatorExpectation validator,
      String continuationToken) {
    return sendInventoryRead(
        credentialSnapshot, call, inventory, validator, continuationToken, null);
  }

  private ApiResponse sendInventoryRead(
      ProtectedState credentialSnapshot,
      ApiCall call,
      AccountJwtValidatorInventoryBinding.ProtectedInventory inventory,
      AccountJwtValidatorInventoryBinding.ValidatorExpectation validator,
      String continuationToken,
      String replicaSetName) {
    if (credentialSnapshot == null
        || inventory == null
        || validator == null
        || !inventory.matchesApiBinding(credentialSnapshot.config())
        || inventory.validators().stream().noneMatch(expected -> expected == validator)) {
      throw new BindingRejectedException();
    }
    String path;
    if (call == ApiCall.READ_VALIDATOR_DEPLOYMENT) {
      if (continuationToken != null) {
        throw new BindingRejectedException();
      }
      path =
          "/apis/apps/v1/namespaces/"
              + inventory.namespace()
              + "/deployments/"
              + validator.deploymentName();
    } else if (call == ApiCall.LIST_VALIDATOR_PODS) {
      String selector =
          validator.selector().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(entry -> entry.getKey() + "=" + entry.getValue())
              .collect(java.util.stream.Collectors.joining(","));
      path =
          "/api/v1/namespaces/"
              + inventory.namespace()
              + "/pods?labelSelector="
              + queryEncode(selector)
              + "&limit=250";
      if (continuationToken != null && !continuationToken.isEmpty()) {
        if (continuationToken.length() > 2048
            || !continuationToken.matches("[A-Za-z0-9_./+=-]{1,2048}")) {
          throw new BindingRejectedException();
        }
        path += "&continue=" + queryEncode(continuationToken);
      }
    } else if (call == ApiCall.READ_VALIDATOR_REPLICA_SET) {
      if (continuationToken != null
          || replicaSetName == null
          || !replicaSetName.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
        throw new BindingRejectedException();
      }
      path = "/apis/apps/v1/namespaces/" + inventory.namespace() + "/replicasets/" + replicaSetName;
    } else {
      throw new BindingRejectedException();
    }
    return send(credentialSnapshot, call, null, path);
  }

  private static String queryEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public enum ApiCall {
    REVIEW_AUTHENTICATED_PRINCIPAL("POST", "/apis/authentication.k8s.io/v1/selfsubjectreviews"),
    READ_KUBE_SYSTEM_NAMESPACE("GET", "/api/v1/namespaces/kube-system"),
    READ_TARGET_NAMESPACE("GET", null),
    READ_JWKS_CONFIG_MAP("GET", null),
    PATCH_JWKS_CONFIG_MAP("PATCH", null),
    READ_VALIDATOR_DEPLOYMENT("GET", null),
    LIST_VALIDATOR_PODS("GET", null),
    READ_VALIDATOR_REPLICA_SET("GET", null);

    private final String method;
    private final String fixedPath;

    ApiCall(String method, String fixedPath) {
      this.method = method;
      this.fixedPath = fixedPath;
    }

    public String method() {
      return method;
    }

    private String path(String namespace) {
      return switch (this) {
        case REVIEW_AUTHENTICATED_PRINCIPAL, READ_KUBE_SYSTEM_NAMESPACE -> fixedPath;
        case READ_TARGET_NAMESPACE -> "/api/v1/namespaces/" + namespace;
        case READ_JWKS_CONFIG_MAP, PATCH_JWKS_CONFIG_MAP ->
            "/api/v1/namespaces/" + namespace + "/configmaps/jwt-jwks";
        case READ_VALIDATOR_DEPLOYMENT, LIST_VALIDATOR_PODS, READ_VALIDATOR_REPLICA_SET ->
            throw new BindingRejectedException();
      };
    }
  }

  public record ApiResponse(int statusCode, byte[] body) {
    public ApiResponse {
      body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  public static final class BindingRejectedException extends RuntimeException {
    public BindingRejectedException() {
      super("Account JWT JWKS API binding is unavailable or changed");
    }
  }

  public static final class ApiTransportException extends RuntimeException {
    public ApiTransportException() {
      super("Account JWT JWKS Kubernetes API request failed");
    }
  }
}
