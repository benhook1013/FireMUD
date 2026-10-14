package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTER_FRIEND;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class CharacterFriendRepository {
  private final DSLContext dsl;

  public CharacterFriendRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Page<CharacterFriend> findByIdCharacterId(Long characterId, Pageable pageable) {
    Condition condition =
        CHARACTER_FRIEND.CHARACTER_ID.eq(characterId).and(legacyFriendEndpoints());
    long total = dsl.fetchCount(CHARACTER_FRIEND, condition);
    var content =
        dsl.selectFrom(CHARACTER_FRIEND)
            .where(condition)
            .orderBy(CHARACTER_FRIEND.FRIEND_ID.asc())
            .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  public long countByTenantId(Long tenantId) {
    return dsl.fetchCount(
        CHARACTER_FRIEND, CHARACTER_FRIEND.TENANT_ID.eq(tenantId).and(legacyFriendEndpoints()));
  }

  public Optional<CharacterFriend> findById(CharacterFriendKey key) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTER_FRIEND)
            .where(
                CHARACTER_FRIEND
                    .CHARACTER_ID
                    .eq(key.getCharacterId())
                    .and(CHARACTER_FRIEND.FRIEND_ID.eq(key.getFriendId()))
                    .and(legacyFriendEndpoints()))
            .fetchOne(this::toEntity));
  }

  public CharacterFriend save(CharacterFriend entity) {
    if (findById(entity.getId()).isEmpty()) {
      int inserted =
          dsl.insertInto(
                  CHARACTER_FRIEND,
                  CHARACTER_FRIEND.CHARACTER_ID,
                  CHARACTER_FRIEND.FRIEND_ID,
                  CHARACTER_FRIEND.TENANT_ID,
                  CHARACTER_FRIEND.CREATED_AT)
              .select(
                  dsl.select(
                          DSL.val(entity.getId().getCharacterId(), CHARACTER_FRIEND.CHARACTER_ID),
                          DSL.val(entity.getId().getFriendId(), CHARACTER_FRIEND.FRIEND_ID),
                          DSL.val(entity.getTenantId(), CHARACTER_FRIEND.TENANT_ID),
                          DSL.val(
                              toLocalDateTime(entity.getCreatedAt()), CHARACTER_FRIEND.CREATED_AT))
                      .where(
                          CharacterRepository.legacyCharacterExists(
                                  DSL.val(
                                      entity.getId().getCharacterId(),
                                      CHARACTER_FRIEND.CHARACTER_ID))
                              .and(
                                  CharacterRepository.legacyCharacterExists(
                                      DSL.val(
                                          entity.getId().getFriendId(),
                                          CHARACTER_FRIEND.FRIEND_ID)))))
              .execute();
      if (inserted != 1) {
        throw new IllegalStateException("Legacy friend endpoints are unavailable");
      }
    }
    return entity;
  }

  public void deleteById(CharacterFriendKey key) {
    dsl.deleteFrom(CHARACTER_FRIEND)
        .where(
            CHARACTER_FRIEND
                .CHARACTER_ID
                .eq(key.getCharacterId())
                .and(CHARACTER_FRIEND.FRIEND_ID.eq(key.getFriendId()))
                .and(legacyFriendEndpoints()))
        .execute();
  }

  private static Condition legacyFriendEndpoints() {
    return CharacterRepository.legacyCharacterExists(CHARACTER_FRIEND.CHARACTER_ID)
        .and(CharacterRepository.legacyCharacterExists(CHARACTER_FRIEND.FRIEND_ID));
  }

  private CharacterFriend toEntity(Record record) {
    if (record == null) {
      return null;
    }
    CharacterFriend entity = new CharacterFriend();
    CharacterFriendKey key = new CharacterFriendKey();
    key.setCharacterId(record.get(CHARACTER_FRIEND.CHARACTER_ID));
    key.setFriendId(record.get(CHARACTER_FRIEND.FRIEND_ID));
    entity.setId(key);
    entity.setTenantId(record.get(CHARACTER_FRIEND.TENANT_ID));
    entity.setCreatedAt(toInstant(record.get(CHARACTER_FRIEND.CREATED_AT)));
    return entity;
  }
}
