package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
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
        AccountAuthorityOutboxRepository.class.getMethod("findEvent", String.class, long.class));
    assertMandatory(
        AccountAuthorityOutboxRepository.class.getMethod("readCheckpoint", String.class));
  }

  private void assertMandatory(java.lang.reflect.Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
