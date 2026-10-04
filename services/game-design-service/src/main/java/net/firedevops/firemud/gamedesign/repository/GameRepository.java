package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.jooq.Condition;
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
public class GameRepository {
  private static final Table<?> GAME_TABLE = DSL.table(DSL.name("game"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> TENANT_IDENTITY_PROVENANCE_KIND =
      DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class);
  private static final Field<Long> TENANT_IDENTITY_SOURCE_GAME_ID =
      DSL.field(DSL.name("tenant_identity_source_game_id"), Long.class);
  private static final Field<String> TENANT_IDENTITY_SOURCE_LEGACY_TENANT_ID =
      DSL.field(DSL.name("tenant_identity_source_legacy_tenant_id"), String.class);
  private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> DESCRIPTION = DSL.field(DSL.name("description"), String.class);

  private final DSLContext dsl;

  public GameRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<Game> findAll() {
    return dsl.selectFrom(GAME_TABLE).orderBy(ID.asc()).fetch(this::toEntity);
  }

  public Game save(Game game) {
    if (game.getId() == null) {
      if (game.getCanonicalTenantId() != null) {
        throw new IllegalArgumentException("Canonical tenant identity is issued by Game Design");
      }
      Record record =
          dsl.insertInto(GAME_TABLE)
              .set(TENANT_ID, game.getTenantId())
              .set(NAME, game.getName())
              .set(DESCRIPTION, game.getDescription())
              .returning(
                  ID,
                  TENANT_ID,
                  CANONICAL_TENANT_ID,
                  TENANT_IDENTITY_PROVENANCE_KIND,
                  TENANT_IDENTITY_SOURCE_GAME_ID,
                  TENANT_IDENTITY_SOURCE_LEGACY_TENANT_ID,
                  NAME,
                  DESCRIPTION)
              .fetchOne();
      if (record == null) {
        throw new IllegalStateException("Game insert did not return its persisted row");
      }
      Game saved = toEntity(record);
      GameTenantIdentity identity = toTenantIdentity(record);
      if (saved == null
          || saved.getId() == null
          || !saved.getId().equals(identity.sourceGameId())
          || !saved.getTenantId().equals(identity.sourceLegacyTenantId())
          || identity.provenanceKind() != GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW) {
        throw new IllegalStateException(
            "Persisted game tenant identity did not match its owner row");
      }
      return saved;
    }
    Condition identityCondition = ID.eq(game.getId());
    if (game.getTenantId() != null) {
      identityCondition = identityCondition.and(TENANT_ID.eq(game.getTenantId()));
    }
    if (game.getCanonicalTenantId() != null) {
      identityCondition =
          identityCondition.and(CANONICAL_TENANT_ID.eq(game.getCanonicalTenantId()));
    }
    Record record =
        dsl.update(GAME_TABLE)
            .set(NAME, game.getName())
            .set(DESCRIPTION, game.getDescription())
            .where(identityCondition)
            .returning(
                ID,
                TENANT_ID,
                CANONICAL_TENANT_ID,
                TENANT_IDENTITY_PROVENANCE_KIND,
                TENANT_IDENTITY_SOURCE_GAME_ID,
                TENANT_IDENTITY_SOURCE_LEGACY_TENANT_ID,
                NAME,
                DESCRIPTION)
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("Game update identity does not match a persisted game row");
    }
    return toEntity(record);
  }

  public Game findByTenantId(String tenantId) {
    return dsl.selectFrom(GAME_TABLE)
        .where(TENANT_ID.eq(tenantId))
        .limit(1)
        .fetchOne(this::toEntity);
  }

  public Game findByTenantIdForUpdate(String tenantId) {
    return dsl.selectFrom(GAME_TABLE)
        .where(TENANT_ID.eq(tenantId))
        .forUpdate()
        .fetchOne(this::toEntity);
  }

  /** Reads one persisted tenant UUID and validates its provenance against the actual owner row. */
  public Optional<GameTenantIdentity> findRuntimeTenantIdentityByCanonicalTenantId(
      UUID canonicalTenantId) {
    if (canonicalTenantId == null) {
      throw new IllegalArgumentException("Canonical tenant UUID is required");
    }
    return dsl.select(
            ID,
            TENANT_ID,
            CANONICAL_TENANT_ID,
            TENANT_IDENTITY_PROVENANCE_KIND,
            TENANT_IDENTITY_SOURCE_GAME_ID,
            TENANT_IDENTITY_SOURCE_LEGACY_TENANT_ID)
        .from(GAME_TABLE)
        .where(CANONICAL_TENANT_ID.eq(canonicalTenantId))
        .fetchOptional(this::toRuntimeTenantIdentity);
  }

  private Game toEntity(Record record) {
    if (record == null) {
      return null;
    }
    Game game = new Game();
    game.setId(record.get(ID));
    game.setTenantId(record.get(TENANT_ID));
    game.setCanonicalTenantId(record.get(CANONICAL_TENANT_ID));
    game.setName(record.get(NAME));
    game.setDescription(record.get(DESCRIPTION));
    return game;
  }

  private GameTenantIdentity toTenantIdentity(Record record) {
    UUID canonicalTenantId = record.get(CANONICAL_TENANT_ID);
    String provenanceKindValue = record.get(TENANT_IDENTITY_PROVENANCE_KIND);
    Long sourceGameId = record.get(TENANT_IDENTITY_SOURCE_GAME_ID);
    String sourceLegacyTenantId = record.get(TENANT_IDENTITY_SOURCE_LEGACY_TENANT_ID);
    if (canonicalTenantId == null
        || provenanceKindValue == null
        || sourceGameId == null
        || sourceGameId <= 0
        || sourceLegacyTenantId == null
        || sourceLegacyTenantId.isBlank()) {
      throw new IllegalStateException("Persisted game tenant identity provenance is incomplete");
    }
    GameTenantIdentity.ProvenanceKind provenanceKind;
    try {
      provenanceKind = GameTenantIdentity.ProvenanceKind.valueOf(provenanceKindValue);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Persisted game tenant identity provenance kind is unknown", exception);
    }
    return new GameTenantIdentity(
        canonicalTenantId, provenanceKind, sourceGameId, sourceLegacyTenantId);
  }

  private GameTenantIdentity toRuntimeTenantIdentity(Record record) {
    Long actualGameId = record.get(ID);
    String actualTenantKey = record.get(TENANT_ID);
    UUID canonicalTenantId = record.get(CANONICAL_TENANT_ID);
    String provenanceKindValue = record.get(TENANT_IDENTITY_PROVENANCE_KIND);
    Long sourceGameId = record.get(TENANT_IDENTITY_SOURCE_GAME_ID);
    String sourceTenantKey = record.get(TENANT_IDENTITY_SOURCE_LEGACY_TENANT_ID);
    if (actualGameId == null
        || actualGameId <= 0
        || actualTenantKey == null
        || actualTenantKey.isBlank()
        || canonicalTenantId == null
        || provenanceKindValue == null
        || sourceGameId == null
        || sourceGameId <= 0
        || sourceTenantKey == null
        || !actualGameId.equals(sourceGameId)
        || !actualTenantKey.equals(sourceTenantKey)) {
      throw new IllegalStateException(
          "Persisted runtime tenant identity provenance does not match its game row");
    }

    GameTenantIdentity.ProvenanceKind provenanceKind;
    try {
      provenanceKind = GameTenantIdentity.ProvenanceKind.valueOf(provenanceKindValue);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Persisted runtime tenant identity provenance kind is unknown", exception);
    }
    return new GameTenantIdentity(canonicalTenantId, provenanceKind, actualGameId, actualTenantKey);
  }
}
