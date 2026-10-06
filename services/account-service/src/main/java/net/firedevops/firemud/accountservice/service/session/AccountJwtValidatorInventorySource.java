package net.firedevops.firemud.accountservice.service.session;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiOperation;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProtectedInventory;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ValidatorExpectation;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default-inactive reader for one protected expected validator inventory and its live Kubernetes
 * Deployment/Pod observations. The snapshot is non-authorizing evidence only; it does not prove
 * per-Pod token acceptance, readiness completeness, promotion eligibility, or convergence.
 */
public final class AccountJwtValidatorInventorySource {
  private static final int MAX_PAGES = 16;
  private static final int MAX_PODS_PER_VALIDATOR = 128;
  private static final int MAX_CANONICAL_SNAPSHOT_BYTES = 4 * 1024 * 1024;
  private static final String INVENTORY_DOMAIN = "firemud-account-validator-inventory/v1";
  private static final String SELF_REVIEW_REQUEST =
      "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\",\"spec\":{}}";
  private static final String RUNTIME_CONFIG_ENV = "FIREMUD_JWT_VERIFIER_CONFIG";
  private static final Pattern UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern RESOURCE_VERSION = Pattern.compile("[!-~]{1,256}");
  private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern POD_TEMPLATE_HASH = Pattern.compile("[a-z0-9]{5,20}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountJwtJwksApiBinding apiBinding;
  private final AccountJwtValidatorInventoryBinding inventoryBinding;
  private final Clock clock;

  /** No Spring stereotype or automatic activation is provided. */
  public AccountJwtValidatorInventorySource(
      AccountJwtJwksApiBinding apiBinding,
      AccountJwtValidatorInventoryBinding inventoryBinding,
      Clock clock) {
    this.apiBinding =
        Objects.requireNonNull(apiBinding, "protected Kubernetes API binding is required");
    this.inventoryBinding =
        Objects.requireNonNull(
            inventoryBinding, "protected validator inventory binding is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /**
   * Produces a fresh immutable observation. Any unavailable, changed, partial, ambiguous, legacy,
   * or unpinned state is returned as one redacted failure; no Kubernetes response body is retained.
   */
  public InventorySnapshot observe() {
    ProtectedInventory expected =
        inventoryBinding.current().orElseThrow(InventoryUnavailableException::new);
    try (ApiOperation operation = apiBinding.beginOperation()) {
      ParsedBinding api = operation.binding();
      if (!expected.matchesApiBinding(api)) {
        throw new InventoryUnavailableException();
      }
      verifyApiIdentity(operation, expected);
      List<ValidatorObservation> observations = new ArrayList<>();
      for (ValidatorExpectation validator : expected.validators()) {
        DeploymentObservation beforeDeployment = readDeployment(operation, expected, validator);
        List<PodObservation> beforePods =
            readPods(operation, expected, validator, beforeDeployment);
        List<ReplicaSetObservation> beforeReplicaSets =
            readReplicaSets(operation, expected, validator, beforeDeployment, beforePods);
        DeploymentObservation afterDeployment = readDeployment(operation, expected, validator);
        List<PodObservation> afterPods = readPods(operation, expected, validator, afterDeployment);
        List<ReplicaSetObservation> afterReplicaSets =
            readReplicaSets(operation, expected, validator, afterDeployment, afterPods);
        if (!beforeDeployment.equals(afterDeployment)
            || !beforePods.equals(afterPods)
            || !beforeReplicaSets.equals(afterReplicaSets)) {
          throw new InventoryUnavailableException();
        }
        observations.add(
            new ValidatorObservation(
                validator.validatorId(),
                beforeDeployment.name(),
                beforeDeployment.uid(),
                beforeDeployment.generation(),
                beforeDeployment.resourceVersion(),
                beforeDeployment.replicas(),
                beforeDeployment.image(),
                beforeDeployment.verifierConfigSha256(),
                validator.maxCacheAgeSeconds(),
                validator.profiles(),
                beforePods,
                beforeReplicaSets));
      }
      verifyApiIdentity(operation, expected);
      ProtectedInventory afterExpected =
          inventoryBinding.current().orElseThrow(InventoryUnavailableException::new);
      if (!expected.bindingDigest().equals(afterExpected.bindingDigest())
          || !expected.configRevision().equals(afterExpected.configRevision())) {
        throw new InventoryUnavailableException();
      }
      Instant observedAt = clock.instant();
      if (observedAt == null || observedAt.isBefore(Instant.EPOCH)) {
        throw new InventoryUnavailableException();
      }
      return createSnapshot(expected, observations, observedAt);
    } catch (InventoryUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      // Do not expose tokens, API bodies, file paths, parser details, or exception causes.
      throw new InventoryUnavailableException();
    }
  }

  private static void verifyApiIdentity(ApiOperation operation, ProtectedInventory expected) {
    ParsedBinding binding = operation.binding();
    if (!expected.matchesApiBinding(binding)) {
      throw new InventoryUnavailableException();
    }
    ApiResponse selfReview =
        operation.send(
            AccountJwtJwksApiBinding.ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL,
            SELF_REVIEW_REQUEST.getBytes(StandardCharsets.UTF_8));
    if (selfReview.statusCode() != 200 && selfReview.statusCode() != 201) {
      throw new InventoryUnavailableException();
    }
    JsonNode review = parseJson(selfReview.body());
    JsonNode status = review.get("status");
    JsonNode userInfo = status == null ? null : status.get("userInfo");
    JsonNode username = userInfo == null ? null : userInfo.get("username");
    JsonNode evaluationError = status == null ? null : status.get("evaluationError");
    if (!"authentication.k8s.io/v1".equals(text(review.get("apiVersion")))
        || !"SelfSubjectReview".equals(text(review.get("kind")))
        || !binding.expectedApiUsername().equals(text(username))
        || (evaluationError != null
            && !evaluationError.isNull()
            && (!evaluationError.isTextual() || !evaluationError.asText().isEmpty()))) {
      throw new InventoryUnavailableException();
    }
    verifyNamespace(
        operation.send(AccountJwtJwksApiBinding.ApiCall.READ_KUBE_SYSTEM_NAMESPACE, null),
        "kube-system",
        expected.expectedClusterIncarnationUid());
    verifyNamespace(
        operation.send(AccountJwtJwksApiBinding.ApiCall.READ_TARGET_NAMESPACE, null),
        expected.namespace(),
        expected.expectedNamespaceUid());
  }

  private static void verifyNamespace(ApiResponse response, String name, String expectedUid) {
    if (response.statusCode() != 200) {
      throw new InventoryUnavailableException();
    }
    JsonNode root = parseJson(response.body());
    JsonNode metadata = root.get("metadata");
    JsonNode status = root.get("status");
    if (!"v1".equals(text(root.get("apiVersion")))
        || !"Namespace".equals(text(root.get("kind")))
        || metadata == null
        || !name.equals(text(metadata.get("name")))
        || !expectedUid.equals(canonicalUid(text(metadata.get("uid"))))
        || metadata.hasNonNull("deletionTimestamp")
        || status == null
        || !"Active".equals(text(status.get("phase")))) {
      throw new InventoryUnavailableException();
    }
  }

  private static DeploymentObservation readDeployment(
      ApiOperation operation, ProtectedInventory inventory, ValidatorExpectation expected) {
    ApiResponse response = operation.readValidatorDeployment(inventory, expected);
    if (response.statusCode() != 200) {
      throw new InventoryUnavailableException();
    }
    JsonNode root = parseJson(response.body());
    JsonNode metadata = root.get("metadata");
    JsonNode spec = root.get("spec");
    JsonNode status = root.get("status");
    JsonNode selector = spec == null ? null : spec.get("selector");
    JsonNode template = spec == null ? null : spec.get("template");
    JsonNode templateMetadata = template == null ? null : template.get("metadata");
    JsonNode templateSpec = template == null ? null : template.get("spec");
    if (!"apps/v1".equals(text(root.get("apiVersion")))
        || !"Deployment".equals(text(root.get("kind")))
        || metadata == null
        || !expected.deploymentName().equals(text(metadata.get("name")))
        || !inventory.namespace().equals(text(metadata.get("namespace")))
        || !expected.deploymentUid().equals(canonicalUid(text(metadata.get("uid"))))
        || metadata.hasNonNull("deletionTimestamp")
        || spec == null
        || status == null
        || selector == null
        || !selectorMatches(selector.get("matchLabels"), expected.selector())
        || (selector.has("matchExpressions")
            && selector.get("matchExpressions") != null
            && !selector.get("matchExpressions").isNull()
            && (!selector.get("matchExpressions").isArray()
                || !selector.get("matchExpressions").isEmpty()))
        || templateMetadata == null
        || !labelsMatch(templateMetadata.get("labels"), expected.selector())
        || templateSpec == null) {
      throw new InventoryUnavailableException();
    }
    String resourceVersion = resourceVersion(text(metadata.get("resourceVersion")));
    long generation = positiveLong(metadata.get("generation"));
    int replicas = exactInt(spec.get("replicas"), 1, 128);
    if (replicas != expected.replicas()
        || positiveLong(status.get("observedGeneration")) != generation
        || exactInt(status.get("readyReplicas"), 0, 128) != replicas
        || exactInt(status.get("updatedReplicas"), 0, 128) != replicas
        || exactInt(status.get("availableReplicas"), 0, 128) != replicas
        || (status.has("unavailableReplicas")
            && !status.get("unavailableReplicas").isNull()
            && exactInt(status.get("unavailableReplicas"), 0, 128) != 0)) {
      throw new InventoryUnavailableException();
    }
    ContainerConfig container =
        findVerifiedContainer(
            templateSpec.get("containers"), expected, templateSpec.get("volumes"));
    return new DeploymentObservation(
        expected.deploymentName(),
        expected.deploymentUid(),
        resourceVersion,
        generation,
        replicas,
        container.image(),
        container.runtimeConfigSha256());
  }

  private static List<PodObservation> readPods(
      ApiOperation operation,
      ProtectedInventory inventory,
      ValidatorExpectation expected,
      DeploymentObservation deployment) {
    String continuation = null;
    String listResourceVersion = null;
    int pageCount = 0;
    List<PodObservation> pods = new ArrayList<>();
    Set<String> podUids = new LinkedHashSet<>();
    Set<String> podNames = new LinkedHashSet<>();
    do {
      if (++pageCount > MAX_PAGES) {
        throw new InventoryUnavailableException();
      }
      ApiResponse response = operation.listValidatorPods(inventory, expected, continuation);
      if (response.statusCode() != 200) {
        throw new InventoryUnavailableException();
      }
      JsonNode root = parseJson(response.body());
      JsonNode metadata = root.get("metadata");
      JsonNode items = root.get("items");
      if (!"v1".equals(text(root.get("apiVersion")))
          || !"PodList".equals(text(root.get("kind")))
          || metadata == null
          || items == null
          || !items.isArray()) {
        throw new InventoryUnavailableException();
      }
      String pageVersion = resourceVersion(text(metadata.get("resourceVersion")));
      if (listResourceVersion == null) {
        listResourceVersion = pageVersion;
      } else if (!listResourceVersion.equals(pageVersion)) {
        throw new InventoryUnavailableException();
      }
      for (JsonNode item : items) {
        if (pods.size() >= MAX_PODS_PER_VALIDATOR) {
          throw new InventoryUnavailableException();
        }
        PodObservation pod = parsePod(item, inventory.namespace(), expected, deployment);
        if (!podUids.add(pod.uid()) || !podNames.add(pod.name())) {
          throw new InventoryUnavailableException();
        }
        pods.add(pod);
      }
      JsonNode continueNode = metadata.get("continue");
      if (continueNode == null || continueNode.isNull() || !continueNode.isTextual()) {
        continuation = null;
      } else {
        continuation = continueNode.asText();
        if (continuation.isEmpty()) {
          continuation = null;
        } else if (continuation.length() > 2048) {
          throw new InventoryUnavailableException();
        }
      }
    } while (continuation != null);
    if (pods.size() != expected.replicas()) {
      throw new InventoryUnavailableException();
    }
    pods.sort(Comparator.comparing(PodObservation::uid));
    return List.copyOf(pods);
  }

  private static PodObservation parsePod(
      JsonNode pod,
      String namespace,
      ValidatorExpectation expected,
      DeploymentObservation deployment) {
    JsonNode metadata = pod == null ? null : pod.get("metadata");
    JsonNode spec = pod == null ? null : pod.get("spec");
    JsonNode status = pod == null ? null : pod.get("status");
    if (!"v1".equals(text(pod.get("apiVersion")))
        || !"Pod".equals(text(pod.get("kind")))
        || metadata == null
        || spec == null
        || status == null
        || !namespace.equals(text(metadata.get("namespace")))
        || metadata.hasNonNull("deletionTimestamp")
        || !labelsMatch(metadata.get("labels"), expected.selector())
        || !"Running".equals(text(status.get("phase")))) {
      throw new InventoryUnavailableException();
    }
    String name = dnsLabel(text(metadata.get("name")));
    String uid = canonicalUid(text(metadata.get("uid")));
    String resourceVersion = resourceVersion(text(metadata.get("resourceVersion")));
    String templateHash = podTemplateHash(metadata.get("labels"));
    JsonNode owners = metadata.get("ownerReferences");
    if (owners == null || !owners.isArray() || owners.size() != 1) {
      throw new InventoryUnavailableException();
    }
    JsonNode owner = owners.get(0);
    if (!"apps/v1".equals(text(owner.get("apiVersion")))
        || !"ReplicaSet".equals(text(owner.get("kind")))
        || !Boolean.TRUE.equals(booleanValue(owner.get("controller")))) {
      throw new InventoryUnavailableException();
    }
    String ownerName = dnsLabel(text(owner.get("name")));
    String ownerUid = canonicalUid(text(owner.get("uid")));
    ContainerConfig container =
        findVerifiedContainer(spec.get("containers"), expected, spec.get("volumes"));
    if (!deployment.image().equals(container.image())
        || !deployment.verifierConfigSha256().equals(container.runtimeConfigSha256())) {
      throw new InventoryUnavailableException();
    }
    verifyReadyCondition(status.get("conditions"));
    verifyContainerReady(status.get("containerStatuses"), expected, deployment.image());
    return new PodObservation(
        name,
        uid,
        resourceVersion,
        ownerName,
        ownerUid,
        templateHash,
        deployment.image(),
        deployment.verifierConfigSha256());
  }

  private static List<ReplicaSetObservation> readReplicaSets(
      ApiOperation operation,
      ProtectedInventory inventory,
      ValidatorExpectation expected,
      DeploymentObservation deployment,
      List<PodObservation> pods) {
    Map<String, String> observedOwners = new LinkedHashMap<>();
    Map<String, String> templateHashes = new LinkedHashMap<>();
    for (PodObservation pod : pods) {
      String prior = observedOwners.putIfAbsent(pod.ownerName(), pod.ownerUid());
      String priorHash = templateHashes.putIfAbsent(pod.ownerName(), pod.podTemplateHash());
      if ((prior != null && !prior.equals(pod.ownerUid()))
          || (priorHash != null && !priorHash.equals(pod.podTemplateHash()))) {
        throw new InventoryUnavailableException();
      }
    }
    List<ReplicaSetObservation> observations = new ArrayList<>(observedOwners.size());
    for (Map.Entry<String, String> owner : observedOwners.entrySet()) {
      ApiResponse response = operation.readValidatorReplicaSet(inventory, expected, owner.getKey());
      if (response.statusCode() != 200) {
        throw new InventoryUnavailableException();
      }
      observations.add(
          parseReplicaSet(
              response.body(),
              inventory.namespace(),
              expected,
              deployment,
              owner.getKey(),
              owner.getValue(),
              templateHashes.get(owner.getKey())));
    }
    observations.sort(Comparator.comparing(ReplicaSetObservation::uid));
    return List.copyOf(observations);
  }

  private static ReplicaSetObservation parseReplicaSet(
      byte[] responseBody,
      String namespace,
      ValidatorExpectation expected,
      DeploymentObservation deployment,
      String expectedName,
      String expectedUid,
      String expectedTemplateHash) {
    JsonNode root = parseJson(responseBody);
    JsonNode metadata = root.get("metadata");
    JsonNode spec = root.get("spec");
    JsonNode status = root.get("status");
    JsonNode owners = metadata == null ? null : metadata.get("ownerReferences");
    JsonNode selector = spec == null ? null : spec.get("selector");
    JsonNode template = spec == null ? null : spec.get("template");
    JsonNode templateMetadata = template == null ? null : template.get("metadata");
    JsonNode templateSpec = template == null ? null : template.get("spec");
    if (!"apps/v1".equals(text(root.get("apiVersion")))
        || !"ReplicaSet".equals(text(root.get("kind")))
        || metadata == null
        || !expectedName.equals(text(metadata.get("name")))
        || !namespace.equals(text(metadata.get("namespace")))
        || !expectedUid.equals(canonicalUid(text(metadata.get("uid"))))
        || metadata.hasNonNull("deletionTimestamp")
        || owners == null
        || !owners.isArray()
        || owners.size() != 1
        || spec == null
        || status == null
        || selector == null
        || !replicaSetSelectorMatches(
            selector.get("matchLabels"), expected.selector(), expectedTemplateHash)
        || (selector.has("matchExpressions")
            && selector.get("matchExpressions") != null
            && !selector.get("matchExpressions").isNull()
            && (!selector.get("matchExpressions").isArray()
                || !selector.get("matchExpressions").isEmpty()))
        || templateMetadata == null
        || !labelsMatch(templateMetadata.get("labels"), expected.selector())
        || !expectedTemplateHash.equals(
            text(templateMetadata.get("labels").get("pod-template-hash")))
        || templateSpec == null) {
      throw new InventoryUnavailableException();
    }
    JsonNode owner = owners.get(0);
    if (!"apps/v1".equals(text(owner.get("apiVersion")))
        || !"Deployment".equals(text(owner.get("kind")))
        || !deployment.name().equals(text(owner.get("name")))
        || !deployment.uid().equals(canonicalUid(text(owner.get("uid"))))
        || !Boolean.TRUE.equals(booleanValue(owner.get("controller")))) {
      throw new InventoryUnavailableException();
    }
    String resourceVersion = resourceVersion(text(metadata.get("resourceVersion")));
    long generation = positiveLong(metadata.get("generation"));
    int replicas = exactInt(spec.get("replicas"), 1, 128);
    if (positiveLong(status.get("observedGeneration")) != generation
        || exactInt(status.get("readyReplicas"), 0, 128) != replicas
        || replicas != expected.replicas()) {
      throw new InventoryUnavailableException();
    }
    ContainerConfig container =
        findVerifiedContainer(
            templateSpec.get("containers"), expected, templateSpec.get("volumes"));
    if (!deployment.image().equals(container.image())
        || !deployment.verifierConfigSha256().equals(container.runtimeConfigSha256())) {
      throw new InventoryUnavailableException();
    }
    return new ReplicaSetObservation(
        expectedName,
        expectedUid,
        resourceVersion,
        generation,
        replicas,
        deployment.name(),
        deployment.uid(),
        expectedTemplateHash,
        container.image(),
        container.runtimeConfigSha256());
  }

  private static boolean replicaSetSelectorMatches(
      JsonNode actual, Map<String, String> expected, String expectedTemplateHash) {
    if (actual == null
        || !actual.isObject()
        || expectedTemplateHash == null
        || actual.size() != expected.size() + 1
        || !expectedTemplateHash.equals(text(actual.get("pod-template-hash")))) {
      return false;
    }
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      if (!entry.getValue().equals(text(actual.get(entry.getKey())))) {
        return false;
      }
    }
    return true;
  }

  private static String podTemplateHash(JsonNode labels) {
    if (labels == null || !labels.isObject()) {
      throw new InventoryUnavailableException();
    }
    String value = text(labels.get("pod-template-hash"));
    if (value == null || !POD_TEMPLATE_HASH.matcher(value).matches()) {
      throw new InventoryUnavailableException();
    }
    return value;
  }

  private static ContainerConfig findVerifiedContainer(
      JsonNode containers, ValidatorExpectation expected, JsonNode volumes) {
    if (containers == null
        || !containers.isArray()
        || containers.isEmpty()
        || containers.size() > 32) {
      throw new InventoryUnavailableException();
    }
    JsonNode selected = null;
    for (JsonNode container : containers) {
      String name = text(container.get("name"));
      rejectSigningMaterialEnvironment(container.get("env"));
      rejectSigningSecretMounts(container.get("volumeMounts"));
      if (expected.containerName().equals(name)) {
        if (selected != null) {
          throw new InventoryUnavailableException();
        }
        selected = container;
      }
    }
    rejectSigningSecretVolumes(volumes);
    if (selected == null || !expected.image().equals(text(selected.get("image")))) {
      throw new InventoryUnavailableException();
    }
    JsonNode env = selected.get("env");
    JsonNode configNode = null;
    Set<String> environmentNames = new LinkedHashSet<>();
    if (env != null && !env.isNull()) {
      if (!env.isArray() || env.size() > 256) {
        throw new InventoryUnavailableException();
      }
      for (JsonNode variable : env) {
        String envName = text(variable.get("name"));
        if (envName == null || !environmentNames.add(envName)) {
          throw new InventoryUnavailableException();
        }
        if (RUNTIME_CONFIG_ENV.equals(envName)) {
          if (variable.has("valueFrom")
              || !expected.canonicalRuntimeConfig().equals(text(variable.get("value")))) {
            throw new InventoryUnavailableException();
          }
          configNode = variable;
        }
        if ("SPRING_APPLICATION_JSON".equals(envName)
            || "JAVA_TOOL_OPTIONS".equals(envName)
            || "JDK_JAVA_OPTIONS".equals(envName)) {
          throw new InventoryUnavailableException();
        }
      }
    }
    if (configNode == null) {
      throw new InventoryUnavailableException();
    }
    return new ContainerConfig(
        expected.image(),
        sha256(expected.canonicalRuntimeConfig().getBytes(StandardCharsets.UTF_8)));
  }

  private static void rejectSigningMaterialEnvironment(JsonNode env) {
    if (env == null || env.isNull()) {
      return;
    }
    if (!env.isArray()) {
      throw new InventoryUnavailableException();
    }
    for (JsonNode variable : env) {
      String name = text(variable.get("name"));
      if (name != null
          && (name.equals("FIREMUD_AUTH_JWT_SECRET")
              || name.equals("FIREMUD_AUTH_JWT_SECRET_PATH")
              || name.contains("JWT_HMAC")
              || name.contains("JWT_SIGNING_KEY"))) {
        throw new InventoryUnavailableException();
      }
      JsonNode valueFrom = variable.get("valueFrom");
      JsonNode secretKeyRef = valueFrom == null ? null : valueFrom.get("secretKeyRef");
      if (secretKeyRef != null && !secretKeyRef.isNull()) {
        String secretName = text(secretKeyRef.get("name"));
        if ("jwt-signing-keys".equals(secretName)) {
          throw new InventoryUnavailableException();
        }
      }
    }
  }

  private static void rejectSigningSecretMounts(JsonNode mounts) {
    if (mounts == null || mounts.isNull()) {
      return;
    }
    if (!mounts.isArray()) {
      throw new InventoryUnavailableException();
    }
    for (JsonNode mount : mounts) {
      String name = text(mount.get("name"));
      String path = text(mount.get("mountPath"));
      if ("jwt-signing-keys".equals(name)
          || (path != null && path.startsWith("/var/run/secrets/firemud/jwt"))) {
        throw new InventoryUnavailableException();
      }
    }
  }

  private static void rejectSigningSecretVolumes(JsonNode volumes) {
    if (volumes == null || volumes.isNull()) {
      return;
    }
    if (!volumes.isArray()) {
      throw new InventoryUnavailableException();
    }
    for (JsonNode volume : volumes) {
      String name = text(volume.get("name"));
      JsonNode secret = volume.get("secret");
      if ("jwt-signing-keys".equals(name)
          || (secret != null
              && !secret.isNull()
              && "jwt-signing-keys".equals(text(secret.get("secretName"))))) {
        throw new InventoryUnavailableException();
      }
    }
  }

  private static void verifyReadyCondition(JsonNode conditions) {
    if (conditions == null || !conditions.isArray() || conditions.size() > 64) {
      throw new InventoryUnavailableException();
    }
    boolean ready = false;
    for (JsonNode condition : conditions) {
      if ("Ready".equals(text(condition.get("type")))) {
        if (ready || !"True".equals(text(condition.get("status")))) {
          throw new InventoryUnavailableException();
        }
        ready = true;
      }
    }
    if (!ready) {
      throw new InventoryUnavailableException();
    }
  }

  private static void verifyContainerReady(
      JsonNode statuses, ValidatorExpectation expected, String expectedImage) {
    if (statuses == null || !statuses.isArray() || statuses.isEmpty() || statuses.size() > 32) {
      throw new InventoryUnavailableException();
    }
    JsonNode found = null;
    for (JsonNode status : statuses) {
      if (expected.containerName().equals(text(status.get("name")))) {
        if (found != null) {
          throw new InventoryUnavailableException();
        }
        found = status;
      }
    }
    String imageId = found == null ? null : text(found.get("imageID"));
    String digest = imageDigest(imageId);
    String expectedDigest = expected.image().substring(expected.image().lastIndexOf('@') + 1);
    if (found == null
        || !Boolean.TRUE.equals(booleanValue(found.get("ready")))
        || !expectedDigest.equals(digest)) {
      throw new InventoryUnavailableException();
    }
  }

  private static InventorySnapshot createSnapshot(
      ProtectedInventory expected, List<ValidatorObservation> validators, Instant observedAt) {
    List<ValidatorObservation> sorted =
        validators.stream()
            .sorted(Comparator.comparing(ValidatorObservation::validatorId))
            .toList();
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("domain", INVENTORY_DOMAIN);
    preimage.put("observedAt", observedAt.toString());
    preimage.put("environmentId", expected.environmentId());
    preimage.put("clusterId", expected.clusterId());
    preimage.put("clusterIncarnationUid", expected.expectedClusterIncarnationUid());
    preimage.put("namespace", expected.namespace());
    preimage.put("namespaceUid", expected.expectedNamespaceUid());
    preimage.put("apiBindingRevision", expected.apiBindingRevision());
    preimage.put("apiBindingDigest", expected.apiBindingDigest());
    preimage.put("inventoryBindingRevision", expected.configRevision());
    preimage.put("inventoryBindingDigest", expected.bindingDigest());
    preimage.put(
        "validators",
        sorted.stream().map(AccountJwtValidatorInventorySource::validatorMap).toList());
    byte[] canonicalSnapshot = canonicalBytes(preimage);
    if (canonicalSnapshot.length > MAX_CANONICAL_SNAPSHOT_BYTES) {
      throw new InventoryUnavailableException();
    }
    String digest = sha256(canonicalSnapshot);
    return new InventorySnapshot(
        observedAt,
        expected.environmentId(),
        expected.clusterId(),
        expected.expectedClusterIncarnationUid(),
        expected.namespace(),
        expected.expectedNamespaceUid(),
        expected.apiBindingRevision(),
        expected.apiBindingDigest(),
        expected.configRevision(),
        expected.bindingDigest(),
        sorted,
        canonicalSnapshot,
        digest);
  }

  private static Map<String, Object> validatorMap(ValidatorObservation validator) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("validatorId", validator.validatorId());
    result.put("deploymentName", validator.deploymentName());
    result.put("deploymentUid", validator.deploymentUid());
    result.put("deploymentGeneration", Long.toString(validator.deploymentGeneration()));
    result.put("deploymentResourceVersion", validator.deploymentResourceVersion());
    result.put("replicas", Integer.toString(validator.replicas()));
    result.put("image", validator.image());
    result.put("verifierConfigSha256", validator.verifierConfigSha256());
    result.put("maxCacheAgeSeconds", Integer.toString(validator.maxCacheAgeSeconds()));
    result.put(
        "profiles",
        validator.profiles().stream()
            .map(
                profile ->
                    Map.of("tokenProfile", profile.tokenProfile(), "audience", profile.audience()))
            .toList());
    result.put(
        "pods", validator.pods().stream().map(AccountJwtValidatorInventorySource::podMap).toList());
    result.put(
        "replicaSets",
        validator.replicaSets().stream()
            .map(AccountJwtValidatorInventorySource::replicaSetMap)
            .toList());
    return result;
  }

  private static Map<String, Object> replicaSetMap(ReplicaSetObservation replicaSet) {
    return Map.of(
        "name", replicaSet.name(),
        "uid", replicaSet.uid(),
        "resourceVersion", replicaSet.resourceVersion(),
        "generation", Long.toString(replicaSet.generation()),
        "replicas", Integer.toString(replicaSet.replicas()),
        "ownerDeploymentName", replicaSet.ownerDeploymentName(),
        "ownerDeploymentUid", replicaSet.ownerDeploymentUid(),
        "podTemplateHash", replicaSet.podTemplateHash(),
        "image", replicaSet.image(),
        "verifierConfigSha256", replicaSet.verifierConfigSha256());
  }

  private static Map<String, Object> podMap(PodObservation pod) {
    return Map.of(
        "name", pod.name(),
        "uid", pod.uid(),
        "resourceVersion", pod.resourceVersion(),
        "ownerReplicaSetName", pod.ownerName(),
        "ownerReplicaSetUid", pod.ownerUid(),
        "podTemplateHash", pod.podTemplateHash(),
        "image", pod.image(),
        "verifierConfigSha256", pod.verifierConfigSha256());
  }

  private static boolean selectorMatches(JsonNode actual, Map<String, String> expected) {
    if (actual == null || !actual.isObject() || actual.size() != expected.size()) {
      return false;
    }
    return labelsMatch(actual, expected);
  }

  private static boolean labelsMatch(JsonNode labels, Map<String, String> expected) {
    if (labels == null || !labels.isObject()) {
      return false;
    }
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      if (!entry.getValue().equals(text(labels.get(entry.getKey())))) {
        return false;
      }
    }
    return true;
  }

  private static JsonNode parseJson(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024) {
      throw new InventoryUnavailableException();
    }
    try {
      JsonNode parsed = JSON.readTree(decodeUtf8(bytes));
      if (parsed == null || !parsed.isObject()) {
        throw new InventoryUnavailableException();
      }
      return parsed;
    } catch (InventoryUnavailableException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new InventoryUnavailableException();
    }
  }

  private static byte[] canonicalBytes(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception failure) {
      throw new InventoryUnavailableException();
    }
  }

  private static String imageDigest(String imageId) {
    if (imageId == null) {
      throw new InventoryUnavailableException();
    }
    int marker = imageId.lastIndexOf("sha256:");
    if (marker < 0) {
      throw new InventoryUnavailableException();
    }
    String digest = imageId.substring(marker);
    if (!IMAGE_DIGEST.matcher(digest).matches()) {
      throw new InventoryUnavailableException();
    }
    return digest;
  }

  private static String canonicalUid(String value) {
    if (value == null || !UID.matcher(value).matches() || value.startsWith("00000000-")) {
      throw new InventoryUnavailableException();
    }
    return value;
  }

  private static String resourceVersion(String value) {
    if (value == null || !RESOURCE_VERSION.matcher(value).matches()) {
      throw new InventoryUnavailableException();
    }
    return value;
  }

  private static String dnsLabel(String value) {
    if (value == null || !value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw new InventoryUnavailableException();
    }
    return value;
  }

  private static long positiveLong(JsonNode node) {
    long value = exactLong(node, 1L, Long.MAX_VALUE);
    return value;
  }

  private static int exactInt(JsonNode node, int minimum, int maximum) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new InventoryUnavailableException();
    }
    long value = node.longValue();
    if (value < minimum || value > maximum) {
      throw new InventoryUnavailableException();
    }
    return (int) value;
  }

  private static long exactLong(JsonNode node, long minimum, long maximum) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new InventoryUnavailableException();
    }
    long value = node.longValue();
    if (value < minimum || value > maximum) {
      throw new InventoryUnavailableException();
    }
    return value;
  }

  private static Boolean booleanValue(JsonNode node) {
    return node != null && node.isBoolean() ? node.booleanValue() : null;
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
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
      throw new InventoryUnavailableException();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new InventoryUnavailableException();
    }
  }

  private record ContainerConfig(String image, String runtimeConfigSha256) {}

  private record DeploymentObservation(
      String name,
      String uid,
      String resourceVersion,
      long generation,
      int replicas,
      String image,
      String verifierConfigSha256) {}

  public record ReplicaSetObservation(
      String name,
      String uid,
      String resourceVersion,
      long generation,
      int replicas,
      String ownerDeploymentName,
      String ownerDeploymentUid,
      String podTemplateHash,
      String image,
      String verifierConfigSha256) {}

  /** No Pod or Kubernetes API body is retained, only closed non-secret identity fields. */
  public record PodObservation(
      String name,
      String uid,
      String resourceVersion,
      String ownerName,
      String ownerUid,
      String podTemplateHash,
      String image,
      String verifierConfigSha256) {}

  public record ValidatorObservation(
      String validatorId,
      String deploymentName,
      String deploymentUid,
      long deploymentGeneration,
      String deploymentResourceVersion,
      int replicas,
      String image,
      String verifierConfigSha256,
      int maxCacheAgeSeconds,
      List<ProfileExpectation> profiles,
      List<PodObservation> pods,
      List<ReplicaSetObservation> replicaSets) {
    public ValidatorObservation {
      profiles = List.copyOf(profiles);
      pods = List.copyOf(pods);
      replicaSets = List.copyOf(replicaSets);
    }
  }

  public static final class InventorySnapshot {
    private final Instant observedAt;
    private final String environmentId;
    private final String clusterId;
    private final String clusterIncarnationUid;
    private final String namespace;
    private final String namespaceUid;
    private final String apiBindingRevision;
    private final String apiBindingDigest;
    private final String inventoryBindingRevision;
    private final String inventoryBindingDigest;
    private final List<ValidatorObservation> validators;
    private final byte[] canonicalBytes;
    private final String digest;

    private InventorySnapshot(
        Instant observedAt,
        String environmentId,
        String clusterId,
        String clusterIncarnationUid,
        String namespace,
        String namespaceUid,
        String apiBindingRevision,
        String apiBindingDigest,
        String inventoryBindingRevision,
        String inventoryBindingDigest,
        List<ValidatorObservation> validators,
        byte[] canonicalBytes,
        String digest) {
      this.observedAt = Objects.requireNonNull(observedAt);
      this.environmentId = Objects.requireNonNull(environmentId);
      this.clusterId = Objects.requireNonNull(clusterId);
      this.clusterIncarnationUid = Objects.requireNonNull(clusterIncarnationUid);
      this.namespace = Objects.requireNonNull(namespace);
      this.namespaceUid = Objects.requireNonNull(namespaceUid);
      this.apiBindingRevision = Objects.requireNonNull(apiBindingRevision);
      this.apiBindingDigest = Objects.requireNonNull(apiBindingDigest);
      this.inventoryBindingRevision = Objects.requireNonNull(inventoryBindingRevision);
      this.inventoryBindingDigest = Objects.requireNonNull(inventoryBindingDigest);
      this.validators = List.copyOf(validators);
      this.canonicalBytes = Objects.requireNonNull(canonicalBytes).clone();
      this.digest = Objects.requireNonNull(digest);
      if (this.canonicalBytes.length == 0
          || this.canonicalBytes.length > MAX_CANONICAL_SNAPSHOT_BYTES
          || !sha256(this.canonicalBytes).equals(digest)) {
        throw new InventoryUnavailableException();
      }
    }

    public Instant observedAt() {
      return observedAt;
    }

    public String environmentId() {
      return environmentId;
    }

    public String clusterId() {
      return clusterId;
    }

    public String clusterIncarnationUid() {
      return clusterIncarnationUid;
    }

    public String namespace() {
      return namespace;
    }

    public String namespaceUid() {
      return namespaceUid;
    }

    public String apiBindingRevision() {
      return apiBindingRevision;
    }

    public String apiBindingDigest() {
      return apiBindingDigest;
    }

    public String inventoryBindingRevision() {
      return inventoryBindingRevision;
    }

    public String inventoryBindingDigest() {
      return inventoryBindingDigest;
    }

    public List<ValidatorObservation> validators() {
      return validators;
    }

    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    public String digest() {
      return digest;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof InventorySnapshot that)) return false;
      return observedAt.equals(that.observedAt)
          && environmentId.equals(that.environmentId)
          && clusterId.equals(that.clusterId)
          && clusterIncarnationUid.equals(that.clusterIncarnationUid)
          && namespace.equals(that.namespace)
          && namespaceUid.equals(that.namespaceUid)
          && apiBindingRevision.equals(that.apiBindingRevision)
          && apiBindingDigest.equals(that.apiBindingDigest)
          && inventoryBindingRevision.equals(that.inventoryBindingRevision)
          && inventoryBindingDigest.equals(that.inventoryBindingDigest)
          && validators.equals(that.validators)
          && java.util.Arrays.equals(canonicalBytes, that.canonicalBytes)
          && digest.equals(that.digest);
    }

    @Override
    public int hashCode() {
      return 31
              * Objects.hash(
                  observedAt,
                  environmentId,
                  clusterId,
                  clusterIncarnationUid,
                  namespace,
                  namespaceUid,
                  apiBindingRevision,
                  apiBindingDigest,
                  inventoryBindingRevision,
                  inventoryBindingDigest,
                  validators,
                  digest)
          + java.util.Arrays.hashCode(canonicalBytes);
    }
  }

  public static final class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException() {
      super("Account validator inventory is unavailable or inconsistent");
    }
  }
}
