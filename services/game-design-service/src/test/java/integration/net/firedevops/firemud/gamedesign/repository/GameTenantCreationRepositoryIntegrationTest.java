package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

class GameTenantCreationRepositoryIntegrationTest {
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final org.jooq.Field<UUID> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final String NAMESPACE = "fresh-tenant-test";
  private static final String SOURCE_KEY = "new-game-tenant-01";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final GameDesignPostgresIntegrationFixture postgres =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void exactRetryReturnsCommittedReceiptWithoutSecondGameWrite() {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence first =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(first);
    FreshTenantCreationEvidence exactRetry =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    assertThat(exactRetry).isEqualTo(first);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void completedCreationEvidenceCannotBeChangedOrDeleted() {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence receipt =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null));

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(OPERATIONS)
                    .set(DSL.field(DSL.name("name"), String.class), "Changed")
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .deleteFrom(OPERATIONS)
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(receipt);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  private Fixture fixture() {
    String schema = "game_design_creation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource();
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    return new Fixture(dsl, repository, transactionTemplate);
  }

  private record Fixture(
      DSLContext dsl,
      GameTenantCreationRepository repository,
      TransactionTemplate transactionTemplate) {
    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
      return transactionTemplate.execute(
          status -> {
            try {
              return work.call();
            } catch (RuntimeException exception) {
              throw exception;
            } catch (Exception exception) {
              throw new IllegalStateException(exception);
            }
          });
    }
  }
}
