package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.OriginalTenantCreationOutcomeReadGrpcService;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical PostgreSQL definitions with controlled Account binding and peer-context inputs. */
class OriginalTenantCreationOutcomeReadPostgresIntegrationTest {
  private static final String ACCOUNT_URI =
      "spiffe://firemud/ns/fresh-read-test/sa/account-service";
  private static final String NAMESPACE = "fresh-read-test";
  private static final String SOURCE_KEY = "fresh-read-tenant-01";
  private static final String NAME = "Readback World";
  private static final String DESCRIPTION = "Controlled PostgreSQL read fixture";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String ACCOUNT_DIGEST = "sha256:" + "b".repeat(64);
  private static final GameDesignPostgresIntegrationFixture POSTGRES =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void resolvesCreatedOutcomeWithActualV30ReceiptWithoutChangingCommittedRows() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();
    Created original =
        fixture.inTransaction(
            () -> {
              fixture.outcomes.claimLocked(binding);
              FreshTenantCreationEvidence receipt =
                  fixture.creation.createCandidate(
                      NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
              fixture.outcomes.markCreatedLocked(binding, receipt);
              return new Created(receipt, rowState(fixture.dsl, binding));
            });

    ResolveOriginalTenantCreationOutcomeRequest request = request(binding);
    ReadObserver observer = resolve(fixture.service, request, ACCOUNT_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    GameTenantCreationOriginalOutcomeEvidence decoded =
        GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(
            GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest(NAMESPACE, request),
            observer.response);
    assertThat(decoded.outcome())
        .isEqualTo(GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED);
    assertThat(decoded.receipt()).contains(original.receipt());
    assertThat(rowState(fixture.dsl, binding)).isEqualTo(original.rowState());
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_tenant_creation_operations"))))
        .isEqualTo(1);
    assertThat(
            fixture.dsl.fetchCount(DSL.table(DSL.name("game_tenant_creation_original_outcomes"))))
        .isEqualTo(1);
  }

  @Test
  void resolvesOnlyDurableNotCreatedTombstoneAndLeavesItUnchanged() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();
    String tombstoneState =
        fixture.inTransaction(
            () -> {
              fixture.outcomes.closeNotCreatedLocked(binding);
              return rowState(fixture.dsl, binding);
            });

    ResolveOriginalTenantCreationOutcomeRequest request = request(binding);
    ReadObserver observer = resolve(fixture.service, request, ACCOUNT_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    GameTenantCreationOriginalOutcomeEvidence decoded =
        GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(
            GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest(NAMESPACE, request),
            observer.response);
    assertThat(decoded.outcome())
        .isEqualTo(GameTenantCreationOriginalOutcomeEvidence.Outcome.NOT_CREATED);
    assertThat(decoded.receipt()).isEmpty();
    assertThat(rowState(fixture.dsl, binding)).isEqualTo(tombstoneState);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_tenant_creation_operations"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game")))).isZero();
  }

  @Test
  void missingOutcomeRemainsNotFoundAndDoesNotInferOrWriteNoCreation() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();
    String before = rowState(fixture.dsl, binding);

    ResolveOriginalTenantCreationOutcomeRequest request = request(binding);
    ReadObserver observer = resolve(fixture.service, request, ACCOUNT_URI);

    assertThat(observer.failure).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(observer.response).isNull();
    assertThat(fixture.outcomes.readCommitted(binding)).isEmpty();
    assertThat(rowState(fixture.dsl, binding)).isEqualTo(before);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_tenant_creation_operations"))))
        .isZero();
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game")))).isZero();
  }

  private static Fixture fixture() {
    String schema = "gd_original_outcome_read_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource();
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    var transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository creation = new GameTenantCreationRepository(dsl, gameRepository);
    GameTenantCreationOriginalOutcomeRepository outcomes =
        new GameTenantCreationOriginalOutcomeRepository(dsl);
    OriginalTenantCreationOutcomeReadGrpcService service =
        new OriginalTenantCreationOutcomeReadGrpcService(outcomes, creation, NAMESPACE);
    return new Fixture(dsl, outcomes, creation, service, transactionTemplate);
  }

  private static String rowState(DSLContext dsl, OriginalOutcomeBinding binding) {
    var outcome =
        dsl.fetchOne(
            "SELECT row_to_json(original_outcome)::TEXT AS row_bytes, xmin::TEXT AS xmin "
                + "FROM game_tenant_creation_original_outcomes original_outcome "
                + "WHERE target_namespace = ? AND creation_request_id = ?",
            binding.targetNamespace(),
            binding.creationRequestId());
    var operation =
        dsl.fetchOne(
            "SELECT row_to_json(operation)::TEXT AS row_bytes, xmin::TEXT AS xmin "
                + "FROM game_tenant_creation_operations operation "
                + "WHERE target_namespace = ? AND creation_request_id = ?",
            binding.targetNamespace(),
            binding.creationRequestId());
    var game =
        dsl.fetchOne(
            "SELECT row_to_json(source_game)::TEXT AS row_bytes, xmin::TEXT AS xmin "
                + "FROM game source_game WHERE tenant_id = ?",
            SOURCE_KEY);
    return row(outcome) + "|" + row(operation) + "|" + row(game);
  }

  private static String row(org.jooq.Record row) {
    return row == null
        ? "absent"
        : row.get("row_bytes", String.class) + ":" + row.get("xmin", String.class);
  }

  private static OriginalOutcomeBinding binding() {
    return new OriginalOutcomeBinding(
        NAMESPACE,
        REQUEST_ID,
        ACCOUNT_OPERATION_ID,
        ACCOUNT_ID,
        GameTenantCreationDigest.requestDigest(
            NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION),
        ACCOUNT_DIGEST);
  }

  private static ResolveOriginalTenantCreationOutcomeRequest request(
      OriginalOutcomeBinding binding) {
    return GameTenantCreationOriginalOutcomeGrpcCodec.toReadRequest(
        new GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest(
            UUID.randomUUID(),
            new GameTenantCreationOriginalOutcomeEvidence.Binding(
                binding.targetNamespace(),
                binding.creationRequestId(),
                binding.accountAuthorizationOperationId(),
                binding.initiatingAccountId(),
                binding.creationRequestDigest(),
                binding.accountAuthorizationDigest())));
  }

  private static ReadObserver resolve(
      OriginalTenantCreationOutcomeReadGrpcService service,
      ResolveOriginalTenantCreationOutcomeRequest request,
      String peerUri) {
    ReadObserver observer = new ReadObserver();
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.resolveOriginalTenantCreationOutcome(request, observer));
    return observer;
  }

  private record Created(FreshTenantCreationEvidence receipt, String rowState) {}

  private record Fixture(
      DSLContext dsl,
      GameTenantCreationOriginalOutcomeRepository outcomes,
      GameTenantCreationRepository creation,
      OriginalTenantCreationOutcomeReadGrpcService service,
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

  private static final class ReadObserver
      implements StreamObserver<ResolveOriginalTenantCreationOutcomeResponse> {
    private ResolveOriginalTenantCreationOutcomeResponse response;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(ResolveOriginalTenantCreationOutcomeResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
