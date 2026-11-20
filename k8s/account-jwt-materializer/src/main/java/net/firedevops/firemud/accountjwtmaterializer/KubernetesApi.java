package net.firedevops.firemud.accountjwtmaterializer;

import java.io.ByteArrayInputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Direct, bounded HTTPS client for the pre-created Secret and pinned incarnation reads only. */
final class KubernetesApi implements Materializer.KubernetesOperations {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_IDENTITY_BYTES = 32 * 1024;
  private static final int MAX_SECRET_BYTES = SecretState.MAX_SECRET_JSON_BYTES;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final ProtectedBinding.Snapshot snapshot;
  private final java.nio.file.Path bindingPath;
  private final URI apiBase;
  private final HttpClient client;

  KubernetesApi(ProtectedBinding.Snapshot snapshot, java.nio.file.Path bindingPath) {
    this.snapshot = snapshot;
    this.bindingPath = bindingPath;
    this.apiBase = snapshot.binding().kubernetesApiServerUri();
    try {
      snapshot.requireUnchanged(bindingPath);
      SSLParameters tlsParameters = new SSLParameters();
      tlsParameters.setEndpointIdentificationAlgorithm("HTTPS");
      this.client =
          HttpClient.newBuilder()
              .connectTimeout(CONNECT_TIMEOUT)
              .followRedirects(HttpClient.Redirect.NEVER)
              .proxy(new NoProxySelector())
              .sslContext(apiSslContext(snapshot.binding()))
              .sslParameters(tlsParameters)
              .version(HttpClient.Version.HTTP_1_1)
              .build();
    } catch (Exception ex) {
      throw denied();
    }
  }

  @Override
  public void verifyClusterIdentity() {
    ProtectedBinding.Binding binding = snapshot.binding();
    JsonNode ownNamespace =
        getJson("/api/v1/namespaces/" + binding.namespace(), MAX_IDENTITY_BYTES);
    JsonNode systemNamespace = getJson("/api/v1/namespaces/kube-system", MAX_IDENTITY_BYTES);
    JsonNode serviceAccount =
        getJson(
            "/api/v1/namespaces/"
                + binding.namespace()
                + "/serviceaccounts/"
                + binding.expectedServiceAccountName(),
            MAX_IDENTITY_BYTES);
    if (!"v1".equals(text(ownNamespace, "apiVersion"))
        || !"Namespace".equals(text(ownNamespace, "kind"))
        || !binding.namespace().equals(metadataName(ownNamespace))
        || !"v1".equals(text(systemNamespace, "apiVersion"))
        || !"Namespace".equals(text(systemNamespace, "kind"))
        || !"kube-system".equals(metadataName(systemNamespace))
        || !"v1".equals(text(serviceAccount, "apiVersion"))
        || !"ServiceAccount".equals(text(serviceAccount, "kind"))
        || !binding.namespace().equals(metadataNamespace(serviceAccount))
        || !binding.expectedServiceAccountName().equals(metadataName(serviceAccount))
        || !binding.expectedNamespaceUid().equals(metadataUid(ownNamespace))
        || !binding.expectedKubeSystemNamespaceUid().equals(metadataUid(systemNamespace))
        || !binding.expectedClusterIncarnationUid().equals(metadataUid(systemNamespace))
        || !binding.expectedServiceAccountUid().equals(metadataUid(serviceAccount))) {
      throw denied();
    }
  }

  @Override
  public SecretState getFixedSecret() {
    byte[] response =
        getBytes(
            "/api/v1/namespaces/" + snapshot.binding().namespace() + "/secrets/" + SecretState.NAME,
            MAX_SECRET_BYTES);
    try {
      return SecretState.parse(response, snapshot.binding().namespace());
    } finally {
      java.util.Arrays.fill(response, (byte) 0);
    }
  }

  @Override
  public void patchFixedSecret(SecretState before, byte[] mergePatch) {
    try {
      if (!before.uid().matches("[0-9a-fA-F-]{36}")
          || mergePatch == null
          || mergePatch.length == 0
          || mergePatch.length > MAX_SECRET_BYTES) {
        throw denied();
      }
      String path =
          "/api/v1/namespaces/" + snapshot.binding().namespace() + "/secrets/" + SecretState.NAME;
      byte[] response =
          send("PATCH", path, mergePatch, "application/merge-patch+json", MAX_SECRET_BYTES);
      java.util.Arrays.fill(response, (byte) 0);
    } finally {
      if (mergePatch != null) java.util.Arrays.fill(mergePatch, (byte) 0);
    }
  }

  private JsonNode getJson(String path, int maxBytes) {
    byte[] response = getBytes(path, maxBytes);
    try {
      return JSON.readTree(response);
    } catch (Exception ex) {
      throw denied();
    } finally {
      java.util.Arrays.fill(response, (byte) 0);
    }
  }

  private byte[] getBytes(String path, int maxBytes) {
    return send("GET", path, null, null, maxBytes);
  }

  private byte[] send(
      String method, String path, byte[] requestBody, String contentType, int maxResponseBytes) {
    requireSnapshot();
    String token = readBearerToken();
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(endpoint(path))
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + token);
      if (requestBody == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request.header("Content-Type", contentType);
        request.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
      }
      HttpResponse<byte[]> response =
          client.send(request.build(), info -> new BoundedBodySubscriber(maxResponseBytes));
      requireSnapshot();
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        java.util.Arrays.fill(response.body(), (byte) 0);
        throw denied();
      }
      return response.body();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw denied();
    } catch (Exception ex) {
      requireSnapshot();
      throw denied();
    } finally {
      token = null;
      if (requestBody != null) {
        java.util.Arrays.fill(requestBody, (byte) 0);
      }
    }
  }

  private URI endpoint(String path) {
    return URI.create(
        apiBase.getScheme() + "://" + snapshot.binding().kubernetesApiAuthority() + path);
  }

  private String readBearerToken() {
    byte[] tokenBytes = null;
    try {
      tokenBytes = ProtectedBinding.readCredential(snapshot.binding().kubernetesTokenPath());
      String token = new String(tokenBytes, StandardCharsets.UTF_8).trim();
      if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
        throw denied();
      }
      return token;
    } catch (java.io.IOException | RuntimeException ex) {
      throw denied();
    } finally {
      if (tokenBytes != null) java.util.Arrays.fill(tokenBytes, (byte) 0);
    }
  }

  private void requireSnapshot() {
    try {
      snapshot.requireUnchanged(bindingPath);
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static SSLContext apiSslContext(ProtectedBinding.Binding binding) throws Exception {
    byte[] caBytes = ProtectedBinding.readCredential(binding.kubernetesApiCaPath());
    try {
      var certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(caBytes));
      if (certificates.isEmpty()) {
        throw denied();
      }
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null);
      int index = 0;
      for (var certificate : certificates) {
        store.setCertificateEntry("kubernetes-api-ca-" + index++, certificate);
      }
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context;
    } finally {
      java.util.Arrays.fill(caBytes, (byte) 0);
    }
  }

  private static String metadataUid(JsonNode root) {
    JsonNode metadata = root == null ? null : root.get("metadata");
    JsonNode uid = metadata == null ? null : metadata.get("uid");
    if (uid == null || !uid.isTextual()) {
      throw denied();
    }
    return uid.textValue();
  }

  private static String metadataName(JsonNode root) {
    return text(root, "metadata", "name");
  }

  private static String metadataNamespace(JsonNode root) {
    return text(root, "metadata", "namespace");
  }

  private static String text(JsonNode root, String... path) {
    JsonNode value = root;
    for (String field : path) {
      value = value == null ? null : value.get(field);
    }
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw denied();
    }
    return value.textValue();
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Pinned Kubernetes API operation was denied or unavailable");
  }

  private static final class NoProxySelector extends ProxySelector {
    @Override
    public List<Proxy> select(URI uri) {
      return List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {
      // No proxy is configured, so there is no proxy failure to recover from.
    }
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximumBytes;
    private byte[] bytes;
    private int length;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int maximumBytes) {
      this.maximumBytes = maximumBytes;
      this.bytes = new byte[maximumBytes];
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result.minimalCompletionStage();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      try {
        if (bytes == null || result.isDone()) {
          subscription.cancel();
          return;
        }
        for (ByteBuffer buffer : buffers) {
          int count = buffer.remaining();
          if (count > maximumBytes - length) {
            subscription.cancel();
            java.util.Arrays.fill(bytes, (byte) 0);
            bytes = null;
            result.completeExceptionally(denied());
            return;
          }
          buffer.get(bytes, length, count);
          length += count;
        }
        subscription.request(1);
      } catch (RuntimeException ex) {
        subscription.cancel();
        result.completeExceptionally(denied());
      }
    }

    @Override
    public void onError(Throwable failure) {
      if (bytes != null) {
        java.util.Arrays.fill(bytes, (byte) 0);
        bytes = null;
      }
      result.completeExceptionally(denied());
    }

    @Override
    public void onComplete() {
      if (bytes == null || result.isDone()) {
        return;
      }
      byte[] response = java.util.Arrays.copyOf(bytes, length);
      java.util.Arrays.fill(bytes, (byte) 0);
      bytes = null;
      result.complete(response);
    }
  }
}
