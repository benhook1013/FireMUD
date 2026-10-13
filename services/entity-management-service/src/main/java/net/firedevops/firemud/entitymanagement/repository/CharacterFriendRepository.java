package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTER_FRIEND;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey;
import org.jooq.DSLContext;
import org.jooq.Record;
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
    long total = dsl.fetchCount(CHARACTER_FRIEND, CHARACTER_FRIEND.CHARACTER_ID.eq(characterId));
    var content =
        dsl.selectFrom(CHARACTER_FRIEND)
            .where(CHARACTER_FRIEND.CHARACTER_ID.eq(characterId))
            .orderBy(CHARACTER_FRIEND.FRIEND_ID.asc())
            .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(offsetOrZero(pageable))
            .fetch(this::toEntity);
    return JooqEntityManagementRepositorySupport.page(content, pageable, total);
  }

  public long countByTenantId(Long tenantId) {
    return dsl.fetchCount(CHARACTER_FRIEND, CHARACTER_FRIEND.TENANT_ID.eq(tenantId));
  }

  public Optional<CharacterFriend> findById(CharacterFriendKey key) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTER_FRIEND)
            .where(
                CHARACTER_FRIEND
                    .CHARACTER_ID
                    .eq(key.getCharacterId())
                    .and(CHARACTER_FRIEND.FRIEND_ID.eq(key.getFriendId())))
            .fetchOne(this::toEntity));
  }

  public CharacterFriend save(CharacterFriend entity) {
    if (findById(entity.getId()).isEmpty()) {
      dsl.insertInto(CHARACTER_FRIEND)
          .set(CHARACTER_FRIEND.CHARACTER_ID, entity.getId().getCharacterId())
          .set(CHARACTER_FRIEND.FRIEND_ID, entity.getId().getFriendId())
          .set(CHARACTER_FRIEND.TENANT_ID, entity.getTenantId())
          .set(CHARACTER_FRIEND.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
          .execute();
    }
    return entity;
  }

  public void deleteById(CharacterFriendKey key) {
    dsl.deleteFrom(CHARACTER_FRIEND)
        .where(
            CHARACTER_FRIEND
                .CHARACTER_ID
                .eq(key.getCharacterId())
                .and(CHARACTER_FRIEND.FRIEND_ID.eq(key.getFriendId())))
        .execute();
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
