package net.firedevops.firemud.gamedesign.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PublishedPluginVersionRepository {
  private static final Table<?> TABLE_REF = DSL.table(DSL.name("published_plugin_versions"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> PLUGIN_ID = DSL.field(DSL.name("plugin_id"), String.class);
  private static final Field<String> PLUGIN_VERSION_ID =
      DSL.field(DSL.name("plugin_version_id"), String.class);
  private static final Field<Long> BASE_VERSION_ID =
      DSL.field(DSL.name("base_version_id"), Long.class);
  private static final Field<String> PUBLICATION_STATE =
      DSL.field(DSL.name("publication_state"), String.class);
  private static final Field<String> ABILITY_SCHEMA_DIGEST =
      DSL.field(DSL.name("ability_schema_digest"), String.class);
  private static final Field<String> BUNDLE_DIGEST =
      DSL.field(DSL.name("bundle_digest"), String.class);
  private static final Field<Integer> MANIFEST_SCHEMA_VERSION =
      DSL.field(DSL.name("manifest_schema_version"), Integer.class);
  private static final Field<String> DISTRIBUTION_MANIFEST_HASH =
      DSL.field(DSL.name("distribution_manifest_hash"), String.class);
  private static final Field<String> DISTRIBUTION_MANIFEST_PATH =
      DSL.field(DSL.name("distribution_manifest_path"), String.class);
  private static final Field<String> SIGNER_KEY_ID =
      DSL.field(DSL.name("signer_key_id"), String.class);
  private static final Field<Boolean> SIGNER_REVOKED =
      DSL.field(DSL.name("signer_revoked"), Boolean.class);
  private static final Field<String> COMPONENT_POLICY_DECISION =
      DSL.field(DSL.name("component_policy_decision"), String.class);
  private static final Field<String> NOTES = DSL.field(DSL.name("notes"), String.class);
  private static final Field<String> STATUS_REASON =
      DSL.field(DSL.name("status_reason"), String.class);
  private static final Field<LocalDateTime> LAST_CHANGED_AT =
      DSL.field(DSL.name("last_changed_at"), LocalDateTime.class);

  private final DSLContext dsl;

  public PublishedPluginVersionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PublishedPluginVersion> findByTenantIdAndPluginIdAndPluginVersionId(
      String tenantId, String pluginId, String pluginVersionId) {
    return Optional.ofNullable(
        dsl.selectFrom(TABLE_REF)
            .where(
                TENANT_ID
                    .eq(tenantId)
                    .and(PLUGIN_ID.eq(pluginId))
                    .and(PLUGIN_VERSION_ID.eq(pluginVersionId)))
            .fetchOne(this::toEntity));
  }

  public List<PublishedPluginVersion> findAllByTenantIdAndPluginIdAndPublicationState(
      String tenantId, String pluginId, VersionLifecycleState publicationState) {
    return dsl.selectFrom(TABLE_REF)
        .where(
            TENANT_ID
                .eq(tenantId)
                .and(PLUGIN_ID.eq(pluginId))
                .and(PUBLICATION_STATE.eq(publicationState.name())))
        .orderBy(LAST_CHANGED_AT.desc(), ID.desc())
        .fetch(this::toEntity);
  }

  public List<PublishedPluginVersion> listPublishedPluginVersions(
      String tenantId,
      String pluginId,
      VersionLifecycleState publicationState,
      LocalDateTime changedAfter,
      LocalDateTime changedBefore,
      Pageable pageable) {
    Condition condition = TENANT_ID.eq(tenantId);
    if (pluginId != null && !pluginId.isBlank()) {
      condition = condition.and(PLUGIN_ID.eq(pluginId));
    }
    if (publicationState != null) {
      condition = condition.and(PUBLICATION_STATE.eq(publicationState.name()));
    }
    if (changedAfter != null) {
      condition = condition.and(LAST_CHANGED_AT.ge(changedAfter));
    }
    if (changedBefore != null) {
      condition = condition.and(LAST_CHANGED_AT.le(changedBefore));
    }
    return dsl.selectFrom(TABLE_REF)
        .where(condition)
        .orderBy(LAST_CHANGED_AT.desc(), ID.desc())
        .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public PublishedPluginVersion save(PublishedPluginVersion entity) {
    if (entity.getId() == null) {
      Record record =
          dsl.insertInto(TABLE_REF)
              .set(TENANT_ID, entity.getTenantId())
              .set(PLUGIN_ID, entity.getPluginId())
              .set(PLUGIN_VERSION_ID, entity.getPluginVersionId())
              .set(BASE_VERSION_ID, entity.getBaseVersionId())
              .set(PUBLICATION_STATE, entity.getPublicationState().name())
              .set(ABILITY_SCHEMA_DIGEST, entity.getAbilitySchemaDigest())
              .set(BUNDLE_DIGEST, entity.getBundleDigest())
              .set(MANIFEST_SCHEMA_VERSION, entity.getManifestSchemaVersion())
              .set(DISTRIBUTION_MANIFEST_HASH, entity.getDistributionManifestHash())
              .set(DISTRIBUTION_MANIFEST_PATH, entity.getDistributionManifestPath())
              .set(SIGNER_KEY_ID, entity.getSignerKeyId())
              .set(SIGNER_REVOKED, entity.isSignerRevoked())
              .set(COMPONENT_POLICY_DECISION, entity.getComponentPolicyDecision())
              .set(NOTES, entity.getNotes())
              .set(STATUS_REASON, entity.getStatusReason())
              .set(LAST_CHANGED_AT, entity.getLastChangedAt())
              .returning()
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(TABLE_REF)
        .set(TENANT_ID, entity.getTenantId())
        .set(PLUGIN_ID, entity.getPluginId())
        .set(PLUGIN_VERSION_ID, entity.getPluginVersionId())
        .set(BASE_VERSION_ID, entity.getBaseVersionId())
        .set(PUBLICATION_STATE, entity.getPublicationState().name())
        .set(ABILITY_SCHEMA_DIGEST, entity.getAbilitySchemaDigest())
        .set(BUNDLE_DIGEST, entity.getBundleDigest())
        .set(MANIFEST_SCHEMA_VERSION, entity.getManifestSchemaVersion())
        .set(DISTRIBUTION_MANIFEST_HASH, entity.getDistributionManifestHash())
        .set(DISTRIBUTION_MANIFEST_PATH, entity.getDistributionManifestPath())
        .set(SIGNER_KEY_ID, entity.getSignerKeyId())
        .set(SIGNER_REVOKED, entity.isSignerRevoked())
        .set(COMPONENT_POLICY_DECISION, entity.getComponentPolicyDecision())
        .set(NOTES, entity.getNotes())
        .set(STATUS_REASON, entity.getStatusReason())
        .set(LAST_CHANGED_AT, entity.getLastChangedAt())
        .where(ID.eq(entity.getId()))
        .execute();
    return findByTenantIdAndPluginIdAndPluginVersionId(
            entity.getTenantId(), entity.getPluginId(), entity.getPluginVersionId())
        .orElseThrow();
  }

  private PublishedPluginVersion toEntity(Record record) {
    if (record == null) {
      return null;
    }
    PublishedPluginVersion entity = new PublishedPluginVersion();
    entity.setId(record.get(ID));
    entity.setTenantId(record.get(TENANT_ID));
    entity.setPluginId(record.get(PLUGIN_ID));
    entity.setPluginVersionId(record.get(PLUGIN_VERSION_ID));
    entity.setBaseVersionId(record.get(BASE_VERSION_ID));
    entity.setPublicationState(VersionLifecycleState.valueOf(record.get(PUBLICATION_STATE)));
    entity.setAbilitySchemaDigest(record.get(ABILITY_SCHEMA_DIGEST));
    entity.setBundleDigest(record.get(BUNDLE_DIGEST));
    entity.setManifestSchemaVersion(record.get(MANIFEST_SCHEMA_VERSION));
    entity.setDistributionManifestHash(record.get(DISTRIBUTION_MANIFEST_HASH));
    entity.setDistributionManifestPath(record.get(DISTRIBUTION_MANIFEST_PATH));
    entity.setSignerKeyId(record.get(SIGNER_KEY_ID));
    entity.setSignerRevoked(Boolean.TRUE.equals(record.get(SIGNER_REVOKED)));
    entity.setComponentPolicyDecision(record.get(COMPONENT_POLICY_DECISION));
    entity.setNotes(record.get(NOTES));
    entity.setStatusReason(record.get(STATUS_REASON));
    entity.setLastChangedAt(record.get(LAST_CHANGED_AT));
    return entity;
  }
}
