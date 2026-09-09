package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Copies validated material through predecessor-first resourceVersion CAS transitions. */
@Component
public class SecretProjectionService {
  public ProjectionResult project(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      Secret source,
      long sourceGeneration,
      long sourceObjectGeneration,
      String spkiSha256,
      String provenance,
      Supplier<Boolean> runtimeProfileCurrent) {
    requireGuard(runtimeProfileCurrent);
    requireGeneration(sourceGeneration);
    requireGeneration(sourceObjectGeneration);
    requireFingerprint(spkiSha256, "SPKI fingerprint");
    if (provenance == null || provenance.isBlank()) {
      throw new IllegalArgumentException("projection provenance is required");
    }
    if (source == null || source.getData() == null || source.getData().isEmpty()) {
      throw new IllegalArgumentException("validated source Secret is required");
    }
    Map<String, String> data = projectedData(role, source.getData());
    String revision = revisionForData(data);
    String name = targetName(plan, role);
    if (!guardPassed(runtimeProfileCurrent)) {
      return guardFailed(revision);
    }
    var operation = client.secrets().inNamespace(plan.runtimeNamespace()).withName(name);
    Secret existing = operation.get();
    if (existing != null) {
      requireOwned(existing, plan.name(), role, "runtime projection Secret");
      Map<String, String> old = existing.getMetadata().getAnnotations();
      String oldRevision = value(old, HostedIdentityContract.REVISION_ANNOTATION);
      long oldGeneration = generation(old, HostedIdentityContract.SOURCE_GENERATION_ANNOTATION);
      long oldObjectGeneration =
          generation(old, HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION);
      String oldSpki = value(old, HostedIdentityContract.SPKI_SHA256_ANNOTATION);
      requireRevision(oldRevision, "runtime revision");
      requireFingerprint(oldSpki, "runtime SPKI fingerprint");
      if (revision.equals(oldRevision)) {
        if (sourceGeneration != oldGeneration
            || sourceObjectGeneration != oldObjectGeneration
            || !spkiSha256.equals(oldSpki)) {
          throw new IllegalStateException(
              "source identity changed without a material revision change");
        }
        if (data.equals(existing.getData())
            && Objects.equals(source.getType(), existing.getType())) {
          return accepted(old, oldRevision, oldGeneration, oldObjectGeneration, oldSpki)
              ? ProjectionResult.synced(revision)
              : ProjectionResult.awaiting("awaiting-acceptance", revision);
        }
      } else {
        if (!materialMatchesRevision(existing, oldRevision)) {
          throw new IllegalStateException(
              "runtime projection revision does not match its material");
        }
        if (!accepted(old, oldRevision, oldGeneration, oldObjectGeneration, oldSpki)) {
          return ProjectionResult.awaiting("predecessor-not-accepted", oldRevision);
        }
        validateAdvancement(
            sourceGeneration,
            sourceObjectGeneration,
            spkiSha256,
            oldGeneration,
            oldObjectGeneration,
            oldSpki);
        PreservationResult preservation =
            preservePredecessor(client, plan, role, name, existing, runtimeProfileCurrent);
        if (preservation == PreservationResult.GUARD_FAILED) {
          return guardFailed(oldRevision);
        }
        if (preservation == PreservationResult.CAS_CONFLICT) {
          return ProjectionResult.awaiting("predecessor-cas-conflict", oldRevision);
        }
      }
    }
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put(HostedIdentityContract.REVISION_ANNOTATION, revision);
    annotations.put(
        HostedIdentityContract.SOURCE_GENERATION_ANNOTATION, Long.toString(sourceGeneration));
    annotations.put(
        HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION,
        Long.toString(sourceObjectGeneration));
    annotations.put(HostedIdentityContract.SPKI_SHA256_ANNOTATION, spkiSha256);
    annotations.put(HostedIdentityContract.PROVENANCE_ANNOTATION, provenance);
    annotations.put(HostedIdentityContract.DIGEST_ANNOTATION, revision);
    annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "pending");
    if (existing != null) {
      carryAcceptedSnapshot(existing.getMetadata().getAnnotations(), annotations);
    }
    Secret candidate =
        new SecretBuilder()
            .withMetadata(
                new ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(plan.runtimeNamespace())
                    .withLabels(HostedIdentityContract.managedLabels(plan.name(), role))
                    .withAnnotations(annotations)
                    .build())
            .withType(source.getType())
            .withData(data)
            .build();
    try {
      if (existing == null) {
        if (!guardPassed(runtimeProfileCurrent)) {
          return guardFailed(revision);
        }
        client.secrets().inNamespace(plan.runtimeNamespace()).resource(candidate).create();
      } else {
        requireResourceVersion(existing);
        candidate.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
        if (!guardPassed(runtimeProfileCurrent)) {
          return guardFailed(revision);
        }
        client.secrets().inNamespace(plan.runtimeNamespace()).resource(candidate).replace();
      }
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 409) throw exception;
      return ProjectionResult.awaiting("projection-cas-conflict", revision);
    }
    return ProjectionResult.awaiting("projected", revision);
  }

  public ProjectionResult acknowledge(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      String expectedRevision,
      long expectedGeneration,
      long expectedObjectGeneration,
      String expectedSpki,
      Supplier<Boolean> runtimeProfileCurrent) {
    requireGuard(runtimeProfileCurrent);
    requireRevision(expectedRevision, "expected revision");
    requireGeneration(expectedGeneration);
    requireGeneration(expectedObjectGeneration);
    requireFingerprint(expectedSpki, "expected SPKI fingerprint");
    if (!guardPassed(runtimeProfileCurrent)) {
      return guardFailed(expectedRevision);
    }
    var operation =
        client.secrets().inNamespace(plan.runtimeNamespace()).withName(targetName(plan, role));
    Secret current = operation.get();
    requireOwned(current, plan.name(), role, "runtime projection Secret");
    Map<String, String> annotations = current.getMetadata().getAnnotations();
    String revision = value(annotations, HostedIdentityContract.REVISION_ANNOTATION);
    long sourceGeneration =
        generation(annotations, HostedIdentityContract.SOURCE_GENERATION_ANNOTATION);
    long sourceObjectGeneration =
        generation(annotations, HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION);
    String spki = value(annotations, HostedIdentityContract.SPKI_SHA256_ANNOTATION);
    if (!expectedRevision.equals(revision)
        || expectedGeneration != sourceGeneration
        || expectedObjectGeneration != sourceObjectGeneration
        || !expectedSpki.equals(spki)) {
      return ProjectionResult.awaiting("projection-tuple-changed", revision);
    }
    if (!materialMatchesRevision(current, expectedRevision)) {
      return ProjectionResult.awaiting("projection-material-changed", revision);
    }
    if (accepted(annotations, revision, sourceGeneration, sourceObjectGeneration, spki))
      return ProjectionResult.synced(revision);
    requireResourceVersion(current);
    if (!guardPassed(runtimeProfileCurrent)) {
      return guardFailed(revision);
    }
    Map<String, String> updated = new LinkedHashMap<>(annotations);
    updated.put(HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION, revision);
    updated.put(
        HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION,
        Long.toString(sourceGeneration));
    updated.put(
        HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION,
        Long.toString(sourceObjectGeneration));
    updated.put(HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION, spki);
    updated.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "accepted");
    current.getMetadata().setAnnotations(updated);
    try {
      if (!guardPassed(runtimeProfileCurrent)) {
        return guardFailed(revision);
      }
      operation.replace(current);
      return ProjectionResult.synced(revision);
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 409) throw exception;
      return ProjectionResult.awaiting("acceptance-cas-conflict", revision);
    }
  }

  static void validateAdvancement(
      long candidateGeneration,
      long candidateObjectGeneration,
      String candidateSpki,
      long acceptedGeneration,
      long acceptedObjectGeneration,
      String acceptedSpki) {
    if (candidateGeneration <= acceptedGeneration) {
      throw new IllegalStateException("source issuance generation did not advance monotonically");
    }
    if (candidateObjectGeneration < acceptedObjectGeneration) {
      throw new IllegalStateException("source object generation rolled back");
    }
    if (candidateSpki.equals(acceptedSpki)) {
      throw new IllegalStateException("replacement certificate reused the accepted public key");
    }
  }

  private static PreservationResult preservePredecessor(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      String targetName,
      Secret existing,
      Supplier<Boolean> runtimeProfileCurrent) {
    if (!guardPassed(runtimeProfileCurrent)) {
      return PreservationResult.GUARD_FAILED;
    }
    String name = targetName + "-previous";
    Map<String, String> annotations = new LinkedHashMap<>(existing.getMetadata().getAnnotations());
    annotations.put(HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION, "predecessor");
    Secret predecessor =
        new SecretBuilder()
            .withMetadata(
                new ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(plan.identityNamespace())
                    .withLabels(HostedIdentityContract.managedLabels(plan.name(), role))
                    .withAnnotations(annotations)
                    .build())
            .withType(existing.getType())
            .withData(existing.getData())
            .build();
    var operation = client.secrets().inNamespace(plan.identityNamespace()).withName(name);
    if (!guardPassed(runtimeProfileCurrent)) {
      return PreservationResult.GUARD_FAILED;
    }
    Secret prior = operation.get();
    try {
      if (prior == null) {
        if (!guardPassed(runtimeProfileCurrent)) {
          return PreservationResult.GUARD_FAILED;
        }
        client.secrets().inNamespace(plan.identityNamespace()).resource(predecessor).create();
      } else {
        requireOwned(prior, plan.name(), role, "predecessor Secret");
        requireResourceVersion(prior);
        predecessor.getMetadata().setResourceVersion(prior.getMetadata().getResourceVersion());
        if (!guardPassed(runtimeProfileCurrent)) {
          return PreservationResult.GUARD_FAILED;
        }
        client.secrets().inNamespace(plan.identityNamespace()).resource(predecessor).replace();
      }
      return PreservationResult.PRESERVED;
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 409) throw exception;
      return PreservationResult.CAS_CONFLICT;
    }
  }

  private static ProjectionResult guardFailed(String revision) {
    return ProjectionResult.awaiting("runtime-profile-changed", revision);
  }

  private static void requireGuard(Supplier<Boolean> runtimeProfileCurrent) {
    if (runtimeProfileCurrent == null) {
      throw new IllegalArgumentException("runtime profile guard is required");
    }
  }

  private static boolean guardPassed(Supplier<Boolean> runtimeProfileCurrent) {
    return Boolean.TRUE.equals(runtimeProfileCurrent.get());
  }

  private enum PreservationResult {
    PRESERVED,
    CAS_CONFLICT,
    GUARD_FAILED
  }

  private static Map<String, String> projectedData(String role, Map<String, String> sourceData) {
    Map<String, String> result = new LinkedHashMap<>(sourceData);
    if (HostedIdentityContract.GRPC_ROLE.equals(role)) {
      if (!result.containsKey("client.crt") && result.containsKey("tls.crt"))
        result.put("client.crt", result.get("tls.crt"));
      if (!result.containsKey("client.key") && result.containsKey("tls.key"))
        result.put("client.key", result.get("tls.key"));
    }
    return result;
  }

  public String revisionFor(Map<String, String> data) {
    return revisionForData(data);
  }

  public static String revisionForData(Map<String, String> data) {
    if (data == null) {
      throw new IllegalArgumentException("material data is required");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      data.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Base64.getDecoder().decode(entry.getValue()));
                digest.update((byte) 0);
              });
      StringBuilder result = new StringBuilder("sha256:");
      for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("unable to calculate material revision", exception);
    }
  }

  public static String revisionForRole(String role, Map<String, String> data) {
    return revisionForData(projectedData(role, data));
  }

  private static boolean materialMatchesRevision(Secret secret, String revision) {
    try {
      return revision.equals(revisionForData(secret.getData()));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean accepted(
      Map<String, String> annotations,
      String revision,
      long generation,
      long objectGeneration,
      String spki) {
    return "accepted"
            .equals(value(annotations, HostedIdentityContract.CONVERGENCE_STATE_ANNOTATION))
        && revision.equals(value(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION))
        && Long.toString(generation)
            .equals(
                value(annotations, HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION))
        && Long.toString(objectGeneration)
            .equals(
                value(
                    annotations,
                    HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION))
        && spki.equals(value(annotations, HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION));
  }

  private static void carryAcceptedSnapshot(
      Map<String, String> existing, Map<String, String> candidate) {
    var acceptedKeys =
        java.util.List.of(
            HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION,
            HostedIdentityContract.ACCEPTED_SOURCE_GENERATION_ANNOTATION,
            HostedIdentityContract.ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION,
            HostedIdentityContract.ACCEPTED_SPKI_SHA256_ANNOTATION);
    boolean anyAcceptedField =
        existing != null && acceptedKeys.stream().anyMatch(existing::containsKey);
    if (!anyAcceptedField) {
      return;
    }
    for (String key : acceptedKeys) {
      String value = value(existing, key);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("accepted projection snapshot is incomplete");
      }
      candidate.put(key, value);
    }
  }

  private static long generation(Map<String, String> annotations, String key) {
    try {
      long result = Long.parseLong(value(annotations, key));
      requireGeneration(result);
      return result;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("projection has no valid source generation", exception);
    }
  }

  private static void requireGeneration(long value) {
    if (value < 1) throw new IllegalArgumentException("source generation must be positive");
  }

  private static void requireRevision(String value, String kind) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalStateException(kind + " is invalid");
  }

  private static void requireFingerprint(String value, String kind) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalStateException(kind + " is invalid");
  }

  private static void requireResourceVersion(Secret secret) {
    if (secret.getMetadata().getResourceVersion() == null
        || secret.getMetadata().getResourceVersion().isBlank()) {
      throw new IllegalStateException("Secret has no resourceVersion for CAS");
    }
  }

  private static void requireOwned(Secret secret, String environment, String role, String kind) {
    if (secret == null || secret.getMetadata() == null)
      throw new IllegalStateException(kind + " is absent or has no metadata");
    if (!owned(secret, environment, role)) {
      throw new IllegalStateException(kind + " is not controller-owned");
    }
  }

  static boolean owned(Secret secret, String environment, String role) {
    if (secret == null || secret.getMetadata() == null) return false;
    Map<String, String> labels = secret.getMetadata().getLabels();
    return labels != null
        && HostedIdentityContract.CONTROLLER_NAME.equals(
            labels.get(HostedIdentityContract.MANAGED_BY_LABEL))
        && environment.equals(labels.get(HostedIdentityContract.ENVIRONMENT_LABEL))
        && role.equals(labels.get(HostedIdentityContract.ROLE_LABEL))
        && HostedIdentityContract.RETAINED.equals(
            labels.get(HostedIdentityContract.RETENTION_LABEL));
  }

  private static String targetName(EnvironmentIdentityPlan plan, String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> plan.ingressSecretName();
      case HostedIdentityContract.TELNET_ROLE -> plan.telnetSecretName();
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> plan.gatewayInternalWsSecretName();
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> plan.tcpProxyBridgeSecretName();
      case HostedIdentityContract.GRPC_ROLE -> plan.grpcSecretName();
      default -> throw new IllegalArgumentException("unsupported identity role: " + role);
    };
  }

  private static String value(Map<String, String> annotations, String key) {
    return annotations == null ? null : annotations.get(key);
  }

  public record ProjectionResult(String state, String revision) {
    public static ProjectionResult synced(String revision) {
      return new ProjectionResult("synced", revision);
    }

    public static ProjectionResult awaiting(String state, String revision) {
      return new ProjectionResult(state, revision);
    }

    public boolean isSynced() {
      return "synced".equals(state);
    }
  }
}
