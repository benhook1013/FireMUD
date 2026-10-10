package net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.Kind;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoffRepository.Status;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic repository-unit proof for the disclosure/World-participation seam. */
class HostedTermsDisclosureHandoffWorldParticipationTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void preparationDeniesPendingWorldParticipationBeforeWritingDisclosureJournal() throws Exception {
    Fixture fixture = new Fixture(Status.PREPARED, false);
    fixture.begin();
    doAnswer(
            invocation -> {
              throw new IllegalStateException(
                  "Original StartSession World participation remains pending");
            })
        .when(fixture.fenceRepository)
        .requireDisclosurePreparation(fixture.handoff.sources());

    assertThatThrownBy(() -> fixture.repository.prepare(fixture.handoff))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("World participation remains pending");

    verify(fixture.fenceRepository).lockDisclosureSources(fixture.handoff.sources());
    verify(fixture.fenceRepository).requireDisclosurePreparation(fixture.handoff.sources());
    assertThat(fixture.executedSql).isEmpty();
  }

  @Test
  void dispatchDenialChecksWorldBeforeJournalReadAndDoesNotAdvanceAmbiguousRetry()
      throws Exception {
    Fixture fixture = new Fixture(Status.AMBIGUOUS, true);
    fixture.begin();
    doAnswer(
            invocation -> {
              throw new IllegalStateException(
                  "Original StartSession World participation remains pending");
            })
        .when(fixture.fenceRepository)
        .requireWorldParticipationDisclosureAdmission(fixture.handoff.sources());

    assertThatThrownBy(() -> fixture.repository.authorizeDispatch(fixture.handoff))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("World participation remains pending");

    verify(fixture.fenceRepository)
        .requireWorldParticipationDisclosureAdmission(fixture.handoff.sources());
    verify(fixture.fenceRepository, never())
        .requireDisclosurePreparation(fixture.handoff.sources());
    assertThat(fixture.journalReads).isZero();
    assertThat(fixture.executedSql).isEmpty();
  }

  @Test
  void settledWorldParticipationKeepsAmbiguousRetryAndSourceLockOrdering() throws Exception {
    Fixture fixture = new Fixture(Status.AMBIGUOUS, true);
    fixture.begin();

    var permit = fixture.repository.authorizeDispatch(fixture.handoff);

    assertThat(permit.newlyAuthorized()).isTrue();
    assertThat(permit.snapshot().status()).isEqualTo(Status.DISPATCH_AUTHORIZED);
    assertThat(permit.snapshot().dispatchAttempts()).isEqualTo(3);
    var order = inOrder(fixture.fenceRepository, fixture.dsl);
    order
        .verify(fixture.fenceRepository)
        .requireWorldParticipationDisclosureAdmission(fixture.handoff.sources());
    order.verify(fixture.dsl, times(4)).fetch(anyString(), any(Object[].class));
    verify(fixture.fenceRepository, never())
        .requireDisclosurePreparation(fixture.handoff.sources());
    assertThat(fixture.executedSql)
        .containsExactly(
            "UPDATE account_hosted_terms_disclosure_handoffs"
                + " SET status = 'DISPATCH_AUTHORIZED', dispatch_attempts = dispatch_attempts + 1 "
                + "WHERE handoff_id = ? AND status = ?");
  }

  private static final class Fixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final Connection connection = mock(Connection.class);
    private final DraftAuthorizationFenceRepository fenceRepository =
        mock(DraftAuthorizationFenceRepository.class);
    private final HostedTermsDisclosureHandoff handoff = handoff();
    private final HostedTermsDisclosureHandoffRepository repository =
        new HostedTermsDisclosureHandoffRepository(dsl, fenceRepository);
    private final List<String> executedSql = new ArrayList<>();
    private final Record handoffRow;
    private final Record sourceRow;
    private final Result<Record> handoffRows;
    private final Result<Record> sourceRows;
    private final Result<Record> noRows = result(List.of());
    private int journalReads;

    private Fixture(Status initialStatus, boolean includeHandoff) throws Exception {
      Map<String, Object> handoffFields = new HashMap<>();
      handoffFields.put("handoff_id", handoff.handoffId());
      handoffFields.put("request_id", handoff.requestId());
      handoffFields.put("kind", handoff.kind().name());
      handoffFields.put("source_key", handoff.sourceKey());
      handoffFields.put("predecessor_digest", handoff.predecessorDigest());
      handoffFields.put("candidate_digest", handoff.candidateDigest());
      handoffFields.put(
          "effective_at", OffsetDateTime.ofInstant(handoff.effectiveAt(), ZoneOffset.UTC));
      handoffFields.put("binding", handoff.canonicalBytes());
      handoffFields.put("binding_digest", HostedTermsEncoding.digest(handoff.canonicalBytes()));
      handoffFields.put("status", initialStatus.name());
      handoffFields.put("dispatch_attempts", 2);
      handoffFields.put("result_outcome", null);
      handoffFields.put("result_payload", null);
      handoffFields.put("result_digest", null);
      handoffFields.put("result_recorded_at", null);
      handoffFields.put("created_at", OffsetDateTime.parse("2026-10-08T00:00:00Z"));
      handoffRow = record(handoffFields);

      SourceEvidence source = handoff.sources().getFirst();
      Map<String, Object> sourceFields = new HashMap<>();
      sourceFields.put("source_key", source.key());
      sourceFields.put("source_evidence", source.canonicalBytes());
      sourceFields.put(
          "source_evidence_digest", HostedTermsEncoding.digest(source.canonicalBytes()));
      sourceRow = record(sourceFields);
      handoffRows = result(includeHandoff ? List.of(handoffRow) : List.of());
      sourceRows = result(List.of(sourceRow));

      doReturn(false).when(connection).getAutoCommit();
      doReturn(false).when(connection).isReadOnly();
      doReturn(Connection.TRANSACTION_READ_COMMITTED).when(connection).getTransactionIsolation();
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
                executedSql.add(statement);
                if (statement.contains("dispatch_attempts = dispatch_attempts + 1")) {
                  handoffFields.put("status", Status.DISPATCH_AUTHORIZED.name());
                  handoffFields.put("dispatch_attempts", 3);
                }
                return 1;
              })
          .when(dsl)
          .execute(anyString(), any(Object[].class));
      doAnswer(
              invocation -> {
                String statement = invocation.getArgument(0);
                if (statement.contains("account_hosted_terms_disclosure_handoffs")) {
                  journalReads++;
                  return handoffRows;
                }
                if (statement.contains("account_hosted_terms_disclosure_sources")) {
                  return sourceRows;
                }
                return noRows;
              })
          .when(dsl)
          .fetch(anyString(), any(Object[].class));
    }

    private void begin() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
  }

  private static HostedTermsDisclosureHandoff handoff() {
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            UUID.randomUUID().toString(),
            null,
            "7",
            null,
            null,
            new byte[] {1, 2, 3});
    return new HostedTermsDisclosureHandoff(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Kind.CATALOG,
        source.key(),
        HostedTermsEncoding.digest(new byte[] {4}),
        HostedTermsEncoding.digest(new byte[] {5}),
        Instant.parse("2026-10-08T00:00:00.123456Z"),
        List.of(source),
        "test-only-authority-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private static Record record(Map<String, Object> fields) {
    Record record = mock(Record.class);
    doAnswer(invocation -> fields.get(invocation.getArgument(0)))
        .when(record)
        .get(anyString(), any(Class.class));
    return record;
  }

  @SuppressWarnings("unchecked")
  private static Result<Record> result(List<Record> records) {
    Result<Record> result = mock(Result.class);
    doReturn(records.size()).when(result).size();
    doReturn(records.isEmpty()).when(result).isEmpty();
    doReturn(records.isEmpty() ? null : records.getFirst()).when(result).getFirst();
    doAnswer(invocation -> records.stream()).when(result).stream();
    return result;
  }
}
