package net.firedevops.firemud.common.operator;

import java.time.Instant;
import java.util.Objects;

/** Immutable typed preimage model for the passive CHARACTER_CREATE/1 action codec. */
public record CharacterCreateOperatorAction(
    Scope scope,
    Target target,
    ExpectedVersion expectedVersion,
    Mutation mutation,
    AuditReason auditReason) {

  public static final String ACTION_FAMILY_SCHEMA_ID = "firemud.entity.create-character";
  public static final String ACTION_FAMILY_SCHEMA_VERSION = "1";
  public static final String ACTION_FAMILY = "CHARACTER_CREATE";
  public static final String OWNER_SERVICE = "entity-management-service";

  public CharacterCreateOperatorAction {
    Objects.requireNonNull(scope, "scope is required");
    Objects.requireNonNull(target, "target is required");
    Objects.requireNonNull(expectedVersion, "expectedVersion is required");
    Objects.requireNonNull(mutation, "mutation is required");
    Objects.requireNonNull(auditReason, "auditReason presence is required");
  }

  /** Audit reason is a schema-declared absent slot for this action family. */
  public enum AuditReason {
    ABSENT
  }

  public enum PlayableStateScope {
    PLAYABLE_STATE_SCOPE_SHARED("SHARED"),
    PLAYABLE_STATE_SCOPE_ISOLATED("ISOLATED");

    private final String catalogValue;

    PlayableStateScope(String catalogValue) {
      this.catalogValue = catalogValue;
    }

    public String catalogValue() {
      return catalogValue;
    }
  }

  public String actionFamilySchemaId() {
    return ACTION_FAMILY_SCHEMA_ID;
  }

  public String actionFamilySchemaVersion() {
    return ACTION_FAMILY_SCHEMA_VERSION;
  }

  public String actionFamily() {
    return ACTION_FAMILY;
  }

  /** Returns the immutable ADR 0048 target owner; it is not part of the mutation digest. */
  public String targetOwner() {
    return OWNER_SERVICE;
  }

  public record Scope(
      String targetNamespace,
      String tenantId,
      String playableStateNamespaceId,
      PlayableStateScope playableStateScope) {
    public Scope {
      Objects.requireNonNull(targetNamespace, "targetNamespace is required");
      Objects.requireNonNull(tenantId, "tenantId is required");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId is required");
      Objects.requireNonNull(playableStateScope, "playableStateScope is required");
    }
  }

  public record Target(
      String accountId,
      String realmId,
      String worldSlug,
      String realmSlug,
      String gameInstanceId,
      String versionId) {
    public Target {
      Objects.requireNonNull(accountId, "accountId is required");
      Objects.requireNonNull(realmId, "realmId is required");
      Objects.requireNonNull(worldSlug, "worldSlug is required");
      Objects.requireNonNull(realmSlug, "realmSlug is required");
      Objects.requireNonNull(gameInstanceId, "gameInstanceId is required");
      Objects.requireNonNull(versionId, "versionId is required");
    }
  }

  /** Public-production snapshot; private/playtest slots remain unsupported in this receiver. */
  public record ExpectedVersion(
      String catalogRevision,
      String pointerVersion,
      String activeLifecycleEpoch,
      Instant evaluatedAt,
      Instant connectScopeExpiresAt) {
    public ExpectedVersion {
      Objects.requireNonNull(catalogRevision, "catalogRevision is required");
      Objects.requireNonNull(pointerVersion, "pointerVersion is required");
      Objects.requireNonNull(activeLifecycleEpoch, "activeLifecycleEpoch is required");
      Objects.requireNonNull(evaluatedAt, "evaluatedAt is required");
      Objects.requireNonNull(connectScopeExpiresAt, "connectScopeExpiresAt is required");
    }
  }

  public record Mutation(
      String purpose,
      String entryPolicy,
      Catalog catalog,
      Pointer pointer,
      AuthoredSource authoredSource,
      String canonicalCreationInput) {
    public Mutation {
      Objects.requireNonNull(purpose, "purpose is required");
      Objects.requireNonNull(entryPolicy, "entryPolicy is required");
      Objects.requireNonNull(catalog, "catalog is required");
      Objects.requireNonNull(pointer, "pointer is required");
      Objects.requireNonNull(authoredSource, "authoredSource is required");
      Objects.requireNonNull(canonicalCreationInput, "canonicalCreationInput is required");
    }
  }

  public record Catalog(
      String tenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName,
      String realmId,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String playableStateNamespaceId,
      String characterCreationPolicy,
      String catalogRevision) {
    public Catalog {
      Objects.requireNonNull(tenantId, "catalog.tenantId is required");
      Objects.requireNonNull(tenantSlug, "catalog.tenantSlug is required");
      Objects.requireNonNull(worldSlug, "catalog.worldSlug is required");
      Objects.requireNonNull(worldDisplayName, "catalog.worldDisplayName is required");
      Objects.requireNonNull(realmId, "catalog.realmId is required");
      Objects.requireNonNull(realmSlug, "catalog.realmSlug is required");
      Objects.requireNonNull(realmDisplayName, "catalog.realmDisplayName is required");
      Objects.requireNonNull(stateScope, "catalog.stateScope is required");
      Objects.requireNonNull(
          playableStateNamespaceId, "catalog.playableStateNamespaceId is required");
      Objects.requireNonNull(
          characterCreationPolicy, "catalog.characterCreationPolicy is required");
      Objects.requireNonNull(catalogRevision, "catalog.catalogRevision is required");
    }
  }

  public record Pointer(
      String tenantId,
      String realmId,
      String worldSlug,
      String realmSlug,
      String admissionState,
      String admissibleGameInstanceId,
      String pointerVersion,
      String catalogRevision,
      Instant updatedAt) {
    public Pointer {
      Objects.requireNonNull(tenantId, "pointer.tenantId is required");
      Objects.requireNonNull(realmId, "pointer.realmId is required");
      Objects.requireNonNull(worldSlug, "pointer.worldSlug is required");
      Objects.requireNonNull(realmSlug, "pointer.realmSlug is required");
      Objects.requireNonNull(admissionState, "pointer.admissionState is required");
      Objects.requireNonNull(
          admissibleGameInstanceId, "pointer.admissibleGameInstanceId is required");
      Objects.requireNonNull(pointerVersion, "pointer.pointerVersion is required");
      Objects.requireNonNull(catalogRevision, "pointer.catalogRevision is required");
      Objects.requireNonNull(updatedAt, "pointer.updatedAt is required");
    }
  }

  public record AuthoredSource(
      String schema,
      String bindingId,
      String bindingDigest,
      String canonicalTenantId,
      String canonicalVersionId,
      String worldSourceOperationId,
      String worldSourceEvidenceDigest,
      String worldSlug,
      String publicationWorkflowId,
      String selectedCommitId,
      String selectedCommitDigest,
      Policy policy,
      Core core,
      Descriptor descriptor,
      Rule disposition,
      Rule safeFeedback) {
    public AuthoredSource {
      Objects.requireNonNull(schema, "authoredSource.schema is required");
      Objects.requireNonNull(bindingId, "authoredSource.bindingId is required");
      Objects.requireNonNull(bindingDigest, "authoredSource.bindingDigest is required");
      Objects.requireNonNull(canonicalTenantId, "authoredSource.canonicalTenantId is required");
      Objects.requireNonNull(canonicalVersionId, "authoredSource.canonicalVersionId is required");
      Objects.requireNonNull(
          worldSourceOperationId, "authoredSource.worldSourceOperationId is required");
      Objects.requireNonNull(
          worldSourceEvidenceDigest, "authoredSource.worldSourceEvidenceDigest is required");
      Objects.requireNonNull(worldSlug, "authoredSource.worldSlug is required");
      Objects.requireNonNull(
          publicationWorkflowId, "authoredSource.publicationWorkflowId is required");
      Objects.requireNonNull(selectedCommitId, "authoredSource.selectedCommitId is required");
      Objects.requireNonNull(
          selectedCommitDigest, "authoredSource.selectedCommitDigest is required");
      Objects.requireNonNull(policy, "authoredSource.policy is required");
      Objects.requireNonNull(core, "authoredSource.core is required");
      Objects.requireNonNull(descriptor, "authoredSource.descriptor is required");
      Objects.requireNonNull(disposition, "authoredSource.disposition is required");
      Objects.requireNonNull(safeFeedback, "authoredSource.safeFeedback is required");
    }
  }

  public record Policy(
      String commitId,
      String revisionId,
      String logicalRevisionId,
      String canonicalPolicyJson,
      String contentDigest) {
    public Policy {
      Objects.requireNonNull(commitId, "policy.commitId is required");
      Objects.requireNonNull(revisionId, "policy.revisionId is required");
      Objects.requireNonNull(logicalRevisionId, "policy.logicalRevisionId is required");
      Objects.requireNonNull(canonicalPolicyJson, "policy.canonicalPolicyJson is required");
      Objects.requireNonNull(contentDigest, "policy.contentDigest is required");
    }
  }

  public record Core(String canonicalJson) {
    public Core {
      Objects.requireNonNull(canonicalJson, "core.canonicalJson is required");
    }
  }

  public record Descriptor(String canonicalJson, String digest) {
    public Descriptor {
      Objects.requireNonNull(canonicalJson, "descriptor.canonicalJson is required");
      Objects.requireNonNull(digest, "descriptor.digest is required");
    }
  }

  public record Rule(
      String key,
      String definitionJson,
      String definitionDigest,
      String sourceCommitId,
      String sourceCommitDigest,
      String revisionId,
      String revisionOrder) {
    public Rule {
      Objects.requireNonNull(key, "rule.key is required");
      Objects.requireNonNull(definitionJson, "rule.definitionJson is required");
      Objects.requireNonNull(definitionDigest, "rule.definitionDigest is required");
      Objects.requireNonNull(sourceCommitId, "rule.sourceCommitId is required");
      Objects.requireNonNull(sourceCommitDigest, "rule.sourceCommitDigest is required");
      Objects.requireNonNull(revisionId, "rule.revisionId is required");
      Objects.requireNonNull(revisionOrder, "rule.revisionOrder is required");
    }
  }
}
