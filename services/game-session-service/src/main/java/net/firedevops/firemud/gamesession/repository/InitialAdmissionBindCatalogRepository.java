package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayInitialAdmissionBindCatalog.GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayTenantSharedPlayableStateNamespace.GAMEPLAY_TENANT_SHARED_PLAYABLE_STATE_NAMESPACE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class InitialAdmissionBindCatalogRepository {
  private static final String TENANT_CATALOG_LOCK_PREFIX = "initial-admission-bind-catalog:";
  private static final String SHARED_NAMESPACE_LOCK_PREFIX = "tenant-shared-playable-state:";
  private static final String PUBLISHED_CATALOG_LOCK_PREFIX = "published-realm-catalog:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final tools.jackson.databind.ObjectMapper OBJECT_MAPPER =
      new tools.jackson.databind.ObjectMapper();

  private static final Table<?> PUBLISHED_SNAPSHOT =
      DSL.table(DSL.name("gameplay_published_realm_catalog_snapshot"));
  private static final Table<?> PUBLISHED_IDENTITY =
      DSL.table(DSL.name("gameplay_published_realm_catalog_identity"));
  private static final Table<?> PUBLISHED_ENTRY =
      DSL.table(DSL.name("gameplay_published_realm_catalog_entry"));
  private static final Table<?> PUBLISHED_SHARED_NAMESPACE =
      DSL.table(DSL.name("gameplay_published_tenant_shared_playable_state_namespace"));
  private static final Table<?> RETAINED_ASSOCIATION =
      DSL.table(DSL.name("game_session_retained_tenant_association"));

  private static final Field<String> TARGET_NAMESPACE = field("target_namespace", String.class);
  private static final Field<Long> TENANT_ID = field("tenant_id", Long.class);
  private static final Field<UUID> CANONICAL_TENANT_ID = field("canonical_tenant_id", UUID.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID = field("source_game_row_id", Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      field("source_game_tenant_key", String.class);
  private static final Field<String> TENANT_PROVENANCE_KIND =
      field("tenant_identity_provenance_kind", String.class);
  private static final Field<String> ASSOCIATION_PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<Long> LEGACY_GAME_SESSION_TENANT_ID =
      DSL.field(DSL.name("legacy_game_session_tenant_id"), Long.class);
  private static final Field<Long> CATALOG_REVISION = field("catalog_revision", Long.class);
  private static final Field<Long> VERSION_ID = field("version_id", Long.class);
  private static final Field<Integer> VERSION_NUMBER = field("version_number", Integer.class);
  private static final Field<String> RELEASE_BUNDLE_IDENTITY =
      field("release_bundle_identity", String.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      field("publish_workflow_id", String.class);
  private static final Field<String> MANIFEST_HASH = field("manifest_hash", String.class);
  private static final Field<String> POLICY_SET_DIGEST = field("policy_set_digest", String.class);
  private static final Field<Integer> POLICY_COUNT = field("policy_count", Integer.class);
  private static final Field<OffsetDateTime> SNAPSHOT_CREATED_AT =
      field("created_at", OffsetDateTime.class);
  private static final Field<String> REALM_SLUG = field("realm_slug", String.class);
  private static final Field<String> WORLD_SLUG = field("world_slug", String.class);
  private static final Field<String> INITIAL_WORLD_SLUG = field("initial_world_slug", String.class);
  private static final Field<UUID> REALM_ID = field("realm_id", UUID.class);
  private static final Field<UUID> POLICY_ID = field("policy_id", UUID.class);
  private static final Field<Long> SOURCE_REVISION_ID = field("source_revision_id", Long.class);
  private static final Field<String> WORLD_DISPLAY_NAME = field("world_display_name", String.class);
  private static final Field<String> REALM_DISPLAY_NAME = field("realm_display_name", String.class);
  private static final Field<Boolean> VISIBLE = field("visible", Boolean.class);
  private static final Field<Boolean> PUBLIC_PRODUCTION = field("public_production", Boolean.class);
  private static final Field<String> STATE_SCOPE = field("state_scope", String.class);
  private static final Field<String> ENTRY_POLICY = field("entry_policy", String.class);
  private static final Field<String> POLICY_JSON = field("policy_json", String.class);
  private static final Field<String> POLICY_DIGEST = field("policy_digest", String.class);
  private static final Field<UUID> PLAYABLE_STATE_NAMESPACE_ID =
      field("playable_state_namespace_id", UUID.class);
  private static final Field<String> NAMESPACE_RESOLUTION =
      field("namespace_resolution", String.class);
  private static final Field<UUID> IDENTITY_NAMESPACE_ID =
      DSL.field(DSL.name("playable_state_namespace_id"), UUID.class);
  private static final Field<UUID> ALLOCATED_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<OffsetDateTime> ALLOCATED_AT =
      DSL.field(DSL.name("allocated_at"), OffsetDateTime.class);

  private record NamespaceAssignment(UUID namespaceId, NamespaceResolution resolution) {}

  private final DSLContext dsl;

  public InitialAdmissionBindCatalogRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Commits one complete immutable Game Design policy set after its remote owner read has
   * completed. The retained association is reread locally under this transaction before any catalog
   * identity or snapshot row is written.
   */
  @Transactional
  public PublishedRealmCatalogSnapshot materializePublishedSnapshot(
      String exactNamespace,
      long tenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      PublishedRealmEntryPolicySetEvidence policySet) {
    validatePublishedSource(
        exactNamespace,
        tenantId,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        policySet);
    lockPublishedTenant(exactNamespace, tenantId, canonicalTenantId);
    requireRetainedAssociationMatches(
        exactNamespace,
        tenantId,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind);

    Optional<PublishedRealmCatalogSnapshot> exactVersion =
        findPublishedSnapshotByVersionInTransaction(
            exactNamespace, tenantId, policySet.versionId());
    if (exactVersion.isPresent()) {
      PublishedRealmCatalogSnapshot stored = exactVersion.get();
      requireExactPublishedRetry(
          stored,
          exactNamespace,
          tenantId,
          canonicalTenantId,
          sourceGameRowId,
          sourceGameTenantKey,
          provenanceKind,
          policySet);
      return stored;
    }

    Record current = latestSnapshotHeader(exactNamespace, tenantId);
    long catalogRevision = current == null ? 1L : Math.addExact(current.get(CATALOG_REVISION), 1L);
    if (current != null && current.get(VERSION_NUMBER) >= policySet.versionNumber()) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_STALE_VERSION: owner version is not newer than the stored snapshot");
    }

    Instant now = Instant.now();
    insertSnapshotHeader(
        exactNamespace,
        tenantId,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        catalogRevision,
        policySet,
        now);

    List<PublishedRealmCatalogEntry> entries = new ArrayList<>(policySet.policies().size());
    for (PublishedRealmEntryPolicyEvidence evidence : policySet.policies()) {
      RealmEntryPolicy policy = evidence.policy();
      UUID realmId = resolveRealmIdentity(exactNamespace, tenantId, canonicalTenantId, policy);
      NamespaceAssignment namespace =
          resolveNamespace(
              exactNamespace, tenantId, canonicalTenantId, catalogRevision, realmId, evidence);
      insertPublishedEntry(exactNamespace, tenantId, catalogRevision, realmId, namespace, evidence);
      entries.add(
          new PublishedRealmCatalogEntry(
              tenantId,
              catalogRevision,
              realmId,
              namespace.namespaceId(),
              namespace.resolution(),
              evidence));
    }

    PublishedRealmCatalogSnapshot snapshot =
        new PublishedRealmCatalogSnapshot(
            tenantId,
            exactNamespace,
            canonicalTenantId,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            catalogRevision,
            policySet,
            entries,
            now);
    return requireSnapshotReadback(snapshot);
  }

  /** Reads the exact immutable published snapshot referenced by a catalog revision. */
  @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
  public Optional<PublishedRealmCatalogSnapshot> findPublishedSnapshot(
      String exactNamespace, long tenantId, long catalogRevision) {
    if (exactNamespace == null
        || exactNamespace.isBlank()
        || tenantId <= 0
        || catalogRevision <= 0) {
      throw new IllegalArgumentException(
          "Exact namespace, tenant, and catalog revision are required");
    }
    return findPublishedSnapshotInTransaction(exactNamespace, tenantId, catalogRevision);
  }

  /** Returns the newest committed complete policy snapshot, without implying pointer activation. */
  @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
  public Optional<PublishedRealmCatalogSnapshot> findLatestPublishedSnapshot(
      String exactNamespace, long tenantId) {
    if (exactNamespace == null || exactNamespace.isBlank() || tenantId <= 0) {
      throw new IllegalArgumentException("Exact namespace and tenant are required");
    }
    Record latest = latestSnapshotHeader(exactNamespace, tenantId);
    return latest == null
        ? Optional.empty()
        : findPublishedSnapshotInTransaction(
            exactNamespace, tenantId, latest.get(CATALOG_REVISION));
  }

  public Optional<InitialAdmissionBindCatalog> findByTenantId(long tenantId) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG)
        .where(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.TENANT_ID.eq(tenantId))
        .fetchOptional(this::toEntity);
  }

  public Optional<InitialAdmissionBindCatalog> findByTenantIdAndSelectors(
      long tenantId, String worldSlug, String realmSlug) {
    return dsl.selectFrom(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG)
        .where(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_SLUG.eq(worldSlug))
                .and(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_SLUG.eq(realmSlug)))
        .fetchOptional(this::toEntity);
  }

  public void lockTenantCatalogAndSharedNamespace(long tenantId) {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    lock(TENANT_CATALOG_LOCK_PREFIX + tenantId);
    lock(SHARED_NAMESPACE_LOCK_PREFIX + tenantId);
  }

  public UUID getOrCreateTenantSharedNamespace(long tenantId) {
    var table = GAMEPLAY_TENANT_SHARED_PLAYABLE_STATE_NAMESPACE;
    UUID existing =
        dsl.select(table.PLAYABLE_STATE_NAMESPACE_ID)
            .from(table)
            .where(table.TENANT_ID.eq(tenantId))
            .fetchOne(table.PLAYABLE_STATE_NAMESPACE_ID);
    if (existing != null) {
      return existing;
    }
    UUID candidate = UUID.randomUUID();
    dsl.insertInto(table)
        .set(table.TENANT_ID, tenantId)
        .set(table.PLAYABLE_STATE_NAMESPACE_ID, candidate)
        .set(table.ALLOCATED_AT, toLocalDateTime(Instant.now()))
        .execute();
    return candidate;
  }

  public InitialAdmissionBindCatalog insert(
      InitialAdmissionBindCatalogDescriptor descriptor,
      UUID realmId,
      UUID playableStateNamespaceId) {
    dsl.insertInto(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_ID, realmId)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.TENANT_ID, descriptor.tenantId())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.GAME_TEMPLATE_ID, descriptor.gameTemplateId())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_SLUG, descriptor.worldSlug())
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_DISPLAY_NAME,
            descriptor.worldDisplayName())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_SLUG, descriptor.realmSlug())
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_DISPLAY_NAME,
            descriptor.realmDisplayName())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CATALOG_REVISION, 1L)
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PLAYABLE_STATE_NAMESPACE_ID,
            playableStateNamespaceId)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.VISIBLE, true)
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PUBLIC_PRODUCTION_REALM, true)
        .set(
            GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REQUIRES_CHARACTER_SELECTION,
            descriptor.requiresCharacterSelection())
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.STATE_SCOPE, "SHARED")
        .set(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CHARACTER_CREATION_POLICY, "ALLOW_NEW")
        .execute();
    return findByTenantId(descriptor.tenantId()).orElseThrow();
  }

  private Optional<PublishedRealmCatalogSnapshot> findPublishedSnapshotInTransaction(
      String exactNamespace, long tenantId, long catalogRevision) {
    return dsl.selectFrom(PUBLISHED_SNAPSHOT)
        .where(
            TARGET_NAMESPACE
                .eq(exactNamespace)
                .and(TENANT_ID.eq(tenantId))
                .and(CATALOG_REVISION.eq(catalogRevision)))
        .fetchOptional(this::toPublishedSnapshot);
  }

  private Optional<PublishedRealmCatalogSnapshot> findPublishedSnapshotByVersionInTransaction(
      String exactNamespace, long tenantId, long versionId) {
    return dsl.selectFrom(PUBLISHED_SNAPSHOT)
        .where(
            TARGET_NAMESPACE
                .eq(exactNamespace)
                .and(TENANT_ID.eq(tenantId))
                .and(VERSION_ID.eq(versionId)))
        .fetchOptional(this::toPublishedSnapshot);
  }

  private Record latestSnapshotHeader(String exactNamespace, long tenantId) {
    return dsl.selectFrom(PUBLISHED_SNAPSHOT)
        .where(TARGET_NAMESPACE.eq(exactNamespace).and(TENANT_ID.eq(tenantId)))
        .orderBy(CATALOG_REVISION.desc())
        .limit(1)
        .fetchOne();
  }

  private PublishedRealmCatalogSnapshot toPublishedSnapshot(Record header) {
    String namespace = Objects.requireNonNull(header.get(TARGET_NAMESPACE));
    long tenantId = Objects.requireNonNull(header.get(TENANT_ID));
    long revision = Objects.requireNonNull(header.get(CATALOG_REVISION));
    UUID canonicalTenantId = Objects.requireNonNull(header.get(CANONICAL_TENANT_ID));
    long sourceGameRowId = Objects.requireNonNull(header.get(SOURCE_GAME_ROW_ID));
    String sourceGameTenantKey = Objects.requireNonNull(header.get(SOURCE_GAME_TENANT_KEY));
    String provenanceKind = Objects.requireNonNull(header.get(TENANT_PROVENANCE_KIND));
    long versionId = Objects.requireNonNull(header.get(VERSION_ID));
    int versionNumber = Objects.requireNonNull(header.get(VERSION_NUMBER));
    String releaseBundleIdentity = Objects.requireNonNull(header.get(RELEASE_BUNDLE_IDENTITY));
    String publishWorkflowId = Objects.requireNonNull(header.get(PUBLISH_WORKFLOW_ID));
    String manifestHash = Objects.requireNonNull(header.get(MANIFEST_HASH));
    String policySetDigest = Objects.requireNonNull(header.get(POLICY_SET_DIGEST));
    int policyCount = Objects.requireNonNull(header.get(POLICY_COUNT));

    List<? extends Record> rows =
        dsl.selectFrom(PUBLISHED_ENTRY)
            .where(
                TARGET_NAMESPACE
                    .eq(namespace)
                    .and(TENANT_ID.eq(tenantId))
                    .and(CATALOG_REVISION.eq(revision)))
            .orderBy(WORLD_SLUG.collate("C").asc(), REALM_SLUG.collate("C").asc())
            .fetch();
    if (rows.size() != policyCount || rows.isEmpty() || rows.size() > 128) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_SNAPSHOT_INCOMPLETE: persisted policy set is not complete");
    }

    List<PublishedRealmEntryPolicyEvidence> policyEvidence = new ArrayList<>(rows.size());
    List<PublishedRealmCatalogEntry> entries = new ArrayList<>(rows.size());
    for (Record row : rows) {
      RealmEntryPolicy policy;
      try {
        policy = RealmEntryPolicy.parseCanonical(row.get(POLICY_JSON), OBJECT_MAPPER);
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_POLICY_INVALID: persisted policy JSON is invalid", exception);
      }
      PublishedRealmEntryPolicyEvidence evidence;
      try {
        evidence =
            new PublishedRealmEntryPolicyEvidence(
                row.get(POLICY_ID),
                canonicalTenantId,
                provenanceKind,
                sourceGameRowId,
                sourceGameTenantKey,
                versionId,
                versionNumber,
                row.get(SOURCE_REVISION_ID),
                releaseBundleIdentity,
                publishWorkflowId,
                manifestHash,
                policy,
                row.get(POLICY_DIGEST));
      } catch (RuntimeException exception) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_POLICY_INVALID: persisted owner evidence is malformed",
            exception);
      }
      if (!Objects.equals(row.get(CANONICAL_TENANT_ID), canonicalTenantId)
          || !Objects.equals(row.get(WORLD_SLUG), policy.worldSlug())
          || !Objects.equals(row.get(WORLD_DISPLAY_NAME), policy.worldDisplayName())
          || !Objects.equals(row.get(REALM_SLUG), policy.realmSlug())
          || !Objects.equals(row.get(REALM_DISPLAY_NAME), policy.realmDisplayName())
          || !Objects.equals(row.get(VISIBLE), policy.visible())
          || !Objects.equals(row.get(PUBLIC_PRODUCTION), policy.publicProduction())
          || !Objects.equals(row.get(STATE_SCOPE), policy.stateScope().name())
          || !Objects.equals(row.get(ENTRY_POLICY), policy.entryPolicy().name())) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_POLICY_INVALID: stored columns contradict canonical policy");
      }
      policyEvidence.add(evidence);
      String resolutionValue = Objects.requireNonNull(row.get(NAMESPACE_RESOLUTION));
      NamespaceResolution resolution;
      try {
        resolution = NamespaceResolution.valueOf(resolutionValue);
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_NAMESPACE_INVALID: unsupported namespace resolution",
            exception);
      }
      entries.add(
          new PublishedRealmCatalogEntry(
              tenantId,
              revision,
              row.get(REALM_ID),
              row.get(PLAYABLE_STATE_NAMESPACE_ID),
              resolution,
              evidence));
    }

    PublishedRealmEntryPolicySetEvidence setEvidence;
    try {
      setEvidence =
          new PublishedRealmEntryPolicySetEvidence(
              canonicalTenantId,
              versionId,
              versionNumber,
              releaseBundleIdentity,
              publishWorkflowId,
              manifestHash,
              policyEvidence,
              policySetDigest);
      setEvidence.requireValidDigest(OBJECT_MAPPER);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_SNAPSHOT_INVALID: complete policy-set evidence is invalid",
          exception);
    }

    OffsetDateTime createdAt = Objects.requireNonNull(header.get(SNAPSHOT_CREATED_AT));
    try {
      return new PublishedRealmCatalogSnapshot(
          tenantId,
          namespace,
          canonicalTenantId,
          sourceGameRowId,
          sourceGameTenantKey,
          provenanceKind,
          revision,
          setEvidence,
          entries,
          createdAt.toInstant());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_SNAPSHOT_INVALID: persisted snapshot is contradictory",
          exception);
    }
  }

  private void insertSnapshotHeader(
      String exactNamespace,
      long tenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      long catalogRevision,
      PublishedRealmEntryPolicySetEvidence policySet,
      Instant now) {
    dsl.insertInto(PUBLISHED_SNAPSHOT)
        .set(TARGET_NAMESPACE, exactNamespace)
        .set(TENANT_ID, tenantId)
        .set(CANONICAL_TENANT_ID, canonicalTenantId)
        .set(CATALOG_REVISION, catalogRevision)
        .set(SOURCE_GAME_ROW_ID, sourceGameRowId)
        .set(SOURCE_GAME_TENANT_KEY, sourceGameTenantKey)
        .set(TENANT_PROVENANCE_KIND, provenanceKind)
        .set(VERSION_ID, policySet.versionId())
        .set(VERSION_NUMBER, policySet.versionNumber())
        .set(RELEASE_BUNDLE_IDENTITY, policySet.releaseBundleIdentity())
        .set(PUBLISH_WORKFLOW_ID, policySet.publishWorkflowId())
        .set(MANIFEST_HASH, policySet.manifestHash())
        .set(POLICY_SET_DIGEST, policySet.policySetDigest())
        .set(POLICY_COUNT, policySet.policies().size())
        .set(SNAPSHOT_CREATED_AT, toOffsetDateTime(now))
        .execute();
  }

  private UUID resolveRealmIdentity(
      String exactNamespace, long tenantId, UUID canonicalTenantId, RealmEntryPolicy policy) {
    Record existing =
        dsl.selectFrom(PUBLISHED_IDENTITY)
            .where(
                TARGET_NAMESPACE
                    .eq(exactNamespace)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(REALM_SLUG.eq(policy.realmSlug())))
            .fetchOne();
    if (existing != null) {
      if (!canonicalTenantId.equals(existing.get(CANONICAL_TENANT_ID))) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_IDENTITY_CONFLICT: realm slug is bound to another tenant");
      }
      if (!Long.valueOf(tenantId).equals(existing.get(TENANT_ID))) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_IDENTITY_CONFLICT: canonical tenant mapping changed its local owner ID");
      }
      if (!policy.worldSlug().equals(existing.get(INITIAL_WORLD_SLUG))) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_SELECTOR_CHANGED: stable realm selector changed world");
      }
      return Objects.requireNonNull(existing.get(REALM_ID));
    }

    UUID realmId = UUID.randomUUID();
    dsl.insertInto(PUBLISHED_IDENTITY)
        .set(TARGET_NAMESPACE, exactNamespace)
        .set(TENANT_ID, tenantId)
        .set(CANONICAL_TENANT_ID, canonicalTenantId)
        .set(REALM_SLUG, policy.realmSlug())
        .set(INITIAL_WORLD_SLUG, policy.worldSlug())
        .set(REALM_ID, realmId)
        .set(SNAPSHOT_CREATED_AT, toOffsetDateTime(Instant.now()))
        .execute();
    return realmId;
  }

  private NamespaceAssignment resolveNamespace(
      String exactNamespace,
      long tenantId,
      UUID canonicalTenantId,
      long catalogRevision,
      UUID realmId,
      PublishedRealmEntryPolicyEvidence evidence) {
    Record previous =
        dsl.selectFrom(PUBLISHED_ENTRY)
            .where(
                TARGET_NAMESPACE
                    .eq(exactNamespace)
                    .and(TENANT_ID.eq(tenantId))
                    .and(REALM_ID.eq(realmId)))
            .orderBy(CATALOG_REVISION.desc())
            .limit(1)
            .fetchOne();
    RealmEntryPolicy policy = evidence.policy();
    if (previous != null) {
      String previousScope = Objects.requireNonNull(previous.get(STATE_SCOPE));
      NamespaceResolution previousResolution;
      try {
        previousResolution =
            NamespaceResolution.valueOf(Objects.requireNonNull(previous.get(NAMESPACE_RESOLUTION)));
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_NAMESPACE_INVALID: prior namespace state is unsupported",
            exception);
      }
      if (!previousScope.equals(policy.stateScope().name())
          || previousResolution != NamespaceResolution.RESOLVED
          || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED) {
        return new NamespaceAssignment(null, NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
      }
      UUID previousNamespace = Objects.requireNonNull(previous.get(PLAYABLE_STATE_NAMESPACE_ID));
      if (previousNamespace.equals(NIL_UUID)) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_NAMESPACE_INVALID: prior namespace is nil");
      }
      return new NamespaceAssignment(previousNamespace, NamespaceResolution.RESOLVED);
    }

    if (policy.stateScope() != RealmEntryPolicy.StateScope.SHARED) {
      return new NamespaceAssignment(null, NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
    }
    return new NamespaceAssignment(
        getOrCreateCanonicalTenantSharedNamespace(exactNamespace, canonicalTenantId),
        NamespaceResolution.RESOLVED);
  }

  private UUID getOrCreateCanonicalTenantSharedNamespace(
      String exactNamespace, UUID canonicalTenantId) {
    Record existing =
        dsl.selectFrom(PUBLISHED_SHARED_NAMESPACE)
            .where(
                TARGET_NAMESPACE.eq(exactNamespace).and(ALLOCATED_TENANT_ID.eq(canonicalTenantId)))
            .fetchOne();
    if (existing != null) {
      UUID namespaceId = Objects.requireNonNull(existing.get(IDENTITY_NAMESPACE_ID));
      if (namespaceId.equals(NIL_UUID)) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_NAMESPACE_INVALID: shared namespace is nil");
      }
      return namespaceId;
    }
    UUID namespaceId = UUID.randomUUID();
    dsl.insertInto(PUBLISHED_SHARED_NAMESPACE)
        .set(TARGET_NAMESPACE, exactNamespace)
        .set(ALLOCATED_TENANT_ID, canonicalTenantId)
        .set(IDENTITY_NAMESPACE_ID, namespaceId)
        .set(ALLOCATED_AT, toOffsetDateTime(Instant.now()))
        .execute();
    return namespaceId;
  }

  private void insertPublishedEntry(
      String exactNamespace,
      long tenantId,
      long catalogRevision,
      UUID realmId,
      NamespaceAssignment namespace,
      PublishedRealmEntryPolicyEvidence evidence) {
    RealmEntryPolicy policy = evidence.policy();
    dsl.insertInto(PUBLISHED_ENTRY)
        .set(TARGET_NAMESPACE, exactNamespace)
        .set(TENANT_ID, tenantId)
        .set(CANONICAL_TENANT_ID, evidence.canonicalTenantId())
        .set(CATALOG_REVISION, catalogRevision)
        .set(REALM_ID, realmId)
        .set(POLICY_ID, evidence.policyId())
        .set(SOURCE_REVISION_ID, evidence.sourceRevisionId())
        .set(WORLD_SLUG, policy.worldSlug())
        .set(WORLD_DISPLAY_NAME, policy.worldDisplayName())
        .set(REALM_SLUG, policy.realmSlug())
        .set(REALM_DISPLAY_NAME, policy.realmDisplayName())
        .set(VISIBLE, policy.visible())
        .set(PUBLIC_PRODUCTION, policy.publicProduction())
        .set(STATE_SCOPE, policy.stateScope().name())
        .set(ENTRY_POLICY, policy.entryPolicy().name())
        .set(POLICY_JSON, policy.canonicalJson())
        .set(POLICY_DIGEST, evidence.policyDigest())
        .set(PLAYABLE_STATE_NAMESPACE_ID, namespace.namespaceId())
        .set(NAMESPACE_RESOLUTION, namespace.resolution().name())
        .execute();
  }

  private void requireRetainedAssociationMatches(
      String exactNamespace,
      long tenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    Record association =
        dsl.select(
                TARGET_NAMESPACE,
                CANONICAL_TENANT_ID,
                LEGACY_GAME_SESSION_TENANT_ID,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                ASSOCIATION_PROVENANCE_KIND)
            .from(RETAINED_ASSOCIATION)
            .where(
                TARGET_NAMESPACE.eq(exactNamespace).and(CANONICAL_TENANT_ID.eq(canonicalTenantId)))
            .fetchOne();
    if (association == null
        || !exactNamespace.equals(association.get(TARGET_NAMESPACE))
        || !canonicalTenantId.equals(association.get(CANONICAL_TENANT_ID))
        || !Long.valueOf(tenantId).equals(association.get(LEGACY_GAME_SESSION_TENANT_ID))
        || !Long.valueOf(sourceGameRowId).equals(association.get(SOURCE_GAME_ROW_ID))
        || !sourceGameTenantKey.equals(association.get(SOURCE_GAME_TENANT_KEY))
        || !provenanceKind.equals(association.get(ASSOCIATION_PROVENANCE_KIND))) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_TENANT_IDENTITY_MISMATCH: retained association changed or is absent");
    }
  }

  private void lockPublishedTenant(String exactNamespace, long tenantId, UUID canonicalTenantId) {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    List.of(
            PUBLISHED_CATALOG_LOCK_PREFIX + exactNamespace + ":canonical:" + canonicalTenantId,
            PUBLISHED_CATALOG_LOCK_PREFIX + exactNamespace + ":tenant:" + tenantId)
        .stream()
        .sorted()
        .forEach(
            key ->
                dsl.fetch(
                    "select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))", key));
  }

  private void validatePublishedSource(
      String exactNamespace,
      long tenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      PublishedRealmEntryPolicySetEvidence policySet) {
    if (exactNamespace == null
        || exactNamespace.isBlank()
        || tenantId <= 0
        || canonicalTenantId == null
        || NIL_UUID.equals(canonicalTenantId)
        || sourceGameRowId <= 0
        || sourceGameTenantKey == null
        || sourceGameTenantKey.isBlank()
        || !("NEW_GAME_ROW".equals(provenanceKind) || "RETAINED_GAME_V29".equals(provenanceKind))
        || policySet == null
        || !canonicalTenantId.equals(policySet.canonicalTenantId())
        || !policySet.hasValidDigest(OBJECT_MAPPER)) {
      throw new IllegalArgumentException(
          "PUBLISHED_REALM_CATALOG_OWNER_EVIDENCE_INVALID: exact owner evidence is required");
    }
    for (PublishedRealmEntryPolicyEvidence policy : policySet.policies()) {
      if (!canonicalTenantId.equals(policy.canonicalTenantId())
          || sourceGameRowId != policy.sourceGameRowId()
          || !sourceGameTenantKey.equals(policy.sourceGameTenantKey())
          || !provenanceKind.equals(policy.tenantIdentityProvenanceKind())) {
        throw new IllegalArgumentException(
            "PUBLISHED_REALM_CATALOG_SOURCE_MISMATCH: policy provenance contradicts the retained association");
      }
    }
  }

  private void requireExactPublishedRetry(
      PublishedRealmCatalogSnapshot stored,
      String exactNamespace,
      long tenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      PublishedRealmEntryPolicySetEvidence policySet) {
    if (!stored.targetNamespace().equals(exactNamespace)
        || stored.tenantId() != tenantId
        || !stored.canonicalTenantId().equals(canonicalTenantId)
        || stored.sourceGameRowId() != sourceGameRowId
        || !stored.sourceGameTenantKey().equals(sourceGameTenantKey)
        || !stored.tenantIdentityProvenanceKind().equals(provenanceKind)
        || !stored.policySetEvidence().equals(policySet)) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_VERSION_CONFLICT: exact published version was reused with changed evidence");
    }
  }

  private PublishedRealmCatalogSnapshot requireSnapshotReadback(
      PublishedRealmCatalogSnapshot expected) {
    PublishedRealmCatalogSnapshot actual =
        findPublishedSnapshotInTransaction(
                expected.targetNamespace(), expected.tenantId(), expected.catalogRevision())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "PUBLISHED_REALM_CATALOG_READBACK_MISSING: committed snapshot is absent"));
    if (!actual.canonicalTenantId().equals(expected.canonicalTenantId())
        || actual.sourceGameRowId() != expected.sourceGameRowId()
        || !actual.sourceGameTenantKey().equals(expected.sourceGameTenantKey())
        || !actual.tenantIdentityProvenanceKind().equals(expected.tenantIdentityProvenanceKind())
        || !actual.policySetEvidence().equals(expected.policySetEvidence())
        || actual.entries().size() != expected.entries().size()) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_READBACK_INVALID: committed snapshot differs from its exact input");
    }
    for (int index = 0; index < actual.entries().size(); index++) {
      PublishedRealmCatalogEntry actualEntry = actual.entries().get(index);
      PublishedRealmCatalogEntry expectedEntry = expected.entries().get(index);
      if (!actualEntry.realmId().equals(expectedEntry.realmId())
          || !Objects.equals(
              actualEntry.playableStateNamespaceId(), expectedEntry.playableStateNamespaceId())
          || actualEntry.namespaceResolution() != expectedEntry.namespaceResolution()
          || !actualEntry.policyEvidence().equals(expectedEntry.policyEvidence())) {
        throw new IllegalStateException(
            "PUBLISHED_REALM_CATALOG_READBACK_INVALID: committed entry differs from its exact input");
      }
    }
    return actual;
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }

  private void lock(String key) {
    dsl.fetch("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))", key);
  }

  private InitialAdmissionBindCatalog toEntity(Record record) {
    return new InitialAdmissionBindCatalog(
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.TENANT_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.GAME_TEMPLATE_ID),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_SLUG),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.WORLD_DISPLAY_NAME),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_SLUG),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REALM_DISPLAY_NAME),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CATALOG_REVISION),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PLAYABLE_STATE_NAMESPACE_ID),
        Boolean.TRUE.equals(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.VISIBLE)),
        Boolean.TRUE.equals(
            record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.PUBLIC_PRODUCTION_REALM)),
        Boolean.TRUE.equals(
            record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.REQUIRES_CHARACTER_SELECTION)),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.STATE_SCOPE),
        record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CHARACTER_CREATION_POLICY),
        toInstant(record.get(GAMEPLAY_INITIAL_ADMISSION_BIND_CATALOG.CREATED_AT)));
  }
}
