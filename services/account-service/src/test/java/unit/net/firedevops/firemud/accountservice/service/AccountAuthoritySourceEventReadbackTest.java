package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import org.junit.jupiter.api.Test;

class AccountAuthoritySourceEventReadbackTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");

  @Test
  void unsupportedGenericAccountEventMapsToTypedUnavailableEvidence() {
    UUID accountUuid = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String stream = "account:auth-authority:v1:account/" + accountUuid;
    String eventId = "generic-account-event-1";
    String eventDigest = "sha256:" + "c".repeat(64);
    Event event =
        new Event(
            stream,
            "generic-account-request-1",
            1L,
            eventId,
            eventDigest,
            "{\"schemaVersion\":\"account-auth-authority-source-event/v1\"}"
                .getBytes(StandardCharsets.UTF_8));
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository passwordResets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logoutAll = mock(AccountLogoutAllOperationRepository.class);
    AccountSecurityStateOperationRepository securityState =
        mock(AccountSecurityStateOperationRepository.class);
    when(outbox.readCheckpoint(stream))
        .thenReturn(Optional.of(new Checkpoint(stream, 1L, eventId, eventDigest)));
    when(outbox.findEvent(stream, 1L)).thenReturn(Optional.of(event));
    var readback =
        new AccountAuthoritySourceEventReadback(outbox, passwordResets, logoutAll, securityState);
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(42L);
    ScopeState current =
        new ScopeState(
            AuthorityScope.account(accountUuid), 2L, 2L, new IssuanceFence(accountUuid, 5L, 8L));

    assertThatThrownBy(() -> readback.requireCurrentLatest(account, current))
        .isExactlyInstanceOf(SourceEvidenceUnavailableException.class);

    verifyNoInteractions(passwordResets, logoutAll, securityState);
  }

  @Test
  void restrictionReceiptRequiresEventSequenceToTrailResultGenerationByOne() {
    assertThatThrownBy(
            () ->
                new AccountPlatformRestrictionOperationRepository.Operation(
                    restrictionRequest(),
                    account().getId(),
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
                    new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 1L, 1L),
                    Optional.of(
                        new AccountPlatformRestrictionOperationRepository.Receipt(
                            restrictionEvent(2L, "2", "2"),
                            accountState(2L, 2L, 2L, 2L),
                            2L,
                            2L,
                            UUID.fromString("33333333-3333-4333-8333-333333333333"),
                            RestrictionState.RESTRICTED))))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionSourceUnavailableException
                .class);
  }

  @Test
  void restrictionVariantWithoutImmutableRevisionReceiptFailsClosed() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountSecurityStateOperationRepository securityStates =
        mock(AccountSecurityStateOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            securityStates);
    Event event = restrictionEvent(1L, "2", "2");
    when(outbox.readCheckpoint(event.outboxStreamKey()))
        .thenReturn(
            Optional.of(
                new Checkpoint(event.outboxStreamKey(), 1L, event.eventId(), event.eventDigest())));
    when(outbox.findEvent(event.outboxStreamKey(), 1L)).thenReturn(Optional.of(event));

    assertThatThrownBy(() -> readback.requireCurrentLatest(account(), accountState(2L, 2L, 2L, 2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no immutable operation receipt");

    verify(securityStates).findRestrictionByRequestIdShared(UUID.fromString(event.requestId()));
  }

  @Test
  void restrictionVariantRejectsMismatchedCategoryRevisionReceiptAndCurrentSourceProof() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountSecurityStateOperationRepository securityStates =
        mock(AccountSecurityStateOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            securityStates);
    Event event = restrictionEvent(1L, "2", "2");
    when(outbox.readCheckpoint(event.outboxStreamKey()))
        .thenReturn(
            Optional.of(
                new Checkpoint(event.outboxStreamKey(), 1L, event.eventId(), event.eventDigest())));
    when(outbox.findEvent(event.outboxStreamKey(), 1L)).thenReturn(Optional.of(event));

    assertThatThrownBy(() -> readback.requireCurrentLatest(account(), accountState(3L, 3L, 3L, 3L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match its current generation and source version");
    verify(securityStates, never())
        .findRestrictionByRequestIdShared(UUID.fromString(event.requestId()));

    var mismatchedRequest =
        new AccountPlatformRestrictionMutationRequest(
            UUID.fromString(event.requestId()),
            ACCOUNT_UUID,
            Category.PLATFORM_ACCESS_BAN,
            1L,
            1L,
            1L,
            1L,
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            RestrictionState.RESTRICTED,
            AccountPlatformRestrictionMutationRequest.SourceKind.LOGGING_ADMIN_MODERATION,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "sha256:" + "b".repeat(64));
    var mismatchedOperation =
        new AccountPlatformRestrictionOperationRepository.Operation(
            mismatchedRequest,
            account().getId(),
            AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
            new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 1L, 1L),
            Optional.of(
                new AccountPlatformRestrictionOperationRepository.Receipt(
                    event,
                    accountState(2L, 2L, 2L, 2L),
                    2L,
                    2L,
                    UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    RestrictionState.RESTRICTED)));
    when(securityStates.findRestrictionByRequestIdShared(UUID.fromString(event.requestId())))
        .thenReturn(Optional.of(mismatchedOperation));
    assertThatThrownBy(() -> readback.requireCurrentLatest(account(), accountState(2L, 2L, 2L, 2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operation association/result differs");

    AccountPlatformRestrictionMutationRequest matchingRequest = restrictionRequest();
    var mismatchedRevisionOperation =
        new AccountPlatformRestrictionOperationRepository.Operation(
            matchingRequest,
            account().getId(),
            AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
            new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 1L, 1L),
            Optional.of(
                new AccountPlatformRestrictionOperationRepository.Receipt(
                    event,
                    accountState(2L, 2L, 2L, 2L),
                    3L,
                    2L,
                    UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    RestrictionState.RESTRICTED)));
    when(securityStates.findRestrictionByRequestIdShared(UUID.fromString(event.requestId())))
        .thenReturn(Optional.of(mismatchedRevisionOperation));
    assertThatThrownBy(() -> readback.requireCurrentLatest(account(), accountState(2L, 2L, 2L, 2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operation association/result differs");

    var mismatchedFenceOperation =
        new AccountPlatformRestrictionOperationRepository.Operation(
            matchingRequest,
            account().getId(),
            AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
            new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 2L, 2L),
            Optional.of(
                new AccountPlatformRestrictionOperationRepository.Receipt(
                    event,
                    accountState(2L, 2L, 2L, 2L),
                    2L,
                    2L,
                    UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    RestrictionState.RESTRICTED)));
    when(securityStates.findRestrictionByRequestIdShared(UUID.fromString(event.requestId())))
        .thenReturn(Optional.of(mismatchedFenceOperation));
    assertThatThrownBy(() -> readback.requireCurrentLatest(account(), accountState(2L, 2L, 2L, 2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operation association/result differs");
  }

  private Event restrictionEvent(long sequence, String generation, String sourceVersion) {
    UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String stream = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    var event =
        restrictionRequest()
            .sealEvent(
                generation,
                sourceVersion,
                Long.toString(sequence),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                2L,
                2L);
    return new Event(
        stream,
        requestId.toString(),
        sequence,
        event.eventId(),
        event.eventDigest(),
        event.canonicalJsonUtf8());
  }

  private AccountPlatformRestrictionMutationRequest restrictionRequest() {
    return new AccountPlatformRestrictionMutationRequest(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        ACCOUNT_UUID,
        Category.ACCOUNT_SECURITY_LOCK,
        1L,
        1L,
        1L,
        1L,
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        RestrictionState.RESTRICTED,
        AccountPlatformRestrictionMutationRequest.SourceKind.ACCOUNT_SECURITY_POLICY,
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "sha256:" + "b".repeat(64));
  }

  private Account account() {
    Account account = new Account();
    account.setId(11L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(11L);
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
}
