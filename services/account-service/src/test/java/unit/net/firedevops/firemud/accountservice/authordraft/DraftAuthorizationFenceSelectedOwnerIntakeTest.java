package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic Account owner tests; PostgreSQL trigger installation is not proven here. */
class DraftAuthorizationFenceSelectedOwnerIntakeTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pendingSelectedOwnerReservationBlocksDisclosurePreparation() throws Exception {
    Fixture fixture = new Fixture(true);
    fixture.begin();

    assertThatThrownBy(
            () -> fixture.repository.requireDisclosurePreparation(List.of(fixture.currentSource)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Selected owner intake source read remains pending");

    assertThat(fixture.fetchedSql)
        .anyMatch(sql -> sql.contains("account_selected_owner_intake_source_read_sources"))
        .noneMatch(sql -> sql.contains("SELECT * FROM account_draft_authorization_fences"));
    assertThat(fixture.executedSql)
        .noneMatch(sql -> sql.contains("UPDATE account_draft_authorization_fences"));
  }

  @Test
  void currentCreatorMaySelectAnotherActorsExactSettledOriginalDraft() throws Exception {
    Fixture fixture = new Fixture(false);
    fixture.begin();
    UUID originalAuthor = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID currentCreator = UUID.fromString("22222222-2222-4222-8222-222222222222");
    assertThat(fixture.originalBinding.actorAccountId()).isEqualTo(originalAuthor);
    assertThat(fixture.currentSource.scopeId()).isEqualTo(currentCreator.toString());

    fixture.repository.requireSelectedOwnerIntakeAdmission(
        List.of(fixture.currentSource), fixture.selected);

    assertThat(fixture.fetchedSql)
        .anyMatch(sql -> sql.contains("WHERE request_id = ? AND commit_id = ? FOR UPDATE"))
        .anyMatch(sql -> sql.contains("account_draft_authorization_owner_readbacks"));
  }

  @Test
  void substitutedSelectedBindingCannotUseAnOriginalSettlement() throws Exception {
    Fixture fixture = new Fixture(false);
    fixture.begin();
    DraftCommitBinding changed =
        selected(fixture.selected.requestId(), fixture.selected.commitId(), "changed");

    assertThatThrownBy(
            () ->
                fixture.repository.requireSelectedOwnerIntakeAdmission(
                    List.of(fixture.currentSource), changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed and settled");
  }

  private static final class Fixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final Connection connection = mock(Connection.class);
    private final Record lockRow = mock(Record.class);
    private final DraftCommitBinding selected =
        selected(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "selected");
    private final UUID originalAuthor = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final SourceEvidence originalSource = source(originalAuthor);
    private final UUID currentCreator = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final SourceEvidence currentSource = source(currentCreator);
    private final DraftAuthorizationFenceBinding originalBinding = originalBinding();
    private final Record originalRow = recordForBinding(originalBinding);
    private final Result<Record> originalSources = result(List.of(sourceRow(originalSource)));
    private final Result<Record> committedReadbacks = result(List.of(readbackRow(originalBinding)));
    private final Result<Record> emptyRows = result(List.of());
    private final Result<Record> pendingRows = result(List.of(mock(Record.class)));
    private final boolean pending;
    private final List<String> fetchedSql = new java.util.ArrayList<>();
    private final List<String> executedSql = new java.util.ArrayList<>();
    private final DraftAuthorizationFenceRepository repository =
        new DraftAuthorizationFenceRepository(dsl);

    private Fixture(boolean pending) throws Exception {
      this.pending = pending;
      doAnswer(invocation -> false).when(connection).getAutoCommit();
      doAnswer(invocation -> false).when(connection).isReadOnly();
      doAnswer(invocation -> Connection.TRANSACTION_READ_COMMITTED)
          .when(connection)
          .getTransactionIsolation();
      doAnswer(
              invocation -> {
                invocation.<ConnectionRunnable>getArgument(0).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      doAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                executedSql.add(sql);
                return 1;
              })
          .when(dsl)
          .execute(anyString(), any(Object[].class));
      doAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                fetchedSql.add(sql);
                if (sql.contains("account_selected_owner_intake_source_read_sources")) {
                  return pending ? pendingRows : emptyRows;
                }
                if (sql.contains("account_draft_authorization_owner_readbacks")) {
                  return committedReadbacks;
                }
                if (sql.contains("account_draft_authorization_sources WHERE operation_id")) {
                  return originalSources;
                }
                return emptyRows;
              })
          .when(dsl)
          .fetch(anyString(), any(Object[].class));
      doAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                fetchedSql.add(sql);
                if (sql.contains("WHERE request_id = ? AND commit_id = ?")) return originalRow;
                if (sql.contains("account_draft_authorization_source_locks")) return lockRow;
                return null;
              })
          .when(dsl)
          .fetchOne(anyString(), any(Object[].class));
    }

    private void begin() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private DraftAuthorizationFenceBinding originalBinding() {
      var original =
          new DraftAuthorizationFenceBinding(
              UUID.fromString("55555555-5555-4555-8555-555555555555"),
              selected.requestId(),
              selected.commitId(),
              UUID.fromString("66666666-6666-4666-8666-666666666666"),
              originalAuthor,
              selected.target().canonicalTenantId(),
              selected.target().canonicalVersionId(),
              selected.baseCommitId(),
              "0",
              selected.canonicalBytes(),
              selected.canonicalBytes(),
              selected.digest(),
              List.of(originalSource));
      return original.withRequiredOwners();
    }
  }

  private static DraftCommitBinding selected(UUID request, UUID commit, String payload) {
    UUID tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID version = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            tenant, version, 9L, "tenant-key", 10L, "tenant-key", "NEW_GAME_ROW"),
        request,
        commit,
        "base-1",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULES",
                "rules",
                "TENANT",
                tenant.toString(),
                "0")));
  }

  private static SourceEvidence source(UUID actor) {
    return new SourceEvidence(
        SourceKind.ACCOUNT, actor.toString(), "1", "1", null, null, new byte[] {1, 2, 3});
  }

  private static Record recordForBinding(DraftAuthorizationFenceBinding binding) {
    Record record = mock(Record.class);
    doReturn(binding.operationId()).when(record).get("operation_id", UUID.class);
    doReturn(binding.requestId()).when(record).get("request_id", UUID.class);
    doReturn(binding.commitId()).when(record).get("commit_id", UUID.class);
    doReturn(binding.fenceId()).when(record).get("fence_id", UUID.class);
    doReturn(binding.canonicalBytes()).when(record).get("binding", byte[].class);
    doReturn("COMMIT_ORDER").when(record).get("ordering", String.class);
    return record;
  }

  private static Record sourceRow(SourceEvidence source) {
    Record row = mock(Record.class);
    doReturn(source.key()).when(row).get("source_key", String.class);
    doReturn(source.canonicalBytes()).when(row).get("source_evidence", byte[].class);
    return row;
  }

  private static Record readbackRow(DraftAuthorizationFenceBinding binding) {
    OwnerReadback readback =
        new OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            new byte[] {9});
    Record row = mock(Record.class);
    doReturn(Owner.GAME_DESIGN.name()).when(row).get("owner", String.class);
    doReturn(Outcome.COMMITTED.name()).when(row).get("outcome", String.class);
    doReturn(readback.canonicalBytes()).when(row).get("readback", byte[].class);
    return row;
  }

  @SuppressWarnings("unchecked")
  private static Result<Record> result(List<Record> rows) {
    Result<Record> result = mock(Result.class);
    doReturn(rows.size()).when(result).size();
    doReturn(rows.isEmpty()).when(result).isEmpty();
    doAnswer(invocation -> rows.iterator()).when(result).iterator();
    doAnswer(invocation -> rows.stream()).when(result).stream();
    return result;
  }
}
