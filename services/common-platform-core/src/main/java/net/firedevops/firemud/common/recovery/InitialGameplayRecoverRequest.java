package net.firedevops.firemud.common.recovery;

import java.text.Normalizer;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;

/**
 * Closed initial-recover selectors, never proof of ownership, authorization, a lock or a fence.
 * Account redemption and the receiving owner's live association/storage checks remain mandatory.
 * This is not ADR 0048's reservation or post-authorization execution tuple.
 */
public record InitialGameplayRecoverRequest(
    UUID controlPlaneRequestId,
    Scope scope,
    Target target,
    Mutation mutation,
    AuditReason auditReason) {
  public static final String ACTION_FAMILY_SCHEMA_ID = "initial-gameplay-recover/v1";
  public static final String ACTION_FAMILY_SCHEMA_VERSION = "1";
  public static final String TARGET_OWNER = "recovery-controller-service";

  public InitialGameplayRecoverRequest {
    controlPlaneRequestId = uuid(controlPlaneRequestId);
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(mutation, "mutation");
    Objects.requireNonNull(auditReason, "auditReason");
    if (!scope.targetNamespace().equals(target.peerStorageBinding().targetNamespace())) {
      throw new IllegalArgumentException("Peer/storage namespace differs from scope");
    }
  }

  public record Scope(
      UUID canonicalTenantId, String targetNamespace, UUID playableStateNamespaceId) {
    public Scope {
      canonicalTenantId = uuid(canonicalTenantId);
      targetNamespace = namespace(targetNamespace);
      playableStateNamespaceId = uuid(playableStateNamespaceId);
    }
  }

  public record Target(
      LaunchAssociation launchAssociation,
      PeerStorageBinding peerStorageBinding,
      String evidenceRef,
      String evidenceDigest) {
    public Target {
      Objects.requireNonNull(launchAssociation, "launchAssociation");
      Objects.requireNonNull(peerStorageBinding, "peerStorageBinding");
      evidenceRef = text(evidenceRef, 512);
      if (evidenceRef.codePoints().anyMatch(InitialGameplayRecoverRequest::isNoncharacter)) {
        throw new IllegalArgumentException(
            "Evidence reference must not contain Unicode noncharacters");
      }
      evidenceDigest = digest(evidenceDigest);
    }
  }

  /** Exact selectors for a Game Session owner read, not a caller-issued association attestation. */
  public record LaunchAssociation(
      UUID gameInstanceUuid,
      UUID tenantAssociationOperationId,
      String worldSlug,
      UUID launchControlPlaneRequestId,
      String launchDescriptorId,
      String descriptorRequestDigest,
      String descriptorResultDigest,
      String releaseAttestationEvidenceDigest) {
    public LaunchAssociation {
      gameInstanceUuid = uuid(gameInstanceUuid);
      tenantAssociationOperationId = uuid(tenantAssociationOperationId);
      worldSlug = text(worldSlug, 128);
      launchControlPlaneRequestId = uuid(launchControlPlaneRequestId);
      launchDescriptorId = text(launchDescriptorId, 256);
      descriptorRequestDigest = digest(descriptorRequestDigest);
      descriptorResultDigest = digest(descriptorResultDigest);
      releaseAttestationEvidenceDigest = digest(releaseAttestationEvidenceDigest);
    }
  }

  /** Complete requested binding; the owner must independently authenticate/revalidate it. */
  public record PeerStorageBinding(
      String targetNamespace,
      String clusterIdentity,
      String peerWorkloadIdentity,
      String storageIdentity,
      String storageRevision,
      String bindingDigest) {
    public PeerStorageBinding {
      targetNamespace = namespace(targetNamespace);
      clusterIdentity = journalSelector(clusterIdentity, 256);
      peerWorkloadIdentity = text(peerWorkloadIdentity, 256);
      if (!peerWorkloadIdentity.equals(
          "spiffe://firemud/ns/" + targetNamespace + "/sa/game-session-service")) {
        throw new IllegalArgumentException("Expected same-namespace Game Session peer selector");
      }
      storageIdentity = journalSelector(storageIdentity, 256);
      storageRevision = journalSelector(storageRevision, 128);
      bindingDigest = digest(bindingDigest);
    }
  }

  public enum MaintenanceLockPresence {
    RESULT_ONLY
  }

  /** No lock credential can be supplied to this action, including through an optional arm. */
  public record Mutation(UUID operationId, MaintenanceLockPresence maintenanceLockPresence) {
    public Mutation {
      operationId = uuid(operationId);
      Objects.requireNonNull(maintenanceLockPresence, "maintenanceLockPresence");
    }

    public String verb() {
      return "recover";
    }

    public String workflow() {
      return "initial-gameplay-release";
    }
  }

  public enum AuditReasonCode {
    INITIAL_GAMEPLAY_RELEASE
  }

  public record AuditReason(AuditReasonCode code, String detail) {
    public AuditReason {
      Objects.requireNonNull(code, "code");
      detail = text(detail, 1024);
    }
  }

  static UUID uuid(UUID value) {
    Objects.requireNonNull(value, "UUID");
    if (value.equals(new UUID(0, 0))) throw new IllegalArgumentException("UUID must be non-nil");
    return value;
  }

  static UUID parseUuid(String value) {
    if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("UUID must be canonical lowercase text");
    }
    return uuid(UUID.fromString(value));
  }

  static String text(String value, int limit) {
    Objects.requireNonNull(value, "text");
    if (GameTenantCreationDigest.utf8ByteLength(value) > limit) {
      throw new IllegalArgumentException("Raw text exceeds family limit");
    }
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    if (isUnicodeWhitespaceOnly(normalized)
        || hasUnicodeWhitespaceBoundary(normalized)
        || normalized.codePoints().anyMatch(Character::isISOControl)
        || GameTenantCreationDigest.utf8ByteLength(normalized) > limit) {
      throw new IllegalArgumentException("Text must be bounded nonblank Unicode without controls");
    }
    return normalized;
  }

  private static String journalSelector(String value, int limit) {
    String normalized = text(value, limit);
    if (!normalized.equals(value)
        || value.codePoints().anyMatch(InitialGameplayRecoverRequest::isNoncharacter)) {
      throw new IllegalArgumentException(
          "Peer/storage selectors must be in NFC and contain no Unicode noncharacters");
    }
    return value;
  }

  private static boolean isNoncharacter(int codePoint) {
    return (codePoint >= 0xfdd0 && codePoint <= 0xfdef) || (codePoint & 0xffff) >= 0xfffe;
  }

  private static boolean isUnicodeWhitespaceOnly(String value) {
    return value.codePoints().allMatch(InitialGameplayRecoverRequest::isUnicodeWhitespace);
  }

  private static boolean hasUnicodeWhitespaceBoundary(String value) {
    return isUnicodeWhitespace(value.codePointAt(0))
        || isUnicodeWhitespace(value.codePointBefore(value.length()));
  }

  private static boolean isUnicodeWhitespace(int codePoint) {
    // Java splits Unicode White_Space across these methods and omits NEXT LINE from both.
    return Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint)
        || codePoint == 0x0085;
  }

  static String namespace(String value) {
    value = text(value, 63);
    if (!value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
      throw new IllegalArgumentException("Namespace must be a Kubernetes namespace");
    }
    return value;
  }

  static String digest(String value) {
    Objects.requireNonNull(value, "digest");
    if (!GameTenantCreationDigest.isDigest(value)) {
      throw new IllegalArgumentException("Evidence digest must be lowercase sha256-prefixed hex");
    }
    return value;
  }
}
