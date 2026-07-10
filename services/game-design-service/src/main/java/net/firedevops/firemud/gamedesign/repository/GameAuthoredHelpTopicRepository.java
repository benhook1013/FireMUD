package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.GameAuthoredHelpTopic;
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
public class GameAuthoredHelpTopicRepository {
  private static final Table<?> TOPIC_TABLE = DSL.table(DSL.name("game_authored_help_topic"));
  private static final Table<?> KEY_TABLE = DSL.table(DSL.name("game_authored_help_topic_key"));
  private static final Field<Long> ID =
      DSL.field(DSL.name("game_authored_help_topic", "id"), Long.class);
  private static final Field<String> TENANT_ID =
      DSL.field(DSL.name("game_authored_help_topic", "tenant_id"), String.class);
  private static final Field<Long> GAME_TEMPLATE_ID =
      DSL.field(DSL.name("game_authored_help_topic", "game_template_id"), Long.class);
  private static final Field<String> CANONICAL_TOPIC_KEY =
      DSL.field(DSL.name("game_authored_help_topic", "canonical_topic_key"), String.class);
  private static final Field<String> TITLE =
      DSL.field(DSL.name("game_authored_help_topic", "title"), String.class);
  private static final Field<String> BODY =
      DSL.field(DSL.name("game_authored_help_topic", "body"), String.class);
  private static final Field<Boolean> PUBLISHED =
      DSL.field(DSL.name("game_authored_help_topic", "published"), Boolean.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("game_authored_help_topic", "created_at"), Timestamp.class);
  private static final Field<Timestamp> UPDATED_AT =
      DSL.field(DSL.name("game_authored_help_topic", "updated_at"), Timestamp.class);
  private static final Field<Long> KEY_TOPIC_ID =
      DSL.field(DSL.name("game_authored_help_topic_key", "help_topic_id"), Long.class);
  private static final Field<String> LOOKUP_KEY =
      DSL.field(DSL.name("game_authored_help_topic_key", "lookup_key"), String.class);
  private static final Field<String> KEY_TENANT_ID =
      DSL.field(DSL.name("game_authored_help_topic_key", "tenant_id"), String.class);
  private static final Field<Long> KEY_GAME_TEMPLATE_ID =
      DSL.field(DSL.name("game_authored_help_topic_key", "game_template_id"), Long.class);

  private final DSLContext dsl;

  public GameAuthoredHelpTopicRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<GameAuthoredHelpTopic> findPublishedByCanonicalKey(
      String tenantId, long gameTemplateId, String canonicalTopicKey) {
    return findOne(
        TOPIC_TABLE,
        TENANT_ID
            .eq(tenantId)
            .and(GAME_TEMPLATE_ID.eq(gameTemplateId))
            .and(CANONICAL_TOPIC_KEY.eq(canonicalTopicKey))
            .and(PUBLISHED.isTrue()));
  }

  public Optional<GameAuthoredHelpTopic> findPublishedByAliasKey(
      String tenantId, long gameTemplateId, String aliasKey) {
    return findOne(
        TOPIC_TABLE.join(KEY_TABLE).on(ID.eq(KEY_TOPIC_ID)),
        TENANT_ID
            .eq(tenantId)
            .and(GAME_TEMPLATE_ID.eq(gameTemplateId))
            .and(LOOKUP_KEY.eq(aliasKey))
            .and(PUBLISHED.isTrue()));
  }

  public Optional<GameAuthoredHelpTopic> findByScopeAndCanonicalKey(
      String tenantId, long gameTemplateId, String canonicalTopicKey) {
    return findOne(
        TOPIC_TABLE,
        TENANT_ID
            .eq(tenantId)
            .and(GAME_TEMPLATE_ID.eq(gameTemplateId))
            .and(CANONICAL_TOPIC_KEY.eq(canonicalTopicKey)));
  }

  public Optional<GameAuthoredHelpTopic> findByScopeAndAliasKey(
      String tenantId, long gameTemplateId, String aliasKey) {
    return findOne(
        TOPIC_TABLE.join(KEY_TABLE).on(ID.eq(KEY_TOPIC_ID)),
        TENANT_ID
            .eq(tenantId)
            .and(GAME_TEMPLATE_ID.eq(gameTemplateId))
            .and(LOOKUP_KEY.eq(aliasKey)));
  }

  public List<GameAuthoredHelpTopic> findAllByScope(String tenantId, long gameTemplateId) {
    return dsl.selectFrom(TOPIC_TABLE)
        .where(TENANT_ID.eq(tenantId).and(GAME_TEMPLATE_ID.eq(gameTemplateId)))
        .orderBy(CANONICAL_TOPIC_KEY.asc())
        .fetch(this::toEntity);
  }

  public GameAuthoredHelpTopic save(GameAuthoredHelpTopic topic) {
    Instant now = Instant.now();
    if (topic.getId() == null) {
      Long id =
          Objects.requireNonNull(
              dsl.insertInto(TOPIC_TABLE)
                  .set(TENANT_ID, topic.getTenantId())
                  .set(GAME_TEMPLATE_ID, topic.getGameTemplateId())
                  .set(CANONICAL_TOPIC_KEY, topic.getCanonicalTopicKey())
                  .set(TITLE, topic.getTitle())
                  .set(BODY, topic.getBody())
                  .set(PUBLISHED, topic.isPublished())
                  .set(CREATED_AT, Timestamp.from(now))
                  .set(UPDATED_AT, Timestamp.from(now))
                  .returningResult(ID)
                  .fetchOne(ID),
              "Failed to insert game-authored help topic");
      return findById(id).orElseThrow();
    }

    dsl.update(TOPIC_TABLE)
        .set(CANONICAL_TOPIC_KEY, topic.getCanonicalTopicKey())
        .set(TITLE, topic.getTitle())
        .set(BODY, topic.getBody())
        .set(PUBLISHED, topic.isPublished())
        .set(UPDATED_AT, Timestamp.from(now))
        .where(ID.eq(topic.getId()))
        .execute();
    return findById(topic.getId()).orElseThrow();
  }

  public void replaceLookupKeys(GameAuthoredHelpTopic topic, List<String> aliases) {
    dsl.deleteFrom(KEY_TABLE).where(KEY_TOPIC_ID.eq(topic.getId())).execute();
    for (String key : lookupKeys(topic.getCanonicalTopicKey(), aliases)) {
      dsl.insertInto(KEY_TABLE)
          .set(KEY_TOPIC_ID, topic.getId())
          .set(KEY_TENANT_ID, topic.getTenantId())
          .set(KEY_GAME_TEMPLATE_ID, topic.getGameTemplateId())
          .set(LOOKUP_KEY, key)
          .execute();
    }
  }

  public void delete(GameAuthoredHelpTopic topic) {
    dsl.deleteFrom(TOPIC_TABLE).where(ID.eq(topic.getId())).execute();
  }

  private Optional<GameAuthoredHelpTopic> findById(long id) {
    return findOne(TOPIC_TABLE, ID.eq(id));
  }

  private Optional<GameAuthoredHelpTopic> findOne(Table<?> table, org.jooq.Condition condition) {
    return Optional.ofNullable(dsl.selectFrom(table).where(condition).fetchOne(this::toEntity));
  }

  private GameAuthoredHelpTopic toEntity(Record record) {
    if (record == null) {
      return null;
    }
    GameAuthoredHelpTopic topic = new GameAuthoredHelpTopic();
    topic.setId(record.get(ID));
    topic.setTenantId(record.get(TENANT_ID));
    topic.setGameTemplateId(record.get(GAME_TEMPLATE_ID));
    topic.setCanonicalTopicKey(record.get(CANONICAL_TOPIC_KEY));
    topic.setTitle(record.get(TITLE));
    topic.setBody(record.get(BODY));
    topic.setPublished(Boolean.TRUE.equals(record.get(PUBLISHED)));
    Timestamp createdAt = record.get(CREATED_AT);
    topic.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
    Timestamp updatedAt = record.get(UPDATED_AT);
    topic.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
    topic.setAliases(
        dsl.select(LOOKUP_KEY)
            .from(KEY_TABLE)
            .where(KEY_TOPIC_ID.eq(topic.getId()).and(LOOKUP_KEY.ne(topic.getCanonicalTopicKey())))
            .orderBy(LOOKUP_KEY.asc())
            .fetch(LOOKUP_KEY));
    return topic;
  }

  private List<String> lookupKeys(String canonicalTopicKey, List<String> aliases) {
    java.util.ArrayList<String> keys = new java.util.ArrayList<>();
    keys.add(canonicalTopicKey);
    keys.addAll(aliases);
    return List.copyOf(keys);
  }
}
