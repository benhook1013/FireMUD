package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic unit proof for Account's World-participation exclusion seam. */
class DraftAuthorizationFenceWorldParticipationTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void worldParticipationAdmissionRequiresWritableReadCommittedOwnerTransaction() throws Exception {
    Fixture fixture = new Fixture(false);

    assertThatThrownBy(
            () ->
                fixture.repository.requireWorldParticipationDisclosureAdmission(
                    List.of(fixture.source)))
        .isInstanceOf(IllegalStateException.class);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(
            () ->
                fixture.repository.requireWorldParticipationDisclosureAdmission(
                    List.of(fixture.source)))
        .isInstanceOf(IllegalStateException.class);

    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    fixture.begin();
    fixture.autoCommit = true;
    assertThatThrownBy(
            () ->
                fixture.repository.requireWorldParticipationDisclosureAdmission(
                    List.of(fixture.source)))
        .isInstanceOf(IllegalStateException.class);

    fixture.autoCommit = false;
    fixture.isolation = Connection.TRANSACTION_REPEATABLE_READ;
    assertThatThrownBy(
            () ->
                fixture.repository.requireWorldParticipationDisclosureAdmission(
                    List.of(fixture.source)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.lockedKeys).isEmpty();
  }

  @Test
  void pendingWorldParticipationBlocksDisclosureAdmissionAndChecksSortedExactKeys()
      throws Exception {
    Fixture fixture = new Fixture(true);
    fixture.begin();
    SourceEvidence first = source("a-first");
    SourceEvidence second = source("z-second");
    fixture.pendingWorldKey = second.key();

    assertThatThrownBy(
            () ->
                fixture.repository.requireWorldParticipationDisclosureAdmission(
                    List.of(second, first)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("World participation remains pending");

    assertThat(fixture.lockedKeys).containsExactly(first.key(), second.key());
    assertThat(fixture.worldKeys).containsExactly(first.key(), second.key());
    assertThat(fixture.eventOrder)
        .containsExactly(
            "lock:" + first.key(),
            "lock-row:" + first.key(),
            "lock:" + second.key(),
            "lock-row:" + second.key(),
            "world:" + first.key(),
            "world:" + second.key());
    assertThat(fixture.worldSql.getFirst())
        .contains("JOIN account_start_session_world_participations")
        .contains("NOT account_start_session_world_participation_is_settled");
  }

  @Test
  void disclosurePreparationRejectsPendingWorldParticipationWithoutOwnerOperationReads()
      throws Exception {
    Fixture fixture = new Fixture(true);
    fixture.begin();

    assertThatThrownBy(
            () -> fixture.repository.requireDisclosurePreparation(List.of(fixture.source)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("World participation remains pending");

    assertThat(fixture.worldKeys).containsExactly(fixture.source.key());
    assertThat(fixture.fetchedSql)
        .anyMatch(statement -> statement.contains("account_draft_authorization_source_changes"))
        .anyMatch(statement -> statement.contains("account_selected_publication_sources"))
        .anyMatch(statement -> statement.contains("account_game_logic_intake_sources"));
    assertThat(fixture.fetchedSql)
        .noneMatch(
            statement ->
                statement.contains("account_draft_authorization_fences")
                    || statement.contains(
                        "SELECT operation_id FROM account_draft_authorization_sources"));
    assertThat(fixture.sql).noneMatch(statement -> statement.contains("UPDATE "));
  }

  @Test
  void pendingWorldParticipationBlocksSourceMutationAndAbortWhileSettledKeepsPriorAdmission()
      throws Exception {
    Fixture pending = new Fixture(true);
    pending.begin();
    SourceChange pendingChange = sourceChange(pending.source);
    pending.installWaitingChange(pendingChange);

    assertThat(pending.repository.sourceMutationPermitted(pendingChange)).isFalse();
    assertThat(pending.repository.sourceAbortPermitted(pendingChange)).isFalse();
    assertThat(pending.sql).noneMatch(statement -> statement.contains("UPDATE "));

    Fixture settled = new Fixture(false);
    settled.begin();
    SourceChange settledChange = sourceChange(settled.source);
    settled.installWaitingChange(settledChange);

    assertThat(settled.repository.sourceMutationPermitted(settledChange)).isTrue();
    assertThat(settled.repository.sourceAbortPermitted(settledChange)).isTrue();
    assertThat(settled.worldKeys).containsExactly(settled.source.key(), settled.source.key());
  }

  @Test
  void disclosureSourceLockAcceptsCanonicalKeysThrough2048BytesWithoutAliasing() throws Exception {
    Fixture fixture = new Fixture(false);
    fixture.begin();
    SourceEvidence boundary = source("x".repeat(2035));
    assertThat(boundary.key().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(2048);

    fixture.repository.lockDisclosureSources(List.of(boundary));

    assertThat(fixture.lockedKeys).containsExactly(boundary.key());
    assertThatThrownBy(() -> source("x".repeat(2036)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2048 UTF-8 bytes");
  }

  private static SourceChange sourceChange(SourceEvidence source) {
    return new SourceChange(UUID.randomUUID(), List.of(source), new byte[] {1, 2, 3});
  }

  private static SourceEvidence source(String scope) {
    return new SourceEvidence(
        SourceKind.HOSTED_TERMS, scope, null, "1", null, null, new byte[] {4, 5, 6});
  }

  private static final class Fixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final Connection connection = mock(Connection.class);
    private final SourceEvidence source = source("world-participation-test");
    private final DraftAuthorizationFenceRepository repository =
        new DraftAuthorizationFenceRepository(dsl);
    private final List<String> lockedKeys = new ArrayList<>();
    private final List<String> worldKeys = new ArrayList<>();
    private final List<String> worldSql = new ArrayList<>();
    private final List<String> eventOrder = new ArrayList<>();
    private final List<String> sql = new ArrayList<>();
    private final List<String> fetchedSql = new ArrayList<>();
    private final Result<Record> emptyRows = rows(true);
    private final Result<Record> matchingRows = rows(false);
    private final Record lockRow = mock(Record.class);
    private final Record changeRow = mock(Record.class);
    private final boolean worldPending;
    private boolean autoCommit;
    private String pendingWorldKey;
    private int isolation = Connection.TRANSACTION_READ_COMMITTED;
    private Result<Record> changedRows = emptyRows;

    private Fixture(boolean worldPending) throws Exception {
      this.worldPending = worldPending;
      doAnswer(invocation -> autoCommit).when(connection).getAutoCommit();
      doAnswer(invocation -> false).when(connection).isReadOnly();
      doAnswer(invocation -> isolation).when(connection).getTransactionIsolation();
      doAnswer(
              invocation -> {
                invocation.<ConnectionRunnable>getArgument(0).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      doAnswer(
              invocation -> {
                String statement = invocation.getArgument(0);
                sql.add(statement);
                if (statement.startsWith("INSERT INTO account_draft_authorization_source_locks")) {
                  String sourceKey = invocation.getArgument(1, String.class);
                  lockedKeys.add(sourceKey);
                  eventOrder.add("lock:" + sourceKey);
                }
                return 1;
              })
          .when(dsl)
          .execute(anyString(), any(Object[].class));
      doAnswer(
              invocation -> {
                String statement = invocation.getArgument(0);
                fetchedSql.add(statement);
                if (statement.contains("account_start_session_world_participation_sources")) {
                  String sourceKey = invocation.getArgument(1, String.class);
                  worldKeys.add(sourceKey);
                  worldSql.add(statement);
                  eventOrder.add("world:" + sourceKey);
                  return worldPending
                          && (pendingWorldKey == null || pendingWorldKey.equals(sourceKey))
                      ? matchingRows
                      : emptyRows;
                }
                if (statement.contains("account_draft_authorization_changed_scopes")) {
                  return changedRows;
                }
                return emptyRows;
              })
          .when(dsl)
          .fetch(anyString(), any(Object[].class));
      doAnswer(
              invocation -> {
                String statement = invocation.getArgument(0);
                fetchedSql.add(statement);
                if (statement.contains("account_draft_authorization_source_changes")) {
                  return changeRow;
                }
                if (statement.contains("account_draft_authorization_source_locks")) {
                  String sourceKey = invocation.getArgument(1, String.class);
                  eventOrder.add("lock-row:" + sourceKey);
                }
                return lockRow;
              })
          .when(dsl)
          .fetchOne(anyString(), any(Object[].class));
    }

    private void begin() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private void installWaitingChange(SourceChange change) {
      doReturn(change.canonicalBytes()).when(changeRow).get("binding", byte[].class);
      doReturn("WAITING").when(changeRow).get("status", String.class);
      changedRows = rowsWithSource(change.sources().getFirst().key());
    }
  }

  @SuppressWarnings("unchecked")
  private static Result<Record> rows(boolean empty) {
    Result<Record> result = mock(Result.class);
    doReturn(empty).when(result).isEmpty();
    doReturn(List.of()).when(result).getValues("operation_id", UUID.class);
    doReturn(List.of()).when(result).getValues("source_key", String.class);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Result<Record> rowsWithSource(String sourceKey) {
    Result<Record> result = mock(Result.class);
    doReturn(List.of(sourceKey)).when(result).getValues("source_key", String.class);
    doReturn(false).when(result).isEmpty();
    return result;
  }
}
