package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.ChatMessages.CHAT_MESSAGES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.ChatMessage;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.jooq.tables.records.ChatMessagesRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ChatMessageRepository {
  private final DSLContext dsl;

  public ChatMessageRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ChatMessage> findByTenantIdAndEffectId(Long tenantId, String effectId) {
    return dsl.selectFrom(CHAT_MESSAGES)
        .where(CHAT_MESSAGES.TENANT_ID.eq(tenantId).and(CHAT_MESSAGES.EFFECT_ID.eq(effectId)))
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public ChatMessage save(ChatMessage entity) {
    if (entity.getId() == null) {
      ChatMessagesRecord record = dsl.newRecord(CHAT_MESSAGES);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(CHAT_MESSAGES)
            .set(CHAT_MESSAGES.TENANT_ID, entity.getTenantId())
            .set(CHAT_MESSAGES.SENDER_ACCOUNT_ID, entity.getSenderAccountId())
            .set(CHAT_MESSAGES.CONTENT, entity.getContent())
            .set(
                CHAT_MESSAGES.TIMESTAMP,
                JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getTimestamp()))
            .set(CHAT_MESSAGES.GUILD_ID, entity.getGuildId())
            .set(CHAT_MESSAGES.CITY_ID, entity.getCityId())
            .set(CHAT_MESSAGES.RECIPIENT_ACCOUNT_ID, entity.getRecipientAccountId())
            .set(CHAT_MESSAGES.EFFECT_ID, entity.getEffectId())
            .set(CHAT_MESSAGES.TYPE, entity.getType() == null ? null : entity.getType().name())
            .where(CHAT_MESSAGES.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(CHAT_MESSAGES.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<ChatMessage> findById(Long id) {
    return dsl.selectFrom(CHAT_MESSAGES)
        .where(CHAT_MESSAGES.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(ChatMessagesRecord record, ChatMessage entity) {
    record.setTenantId(entity.getTenantId());
    record.setSenderAccountId(entity.getSenderAccountId());
    record.setContent(entity.getContent());
    record.setTimestamp(JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getTimestamp()));
    record.setGuildId(entity.getGuildId());
    record.setCityId(entity.getCityId());
    record.setRecipientAccountId(entity.getRecipientAccountId());
    record.setEffectId(entity.getEffectId());
    record.setType(entity.getType() == null ? null : entity.getType().name());
  }

  private ChatMessage toEntity(Record record) {
    ChatMessage entity = new ChatMessage();
    entity.setId(record.get(CHAT_MESSAGES.ID));
    entity.setTenantId(record.get(CHAT_MESSAGES.TENANT_ID));
    entity.setSenderAccountId(record.get(CHAT_MESSAGES.SENDER_ACCOUNT_ID));
    entity.setContent(record.get(CHAT_MESSAGES.CONTENT));
    entity.setTimestamp(
        JooqSocialGroupsRepositorySupport.toInstant(record.get(CHAT_MESSAGES.TIMESTAMP)));
    entity.setGuildId(record.get(CHAT_MESSAGES.GUILD_ID));
    entity.setCityId(record.get(CHAT_MESSAGES.CITY_ID));
    entity.setRecipientAccountId(record.get(CHAT_MESSAGES.RECIPIENT_ACCOUNT_ID));
    entity.setEffectId(record.get(CHAT_MESSAGES.EFFECT_ID));
    String type = record.get(CHAT_MESSAGES.TYPE);
    entity.setType(type == null ? null : ChatType.valueOf(type));
    return entity;
  }
}
