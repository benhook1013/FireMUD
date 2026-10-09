package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Flyway/PostgreSQL owner-read proof over explicitly stipulated test-only original bindings.
 * The database rows, separate Account owner transaction, immutable order/readback state, and V99
 * source guards are real. The creator/source capture and workload Context are test fixtures only;
 * this proves neither authenticated creator issuance nor physical mTLS or a production caller.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountDraftCommitOrderReadPostgresIntegrationTest {
  private static final String NAMESPACE = "held-order-proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void acceptsOnlyExactCommittedHeldOrderInSeparateWritableReadCommittedTransaction()
      throws Exception {
    Fixture fixture = fixture();
    DraftAuthorizationFenceBinding binding = binding(fixture);
    fixture.tx(
        () -> {
          fixture.repository.reserve(binding);
          fixture.repository.claimCommitOrder(binding);
          return null;
        });
    var before = snapshot(fixture);
    assertThat(ordering(fixture, binding)).isEqualTo("COMMIT_ORDER");
    fixture.transactions.reset();

    asWorld(() -> fixture.reader.requireHeld(request(binding)));

    assertThat(fixture.transactions.beginCount()).isOne();
    assertThat(fixture.transactions.lastDefinition().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(fixture.transactions.lastDefinition().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(fixture.transactions.lastDefinition().isReadOnly()).isFalse();
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertThat(fixture.tx(() -> fixture.repository.readSettlement(binding)))
        .isEqualTo(Settlement.PENDING);

    // V99's real source guard remains effective while this original owner result is unresolved.
    String passwordBefore = accountPassword(fixture, binding.actorAccountId());
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> {
                      fixture.dsl.execute(
                          "UPDATE accounts SET password_hash = ? WHERE account_uuid = ?",
                          "test-only-blocked-change",
                          binding.actorAccountId());
                      return null;
                    }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("Required creator source is held");
    assertThat(accountPassword(fixture, binding.actorAccountId())).isEqualTo(passwordBefore);
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  @Test
  void acceptsExactGameLogicParticipantAndPreservesOriginalBindingBytes() throws Exception {
    Fixture fixture = fixture();
    DraftAuthorizationFenceBinding binding = binding(fixture, true);
    assertThat(binding.schemaVersion()).isEqualTo(DraftAuthorizationFenceBinding.SCHEMA_V2);
    assertThat(binding.requiredOwners()).contains(Owner.GAME_LOGIC);
    reserveAndCommitOrder(fixture, binding);
    var before = snapshot(fixture);
    fixture.transactions.reset();

    asGameLogic(() -> fixture.reader.requireHeld(request(binding)));

    assertThat(fixture.transactions.beginCount()).isOne();
    assertThat(fixture.transactions.lastDefinition().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(fixture.transactions.lastDefinition().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(fixture.transactions.lastDefinition().isReadOnly()).isFalse();
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertThat(fixture.tx(() -> fixture.repository.readSettlement(binding)))
        .isEqualTo(Settlement.PENDING);
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT binding FROM account_draft_authorization_fences WHERE operation_id = ?",
                        binding.operationId()))
                .get("binding", byte[].class))
        .containsExactly(binding.canonicalBytes());
  }

  @Test
  void deniesNonparticipatingGameLogicBeforeDatabaseAccessOrMutation() throws Exception {
    Fixture fixture = fixture();
    DraftAuthorizationFenceBinding binding = binding(fixture);
    reserveAndCommitOrder(fixture, binding);
    var before = snapshot(fixture);
    fixture.transactions.reset();

    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () -> asGameLogic(() -> fixture.reader.requireHeld(request(binding))));

    assertThat(fixture.transactions.beginCount()).isZero();
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT binding FROM account_draft_authorization_fences WHERE operation_id = ?",
                        binding.operationId()))
                .get("binding", byte[].class))
        .containsExactly(binding.canonicalBytes());
  }

  @Test
  void deniesReservedRevokedSettledChangedAndMissingBindings() {
    for (ReadCase readCase : ReadCase.values()) {
      Fixture fixture = fixture();
      DraftAuthorizationFenceBinding original = binding(fixture, true);
      DraftAuthorizationFenceBinding requested = original;
      switch (readCase) {
        case RESERVED -> fixture.tx(() -> fixture.repository.reserve(original));
        case REVOKE_ORDER -> {
          fixture.tx(() -> fixture.repository.reserve(original));
          var change =
              new DraftAuthorizationFenceRepository.SourceChange(
                  UUID.randomUUID(), original.sources(), new byte[] {9});
          assertThat(fixture.tx(() -> fixture.repository.requestSourceChange(change))).isFalse();
          assertThat(ordering(fixture, original)).isEqualTo("REVOKE_ORDER");
        }
        case SETTLED -> {
          reserveAndCommitOrder(fixture, original);
          recordReadback(fixture, original, Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {1});
          recordReadback(fixture, original, Owner.WORLD, Outcome.COMMITTED, new byte[] {2});
          recordReadback(fixture, original, Owner.GAME_LOGIC, Outcome.COMMITTED, new byte[] {3});
          assertThat(fixture.tx(() -> fixture.repository.readSettlement(original)))
              .isEqualTo(Settlement.COMMITTED);
        }
        case CHANGED_BINDING -> {
          reserveAndCommitOrder(fixture, original);
          requested = changedBinding(original);
        }
        case MISSING -> requested = binding(fixture, true);
      }

      var before = snapshot(fixture);
      fixture.transactions.reset();
      DraftAuthorizationFenceBinding submitted = requested;
      assertStatus(
          Status.Code.FAILED_PRECONDITION,
          () -> asGameLogic(() -> fixture.reader.requireHeld(request(submitted))));
      assertThat(fixture.transactions.beginCount()).isOne();
      assertThat(snapshot(fixture)).isEqualTo(before);
      if (readCase != ReadCase.MISSING) {
        assertThat(
                Objects.requireNonNull(
                        fixture.dsl.fetchOne(
                            "SELECT binding FROM account_draft_authorization_fences WHERE operation_id = ?",
                            original.operationId()))
                    .get("binding", byte[].class))
            .containsExactly(original.canonicalBytes());
      }
    }
  }

  @Test
  void rejectsMissingAndWrongWorkloadContextBeforeOpeningOwnerTransaction() {
    Fixture fixture = fixture();
    DraftAuthorizationFenceBinding binding = binding(fixture);
    reserveAndCommitOrder(fixture, binding);
    var request = request(binding);
    fixture.transactions.reset();

    assertStatus(Status.Code.UNAUTHENTICATED, () -> fixture.reader.requireHeld(request));
    assertThat(fixture.transactions.beginCount()).isZero();
    for (String uri :
        List.of(
            "spiffe://firemud/ns/held-order-proof/sa/account-service",
            "spiffe://firemud/ns/other/sa/world-management-service")) {
      assertStatus(
          Status.Code.PERMISSION_DENIED,
          () -> asPeer(uri, () -> fixture.reader.requireHeld(request)));
      assertThat(fixture.transactions.beginCount()).isZero();
    }
  }

  private static Fixture fixture() {
    String schema = "held_order_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(postgres.getJdbcUrl());
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("latest")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    CountingTransactionManager transactions =
        new CountingTransactionManager(new DataSourceTransactionManager(source));
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    DraftAuthorizationFenceRepository repository = new DraftAuthorizationFenceRepository(dsl);
    AccountDraftCommitOrderReadService reader =
        new AccountDraftCommitOrderReadService(repository, transactions, NAMESPACE);
    return new Fixture(dsl, repository, transactions, transaction, reader);
  }

  private static DraftAuthorizationFenceBinding binding(Fixture fixture) {
    return binding(fixture, false);
  }

  private static DraftAuthorizationFenceBinding binding(
      Fixture fixture, boolean gameLogicRequired) {
    UUID account =
        fixture.tx(
            () -> {
              Account row = new Account();
              String suffix = UUID.randomUUID().toString();
              row.setUsername("held-order-" + suffix);
              row.setEmail(suffix + "@example.test");
              row.setPasswordHash("test-only-original-hash");
              return new AccountRepository(fixture.dsl).save(row).getAccountUuid();
            });
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    List<RevisionPayload> revisions =
        new ArrayList<>(
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-change")));
    List<AffectedUnit> affectedUnits =
        new ArrayList<>(
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "REGION",
                    "test-region",
                    "AGGREGATE",
                    "test-region",
                    "0")));
    if (gameLogicRequired) {
      revisions.add(
          new RevisionPayload(
              "1", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_LOGIC, "test-rule-change"));
      affectedUnits.add(
          new AffectedUnit(
              DraftCommitBinding.Owner.GAME_LOGIC,
              "RULE_SET",
              "test-rules",
              "AGGREGATE",
              "test-rules",
              "0"));
    }
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new TargetProof(
                tenant, version, 17L, "test-tenant", 23L, "test-tenant", "NEW_GAME_ROW"),
            request,
            commit,
            "test-base",
            revisions,
            affectedUnits);
    byte[] draftBytes = complete.canonicalBytes();
    DraftAuthorizationFenceBinding binding =
        new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            request,
            commit,
            UUID.randomUUID(),
            account,
            tenant,
            version,
            complete.baseCommitId(),
            "0",
            draftBytes,
            draftBytes,
            complete.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    account.toString(),
                    "1",
                    "1",
                    "test-account-stream/" + account,
                    "0",
                    new byte[] {7})));
    return gameLogicRequired ? binding.withRequiredOwners() : binding;
  }

  private static DraftAuthorizationFenceBinding changedBinding(
      DraftAuthorizationFenceBinding original) {
    SourceEvidence source = original.sources().getFirst();
    return new DraftAuthorizationFenceBinding(
        original.operationId(),
        original.requestId(),
        original.commitId(),
        original.fenceId(),
        original.actorAccountId(),
        original.tenantId(),
        original.versionId(),
        original.baseCommitId(),
        original.expectedDraftEpoch(),
        original.gameDesignBinding(),
        original.normalizedInput(),
        original.inputDigest(),
        List.of(
            new SourceEvidence(
                source.kind(),
                source.scopeId(),
                source.generation(),
                source.sourceVersion(),
                source.checkpointStream(),
                source.checkpointSequence(),
                new byte[] {8})),
        original.schemaVersion(),
        original.requiredOwners());
  }

  private static void reserveAndCommitOrder(
      Fixture fixture, DraftAuthorizationFenceBinding binding) {
    fixture.tx(
        () -> {
          fixture.repository.reserve(binding);
          fixture.repository.claimCommitOrder(binding);
          return null;
        });
  }

  private static void recordReadback(
      Fixture fixture,
      DraftAuthorizationFenceBinding binding,
      Owner owner,
      Outcome outcome,
      byte[] result) {
    OwnerReadback readback =
        new OwnerReadback(
            owner,
            outcome,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            result);
    fixture.tx(
        () -> {
          fixture.repository.recordOwnerReadback(binding, readback);
          return null;
        });
  }

  private static DraftCommitOrderReadEvidence.Request request(
      DraftAuthorizationFenceBinding binding) {
    return DraftCommitOrderReadEvidence.Request.create(NAMESPACE, binding.canonicalBytes());
  }

  private static String ordering(Fixture fixture, DraftAuthorizationFenceBinding binding) {
    return Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT ordering FROM account_draft_authorization_fences WHERE operation_id = ?",
                binding.operationId()))
        .get("ordering", String.class);
  }

  private static String accountPassword(Fixture fixture, UUID accountId) {
    return Objects.requireNonNull(
            fixture.dsl.fetchOne(
                "SELECT password_hash FROM accounts WHERE account_uuid = ?", accountId))
        .get("password_hash", String.class);
  }

  private static EvidenceSnapshot snapshot(Fixture fixture) {
    return new EvidenceSnapshot(
        fixture.dsl.fetch("SELECT * FROM account_draft_authorization_fences ORDER BY operation_id"),
        fixture.dsl.fetch(
            "SELECT * FROM account_draft_authorization_sources ORDER BY operation_id, source_key"),
        fixture.dsl.fetch(
            "SELECT * FROM account_draft_authorization_owner_readbacks ORDER BY operation_id, owner"),
        fixture.dsl.fetch(
            "SELECT * FROM account_draft_authorization_source_locks ORDER BY source_key"));
  }

  private static void assertStatus(Status.Code expected, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(expected);
  }

  private static void asWorld(Runnable operation) {
    asPeer("spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service", operation);
  }

  private static void asGameLogic(Runnable operation) {
    asPeer("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-logic-service", operation);
  }

  private static void asPeer(String uri, Runnable operation) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      operation.run();
    } finally {
      context.detach(previous);
    }
  }

  private enum ReadCase {
    RESERVED,
    REVOKE_ORDER,
    SETTLED,
    CHANGED_BINDING,
    MISSING
  }

  private record EvidenceSnapshot(
      List<Record> fences,
      List<Record> sources,
      List<Record> ownerReadbacks,
      List<Record> sourceLocks) {}

  private record Fixture(
      DSLContext dsl,
      DraftAuthorizationFenceRepository repository,
      CountingTransactionManager transactions,
      TransactionTemplate transaction,
      AccountDraftCommitOrderReadService reader) {
    private <T> T tx(Supplier<T> work) {
      return transaction.execute(status -> work.get());
    }
  }

  /**
   * Counts and records owner transaction definitions while delegating to real JDBC transactions.
   */
  private static final class CountingTransactionManager implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final AtomicInteger begins = new AtomicInteger();
    private volatile TransactionDefinition lastDefinition;

    private CountingTransactionManager(PlatformTransactionManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      begins.incrementAndGet();
      lastDefinition = definition;
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) {
      delegate.commit(status);
    }

    @Override
    public void rollback(TransactionStatus status) {
      delegate.rollback(status);
    }

    private int beginCount() {
      return begins.get();
    }

    private TransactionDefinition lastDefinition() {
      return Objects.requireNonNull(lastDefinition);
    }

    private void reset() {
      begins.set(0);
      lastDefinition = null;
    }
  }
}
