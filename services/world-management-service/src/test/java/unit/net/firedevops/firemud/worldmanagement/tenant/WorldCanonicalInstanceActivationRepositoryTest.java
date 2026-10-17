package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.exception.TooManyRowsException;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceActivationRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycle =
      mock(WorldCanonicalInstanceLifecycleReadRepository.class);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void immutableResultReadRejectsAmbientTransactionBeforeRepositoryOrDatabaseUse() {
    var repository = new WorldCanonicalInstanceActivationRepository(dsl, manager, lifecycle);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readResult(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");

    verifyNoInteractions(dsl, manager, lifecycle);
  }

  @Test
  void activationRejectsAmbientTransactionBeforeOpeningItsOwnerCas() {
    var repository = new WorldCanonicalInstanceActivationRepository(dsl, manager, lifecycle);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                repository.activate(
                    request(),
                    new WorldCanonicalInstanceActivationService.HeldActivationAuthority() {
                      public void requireHeld() {}

                      public void close() {}
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");

    verifyNoInteractions(dsl, manager, lifecycle);
  }

  @Test
  void protectedActivationRecoveryRejectsAmbientTransactionBeforeAnyRead() {
    var repository = new WorldCanonicalInstanceActivationRepository(dsl, manager, lifecycle);
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readProtectedActivationByAcquisitionInput(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");

    verifyNoInteractions(dsl, manager, lifecycle);
  }

  @Test
  void synchronizationOnlyScopeCannotJoinProtectedActivationOrHistoricalRead() {
    var repository = new WorldCanonicalInstanceActivationRepository(dsl, manager, lifecycle);
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> repository.readProtectedActivationByAcquisitionInput(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    assertThatThrownBy(() -> repository.readResult(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(dsl, manager, lifecycle);
  }

  @Test
  void multipleProtectedFenceRowsBecomeAnActivationConflictInsteadOfJooqCardinalityFailure()
      throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var input = fixture.input();
    String query =
        "SELECT * FROM world_canonical_instance_activation_fence "
            + "WHERE activation_request_id=? OR canonical_game_instance_id=?";
    assertThatThrownBy(
            () ->
                context(provider(fixture, 2, 0, new AtomicInteger(), new AtomicInteger()))
                    .fetchOne(
                        query,
                        input.worldActivationRequest().activationRequestId(),
                        input.worldActivationRequest().canonicalGameInstanceId()))
        .isInstanceOf(TooManyRowsException.class);

    AtomicInteger protectedQueries = new AtomicInteger();
    AtomicInteger legacyQueries = new AtomicInteger();
    var repository = repository(provider(fixture, 2, 0, protectedQueries, legacyQueries));

    assertThatThrownBy(() -> repository.readProtectedActivationByAcquisitionInput(input))
        .isInstanceOf(WorldCanonicalInstanceActivationRepository.ActivationConflictException.class)
        .hasMessageContaining("Multiple World activation fences");
    assertThat(protectedQueries).hasValue(1);
    assertThat(legacyQueries).hasValue(0);
  }

  @Test
  void multipleLegacyRowsRemainAnActivationConflictInsteadOfJooqCardinalityFailure()
      throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var input = fixture.input();
    String query =
        "SELECT 1 FROM world_canonical_instance_activation_operation "
            + "WHERE activation_request_id=? "
            + "OR (canonical_game_instance_id=? AND outcome='COMMITTED')";
    assertThatThrownBy(
            () ->
                context(provider(fixture, 0, 2, new AtomicInteger(), new AtomicInteger()))
                    .fetchOne(
                        query,
                        input.worldActivationRequest().activationRequestId(),
                        input.worldActivationRequest().canonicalGameInstanceId()))
        .isInstanceOf(TooManyRowsException.class);

    AtomicInteger protectedQueries = new AtomicInteger();
    AtomicInteger legacyQueries = new AtomicInteger();
    var repository = repository(provider(fixture, 0, 2, protectedQueries, legacyQueries));

    assertThatThrownBy(() -> repository.readProtectedActivationByAcquisitionInput(input))
        .isInstanceOf(WorldCanonicalInstanceActivationRepository.ActivationConflictException.class)
        .hasMessageContaining("retained legacy World activation result");
    assertThat(protectedQueries).hasValue(1);
    assertThat(legacyQueries).hasValue(1);
  }

  @Test
  void protectedRecoveryKeepsEmptyAndSinglePendingFenceReadbacks() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    AtomicInteger emptyProtectedQueries = new AtomicInteger();
    AtomicInteger emptyLegacyQueries = new AtomicInteger();
    var emptyRepository =
        repository(provider(fixture, 0, 0, emptyProtectedQueries, emptyLegacyQueries));

    assertThat(emptyRepository.readProtectedActivationByAcquisitionInput(fixture.input()))
        .isEmpty();
    assertThat(emptyProtectedQueries).hasValue(1);
    assertThat(emptyLegacyQueries).hasValue(1);

    AtomicInteger pendingProtectedQueries = new AtomicInteger();
    AtomicInteger pendingLegacyQueries = new AtomicInteger();
    var pendingRepository =
        repository(provider(fixture, 1, 0, pendingProtectedQueries, pendingLegacyQueries));

    assertThat(pendingRepository.readProtectedActivationByAcquisitionInput(fixture.input()))
        .hasValueSatisfying(readback -> assertThat(readback.pending()).isTrue());
    assertThat(pendingProtectedQueries).hasValue(1);
    assertThat(pendingLegacyQueries).hasValue(0);
  }

  private static WorldCanonicalInstanceActivationRepository repository(MockDataProvider provider) {
    MockConnection connection = new MockConnection(provider);
    DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
    return new WorldCanonicalInstanceActivationRepository(
        dsl,
        new DataSourceTransactionManager(new SingleConnectionDataSource(connection, true)),
        mock(WorldCanonicalInstanceLifecycleReadRepository.class));
  }

  private static DSLContext context(MockDataProvider provider) {
    return DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
  }

  private static MockDataProvider provider(
      WorldOriginalStartSessionActivationTransportTestSupport.Fixture fixture,
      int protectedRowCount,
      int legacyRowCount,
      AtomicInteger protectedQueries,
      AtomicInteger legacyQueries) {
    DSLContext rowDsl = DSL.using(SQLDialect.POSTGRES);
    return context -> {
      String sql = context.sql().toLowerCase(Locale.ROOT);
      if (sql.contains("current_setting('transaction_isolation')")) {
        Field<String> isolation = DSL.field(DSL.name("isolation"), String.class);
        Field<String> readOnly = DSL.field(DSL.name("read_only"), String.class);
        Result<Record> rows = rowDsl.newResult(new Field<?>[] {isolation, readOnly});
        Record row = rowDsl.newRecord(isolation, readOnly);
        row.set(isolation, "repeatable read");
        row.set(readOnly, "on");
        rows.add(row);
        return new MockResult[] {new MockResult(1, rows)};
      }
      if (sql.contains("world_canonical_instance_activation_fence")) {
        protectedQueries.incrementAndGet();
        Result<Record> rows = protectedRows(rowDsl, fixture, protectedRowCount);
        return new MockResult[] {new MockResult(rows.size(), rows)};
      }
      if (sql.contains("world_canonical_instance_activation_operation")) {
        legacyQueries.incrementAndGet();
        Result<Record> rows = legacyRows(rowDsl, fixture.input(), legacyRowCount);
        return new MockResult[] {new MockResult(rows.size(), rows)};
      }
      throw new AssertionError("Unexpected SQL in read-only activation recovery: " + context.sql());
    };
  }

  private static Result<Record> protectedRows(
      DSLContext rowDsl,
      WorldOriginalStartSessionActivationTransportTestSupport.Fixture fixture,
      int rowCount) {
    if (rowCount == 1) return pendingProtectedRow(rowDsl, fixture);
    Field<UUID> activationId = DSL.field(DSL.name("activation_request_id"), UUID.class);
    Field<UUID> gameInstanceId = DSL.field(DSL.name("canonical_game_instance_id"), UUID.class);
    Result<Record> rows = rowDsl.newResult(new Field<?>[] {activationId, gameInstanceId});
    UUID requestedActivationId = fixture.input().worldActivationRequest().activationRequestId();
    UUID requestedGameInstanceId =
        fixture.input().worldActivationRequest().canonicalGameInstanceId();
    UUID otherId = UUID.fromString("99999999-9999-4999-8999-999999999999");
    if (rowCount > 0) {
      Record requestMatch = rowDsl.newRecord(activationId, gameInstanceId);
      requestMatch.set(activationId, requestedActivationId);
      requestMatch.set(gameInstanceId, otherId);
      rows.add(requestMatch);
    }
    if (rowCount > 1) {
      Record instanceMatch = rowDsl.newRecord(activationId, gameInstanceId);
      instanceMatch.set(activationId, otherId);
      instanceMatch.set(gameInstanceId, requestedGameInstanceId);
      rows.add(instanceMatch);
    }
    return rows;
  }

  private static Result<Record> pendingProtectedRow(
      DSLContext rowDsl, WorldOriginalStartSessionActivationTransportTestSupport.Fixture fixture) {
    Field<byte[]> evidenceBytes =
        DSL.field(DSL.name("account_protection_evidence_bytes"), byte[].class);
    Field<String> evidenceDigest =
        DSL.field(DSL.name("account_protection_evidence_digest"), String.class);
    Field<String> operationDigest =
        DSL.field(DSL.name("protection_operation_digest"), String.class);
    Field<UUID> protectionId = DSL.field(DSL.name("account_protection_id"), UUID.class);
    Field<Long> protectionFence = DSL.field(DSL.name("account_protection_fence"), Long.class);
    Field<String> operationState = DSL.field(DSL.name("operation_state"), String.class);
    Result<Record> rows =
        rowDsl.newResult(
            new Field<?>[] {
              evidenceBytes,
              evidenceDigest,
              operationDigest,
              protectionId,
              protectionFence,
              operationState
            });
    var protection = fixture.protection();
    byte[] bytes = protection.canonicalBytes();
    Record row =
        rowDsl.newRecord(
            evidenceBytes,
            evidenceDigest,
            operationDigest,
            protectionId,
            protectionFence,
            operationState);
    row.set(evidenceBytes, bytes);
    row.set(evidenceDigest, digest(bytes));
    row.set(operationDigest, protection.requestDigest());
    row.set(protectionId, protection.accountProtectionId());
    row.set(protectionFence, protection.accountProtectionFence());
    row.set(operationState, "PENDING");
    rows.add(row);
    return rows;
  }

  private static Result<Record> legacyRows(
      DSLContext rowDsl,
      AccountStartSessionActivationProtectionAcquisitionInput input,
      int rowCount) {
    Field<UUID> activationId = DSL.field(DSL.name("activation_request_id"), UUID.class);
    Field<UUID> gameInstanceId = DSL.field(DSL.name("canonical_game_instance_id"), UUID.class);
    Field<String> outcome = DSL.field(DSL.name("outcome"), String.class);
    Result<Record> rows = rowDsl.newResult(new Field<?>[] {activationId, gameInstanceId, outcome});
    UUID requestedActivationId = input.worldActivationRequest().activationRequestId();
    UUID requestedGameInstanceId = input.worldActivationRequest().canonicalGameInstanceId();
    UUID otherId = UUID.fromString("99999999-9999-4999-8999-999999999999");
    if (rowCount > 0) {
      Record requestMatch = rowDsl.newRecord(activationId, gameInstanceId, outcome);
      requestMatch.set(activationId, requestedActivationId);
      requestMatch.set(gameInstanceId, otherId);
      requestMatch.set(outcome, "ABORTED");
      rows.add(requestMatch);
    }
    if (rowCount > 1) {
      Record instanceMatch = rowDsl.newRecord(activationId, gameInstanceId, outcome);
      instanceMatch.set(activationId, otherId);
      instanceMatch.set(gameInstanceId, requestedGameInstanceId);
      instanceMatch.set(outcome, "COMMITTED");
      rows.add(instanceMatch);
    }
    return rows;
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static WorldCanonicalInstanceActivation.Request request() {
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.canonicalGameInstanceId())
        .thenReturn(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    var preparing = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(preparing.request()).thenReturn(lifecycleRequest);
    when(preparing.lifecycleStatus()).thenReturn("PREPARING");
    when(preparing.lifecycleEpoch()).thenReturn(1L);
    when(preparing.rowVersion()).thenReturn(0L);
    when(preparing.canonicalBytes())
        .thenReturn(
            ("{\"request\":{\"readRequestId\":\"11111111-1111-4111-8111-111111111111\"}}")
                .getBytes(StandardCharsets.UTF_8));
    return new WorldCanonicalInstanceActivation.Request(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), preparing);
  }
}
