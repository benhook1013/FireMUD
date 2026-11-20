package net.firedevops.firemud.accountjwtmaterializer;

import com.google.protobuf.Message;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.firedevops.firemud.account.v1.EnrollBootstrapRequest;
import net.firedevops.firemud.account.v1.EnrollBootstrapResponse;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.PromotionPublicKeyIdentity;
import net.firedevops.firemud.account.v1.RecordGenerationResultRequest;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultRequest;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.account.v1.RecordSecretObservationRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only bridge to the unchanged package-private materializer, with controlled Secret storage.
 */
public final class CanonicalMaterializerDriver {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ProtectedBinding.Snapshot snapshot;
  private final MemorySecretApi secret;
  private final BiFunction<String, Message, Message> invoke;

  public CanonicalMaterializerDriver(
      String namespace,
      String clusterUid,
      String namespaceUid,
      String trustDigest,
      String trustRevision,
      String secretUid,
      BiFunction<String, Message, Message> invoke) {
    this.invoke = invoke;
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/jwt-signer-materializer";
    String dns = "account-service." + namespace + ".svc.cluster.local";
    var binding =
        new ProtectedBinding.Binding(
            trustRevision,
            "prod",
            "prod-cluster-1",
            namespace,
            clusterUid,
            namespaceUid,
            clusterUid,
            "jwt-signer-materializer",
            namespaceUid,
            peer,
            "d".repeat(64),
            List.of("d".repeat(64)),
            trustDigest,
            dns + ":6565",
            dns,
            "spiffe://firemud/ns/" + namespace + "/sa/account-service",
            List.of("e".repeat(64)),
            Path.of("unused-ca"),
            Path.of("unused-certificate"),
            Path.of("unused-key"),
            URI.create("https://kubernetes.test:6443"),
            "kubernetes.test",
            Path.of("unused-api-ca"),
            Path.of("unused-token"));
    snapshot = new ProtectedBinding.Snapshot(binding, "controlled-test-binding");
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("name", SecretState.NAME);
    metadata.put("namespace", namespace);
    metadata.put("uid", secretUid);
    metadata.put("resourceVersion", "12");
    metadata.put("annotations", new LinkedHashMap<String, String>());
    var resource = new LinkedHashMap<String, Object>();
    resource.put("apiVersion", "v1");
    resource.put("kind", "Secret");
    resource.put("type", "Opaque");
    resource.put("metadata", metadata);
    resource.put("data", new LinkedHashMap<String, String>());
    secret = new MemorySecretApi(resource, namespace);
  }

  public String observe() {
    return new Materializer(snapshot, new Owner(), secret).observeSecretOnce().name();
  }

  public String reconcile() {
    return new Materializer(snapshot, new Owner(), secret).reconcileOnce().name();
  }

  public int patches() {
    return secret.patchCount;
  }

  public void loseNextPatchReply() {
    secret.throwAfterApply = true;
  }

  public byte[] pendingBundle() {
    return secret.read().slotBytes(SecretState.PENDING);
  }

  private final class Owner implements Materializer.AccountOperations {
    public EnrollBootstrapResponse enrollBootstrap() {
      return (EnrollBootstrapResponse)
          invoke.apply("enroll", EnrollBootstrapRequest.newBuilder().setSchemaVersion(1).build());
    }

    public GetCurrentGenerationRequestResponse getCurrentGenerationRequest() {
      return (GetCurrentGenerationRequestResponse)
          invoke.apply(
              "generation",
              GetCurrentGenerationRequestRequest.newBuilder().setSchemaVersion(1).build());
    }

    public GetCurrentGenerationRequestResponse recordSecretObservation(
        String operation, String digest, String uid, String rv) {
      return (GetCurrentGenerationRequestResponse)
          invoke.apply(
              "observe",
              RecordSecretObservationRequest.newBuilder()
                  .setSchemaVersion(1)
                  .setOperationId(operation)
                  .setOperationDigest(digest)
                  .setSecretUid(uid)
                  .setObservedResourceVersion(rv)
                  .build());
    }

    public RecordGenerationResultResponse recordGenerationResult(
        String operation, String digest, String uid, String prior, String observed, String jwk) {
      return (RecordGenerationResultResponse)
          invoke.apply(
              "record",
              RecordGenerationResultRequest.newBuilder()
                  .setSchemaVersion(1)
                  .setOperationId(operation)
                  .setGenerationRequestDigest(digest)
                  .setSecretUid(uid)
                  .setExpectedPriorResourceVersion(prior)
                  .setObservedResourceVersion(observed)
                  .setPublicJwkJson(jwk)
                  .build());
    }

    public GetCurrentPromotionRequestResponse getCurrentPromotionRequest() {
      return (GetCurrentPromotionRequestResponse)
          invoke.apply(
              "promotion",
              GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build());
    }

    public RecordPrivatePromotionResultResponse recordPrivatePromotionResult(
        GetCurrentPromotionRequestResponse request,
        String uid,
        String prior,
        String observed,
        SignerKeyBundle.PublicIdentity current,
        SignerKeyBundle.PublicIdentity previous,
        List<String> slots) {
      var builder =
          RecordPrivatePromotionResultRequest.newBuilder()
              .setSchemaVersion(1)
              .setPromotionOperationId(request.getPromotionOperationId())
              .setPromotionRequestDigest(request.getPromotionRequestDigest())
              .setGenerationOperationId(request.getGenerationOperationId())
              .setGenerationOperationDigest(request.getGenerationOperationDigest())
              .setSecretUid(uid)
              .setExpectedPriorResourceVersion(prior)
              .setObservedResourceVersion(observed)
              .setCurrent(identity(current))
              .addAllResultingSlots(slots);
      if (previous != null) builder.setPrevious(identity(previous));
      return (RecordPrivatePromotionResultResponse) invoke.apply("promote", builder.build());
    }
  }

  private static PromotionPublicKeyIdentity identity(SignerKeyBundle.PublicIdentity value) {
    return PromotionPublicKeyIdentity.newBuilder()
        .setGeneration(value.generation())
        .setKid(value.kid())
        .setPublicKeyFingerprint(value.fingerprint())
        .build();
  }

  public byte[] currentBundle() {
    return secret.read().slotBytes(SecretState.CURRENT);
  }

  private static byte[] json(Object value) {
    return JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
  }

  private static final class MemorySecretApi implements Materializer.KubernetesOperations {
    private final Map<String, Object> resource;
    private final String namespace;
    private int patchCount;
    private boolean throwAfterApply;

    MemorySecretApi(Map<String, Object> resource, String namespace) {
      this.namespace = namespace;
      this.resource = resource;
    }

    @Override
    public void verifyClusterIdentity() {}

    @Override
    public SecretState getFixedSecret() {
      return read();
    }

    SecretState read() {
      return SecretState.parse(json(resource), namespace);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void patchFixedSecret(SecretState before, byte[] mergePatch) {
      patchCount++;
      try {
        JsonNode patch;
        try {
          patch = JSON.readTree(mergePatch);
        } catch (RuntimeException ex) {
          throw new IllegalStateException("simulated malformed patch");
        }
        JsonNode metadataPatch = patch.get("metadata");
        Map<String, Object> metadata = (Map<String, Object>) resource.get("metadata");
        if (!before.uid().equals(metadata.get("uid"))
            || !before.resourceVersion().equals(metadata.get("resourceVersion"))
            || !before.uid().equals(metadataPatch.get("uid").textValue())
            || !before.resourceVersion().equals(metadataPatch.get("resourceVersion").textValue())) {
          throw new IllegalStateException("simulated stale Secret precondition");
        }
        Map<String, String> annotations = (Map<String, String>) metadata.get("annotations");
        if (annotations == null) {
          annotations = new LinkedHashMap<>();
          metadata.put("annotations", annotations);
        }
        JsonNode annotationPatch = metadataPatch.get("annotations");
        for (Map.Entry<String, JsonNode> entry : annotationPatch.properties()) {
          annotations.put(entry.getKey(), entry.getValue().textValue());
        }
        Map<String, String> data = (Map<String, String>) resource.get("data");
        for (Map.Entry<String, JsonNode> entry : patch.get("data").properties()) {
          if (entry.getValue().isNull()) {
            data.remove(entry.getKey());
          } else {
            data.put(entry.getKey(), entry.getValue().textValue());
          }
        }
        int next = Integer.parseInt(before.resourceVersion()) + 1;
        metadata.put("resourceVersion", Integer.toString(next));
        if (throwAfterApply) {
          throwAfterApply = false;
          throw new IllegalStateException("simulated lost patch response");
        }
      } finally {
        java.util.Arrays.fill(mergePatch, (byte) 0);
      }
    }
  }
}
