package net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.jooq.tables.MailMessages.MAIL_MESSAGES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.MailMessage;
import net.firedevops.firemud.socialgroups.jooq.tables.records.MailMessagesRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class MailMessageRepository {
  private final DSLContext dsl;

  public MailMessageRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public MailMessage save(MailMessage entity) {
    if (entity.getId() == null) {
      MailMessagesRecord record = dsl.newRecord(MAIL_MESSAGES);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(MAIL_MESSAGES)
            .set(MAIL_MESSAGES.TENANT_ID, entity.getTenantId())
            .set(MAIL_MESSAGES.SENDER_ACCOUNT_ID, entity.getSenderAccountId())
            .set(MAIL_MESSAGES.RECIPIENT_ACCOUNT_ID, entity.getRecipientAccountId())
            .set(MAIL_MESSAGES.SUBJECT, entity.getSubject())
            .set(MAIL_MESSAGES.CONTENT, entity.getContent())
            .set(
                MAIL_MESSAGES.SENT_AT,
                JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getSentAt()))
            .set(
                MAIL_MESSAGES.READ_AT,
                JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getReadAt()))
            .where(MAIL_MESSAGES.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqSocialGroupsRepositorySupport.staleWrite(MAIL_MESSAGES.getName(), entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<MailMessage> findById(Long id) {
    return dsl.selectFrom(MAIL_MESSAGES)
        .where(MAIL_MESSAGES.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(MailMessagesRecord record, MailMessage entity) {
    record.setTenantId(entity.getTenantId());
    record.setSenderAccountId(entity.getSenderAccountId());
    record.setRecipientAccountId(entity.getRecipientAccountId());
    record.setSubject(entity.getSubject());
    record.setContent(entity.getContent());
    record.setSentAt(JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getSentAt()));
    record.setReadAt(JooqSocialGroupsRepositorySupport.toLocalDateTime(entity.getReadAt()));
  }

  private MailMessage toEntity(Record record) {
    MailMessage entity = new MailMessage();
    entity.setId(record.get(MAIL_MESSAGES.ID));
    entity.setTenantId(record.get(MAIL_MESSAGES.TENANT_ID));
    entity.setSenderAccountId(record.get(MAIL_MESSAGES.SENDER_ACCOUNT_ID));
    entity.setRecipientAccountId(record.get(MAIL_MESSAGES.RECIPIENT_ACCOUNT_ID));
    entity.setSubject(record.get(MAIL_MESSAGES.SUBJECT));
    entity.setContent(record.get(MAIL_MESSAGES.CONTENT));
    entity.setSentAt(
        JooqSocialGroupsRepositorySupport.toInstant(record.get(MAIL_MESSAGES.SENT_AT)));
    entity.setReadAt(
        JooqSocialGroupsRepositorySupport.toInstant(record.get(MAIL_MESSAGES.READ_AT)));
    return entity;
  }
}
