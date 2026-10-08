package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository.BirthSourceUnavailableException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountPlatformRestrictionBirthRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountPlatformRestrictionBirthRepository repository =
      new AccountPlatformRestrictionBirthRepository(dsl);

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactReadRequiresWritableMandatoryTransaction() {
    assertThatThrownBy(() -> repository.readCurrentBirthSource(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.readCurrentBirthSource(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
    verifyNoInteractions(dsl);
  }

  @Test
  void missingAccountAndUnmappedIdentityDenyWithoutEnrollment() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> repository.readCurrentBirthSource(new UUID(0, 0)))
        .isInstanceOf(BirthSourceUnavailableException.class);
    verifyNoInteractions(dsl);
    assertThatThrownBy(() -> repository.readCurrentBirthSource(UUID.randomUUID()))
        .isInstanceOf(BirthSourceUnavailableException.class);
  }

  @Test
  void missingProjectionChangedRevisionAndDigestSubstitutionDenyExactReadback() {
    for (String defect :
        new String[] {"missing", "revision", "eventDigest", "bothDigests", "nullResult"}) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      UUID account = UUID.randomUUID();
      DSLContext ownerDsl = mock(DSLContext.class);
      Map<Category, Map<String, Object>> births = new HashMap<>();
      Map<Category, Map<String, Object>> projections = new HashMap<>();
      Map<Category, Map<String, Object>> events = new HashMap<>();
      for (Category category : Category.values()) {
        Map<String, Object> birth = birth(account, category);
        births.put(category, birth);
        projections.put(category, new HashMap<>(birth));
        events.put(category, new HashMap<>(birth));
      }
      Category lock = Category.ACCOUNT_SECURITY_LOCK;
      if (defect.equals("missing")) projections.remove(lock);
      if (defect.equals("revision")) projections.get(lock).put("revision", 2L);
      if (defect.equals("eventDigest") || defect.equals("bothDigests")) {
        events.get(lock).put("payload_digest", "0".repeat(64));
      }
      if (defect.equals("bothDigests")) births.get(lock).put("payload_digest", "0".repeat(64));
      if (defect.equals("nullResult")) {
        births.get(lock).put("result_id", null);
        projections.get(lock).put("result_id", null);
        events.get(lock).put("result_id", null);
      }
      doAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("FROM accounts"))
                  return record(
                      Map.of(
                          "id",
                          7L,
                          "account_uuid",
                          account,
                          "account_uuid_provenance",
                          "ACCOUNT_REPOSITORY_INSERT",
                          "account_uuid_source_numeric_id",
                          7L,
                          "account_repository_insert_transaction_id",
                          19L,
                          "current_xid",
                          23L));
                if (sql.contains("FROM account_authority_generations"))
                  return record(Map.of("generation", 1L, "source_version", 1L));
                if (sql.contains("FROM account_authority_issuance_fences"))
                  return record(Map.of("issuance_fence", 1L, "source_version", 1L));
                Object[] arguments = (Object[]) invocation.getRawArguments()[1];
                Category category =
                    Category.ACCOUNT_SECURITY_LOCK.storageValue().equals(arguments[1])
                        ? Category.ACCOUNT_SECURITY_LOCK
                        : Category.PLATFORM_ACCESS_BAN;
                if (sql.contains("FROM account_platform_restriction_births"))
                  return record(births.get(category));
                if (sql.contains("FROM account_platform_restriction_projections"))
                  return record(projections.get(category));
                if (sql.contains("FROM account_platform_restriction_birth_outbox"))
                  return record(events.get(category));
                throw new AssertionError("Unexpected source read: " + sql);
              })
          .when(ownerDsl)
          .fetchOne(anyString(), any(Object[].class));
      assertThatThrownBy(
              () ->
                  new AccountPlatformRestrictionBirthRepository(ownerDsl)
                      .readCurrentBirthSource(account))
          .as(defect)
          .isInstanceOf(BirthSourceUnavailableException.class);
    }
  }

  private static Map<String, Object> birth(UUID account, Category category) {
    UUID operation = UUID.randomUUID();
    UUID result = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    ScopeState authority =
        new ScopeState(AuthorityScope.account(account), 1, 1, new IssuanceFence(account, 1, 1));
    Map<String, Object> row = new HashMap<>();
    row.put("account_uuid", account);
    row.put("category", category.storageValue());
    row.put("revision", 1L);
    row.put("enforcement_epoch", 1L);
    row.put("result_id", result);
    row.put("operation_id", operation);
    row.put("event_id", event);
    row.put("source_kind", "CATEGORY");
    row.put("restriction_state", "NONRESTRICTED");
    row.put("account_source_numeric_id", 7L);
    row.put("account_insert_transaction_id", 19L);
    row.put("account_generation", 1L);
    row.put("account_source_version", 1L);
    row.put("issuance_fence", 1L);
    row.put("fence_source_version", 1L);
    row.put("outbox_sequence", 1L);
    row.put(
        "outbox_stream_key", AccountPlatformRestrictionBirthSource.streamKey(account, category));
    row.put(
        "payload_digest",
        AccountPlatformRestrictionBirthSource.birthDigest(
            account, 7, 19, authority, category, operation, result, event));
    return row;
  }

  private static Record record(Map<String, Object> values) {
    if (values == null) return null;
    Record row = mock(Record.class);
    doAnswer(invocation -> values.get(invocation.getArgument(0))).when(row).get(anyString());
    doAnswer(invocation -> values.get(invocation.getArgument(0)))
        .when(row)
        .get(anyString(), any(Class.class));
    return row;
  }
}
