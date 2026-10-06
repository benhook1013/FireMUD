package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamedesign.entity.PublishedRealmEntryPolicy;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PublishedRealmEntryPolicyRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("published_realm_entry_policy"));
  private static final Field<UUID> POLICY_ID = DSL.field(DSL.name("policy_id"), UUID.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> TENANT_IDENTITY_PROVENANCE_KIND =
      DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<Integer> VERSION_NUMBER =
      DSL.field(DSL.name("version_number"), Integer.class);
  private static final Field<Long> RELEASE_BUNDLE_ID =
      DSL.field(DSL.name("release_bundle_id"), Long.class);
  private static final Field<Long> SOURCE_REVISION_ID =
      DSL.field(DSL.name("source_revision_id"), Long.class);
  private static final Field<String> RELEASE_BUNDLE_IDENTITY =
      DSL.field(DSL.name("release_bundle_identity"), String.class);
  private static final Field<String> PUBLISH_WORKFLOW_ID =
      DSL.field(DSL.name("publish_workflow_id"), String.class);
  private static final Field<String> MANIFEST_HASH =
      DSL.field(DSL.name("manifest_hash"), String.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);
  private static final Field<String> REALM_SLUG = DSL.field(DSL.name("realm_slug"), String.class);
  private static final Field<String> REALM_DISPLAY_NAME =
      DSL.field(DSL.name("realm_display_name"), String.class);
  private static final Field<Boolean> VISIBLE = DSL.field(DSL.name("visible"), Boolean.class);
  private static final Field<Boolean> PUBLIC_PRODUCTION =
      DSL.field(DSL.name("public_production"), Boolean.class);
  private static final Field<String> STATE_SCOPE = DSL.field(DSL.name("state_scope"), String.class);
  private static final Field<String> ENTRY_POLICY =
      DSL.field(DSL.name("entry_policy"), String.class);
  private static final Field<String> POLICY_JSON = DSL.field(DSL.name("policy_json"), String.class);
  private static final Field<String> POLICY_DIGEST =
      DSL.field(DSL.name("policy_digest"), String.class);

  private final DSLContext dsl;

  public PublishedRealmEntryPolicyRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void insert(PublishedRealmEntryPolicy policy) {
    int inserted =
        dsl.insertInto(TABLE_REF)
            .set(POLICY_ID, policy.policyId())
            .set(CANONICAL_TENANT_ID, policy.canonicalTenantId())
            .set(TENANT_IDENTITY_PROVENANCE_KIND, policy.tenantIdentityProvenanceKind())
            .set(SOURCE_GAME_ROW_ID, policy.sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, policy.sourceGameTenantKey())
            .set(VERSION_ID, policy.versionId())
            .set(VERSION_NUMBER, policy.versionNumber())
            .set(RELEASE_BUNDLE_ID, policy.releaseBundleId())
            .set(SOURCE_REVISION_ID, policy.sourceRevisionId())
            .set(RELEASE_BUNDLE_IDENTITY, policy.releaseBundleIdentity())
            .set(PUBLISH_WORKFLOW_ID, policy.publishWorkflowId())
            .set(MANIFEST_HASH, policy.manifestHash())
            .set(WORLD_SLUG, policy.worldSlug())
            .set(WORLD_DISPLAY_NAME, policy.worldDisplayName())
            .set(REALM_SLUG, policy.realmSlug())
            .set(REALM_DISPLAY_NAME, policy.realmDisplayName())
            .set(VISIBLE, policy.visible())
            .set(PUBLIC_PRODUCTION, policy.publicProduction())
            .set(STATE_SCOPE, policy.stateScope())
            .set(ENTRY_POLICY, policy.entryPolicy())
            .set(POLICY_JSON, policy.policyJson())
            .set(POLICY_DIGEST, policy.policyDigest())
            .execute();
    if (inserted != 1) {
      throw new IllegalStateException("Published realm-entry policy insert did not affect one row");
    }
  }

  public List<PublishedRealmEntryPolicy> findByScope(
      UUID canonicalTenantId, long versionId, String worldSlug, String realmSlug) {
    return dsl.selectFrom(TABLE_REF)
        .where(
            CANONICAL_TENANT_ID
                .eq(canonicalTenantId)
                .and(VERSION_ID.eq(versionId))
                .and(WORLD_SLUG.eq(worldSlug))
                .and(REALM_SLUG.eq(realmSlug)))
        .orderBy(POLICY_ID.asc())
        .fetch(this::toEntity);
  }

  /** Reads at most the proof bound plus one so callers can reject, never truncate, large sets. */
  public List<PublishedRealmEntryPolicy> findByOwner(UUID canonicalTenantId, long versionId) {
    return dsl.selectFrom(TABLE_REF)
        .where(CANONICAL_TENANT_ID.eq(canonicalTenantId).and(VERSION_ID.eq(versionId)))
        .orderBy(WORLD_SLUG.asc(), REALM_SLUG.asc())
        .limit(PublishedRealmEntryPolicySetEvidence.MAX_POLICIES + 1)
        .fetch(this::toEntity);
  }

  private PublishedRealmEntryPolicy toEntity(Record record) {
    return new PublishedRealmEntryPolicy(
        record.get(POLICY_ID),
        record.get(CANONICAL_TENANT_ID),
        record.get(TENANT_IDENTITY_PROVENANCE_KIND),
        record.get(SOURCE_GAME_ROW_ID),
        record.get(SOURCE_GAME_TENANT_KEY),
        record.get(VERSION_ID),
        record.get(VERSION_NUMBER),
        record.get(RELEASE_BUNDLE_ID),
        record.get(SOURCE_REVISION_ID),
        record.get(RELEASE_BUNDLE_IDENTITY),
        record.get(PUBLISH_WORKFLOW_ID),
        record.get(MANIFEST_HASH),
        record.get(WORLD_SLUG),
        record.get(WORLD_DISPLAY_NAME),
        record.get(REALM_SLUG),
        record.get(REALM_DISPLAY_NAME),
        Boolean.TRUE.equals(record.get(VISIBLE)),
        Boolean.TRUE.equals(record.get(PUBLIC_PRODUCTION)),
        record.get(STATE_SCOPE),
        record.get(ENTRY_POLICY),
        record.get(POLICY_JSON),
        record.get(POLICY_DIGEST));
  }
}
