package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
      String provenance) {
    requireGeneration(sourceGeneration);
    requireGeneration(sourceObjectGeneration);
    requireDigest(spkiSha256, "SPKI fingerprint");
    if (source == null || source.getData() == null || source.getData().isEmpty()) {
      throw new IllegalArgumentException("validated source Secret is required");
    }
    Map<String, String> data = projectedData(role, source.getData());
    String revision = revisionForData(data);
    String name = targetName(plan, role);
    Secret existing = client.secrets().inNamespace(plan.runtimeNamespace()).withName(name).get();
    if (existing != null) {
      requireOwned(existing, plan.name(), role, "runtime projection Secret");
      Map<String, String> old = existing.getMetadata().getAnnotations();
      String oldRevision = value(old, HostedIdentityContract.REVISION_ANNOTATION);
      long oldGeneration = generation(old, HostedIdentityContract.SOURCE_GENERATION_ANNOTATION);
      long oldObjectGeneration =
          generation(old, HostedIdentityContract.SOURCE_OBJECT_GENERATION_ANNOTATION);
      String oldSpki = value(old, HostedIdentityContract.SPKI_SHA256_ANNOTATION);
      requireDigest(oldRevision, "runtime revision");
      requireDigest(oldSpki, "runtime SPKI fingerprint");
      if (revision.equals(oldRevision)) {
        if (sourceGeneration != oldGeneration
            || sourceObjectGeneration != oldObjectGeneration
            || !spkiSha256.equals(oldSpki)) {
          throw new IllegalStateException(
              "source identity changed without a material revision change");
        }
        return accepted(old, oldRevision, oldGeneration, oldObjectGeneration, oldSpki)
            ? ProjectionResult.synced(revision)
            : ProjectionResult.awaiting("awaiting-acceptance", revision);
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
      if (!preservePredecessor(client, plan, role, name, existing)) {
        return ProjectionResult.awaiting("predecessor-cas-conflict", oldRevision);
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
        client.secrets().inNamespace(plan.runtimeNamespace()).resource(candidate).create();
      } else {
        requireResourceVersion(existing);
        candidate.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
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
      String expectedSpki) {
    requireDigest(expectedRevision, "expected revision");
    requireGeneration(expectedGeneration);
    requireGeneration(expectedObjectGeneration);
    requireDigest(expectedSpki, "expected SPKI fingerprint");
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
    if (accepted(annotations, revision, sourceGeneration, sourceObjectGeneration, spki))
      return ProjectionResult.synced(revision);
    requireResourceVersion(current);
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

  private static boolean preservePredecessor(
      KubernetesClient client,
      EnvironmentIdentityPlan plan,
      String role,
      String targetName,
      Secret existing) {
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
    Secret prior = operation.get();
    try {
      if (prior == null) {
        client.secrets().inNamespace(plan.identityNamespace()).resource(predecessor).create();
      } else {
        requireOwned(prior, plan.name(), role, "predecessor Secret");
        requireResourceVersion(prior);
        predecessor.getMetadata().setResourceVersion(prior.getMetadata().getResourceVersion());
        client.secrets().inNamespace(plan.identityNamespace()).resource(predecessor).replace();
      }
      return true;
    } catch (KubernetesClientException exception) {
      if (exception.getCode() != 409) throw exception;
      return false;
    }
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
    } catch (Exception exception) {
      throw new IllegalStateException("unable to calculate material revision", exception);
    }
  }

  public static String revisionForRole(String role, Map<String, String> data) {
    return revisionForData(projectedData(role, data));
  }

  private static boolean accepted(
      Map<String, String> annotations,
      String revision,
      long generation,
      long objectGeneration,
      String spki) {
    return revision.equals(value(annotations, HostedIdentityContract.ACCEPTED_REVISION_ANNOTATION))
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

  private static void requireDigest(String value, String kind) {
    if (value == null || !value.matches("(?:sha256:)?[0-9a-f]{64}"))
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
