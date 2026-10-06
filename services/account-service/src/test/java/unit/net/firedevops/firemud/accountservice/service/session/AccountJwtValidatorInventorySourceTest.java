package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiCall;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiOperation;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProtectedInventory;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ValidatorExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventoryUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtValidatorInventorySourceTest {
  private static final String ENVIRONMENT = "test";
  private static final String CLUSTER = "cluster-test";
  private static final String NAMESPACE = "firemud-test";
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String DEPLOYMENT_UID = "33333333-3333-4333-8333-333333333333";
  private static final String REPLICA_SET_UID = "44444444-4444-4444-8444-444444444444";
  private static final String POD_UID = "55555555-5555-4555-8555-555555555555";
  private static final String POD_TEMPLATE_HASH = "abcde12345";
  private static final String API_DIGEST = "a".repeat(64);
  private static final String IMAGE_DIGEST = "d".repeat(64);
  private static final String IMAGE = "registry.example/firemud/player@sha256:" + IMAGE_DIGEST;
  private static final String EXPECTED_USERNAME =
      "system:serviceaccount:" + NAMESPACE + ":account-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void defaultBindingsRemainInactiveAndUnavailable() {
    AccountJwtValidatorInventorySource source =
        new AccountJwtValidatorInventorySource(
            new AccountJwtJwksApiBinding(), new AccountJwtValidatorInventoryBinding(), CLOCK);

    assertThatThrownBy(source::observe)
        .isInstanceOf(InventoryUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void returnsOnlyStableActualPodAndDeploymentIdentitiesAsNonAuthorizingEvidence()
      throws Exception {
    Fixture fixture = new Fixture();

    InventorySnapshot snapshot = fixture.source.observe();

    assertThat(snapshot.environmentId()).isEqualTo(ENVIRONMENT);
    assertThat(snapshot.clusterIncarnationUid()).isEqualTo(CLUSTER_UID);
    assertThat(snapshot.namespaceUid()).isEqualTo(NAMESPACE_UID);
    assertThat(snapshot.validators()).hasSize(1);
    assertThat(snapshot.validators().get(0).deploymentUid()).isEqualTo(DEPLOYMENT_UID);
    assertThat(snapshot.validators().get(0).pods())
        .extracting(AccountJwtValidatorInventorySource.PodObservation::uid)
        .containsExactly(POD_UID);
    assertThat(snapshot.validators().get(0).pods().get(0).ownerUid()).isEqualTo(REPLICA_SET_UID);
    assertThat(snapshot.validators().get(0).replicaSets())
        .singleElement()
        .satisfies(
            replicaSet -> {
              assertThat(replicaSet.ownerDeploymentName()).isEqualTo("player-service");
              assertThat(replicaSet.ownerDeploymentUid()).isEqualTo(DEPLOYMENT_UID);
            });
    assertThat(snapshot.digest()).matches("[0-9a-f]{64}");
    assertThat(new String(snapshot.canonicalBytes(), StandardCharsets.UTF_8))
        .contains("ownerDeploymentUid", DEPLOYMENT_UID, "podTemplateHash", POD_TEMPLATE_HASH);
  }

  @Test
  void deniesUnexpectedAuthenticatedPrincipalAndNamespaceIncarnation() throws Exception {
    Fixture wrongPrincipal = new Fixture();
    wrongPrincipal.stubSelfReview("system:serviceaccount:" + NAMESPACE + ":other");
    assertUnavailable(wrongPrincipal);
    verify(wrongPrincipal.operation, times(0))
        .readValidatorDeployment(same(wrongPrincipal.inventory), same(wrongPrincipal.validator));

    Fixture wrongNamespace = new Fixture();
    wrongNamespace.stubTargetNamespace("99999999-9999-4999-8999-999999999999");
    assertUnavailable(wrongNamespace);
    verify(wrongNamespace.operation, times(0)).readValidatorDeployment(any(), any());
  }

  @Test
  void deniesLegacyHmacRuntimeAndPartialDeploymentRollout() throws Exception {
    Fixture legacy = new Fixture();
    legacy.stubDeployment(new ApiResponse(200, deploymentJson("10", 1, 1, 1, true)));
    assertUnavailable(legacy);

    Fixture partial = new Fixture();
    partial.stubDeployment(new ApiResponse(200, deploymentJson("10", 1, 1, 0, false)));
    assertUnavailable(partial);
  }

  @Test
  void deniesADeploymentThatChangesBetweenTheTwoInventoryReads() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.operation.readValidatorDeployment(
            same(fixture.inventory), same(fixture.validator)))
        .thenReturn(
            new ApiResponse(200, deploymentJson("10", 1, 1, 1, false)),
            new ApiResponse(200, deploymentJson("11", 2, 2, 1, false)));

    assertUnavailable(fixture);
  }

  @Test
  void deniesReplicaSetOwnedByDifferentDeploymentEvenWhenPodOwnerMatches() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.operation.readValidatorReplicaSet(
            same(fixture.inventory), same(fixture.validator), eq("player-service-abcde")))
        .thenReturn(
            new ApiResponse(
                200,
                replicaSetJson(
                    "40",
                    "44444444-4444-4444-8444-444444444444",
                    "other-service",
                    "99999999-9999-4999-8999-999999999999")));

    assertUnavailable(fixture);
  }

  @Test
  void deniesReplicaSetUidReplacementOrOwnershipChangeBetweenInventoryReads() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.operation.readValidatorReplicaSet(
            same(fixture.inventory), same(fixture.validator), eq("player-service-abcde")))
        .thenReturn(
            new ApiResponse(
                200, replicaSetJson("40", REPLICA_SET_UID, "player-service", DEPLOYMENT_UID)),
            new ApiResponse(
                200,
                replicaSetJson(
                    "41",
                    "66666666-6666-4666-8666-666666666666",
                    "player-service",
                    DEPLOYMENT_UID)));

    assertUnavailable(fixture);
  }

  @Test
  void deniesADeploymentPodListWhosePaginationNeverCompletes() throws Exception {
    Fixture fixture = new Fixture();
    byte[] unfinishedPage =
        jsonBytes(
            Map.of(
                "apiVersion",
                "v1",
                "kind",
                "PodList",
                "metadata",
                Map.of("resourceVersion", "20", "continue", "next-page"),
                "items",
                List.of()));
    when(fixture.operation.listValidatorPods(
            same(fixture.inventory), same(fixture.validator), any()))
        .thenReturn(new ApiResponse(200, unfinishedPage));

    assertUnavailable(fixture);

    verify(fixture.operation, times(16))
        .listValidatorPods(same(fixture.inventory), same(fixture.validator), any());
  }

  private static void assertUnavailable(Fixture fixture) {
    assertThatThrownBy(fixture.source::observe)
        .isInstanceOf(InventoryUnavailableException.class)
        .hasNoCause();
  }

  private static byte[] deploymentJson(
      String resourceVersion,
      int generation,
      int observedGeneration,
      int readyReplicas,
      boolean legacyHmac)
      throws Exception {
    List<Map<String, Object>> env = new ArrayList<>();
    env.add(Map.of("name", "FIREMUD_JWT_VERIFIER_CONFIG", "value", runtimeConfig()));
    if (legacyHmac) {
      env.add(Map.of("name", "FIREMUD_AUTH_JWT_SECRET", "value", "not-retained"));
    }
    Map<String, Object> container =
        Map.of("name", "player-service", "image", IMAGE, "env", env, "volumeMounts", List.of());
    Map<String, Object> template =
        Map.of(
            "metadata", Map.of("labels", Map.of("app", "player-service")),
            "spec", Map.of("containers", List.of(container), "volumes", List.of()));
    Map<String, Object> spec =
        Map.of(
            "replicas",
            1,
            "selector",
            Map.of("matchLabels", Map.of("app", "player-service")),
            "template",
            template);
    Map<String, Object> status =
        Map.of(
            "observedGeneration",
            observedGeneration,
            "readyReplicas",
            readyReplicas,
            "updatedReplicas",
            1,
            "availableReplicas",
            1);
    return jsonBytes(
        Map.of(
            "apiVersion",
            "apps/v1",
            "kind",
            "Deployment",
            "metadata",
            Map.of(
                "name", "player-service",
                "namespace", NAMESPACE,
                "uid", DEPLOYMENT_UID,
                "resourceVersion", resourceVersion,
                "generation", generation),
            "spec",
            spec,
            "status",
            status));
  }

  private static byte[] podListJson() throws Exception {
    Map<String, Object> container =
        Map.of(
            "name",
            "player-service",
            "image",
            IMAGE,
            "env",
            List.of(Map.of("name", "FIREMUD_JWT_VERIFIER_CONFIG", "value", runtimeConfig())),
            "volumeMounts",
            List.of());
    Map<String, Object> pod =
        Map.of(
            "apiVersion", "v1",
            "kind", "Pod",
            "metadata",
                Map.of(
                    "name",
                    "player-service-abcde-12345",
                    "namespace",
                    NAMESPACE,
                    "uid",
                    POD_UID,
                    "resourceVersion",
                    "30",
                    "labels",
                    Map.of("app", "player-service", "pod-template-hash", POD_TEMPLATE_HASH),
                    "ownerReferences",
                    List.of(
                        Map.of(
                            "apiVersion", "apps/v1",
                            "kind", "ReplicaSet",
                            "name", "player-service-abcde",
                            "uid", REPLICA_SET_UID,
                            "controller", true))),
            "spec", Map.of("containers", List.of(container), "volumes", List.of()),
            "status",
                Map.of(
                    "phase", "Running",
                    "conditions", List.of(Map.of("type", "Ready", "status", "True")),
                    "containerStatuses",
                        List.of(
                            Map.of(
                                "name",
                                "player-service",
                                "ready",
                                true,
                                "imageID",
                                "docker-pullable://registry.example/player@sha256:"
                                    + IMAGE_DIGEST))));
    return jsonBytes(
        Map.of(
            "apiVersion",
            "v1",
            "kind",
            "PodList",
            "metadata",
            Map.of("resourceVersion", "20", "continue", ""),
            "items",
            List.of(pod)));
  }

  private static byte[] replicaSetJson(
      String resourceVersion, String uid, String ownerName, String ownerUid) throws Exception {
    Map<String, Object> container =
        Map.of(
            "name",
            "player-service",
            "image",
            IMAGE,
            "env",
            List.of(Map.of("name", "FIREMUD_JWT_VERIFIER_CONFIG", "value", runtimeConfig())),
            "volumeMounts",
            List.of());
    return jsonBytes(
        Map.of(
            "apiVersion", "apps/v1",
            "kind", "ReplicaSet",
            "metadata",
                Map.of(
                    "name",
                    "player-service-abcde",
                    "namespace",
                    NAMESPACE,
                    "uid",
                    uid,
                    "resourceVersion",
                    resourceVersion,
                    "generation",
                    1,
                    "ownerReferences",
                    List.of(
                        Map.of(
                            "apiVersion",
                            "apps/v1",
                            "kind",
                            "Deployment",
                            "name",
                            ownerName,
                            "uid",
                            ownerUid,
                            "controller",
                            true))),
            "spec",
                Map.of(
                    "replicas", 1,
                    "selector",
                        Map.of(
                            "matchLabels",
                            Map.of(
                                "app", "player-service", "pod-template-hash", POD_TEMPLATE_HASH)),
                    "template",
                        Map.of(
                            "metadata",
                                Map.of(
                                    "labels",
                                    Map.of(
                                        "app",
                                        "player-service",
                                        "pod-template-hash",
                                        POD_TEMPLATE_HASH)),
                            "spec",
                                Map.of("containers", List.of(container), "volumes", List.of()))),
            "status", Map.of("observedGeneration", 1, "readyReplicas", 1)));
  }

  private static String runtimeConfig() {
    return AccountJwtValidatorInventoryBinding.parseProtectedBytes(validInventoryBytes())
        .validators()
        .get(0)
        .canonicalRuntimeConfig();
  }

  private static byte[] validInventoryBytes() {
    Map<String, Object> validator =
        Map.of(
            "validatorId",
            "player-service",
            "deploymentName",
            "player-service",
            "deploymentUid",
            DEPLOYMENT_UID,
            "selector",
            Map.of("app", "player-service"),
            "replicas",
            1,
            "containerName",
            "player-service",
            "image",
            IMAGE,
            "jwksUri",
            "https://account-api.test/.well-known/jwks.json",
            "maxCacheAgeSeconds",
            60,
            "profiles",
            List.of(
                Map.of(
                    "tokenProfile", "game-session-account-delegation",
                    "audience", "account-service")));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("version", "account-jwt-validator-inventory-binding/v1");
    root.put("enabled", "true");
    root.put("configRevision", "inventory-r1");
    root.put("environmentId", ENVIRONMENT);
    root.put("clusterId", CLUSTER);
    root.put("namespace", NAMESPACE);
    root.put("expectedClusterIncarnationUid", CLUSTER_UID);
    root.put("expectedNamespaceUid", NAMESPACE_UID);
    root.put("apiBindingRevision", "api-r1");
    root.put("apiBindingDigest", API_DIGEST);
    root.put("validators", List.of(validator));
    root.put("bindingDigest", "0".repeat(64));
    try {
      var unsigned = JSON.readTree(JSON.writeValueAsBytes(root));
      root.put("bindingDigest", AccountJwtValidatorInventoryBinding.computeBindingDigest(unsigned));
      return JSON.writeValueAsBytes(root);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static ParsedBinding apiBinding() {
    return new ParsedBinding(
        "api-r1",
        ENVIRONMENT,
        CLUSTER,
        URI.create("https://kubernetes.test:6443"),
        "kubernetes.test",
        Path.of("/etc/firemud/account-jwt-api/serving-ca.pem"),
        "c".repeat(64),
        Path.of("/var/run/secrets/firemud/account-jwt-api-token/token"),
        NAMESPACE,
        CLUSTER_UID,
        NAMESPACE_UID,
        EXPECTED_USERNAME,
        API_DIGEST);
  }

  private static byte[] jsonBytes(Object value) {
    try {
      return JSON.writeValueAsBytes(value);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static byte[] namespaceJson(String namespace, String uid) {
    return jsonBytes(
        Map.of(
            "apiVersion",
            "v1",
            "kind",
            "Namespace",
            "metadata",
            Map.of("name", namespace, "uid", uid),
            "status",
            Map.of("phase", "Active")));
  }

  private static byte[] selfReviewJson(String username) {
    return jsonBytes(
        Map.of(
            "apiVersion", "authentication.k8s.io/v1",
            "kind", "SelfSubjectReview",
            "status", Map.of("userInfo", Map.of("username", username))));
  }

  private static final class Fixture {
    private final AccountJwtJwksApiBinding apiBinding = mock(AccountJwtJwksApiBinding.class);
    private final AccountJwtValidatorInventoryBinding inventoryBinding =
        mock(AccountJwtValidatorInventoryBinding.class);
    private final ApiOperation operation = mock(ApiOperation.class);
    private final ProtectedInventory inventory =
        AccountJwtValidatorInventoryBinding.parseProtectedBytes(validInventoryBytes());
    private final ValidatorExpectation validator = inventory.validators().get(0);
    private final AccountJwtValidatorInventorySource source;

    private Fixture() throws Exception {
      when(apiBinding.beginOperation()).thenReturn(operation);
      when(operation.binding()).thenReturn(apiBinding());
      when(inventoryBinding.current()).thenReturn(Optional.of(inventory));
      when(operation.send(eq(ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL), any(byte[].class)))
          .thenReturn(new ApiResponse(201, selfReviewJson(EXPECTED_USERNAME)));
      when(operation.send(ApiCall.READ_KUBE_SYSTEM_NAMESPACE, null))
          .thenReturn(new ApiResponse(200, namespaceJson("kube-system", CLUSTER_UID)));
      when(operation.send(ApiCall.READ_TARGET_NAMESPACE, null))
          .thenReturn(new ApiResponse(200, namespaceJson(NAMESPACE, NAMESPACE_UID)));
      ApiResponse deployment = new ApiResponse(200, deploymentJson("10", 1, 1, 1, false));
      ApiResponse pods = new ApiResponse(200, podListJson());
      when(operation.readValidatorDeployment(same(inventory), same(validator)))
          .thenReturn(deployment);
      when(operation.listValidatorPods(same(inventory), same(validator), isNull()))
          .thenReturn(pods);
      when(operation.readValidatorReplicaSet(
              same(inventory), same(validator), eq("player-service-abcde")))
          .thenReturn(
              new ApiResponse(
                  200, replicaSetJson("40", REPLICA_SET_UID, "player-service", DEPLOYMENT_UID)));
      source = new AccountJwtValidatorInventorySource(apiBinding, inventoryBinding, CLOCK);
    }

    private void stubSelfReview(String username) {
      when(operation.send(eq(ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL), any(byte[].class)))
          .thenReturn(new ApiResponse(201, selfReviewJson(username)));
    }

    private void stubTargetNamespace(String uid) {
      when(operation.send(ApiCall.READ_TARGET_NAMESPACE, null))
          .thenReturn(new ApiResponse(200, namespaceJson(NAMESPACE, uid)));
    }

    private void stubDeployment(ApiResponse response) {
      when(operation.readValidatorDeployment(same(inventory), same(validator)))
          .thenReturn(response);
    }
  }
}
