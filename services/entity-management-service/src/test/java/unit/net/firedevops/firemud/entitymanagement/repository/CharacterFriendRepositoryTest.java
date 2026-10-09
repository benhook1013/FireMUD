package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTER_FRIEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class CharacterFriendRepositoryTest {
  private static final Long TENANT_ID = 17L;
  private static final Long CHARACTER_ID = 37L;
  private static final Long FRIEND_ID = 41L;

  @Test
  void saveRejectsSelfRelationshipBeforeDatabaseInteraction() {
    AtomicInteger queryCount = new AtomicInteger();
    CharacterFriendRepository repository =
        repository(
            context -> {
              queryCount.incrementAndGet();
              throw new AssertionError(
                  "self relationships must be rejected before database access");
            });

    assertThatThrownBy(() -> repository.save(friend(CHARACTER_ID, CHARACTER_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FRIEND_SELF_RELATIONSHIP_NOT_ALLOWED");
    assertThat(queryCount).hasValue(0);
  }

  @Test
  void saveStillRejectsDistinctPairWithoutTwoOwnerResolvedActors() {
    AtomicInteger queryCount = new AtomicInteger();
    AtomicReference<Object[]> guardBindings = new AtomicReference<>();
    CharacterFriendRepository repository =
        repository(
            context -> {
              queryCount.incrementAndGet();
              guardBindings.set(context.bindings());
              return ownerResolvedCountResult(1);
            });

    assertThatThrownBy(() -> repository.save(friend(CHARACTER_ID, FRIEND_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FRIEND_ACTOR_IDENTITY_NOT_OWNER_RESOLVED");
    assertThat(queryCount).hasValue(1);
    assertThat(Arrays.asList(guardBindings.get()))
        .contains(CHARACTER_ID, FRIEND_ID, TENANT_ID, "OWNER_RESOLVED");
  }

  @Test
  void saveChecksExactTenantAndOwnerResolvedGuardForDistinctPair() {
    AtomicInteger queryCount = new AtomicInteger();
    AtomicReference<String> guardSql = new AtomicReference<>();
    AtomicReference<Object[]> guardBindings = new AtomicReference<>();
    CharacterFriend entity = friend(CHARACTER_ID, FRIEND_ID);
    CharacterFriendRepository repository =
        repository(
            context -> {
              queryCount.incrementAndGet();
              String sql = context.sql().trim();
              if (sql.toLowerCase(Locale.ROOT).startsWith("select count")) {
                guardSql.set(sql);
                guardBindings.set(context.bindings());
                return ownerResolvedCountResult(2);
              }
              if (sql.toLowerCase(Locale.ROOT).startsWith("select")) {
                DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
                return new MockResult[] {
                  new MockResult(0, resultDsl.newResult(CHARACTER_FRIEND.fields()))
                };
              }
              if (sql.toLowerCase(Locale.ROOT).startsWith("insert")) {
                return new MockResult[] {new MockResult(1)};
              }
              throw new AssertionError("Unexpected database interaction: " + sql);
            });

    assertThat(repository.save(entity)).isSameAs(entity);

    String normalizedGuardSql = guardSql.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    assertThat(normalizedGuardSql)
        .contains(" from \"characters\"")
        .contains("\"id\" in (?, ?)")
        .contains("\"tenant_id\" = ?")
        .contains("\"actor_identity_status\" = ?");
    assertThat(Arrays.asList(guardBindings.get()))
        .contains(CHARACTER_ID, FRIEND_ID, TENANT_ID, "OWNER_RESOLVED");
    assertThat(queryCount).hasValue(3);
  }

  private static CharacterFriendRepository repository(MockDataProvider provider) {
    return new CharacterFriendRepository(
        DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
  }

  private static MockResult[] ownerResolvedCountResult(int count) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    Field<Integer> countField = DSL.field("count", Integer.class);
    Record1<Integer> countRow = resultDsl.newRecord(countField);
    countRow.set(countField, count);
    Result<Record1<Integer>> countResult = resultDsl.newResult(countField);
    countResult.add(countRow);
    return new MockResult[] {new MockResult(1, countResult)};
  }

  private static CharacterFriend friend(Long characterId, Long friendId) {
    CharacterFriendKey key = new CharacterFriendKey();
    key.setCharacterId(characterId);
    key.setFriendId(friendId);
    CharacterFriend entity = new CharacterFriend();
    entity.setId(key);
    entity.setTenantId(TENANT_ID);
    entity.setCreatedAt(Instant.parse("2024-01-03T00:00:00Z"));
    return entity;
  }
}
