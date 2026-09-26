package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountAuthorityOutboxRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountAuthorityOutboxRepository repository =
      new AccountAuthorityOutboxRepository(dsl);

  @Test
  void canonicalStreamKeysKeepExactMembershipScopeIdentity() {
    String membership =
        "account:auth-authority:v1:membership/11111111-1111-4111-8111-111111111111/"
            + "22222222-2222-4222-8222-222222222222";
    byte[] payload = {1, 2, 3};

    Event event = new Event(membership, "request-1", 1L, "event-1", "digest-1", payload);

    assertThat(event.outboxStreamKey()).isEqualTo(membership);
    assertThat(event.outboxSequence()).isEqualTo(1L);
    assertThat(event.payload()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    assertThat(new Checkpoint(membership, 1L, "event-1", "digest-1").outboxStreamKey())
        .isEqualTo(membership);
  }

  @Test
  void malformedKeysAndZeroCheckpointsFailBeforeStorageAccess() {
    assertThatThrownBy(() -> repository.findEvent("account:auth-authority:v1:", 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.findEvent("account:auth-authority:v1:account/abc", 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Checkpoint("account:auth-authority:v1:account/abc", 0L, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.append(
                    "account:auth-authority:v1:account/abc",
                    "request-1",
                    "event-1",
                    "digest-1",
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.append(
                    "account:auth-authority:v1:account/abc",
                    "r".repeat(513),
                    "event-1",
                    "digest-1",
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void returnedPayloadIsDefensivelyCopied() {
    byte[] original = {4, 5, 6};
    Event event =
        new Event(
            "account:auth-authority:v1:tenant/tenant-a",
            "request-1",
            1L,
            "event-1",
            "digest-1",
            original);
    original[0] = 9;

    byte[] readback = event.payload();
    readback[1] = 9;

    assertThat(event.payload()).containsExactly((byte) 4, (byte) 5, (byte) 6);
  }

  @Test
  void sequenceBoundFactoryReceivesAllocatedSequenceAndStoresCompletePayloadEvidence() {
    String streamKey = "account:auth-authority:v1:membership/account-a/tenant-a";
    stubAppend(8L, null);
    byte[] wirePayload = "{\"eventDigest\":\"sha256:wire-digest\"}".getBytes();
    AtomicLong producedSequence = new AtomicLong();

    Event event =
        repository.append(
            streamKey,
            "request-9",
            sequence -> {
              producedSequence.set(sequence);
              return new EventEvidence("event-9", "sha256:wire-digest", wirePayload);
            });

    assertThat(producedSequence).hasValue(9L);
    assertThat(event.outboxSequence()).isEqualTo(9L);
    assertThat(event.eventId()).isEqualTo("event-9");
    assertThat(event.eventDigest()).isEqualTo("sha256:wire-digest");
    assertThat(event.payload()).containsExactly(wirePayload);
    verify(dsl)
        .execute(startsWith("INSERT INTO account_authority_outbox_events"), any(Object[].class));
  }

  @Test
  void exactRetryFactoryReceivesCommittedSequenceAndReturnsStoredEvidence() {
    String streamKey = "account:auth-authority:v1:membership/account-a/tenant-a";
    stubAppend(
        7L,
        eventRecord(
            4L,
            "event-4",
            "sha256:wire-digest-4",
            "{\"eventDigest\":\"sha256:wire-digest-4\"}".getBytes(),
            "request-4"));
    AtomicLong producedSequence = new AtomicLong();

    Event event =
        repository.append(
            streamKey,
            "request-4",
            sequence -> {
              producedSequence.set(sequence);
              return new EventEvidence(
                  "event-4",
                  "sha256:wire-digest-4",
                  "{\"eventDigest\":\"sha256:wire-digest-4\"}".getBytes());
            });

    assertThat(producedSequence).hasValue(4L);
    assertThat(event.outboxSequence()).isEqualTo(4L);
    assertThat(event.eventDigest()).isEqualTo("sha256:wire-digest-4");
  }

  @Test
  void exactRetryWithChangedProducedEvidenceConflictsWithoutAdvancingHead() {
    stubAppend(
        7L,
        eventRecord(
            4L,
            "event-4",
            "sha256:wire-digest-4",
            "{\"eventDigest\":\"sha256:wire-digest-4\"}".getBytes(),
            "request-4"));

    assertThatThrownBy(
            () ->
                repository.append(
                    "account:auth-authority:v1:membership/account-a/tenant-a",
                    "request-4",
                    sequence ->
                        new EventEvidence(
                            "event-4",
                            "sha256:changed",
                            "{\"eventDigest\":\"sha256:changed\"}".getBytes())))
        .isInstanceOf(AccountAuthorityOutboxRepository.IdempotencyConflictException.class);

    verify(dsl, never())
        .fetchOne(startsWith("UPDATE account_authority_outbox_streams"), any(Object[].class));
  }

  @Test
  void factoryFailureCannotAdvanceHeadOrInsertEvent() {
    stubAppend(0L, null);

    assertThatThrownBy(
            () ->
                repository.append(
                    "account:auth-authority:v1:membership/account-a/tenant-a",
                    "request-1",
                    sequence -> {
                      throw new IllegalStateException("producer failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("producer failed");

    verify(dsl, never())
        .fetchOne(startsWith("UPDATE account_authority_outbox_streams"), any(Object[].class));
    verify(dsl, never())
        .execute(startsWith("INSERT INTO account_authority_outbox_events"), any(Object[].class));
  }

  @Test
  void eachUnrelatedStreamFactoryReceivesItsOwnNextSequence() {
    stubAppend(0L, null);
    List<Long> producedSequences = new ArrayList<>();

    Event first =
        repository.append(
            "account:auth-authority:v1:account/account-a",
            "request-1",
            sequence -> {
              producedSequences.add(sequence);
              return new EventEvidence("event-1", "digest-1", new byte[] {1});
            });
    Event otherStream =
        repository.append(
            "account:auth-authority:v1:tenant/tenant-b",
            "request-1",
            sequence -> {
              producedSequences.add(sequence);
              return new EventEvidence("event-1", "digest-1", new byte[] {1});
            });

    assertThat(producedSequences).containsExactly(1L, 1L);
    assertThat(first.outboxSequence()).isEqualTo(1L);
    assertThat(otherStream.outboxSequence()).isEqualTo(1L);
    assertThat(otherStream.outboxStreamKey())
        .isEqualTo("account:auth-authority:v1:tenant/tenant-b");
  }

  @Test
  void producedEvidenceValidatesBoundsAndDefensivelyCopiesPayload() {
    byte[] payload = {4, 5, 6};
    EventEvidence evidence = new EventEvidence("event-1", "digest-1", payload);
    payload[0] = 9;

    assertThat(evidence.payload()).containsExactly((byte) 4, (byte) 5, (byte) 6);
    assertThatThrownBy(() -> new EventEvidence("e".repeat(513), "digest", new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EventEvidence("event", "d".repeat(513), new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void positiveCheckpointReadbackReturnsItsExactEventIdentityAndDigest() {
    String streamKey = "account:auth-authority:v1:membership/account-a/tenant-a";
    Record stream = mock(Record.class);
    Record event = mock(Record.class);
    when(stream.get("last_sequence", Long.class)).thenReturn(2L);
    when(event.get("outbox_sequence", Long.class)).thenReturn(2L);
    when(event.get("event_id", String.class)).thenReturn("event-2");
    when(event.get("event_digest", String.class)).thenReturn("canonical-digest-2");
    when(event.get("payload", byte[].class)).thenReturn(new byte[] {2});
    when(event.get("request_id", String.class)).thenReturn("request-2");
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation ->
                invocation.<String>getArgument(0).contains("account_authority_outbox_streams")
                    ? stream
                    : event);

    assertThat(repository.readCheckpoint(streamKey))
        .contains(new Checkpoint(streamKey, 2L, "event-2", "canonical-digest-2"));
  }

  @Test
  void storageOperationsRequireTheCallersTransaction() throws ReflectiveOperationException {
    assertMandatory(
        AccountAuthorityOutboxRepository.class.getMethod(
            "append", String.class, String.class, String.class, String.class, byte[].class));
    assertMandatory(
        AccountAuthorityOutboxRepository.class.getMethod(
            "append", String.class, String.class, java.util.function.LongFunction.class));
    assertMandatory(
        AccountAuthorityOutboxRepository.class.getMethod("findEvent", String.class, long.class));
    assertMandatory(
        AccountAuthorityOutboxRepository.class.getMethod("readCheckpoint", String.class));
  }

  private void assertMandatory(java.lang.reflect.Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  private void stubAppend(long currentSequence, Record priorRequest) {
    Record stream = streamRecord(currentSequence);
    Record advanced = streamRecord(currentSequence + 1L);
    when(dsl.execute(anyString(), any(Object[].class))).thenReturn(1);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (query.startsWith("SELECT last_sequence")) {
                return stream;
              }
              if (query.startsWith("SELECT outbox_sequence")) {
                return priorRequest;
              }
              if (query.startsWith("SELECT request_id")) {
                return null;
              }
              if (query.startsWith("UPDATE account_authority_outbox_streams")) {
                return advanced;
              }
              throw new AssertionError("Unexpected outbox query: " + query);
            });
  }

  private Record streamRecord(long sequence) {
    Record record = mock(Record.class);
    when(record.get("last_sequence", Long.class)).thenReturn(sequence);
    return record;
  }

  private Record eventRecord(
      long sequence, String eventId, String digest, byte[] payload, String requestId) {
    Record record = mock(Record.class);
    when(record.get("outbox_sequence", Long.class)).thenReturn(sequence);
    when(record.get("event_id", String.class)).thenReturn(eventId);
    when(record.get("event_digest", String.class)).thenReturn(digest);
    when(record.get("payload", byte[].class)).thenReturn(payload);
    when(record.get("request_id", String.class)).thenReturn(requestId);
    return record;
  }
}
