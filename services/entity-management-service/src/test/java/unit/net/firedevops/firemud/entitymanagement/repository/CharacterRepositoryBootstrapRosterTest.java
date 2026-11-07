package unit.net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterActorProjection;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterFixture;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled SQL-result mapping only; this does not execute a writer or PostgreSQL/mTLS. */
class CharacterRepositoryBootstrapRosterTest {
  @Test
  void nullableStoredLevelRefusesTheEntireProjectionWithBoundedPrecondition() throws Exception {
    var source = AccountBootstrapRosterFixture.create();
    var fixture =
        new SqlRows(
            source,
            new ActorRow(UUID.fromString("11111111-1111-4111-8111-111111111111"), "Valid", 7),
            new ActorRow(UUID.fromString("22222222-2222-4222-8222-222222222222"), "Missing", null));

    assertThatThrownBy(fixture::read)
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LEVEL_NULL");
    assertThat(fixture.queries).hasSize(2);
  }

  @Test
  void actualZeroAndNonzeroLevelsPreserveTheirSameRowUuidAndName() throws Exception {
    var source = AccountBootstrapRosterFixture.create();
    UUID zeroActor = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID nonzeroActor = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var fixture =
        new SqlRows(
            source,
            new ActorRow(zeroActor, "Zero Level", 0),
            new ActorRow(nonzeroActor, "Nonzero Level", 7));

    assertThat(fixture.read())
        .containsExactly(
            new AccountBootstrapRosterActorProjection(zeroActor, "Zero Level", 0),
            new AccountBootstrapRosterActorProjection(nonzeroActor, "Nonzero Level", 7));
    assertThat(fixture.queries).hasSize(2);
  }

  @Test
  void missingOwnerTransactionRefusesBeforeSql() throws Exception {
    var fixture = new SqlRows(AccountBootstrapRosterFixture.create());

    assertThatThrownBy(
            () -> fixture.repository.readAccountBootstrapRoster(fixture.account, fixture.target))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Account bootstrap discovery requires owner transaction");
    assertThat(fixture.queries).isEmpty();
  }

  private record ActorRow(UUID characterUuid, String displayName, Integer level) {}

  private static final class SqlRows {
    final UUID account;
    final CanonicalGameplayRosterTarget target;
    final CharacterRepository repository;
    final List<String> queries = new ArrayList<>();

    SqlRows(AccountBootstrapRosterFixture.Fixture source, ActorRow... actors) {
      account = UUID.fromString(source.request().getCanonicalAccountUuid());
      target = source.target();
      var resultDsl = DSL.using(SQLDialect.POSTGRES);
      MockDataProvider provider =
          context -> {
            String sql = context.sql().toLowerCase(Locale.ROOT);
            queries.add(sql);
            assertThat(sql).startsWith("select ");
            if (sql.contains("entity_playable_state_namespace_scopes")) {
              assertThat(context.bindings())
                  .containsExactly(target.tenantUuid(), target.playableStateNamespaceId());
              var field = ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE;
              var rows = resultDsl.newResult(field);
              rows.add(resultDsl.newRecord(field).values(target.playableStateScope().name()));
              return new MockResult[] {new MockResult(rows.size(), rows)};
            }
            assertThat(sql).contains("characters");
            assertThat(context.bindings())
                .contains(
                    account,
                    target.tenantUuid(),
                    target.playableStateNamespaceId(),
                    target.playableStateScope().name(),
                    "OWNER_RESOLVED");
            var rows =
                resultDsl.newResult(CHARACTERS.CHARACTER_UUID, CHARACTERS.NAME, CHARACTERS.LEVEL);
            for (var actor : actors) {
              rows.add(
                  resultDsl
                      .newRecord(CHARACTERS.CHARACTER_UUID, CHARACTERS.NAME, CHARACTERS.LEVEL)
                      .values(actor.characterUuid(), actor.displayName(), actor.level()));
            }
            return new MockResult[] {new MockResult(rows.size(), rows)};
          };
      repository =
          new CharacterRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    }

    List<AccountBootstrapRosterActorProjection> read() {
      boolean active = TransactionSynchronizationManager.isActualTransactionActive();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        return repository.readAccountBootstrapRoster(account, target);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(active);
      }
    }
  }
}
