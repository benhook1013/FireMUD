package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiCall;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ApiSession;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.CasConflictException;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.CasObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.Outcome;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ResourceMissingException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtJwksConfigMapClientTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String KUBE_SYSTEM_UID = "11111111-1111-4111-8111-111111111111";
  private static final String TARGET_NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final String API_USERNAME = "system:serviceaccount:firemud-prod:account-service";
  private static final String JWKS_OLD = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"old\"}]}";
  private static final String JWKS_NEW = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"new\"}]}";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @org.junit.jupiter.api.Test
  void observesOnlyFixedConfigMapAfterPrincipalAndNamespaceChecks() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);

    ConfigMapSnapshot observed = client.observe();

    assertThat(observed.uid()).isEqualTo(CONFIG_MAP_UID);
    assertThat(observed.resourceVersion()).isEqualTo("41");
    assertThat(observed.data()).containsEntry("unrelated.txt", "retain");
    assertThat(api.calls)
        .containsExactly(
            ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL,
            ApiCall.READ_KUBE_SYSTEM_NAMESPACE,
            ApiCall.READ_TARGET_NAMESPACE,
            ApiCall.READ_JWKS_CONFIG_MAP);
    assertThat(api.calls)
        .allMatch(
            call ->
                Set.of(
                        ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL,
                        ApiCall.READ_KUBE_SYSTEM_NAMESPACE,
                        ApiCall.READ_TARGET_NAMESPACE,
                        ApiCall.READ_JWKS_CONFIG_MAP,
                        ApiCall.PATCH_JWKS_CONFIG_MAP)
                    .contains(call));
  }

  @org.junit.jupiter.api.Test
  void absentPrecreatedConfigMapFailsWithoutCreateOrWrite() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    api.resourceExists = false;
    AccountJwtJwksConfigMapClient client = client(api);

    assertThatThrownBy(client::observe).isInstanceOf(ResourceMissingException.class);

    assertThat(api.calls)
        .containsExactly(
            ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL,
            ApiCall.READ_KUBE_SYSTEM_NAMESPACE,
            ApiCall.READ_TARGET_NAMESPACE,
            ApiCall.READ_JWKS_CONFIG_MAP);
    assertThat(api.patchCount).isZero();
  }

  @org.junit.jupiter.api.Test
  void oversizedConfigMapResponseIsRejectedAtTheBound() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    api.oversizedConfigMapResponse = true;

    assertThatThrownBy(() -> client(api).observe())
        .isInstanceOf(AccountJwtJwksConfigMapClient.ApiFailureException.class);

    assertThat(api.patchCount).isZero();
  }

  @org.junit.jupiter.api.Test
  void rejectsWrongAccountPrincipalAndEitherNamespaceIncarnationBeforeConfigMapRead() {
    FakeKubernetesApi wrongPrincipal = new FakeKubernetesApi();
    wrongPrincipal.authenticatedUsername = "system:serviceaccount:firemud-prod:firemud-app";
    assertThatThrownBy(() -> client(wrongPrincipal).observe())
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);
    assertThat(wrongPrincipal.calls).containsExactly(ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL);

    FakeKubernetesApi wrongCluster = new FakeKubernetesApi();
    wrongCluster.kubeSystemUid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    assertThatThrownBy(() -> client(wrongCluster).observe())
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);
    assertThat(wrongCluster.calls)
        .containsExactly(
            ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL, ApiCall.READ_KUBE_SYSTEM_NAMESPACE);

    FakeKubernetesApi wrongNamespace = new FakeKubernetesApi();
    wrongNamespace.targetNamespaceUid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    assertThatThrownBy(() -> client(wrongNamespace).observe())
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);
    assertThat(wrongNamespace.calls)
        .containsExactly(
            ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL,
            ApiCall.READ_KUBE_SYSTEM_NAMESPACE,
            ApiCall.READ_TARGET_NAMESPACE);
  }

  @org.junit.jupiter.api.Test
  void rejectsChangedProtectedTrustBeforeWrite() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    ConfigMapSnapshot expected = client(api).observe();
    api.rejectPatchForChangedTrust = true;

    assertThatThrownBy(() -> client(api).publish(expected, desired("op-7")))
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);

    assertThat(api.patchCount).isZero();
    assertThat(api.data.get(AccountJwtJwksConfigMapClient.JWKS_DATA_KEY)).isEqualTo(JWKS_OLD);
  }

  @org.junit.jupiter.api.Test
  void bearerRenewalIsAcceptedOnlyBeforeACompleteOperationAndModeOrOwnerDriftIsRejected() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);

    client.observe();
    api.bearerToken = "rotated-token";
    api.bearerFileKey = "rotated-inode";
    assertThat(client.observe().uid()).isEqualTo(CONFIG_MAP_UID);

    api.calls.clear();
    api.bearerMode = "0444";
    assertThatThrownBy(client::observe)
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);
    assertThat(api.calls).isEmpty();

    api.bearerMode = "0440";
    api.bearerOwnerUid = 1001;
    assertThatThrownBy(client::observe)
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);
    assertThat(api.calls).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void bearerRotationMidVerificationCannotSplitSelfReviewFromConfigMapRead() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    api.rotateBearerAfterSelfReview = true;

    assertThatThrownBy(client::observe)
        .isInstanceOf(AccountJwtJwksConfigMapClient.BindingRejectedException.class);
    assertThat(api.calls).containsExactly(ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL);

    api.calls.clear();
    assertThat(client.observe().uid()).isEqualTo(CONFIG_MAP_UID);
    assertThat(api.calls)
        .containsExactly(
            ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL,
            ApiCall.READ_KUBE_SYSTEM_NAMESPACE,
            ApiCall.READ_TARGET_NAMESPACE,
            ApiCall.READ_JWKS_CONFIG_MAP);
  }

  @org.junit.jupiter.api.Test
  void compareAndSetConflictNeverOverwritesChangedResourceVersion() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    ConfigMapSnapshot expected = client.observe();
    api.resourceVersion = "42";
    api.data.put("another-writer", "preserved");

    assertThatThrownBy(() -> client.publish(expected, desired("op-8")))
        .isInstanceOf(CasConflictException.class);

    assertThat(api.patchCount).isZero();
    assertThat(api.data).containsEntry("another-writer", "preserved");
  }

  @org.junit.jupiter.api.Test
  void resourceReplacementAndApiConflictAreBothFenced() {
    FakeKubernetesApi replacement = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient replacementClient = client(replacement);
    ConfigMapSnapshot expected = replacementClient.observe();
    replacement.configMapUid = "44444444-4444-4444-8444-444444444444";

    assertThatThrownBy(() -> replacementClient.publish(expected, desired("op-replaced")))
        .isInstanceOf(CasConflictException.class);
    assertThat(replacement.patchCount).isZero();

    FakeKubernetesApi inFlightConflict = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient inFlightClient = client(inFlightConflict);
    ConfigMapSnapshot beforePatch = inFlightClient.observe();
    inFlightConflict.conflictNextPatch = true;

    assertThatThrownBy(() -> inFlightClient.publish(beforePatch, desired("op-conflict")))
        .isInstanceOf(CasConflictException.class);
    assertThat(inFlightConflict.patchCount).isEqualTo(1);
    assertThat(inFlightConflict.data.get(AccountJwtJwksConfigMapClient.JWKS_DATA_KEY))
        .isEqualTo(JWKS_OLD);
  }

  @org.junit.jupiter.api.Test
  void rejectsPrivateJwkMaterialBeforeAnyApiCall() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    Map<String, String> privateJwks =
        Map.of(
            AccountJwtJwksConfigMapClient.JWKS_DATA_KEY,
            "{\"keys\":[{\"kty\":\"RSA\",\"d\":\"private\"}]}",
            AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY,
            marker("op-private"));

    assertThatThrownBy(
            () ->
                client(api)
                    .publish(new ConfigMapSnapshot(CONFIG_MAP_UID, "41", Map.of()), privateJwks))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account public JWKS contains private material");

    assertThat(api.calls).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void lostPatchAcknowledgementSucceedsOnlyAfterExactSameUidContentReadback() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    ConfigMapSnapshot expected = client.observe();
    Map<String, String> desired = desired("durable-operation-9");
    api.loseNextPatchAcknowledgement = true;

    CasObservation result = client.publish(expected, desired);

    assertThat(result.outcome()).isEqualTo(Outcome.EXACT_REPLAY);
    assertThat(result.uid()).isEqualTo(CONFIG_MAP_UID);
    assertThat(result.priorResourceVersion()).isEqualTo("41");
    assertThat(result.resourceVersion()).isEqualTo("42");
    assertThat(result.publishedData()).isEqualTo(desired);
    assertThat(api.patchCount).isEqualTo(1);
  }

  @org.junit.jupiter.api.Test
  void exactDataAtTheUnchangedExpectedVersionIsNotReportedAsCasSuccess() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    Map<String, String> desired = desired("already-present-operation");
    api.data.putAll(desired);
    AccountJwtJwksConfigMapClient client = client(api);
    ConfigMapSnapshot expected = client.observe();

    assertThatThrownBy(() -> client.publish(expected, desired))
        .isInstanceOf(CasConflictException.class)
        .hasMessage("Account public JWKS ConfigMap compare-and-set conflict");

    assertThat(api.patchCount).isZero();
    assertThat(api.resourceVersion).isEqualTo(expected.resourceVersion());
  }

  @org.junit.jupiter.api.Test
  void updatePreservesUnrelatedDataAndPatchContainsOnlyOwnedPublicEntries() throws Exception {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    ConfigMapSnapshot expected = client.observe();
    Map<String, String> desired = desired("durable-operation-10");

    CasObservation result = client.publish(expected, desired);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.resourceVersion()).isEqualTo("42");
    assertThat(result.publishedData()).isEqualTo(desired);
    assertThat(api.data)
        .containsEntry("unrelated.txt", "retain")
        .containsEntry(AccountJwtJwksConfigMapClient.JWKS_DATA_KEY, JWKS_NEW)
        .containsEntry(
            AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY, marker("durable-operation-10"));
    JsonNode patch = JSON.readTree(api.lastPatch);
    assertThat(patch.get("metadata").size()).isEqualTo(2);
    assertThat(patch.get("metadata").get("uid").asText()).isEqualTo(CONFIG_MAP_UID);
    assertThat(patch.get("metadata").get("resourceVersion").asText()).isEqualTo("41");
    assertThat(patch.get("data").size()).isEqualTo(2);
    assertThat(patch.get("data").has("unrelated.txt")).isFalse();
  }

  @org.junit.jupiter.api.Test
  void activeMarkerCasUsesFreshSnapshotAndPreservesUnrelatedEntries() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    ConfigMapSnapshot observed = client.observe();
    Map<String, String> desired =
        Map.of(
            AccountJwtJwksConfigMapClient.JWKS_DATA_KEY,
            JWKS_OLD,
            AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY,
            marker("active-operation"));

    CasObservation result = client.publishActiveProjection(observed, "41", desired);

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.priorResourceVersion()).isEqualTo("41");
    assertThat(result.resourceVersion()).isEqualTo("42");
    assertThat(result.readbackData()).containsEntry("unrelated.txt", "retain");
    assertThat(api.patchCount).isEqualTo(1);
  }

  @org.junit.jupiter.api.Test
  void activeMarkerLostAcknowledgementReplayRequiresAdvancedResourceVersion() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    ConfigMapSnapshot observed = client.observe();
    Map<String, String> desired =
        Map.of(
            AccountJwtJwksConfigMapClient.JWKS_DATA_KEY,
            JWKS_OLD,
            AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY,
            marker("active-operation"));
    api.loseNextPatchAcknowledgement = true;

    CasObservation applied = client.publishActiveProjection(observed, "41", desired);

    assertThat(applied.outcome()).isEqualTo(Outcome.EXACT_REPLAY);
    assertThat(applied.priorResourceVersion()).isEqualTo("41");
    assertThat(applied.resourceVersion()).isEqualTo("42");
    int writes = api.patchCount;
    CasObservation replay = client.publishActiveProjection(client.observe(), "41", desired);

    assertThat(replay.outcome()).isEqualTo(Outcome.EXACT_REPLAY);
    assertThat(replay.priorResourceVersion()).isEqualTo("41");
    assertThat(replay.resourceVersion()).isEqualTo("42");
    assertThat(api.patchCount).isEqualTo(writes);
    assertThat(api.data).containsEntry("unrelated.txt", "retain");
  }

  @org.junit.jupiter.api.Test
  void activeMarkerDoesNotTreatExactDataAtUnchangedPriorVersionAsSuccessfulCas() {
    FakeKubernetesApi api = new FakeKubernetesApi();
    AccountJwtJwksConfigMapClient client = client(api);
    Map<String, String> desired =
        Map.of(
            AccountJwtJwksConfigMapClient.JWKS_DATA_KEY,
            JWKS_OLD,
            AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY,
            marker("active-operation"));
    api.data.putAll(desired);
    ConfigMapSnapshot observed = client.observe();

    assertThatThrownBy(() -> client.publishActiveProjection(observed, "41", desired))
        .isInstanceOf(CasConflictException.class);
    assertThat(api.patchCount).isZero();
    assertThat(api.resourceVersion).isEqualTo("41");
  }

  @org.junit.jupiter.api.Test
  void jwksConfigMapAccessHasNoSecretListCreateOrDeleteCapability() {
    assertThat(ApiCall.values())
        .noneMatch(
            call ->
                call.name().contains("SECRET")
                    || call.name().contains("CREATE")
                    || call.name().contains("DELETE"));
    assertThat(ApiCall.values())
        .filteredOn(call -> call.name().contains("JWKS_CONFIG_MAP"))
        .containsExactly(ApiCall.READ_JWKS_CONFIG_MAP, ApiCall.PATCH_JWKS_CONFIG_MAP);
    assertThat(ApiCall.values())
        .filteredOn(call -> call.name().contains("LIST"))
        .containsExactly(ApiCall.LIST_VALIDATOR_PODS);
  }

  private static AccountJwtJwksConfigMapClient client(FakeKubernetesApi api) {
    return new AccountJwtJwksConfigMapClient(api::beginOperation);
  }

  private static ParsedBinding parsedBinding() {
    return new ParsedBinding(
        "revision-1",
        "prod",
        "prod-cluster-1",
        URI.create("https://kubernetes.example:6443/"),
        "kubernetes.example",
        java.nio.file.Path.of("/etc/firemud/kubernetes-ca.pem"),
        "a".repeat(64),
        java.nio.file.Path.of("/var/run/secrets/firemud/kubernetes/token"),
        NAMESPACE,
        KUBE_SYSTEM_UID,
        TARGET_NAMESPACE_UID,
        API_USERNAME,
        "b".repeat(64));
  }

  private static Map<String, String> desired(String operation) {
    return Map.of(
        AccountJwtJwksConfigMapClient.JWKS_DATA_KEY,
        JWKS_NEW,
        AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY,
        marker(operation));
  }

  private static String marker(String operation) {
    return "{\"ownerOperation\":\"" + operation + "\"}";
  }

  private static String namespaceJson(String name, String uid) {
    return "{\"apiVersion\":\"v1\",\"kind\":\"Namespace\",\"metadata\":{\"name\":\""
        + name
        + "\",\"uid\":\""
        + uid
        + "\"},\"status\":{\"phase\":\"Active\"}}";
  }

  private static final class FakeKubernetesApi {
    private final List<ApiCall> calls = new ArrayList<>();
    private final Map<String, String> data =
        new LinkedHashMap<>(
            Map.of(
                AccountJwtJwksConfigMapClient.JWKS_DATA_KEY,
                JWKS_OLD,
                AccountJwtJwksConfigMapClient.GENERATION_DATA_KEY,
                "{\"ownerOperation\":\"old\"}",
                "unrelated.txt",
                "retain"));
    private String authenticatedUsername = API_USERNAME;
    private String kubeSystemUid = KUBE_SYSTEM_UID;
    private String targetNamespaceUid = TARGET_NAMESPACE_UID;
    private String configMapUid = CONFIG_MAP_UID;
    private String resourceVersion = "41";
    private String bearerToken = "initial-token";
    private String bearerFileKey = "initial-inode";
    private long bearerOwnerUid;
    private long bearerGroupId = 2000;
    private String bearerMode = "0440";
    private boolean resourceExists = true;
    private boolean oversizedConfigMapResponse;
    private boolean rejectPatchForChangedTrust;
    private boolean conflictNextPatch;
    private boolean loseNextPatchAcknowledgement;
    private boolean rotateBearerAfterSelfReview;
    private int patchCount;
    private String lastPatch;
    private Long acceptedBearerOwnerUid;
    private Long acceptedBearerGroupId;
    private String acceptedBearerMode;

    private ApiSession beginOperation() {
      if (acceptedBearerOwnerUid == null) {
        acceptedBearerOwnerUid = bearerOwnerUid;
        acceptedBearerGroupId = bearerGroupId;
        acceptedBearerMode = bearerMode;
      }
      if (acceptedBearerOwnerUid != bearerOwnerUid
          || acceptedBearerGroupId != bearerGroupId
          || !acceptedBearerMode.equals(bearerMode)) {
        throw new AccountJwtJwksApiBinding.BindingRejectedException();
      }
      return new FakeApiSession(this, bearerToken, bearerFileKey);
    }

    private ApiResponse send(ApiCall call, byte[] body) {
      calls.add(call);
      ApiResponse response =
          switch (call) {
            case REVIEW_AUTHENTICATED_PRINCIPAL ->
                response(
                    201,
                    "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\","
                        + "\"status\":{\"userInfo\":{\"username\":\""
                        + authenticatedUsername
                        + "\"}}}");
            case READ_KUBE_SYSTEM_NAMESPACE ->
                response(200, namespaceJson("kube-system", kubeSystemUid));
            case READ_TARGET_NAMESPACE ->
                response(200, namespaceJson(NAMESPACE, targetNamespaceUid));
            case READ_JWKS_CONFIG_MAP ->
                !resourceExists
                    ? response(404, "{}")
                    : oversizedConfigMapResponse
                        ? response(200, " ".repeat(1024 * 1024 + 1))
                        : response(200, configMapJson());
            case PATCH_JWKS_CONFIG_MAP -> patch(body);
            case READ_VALIDATOR_DEPLOYMENT, LIST_VALIDATOR_PODS, READ_VALIDATOR_REPLICA_SET ->
                throw new AssertionError("Unexpected validator call in ConfigMap client test");
          };
      if (call == ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL && rotateBearerAfterSelfReview) {
        rotateBearerAfterSelfReview = false;
        bearerToken = "rotated-during-operation";
        bearerFileKey = "rotated-during-operation-inode";
      }
      return response;
    }

    private ApiResponse patch(byte[] body) {
      if (rejectPatchForChangedTrust) {
        throw new AccountJwtJwksApiBinding.BindingRejectedException();
      }
      patchCount++;
      lastPatch = new String(body, StandardCharsets.UTF_8);
      if (conflictNextPatch) {
        conflictNextPatch = false;
        return response(409, "{}");
      }
      JsonNode patch;
      try {
        patch = JSON.readTree(lastPatch);
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
      JsonNode metadata = patch.get("metadata");
      if (!CONFIG_MAP_UID.equals(metadata.get("uid").asText())
          || !resourceVersion.equals(metadata.get("resourceVersion").asText())) {
        return response(409, "{}");
      }
      JsonNode patchData = patch.get("data");
      patchData.properties().forEach(entry -> data.put(entry.getKey(), entry.getValue().asText()));
      resourceVersion = Long.toString(Long.parseLong(resourceVersion) + 1);
      if (loseNextPatchAcknowledgement) {
        loseNextPatchAcknowledgement = false;
        throw new AccountJwtJwksApiBinding.ApiTransportException();
      }
      return response(200, configMapJson());
    }

    private String configMapJson() {
      try {
        Map<String, Object> metadata =
            Map.of(
                "name", AccountJwtJwksConfigMapClient.CONFIG_MAP_NAME,
                "namespace", NAMESPACE,
                "uid", configMapUid,
                "resourceVersion", resourceVersion);
        Map<String, Object> object =
            Map.of("apiVersion", "v1", "kind", "ConfigMap", "metadata", metadata, "data", data);
        return JSON.writeValueAsString(object);
      } catch (Exception ex) {
        throw new AssertionError(ex);
      }
    }

    private static ApiResponse response(int status, String body) {
      return new ApiResponse(status, body.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static final class FakeApiSession implements ApiSession {
    private final FakeKubernetesApi api;
    private final ParsedBinding binding = parsedBinding();
    private String bearerTokenSnapshot;
    private String bearerFileKeySnapshot;

    private FakeApiSession(FakeKubernetesApi api, String token, String fileKey) {
      this.api = api;
      this.bearerTokenSnapshot = token;
      this.bearerFileKeySnapshot = fileKey;
    }

    @Override
    public ParsedBinding binding() {
      return binding;
    }

    @Override
    public ApiResponse send(ApiCall call, byte[] body) {
      if (bearerTokenSnapshot == null
          || !bearerTokenSnapshot.equals(api.bearerToken)
          || !bearerFileKeySnapshot.equals(api.bearerFileKey)
          || api.acceptedBearerOwnerUid != api.bearerOwnerUid
          || api.acceptedBearerGroupId != api.bearerGroupId
          || !api.acceptedBearerMode.equals(api.bearerMode)) {
        throw new AccountJwtJwksApiBinding.BindingRejectedException();
      }
      return api.send(call, body);
    }

    @Override
    public void close() {
      bearerTokenSnapshot = null;
      bearerFileKeySnapshot = null;
    }
  }
}
