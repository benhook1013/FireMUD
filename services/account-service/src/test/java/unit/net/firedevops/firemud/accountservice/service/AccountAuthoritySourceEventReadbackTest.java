package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository.LogoutAllReceipt;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository.PasswordResetReceipt;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountAuthoritySourceEventReadbackTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");

  @Test
  void sequenceZeroRequiresTheOriginalPositiveBaselineAndNoCheckpoint() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    when(outbox.readCheckpoint("account:auth-authority:v1:account/" + ACCOUNT_UUID))
        .thenReturn(Optional.empty());
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      var snapshot =
          readback.requireCurrentLatest(
              account(),
              new AccountAuthorityGenerationRepository.ScopeState(
                  AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
                  1L,
                  1L,
                  new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 4L, 3L)));

      assertThat(snapshot.outboxSequence()).isZero();
      assertThat(snapshot.latestEvent()).isEmpty();
      verifyNoInteractions(resets, logouts);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void sequenceZeroNeverRepairsAProgressedSourceWithoutHistory() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class));
    when(outbox.readCheckpoint("account:auth-authority:v1:account/" + ACCOUNT_UUID))
        .thenReturn(Optional.empty());
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(
              () ->
                  readback.requireCurrentLatest(
                      account(),
                      new AccountAuthorityGenerationRepository.ScopeState(
                          AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
                          2L,
                          2L,
                          new AccountAuthorityGenerationRepository.IssuanceFence(
                              ACCOUNT_UUID, 5L, 4L))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("original positive 1/1 baseline");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void presentCheckpointIsContradictoryAtPristineBaselineAndSingleCounterProgression() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    String streamKey = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    when(outbox.readCheckpoint(streamKey))
        .thenReturn(
            Optional.of(new Checkpoint(streamKey, 1L, "event-id", "sha256:" + "a".repeat(64))));
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertRejectedCheckpoint(readback, 1L, 1L, "contradictory event history");
      assertRejectedCheckpoint(readback, 2L, 1L, "history is not proven");
      assertRejectedCheckpoint(readback, 1L, 2L, "history is not proven");

      verify(outbox, never()).findEvent(streamKey, 1L);
      verifyNoInteractions(resets, logouts);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void retainedPasswordResetUsesImmutableReceiptWithoutMakingItsVerifierCurrent() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    Account account = account();
    account.setPasswordHash("a-later-password-verifier");
    Event retained = passwordResetEvent(ACCOUNT_UUID, 1L, 2L, 2L, "a".repeat(64));
    PasswordResetReceipt receipt = passwordResetReceipt(retained, 7L, 6L);
    String streamKey = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    when(outbox.readCheckpoint(streamKey))
        .thenReturn(
            Optional.of(new Checkpoint(streamKey, 2L, "later-event", "sha256:" + "c".repeat(64))));
    when(resets.findByRequestId(receipt.requestId())).thenReturn(Optional.of(receipt));
    AccountAuthorityGenerationRepository.ScopeState current = accountState(3L, 3L, 8L, 7L);

    readback.requireRetainedEvent(account, retained, current);

    verify(resets).findByRequestId(receipt.requestId());
    verifyNoInteractions(logouts);
  }

  @Test
  void latestPasswordResetStillRequiresItsRecordedVerifierToBeCurrent() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    Account account = account();
    account.setPasswordHash("a-later-password-verifier");
    Event latest = passwordResetEvent(ACCOUNT_UUID, 1L, 2L, 2L, "b".repeat(64));
    PasswordResetReceipt receipt = passwordResetReceipt(latest, 7L, 6L);
    String streamKey = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    when(outbox.readCheckpoint(streamKey))
        .thenReturn(
            Optional.of(new Checkpoint(streamKey, 1L, latest.eventId(), latest.eventDigest())));
    when(outbox.findEvent(streamKey, 1L)).thenReturn(Optional.of(latest));
    when(resets.findByRequestId(receipt.requestId())).thenReturn(Optional.of(receipt));

    assertThatThrownBy(() -> readback.requireCurrentLatest(account, accountState(2L, 2L, 7L, 6L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match the current password verifier");
  }

  @Test
  void retainedLogoutAllEventRequiresItsExactImmutableReceipt() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    Event retained =
        logoutAllEvent(
            ACCOUNT_UUID, UUID.fromString("a980fa44-619e-4ca4-8ad6-75b0538a66a3"), 1L, 2L, 2L);
    LogoutAllReceipt receipt = logoutReceipt(retained, 7L, 6L);
    String streamKey = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    when(outbox.readCheckpoint(streamKey))
        .thenReturn(
            Optional.of(new Checkpoint(streamKey, 2L, "later-event", "sha256:" + "d".repeat(64))));
    when(logouts.findByRequestId(receipt.requestId())).thenReturn(Optional.of(receipt));

    readback.requireRetainedEvent(account(), retained, accountState(3L, 3L, 8L, 7L));

    verify(logouts).findByRequestId(receipt.requestId());
    verifyNoInteractions(resets);
  }

  @Test
  void retainedReadRejectsChangedEventBytesAndMissingReceipts() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    Event valid = passwordResetEvent(ACCOUNT_UUID, 1L, 2L, 2L, "e".repeat(64));
    String streamKey = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    when(outbox.readCheckpoint(streamKey))
        .thenReturn(
            Optional.of(new Checkpoint(streamKey, 2L, "later-event", "sha256:" + "f".repeat(64))));
    byte[] changedPayload = valid.payload();
    changedPayload[changedPayload.length - 2] ^= 1;
    Event changedBytes =
        new Event(
            valid.outboxStreamKey(),
            valid.requestId(),
            valid.outboxSequence(),
            valid.eventId(),
            valid.eventDigest(),
            changedPayload);

    assertThatThrownBy(
            () ->
                readback.requireRetainedEvent(
                    account(), changedBytes, accountState(3L, 3L, 8L, 7L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid declared");

    Event changedDigest =
        new Event(
            valid.outboxStreamKey(),
            valid.requestId(),
            valid.outboxSequence(),
            valid.eventId(),
            "sha256:" + "0".repeat(64),
            valid.payload());
    assertThatThrownBy(
            () ->
                readback.requireRetainedEvent(
                    account(), changedDigest, accountState(3L, 3L, 8L, 7L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("readback is inconsistent");

    Event unsupportedSchema =
        new Event(
            valid.outboxStreamKey(),
            "unsupported-request",
            1L,
            "unsupported-event",
            "sha256:" + "1".repeat(64),
            "{}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                readback.requireRetainedEvent(
                    account(), unsupportedSchema, accountState(3L, 3L, 8L, 7L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid declared");

    Event wrongAccount =
        passwordResetEvent(
            UUID.fromString("d980fa44-619e-4ca4-8ad6-75b0538a66a3"), 1L, 2L, 2L, "f".repeat(64));
    assertThatThrownBy(
            () ->
                readback.requireRetainedEvent(
                    account(), wrongAccount, accountState(3L, 3L, 8L, 7L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("readback is inconsistent");

    PasswordResetReceipt receipt = passwordResetReceipt(valid, 7L, 6L);
    PasswordResetReceipt mismatchedReceipt =
        new PasswordResetReceipt(
            receipt.accountId(),
            receipt.accountUuid(),
            receipt.tokenHash(),
            receipt.operationKind(),
            receipt.requestId(),
            receipt.requestDigestVersion(),
            receipt.requestDigest(),
            receipt.tokenExpiresAt(),
            receipt.passwordVerifierDigest(),
            receipt.outboxStreamKey(),
            receipt.outboxSequence(),
            receipt.eventId(),
            "sha256:" + "2".repeat(64),
            receipt.accountAuthorityGeneration(),
            receipt.accountSourceVersion(),
            receipt.issuanceFence(),
            receipt.issuanceFenceSourceVersion());
    when(resets.findByRequestId(valid.requestId()))
        .thenReturn(Optional.empty(), Optional.of(mismatchedReceipt));
    assertThatThrownBy(
            () -> readback.requireRetainedEvent(account(), valid, accountState(3L, 3L, 8L, 7L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no immutable operation receipt");
    assertThatThrownBy(
            () -> readback.requireRetainedEvent(account(), valid, accountState(3L, 3L, 8L, 7L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt does not match its source event");
  }

  private void assertRejectedCheckpoint(
      AccountAuthoritySourceEventReadback readback,
      long generation,
      long sourceVersion,
      String expectedMessage) {
    assertThatThrownBy(
            () ->
                readback.requireCurrentLatest(
                    account(),
                    new AccountAuthorityGenerationRepository.ScopeState(
                        AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
                        generation,
                        sourceVersion,
                        new AccountAuthorityGenerationRepository.IssuanceFence(
                            ACCOUNT_UUID, 4L, 3L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(expectedMessage);
  }

  private Account account() {
    Account account = new Account();
    account.setId(11L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(11L);
    account.setPasswordHash("unused-on-sequence-zero");
    return account;
  }

  private AccountAuthorityGenerationRepository.ScopeState accountState(
      long generation, long sourceVersion, long fence, long fenceSourceVersion) {
    return new AccountAuthorityGenerationRepository.ScopeState(
        AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
        generation,
        sourceVersion,
        new AccountAuthorityGenerationRepository.IssuanceFence(
            ACCOUNT_UUID, fence, fenceSourceVersion));
  }

  private Event passwordResetEvent(
      UUID accountUuid, long sequence, long generation, long sourceVersion, String tokenHash) {
    String streamKey = "account:auth-authority:v1:account/" + accountUuid;
    String requestId = "account-password-reset-request-v1:" + tokenHash;
    String eventId = "account-password-reset-event-v1:" + tokenHash;
    var sealed =
        PasswordResetAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventId),
                Map.entry("requestId", requestId),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("sourceScope", "account/" + accountUuid),
                Map.entry("outboxStreamKey", streamKey),
                Map.entry("outboxSequence", Long.toString(sequence)),
                Map.entry("accountAuthorityGeneration", Long.toString(generation)),
                Map.entry("sourceVersion", Long.toString(sourceVersion)),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", Long.toString(generation),
                        "outboxStreamKey", streamKey,
                        "outboxSequence", Long.toString(sequence)))));
    return new Event(
        streamKey, requestId, sequence, eventId, sealed.eventDigest(), sealed.canonicalJsonUtf8());
  }

  private PasswordResetReceipt passwordResetReceipt(
      Event event, long fence, long fenceSourceVersion) {
    String tokenHash = event.requestId().substring("account-password-reset-request-v1:".length());
    LocalDateTime tokenDeadline = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
    String verifierDigest = "b".repeat(64);
    return new PasswordResetReceipt(
        11L,
        ACCOUNT_UUID,
        tokenHash,
        "PASSWORD_RESET",
        event.requestId(),
        1,
        passwordResetRequestDigest(
            ACCOUNT_UUID, tokenHash, tokenDeadline.toString(), verifierDigest),
        tokenDeadline,
        verifierDigest,
        event.outboxStreamKey(),
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        2L,
        2L,
        fence,
        fenceSourceVersion);
  }

  private Event logoutAllEvent(
      UUID accountUuid, UUID requestId, long sequence, long generation, long sourceVersion) {
    String streamKey = "account:auth-authority:v1:account/" + accountUuid;
    String eventId = "account-logout-all-event-v1:" + requestId;
    var sealed =
        AccountLogoutAllAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventId),
                Map.entry("requestId", requestId.toString()),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("sourceScope", "account/" + accountUuid),
                Map.entry("outboxStreamKey", streamKey),
                Map.entry("outboxSequence", Long.toString(sequence)),
                Map.entry("accountAuthorityGeneration", Long.toString(generation)),
                Map.entry("sourceVersion", Long.toString(sourceVersion)),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", Long.toString(generation),
                        "outboxStreamKey", streamKey,
                        "outboxSequence", Long.toString(sequence)))));
    return new Event(
        streamKey,
        requestId.toString(),
        sequence,
        eventId,
        sealed.eventDigest(),
        sealed.canonicalJsonUtf8());
  }

  private LogoutAllReceipt logoutReceipt(Event event, long fence, long fenceSourceVersion) {
    UUID requestId = UUID.fromString(event.requestId());
    String tokenHash = "c".repeat(64);
    String tokenProfile = "control-ui";
    return new LogoutAllReceipt(
        requestId,
        11L,
        ACCOUNT_UUID,
        "ACCOUNT_LOGOUT_ALL",
        1,
        AccountLogoutRequestDigest.accountLogoutAll(ACCOUNT_UUID, tokenProfile, tokenHash),
        tokenHash,
        tokenProfile,
        event.outboxStreamKey(),
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        2L,
        2L,
        fence,
        fenceSourceVersion,
        "LOGOUT_ALL_COMMITTED");
  }

  private String passwordResetRequestDigest(
      UUID accountUuid, String tokenHash, String tokenDeadline, String verifierDigest) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateLengthPrefixed(digest, "account-password-reset-request/v1");
      updateLengthPrefixed(digest, "PASSWORD_RESET");
      updateLengthPrefixed(digest, accountUuid.toString());
      updateLengthPrefixed(digest, tokenHash);
      updateLengthPrefixed(digest, tokenDeadline);
      updateLengthPrefixed(digest, verifierDigest);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void updateLengthPrefixed(MessageDigest digest, String value) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
    digest.update(encoded);
  }
}
