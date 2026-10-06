package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import net.firedevops.firemud.accountservice.jooq.Tables;
import net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class AccountRealmAccessGrantRepositoryTest {
  @Test
  void existingGrantUpdateRequiresAnObservedGenerationBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRealmAccessGrant grant = existingGrant(null);

    assertThatThrownBy(() -> new AccountRealmAccessGrantRepository(dsl).save(grant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Realm grant update requires an observed grant-authority generation");

    verifyNoInteractions(dsl);
  }

  @Test
  void staleGenerationMakesTheUpdateCompareAndSwapMiss() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    AtomicReference<Object[]> boundValues = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          executedSql.set(context.sql().toLowerCase(Locale.ROOT).replace("\"", ""));
          boundValues.set(context.bindings());
          return new MockResult[] {
            new MockResult(
                0,
                DSL.using(SQLDialect.POSTGRES)
                    .newResult(
                        Tables.ACCOUNT_REALM_ACCESS_GRANT.GRANT_VERSION,
                        Tables.ACCOUNT_REALM_ACCESS_GRANT.GRANT_AUTHORITY_GENERATION))
          };
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    UUID observedGeneration = UUID.fromString("f8a89f86-3319-4f1d-9a59-58ec18fb5f54");

    assertThatThrownBy(
            () ->
                new AccountRealmAccessGrantRepository(dsl).save(existingGrant(observedGeneration)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Failed to update account_realm_access_grant id=41");

    String sql = executedSql.get();
    int where = sql.indexOf("where");
    assertThat(sql.substring(where))
        .contains("account_realm_access_grant.id", "grant_authority_generation");
    assertThat(boundValues.get()).contains(observedGeneration);
  }

  private static AccountRealmAccessGrant existingGrant(UUID generation) {
    Account account = new Account();
    account.setId(17L);
    AccountRealmAccessGrant grant = new AccountRealmAccessGrant();
    grant.setId(41L);
    grant.setAccount(account);
    grant.setTenantId(73L);
    grant.setWorldSlug("world");
    grant.setRealmSlug("private");
    grant.setGrantVersion(2L);
    grant.setGranted(true);
    grant.setGrantAuthorityGeneration(generation);
    grant.setGrantedBy("test");
    grant.setGrantReason("unit test");
    grant.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
    grant.setUpdatedAt(Instant.parse("2026-10-02T00:00:00Z"));
    return grant;
  }
}
