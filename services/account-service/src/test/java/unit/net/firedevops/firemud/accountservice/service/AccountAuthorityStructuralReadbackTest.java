package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AccountAuthorityStructuralReadbackTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");

  @Test
  void structuralSecurityStateSnapshotAcceptsCompleteCurrentAndRetainedEvents() {
    Event original = securityStateEvent(ACCOUNT_UUID, 1L, "2", "2");
    AccountSourceSnapshot originalSnapshot = structuralSnapshot(original, 2L, 2L);
    AccountSourceSnapshot newer =
        structuralSnapshot(securityStateEvent(ACCOUNT_UUID, 2L, "3", "3"), 3L, 3L);

    assertThat(originalSnapshot.latestEvent()).contains(original);
    assertThat(new AccountSourceEventReadback(newer, original).requestedEvent())
        .isEqualTo(original);
  }

  @Test
  void structuralSecurityStateSnapshotBindsEveryEventIndexAndCompleteCanonicalBytes()
      throws Exception {
    Event original = securityStateEvent(ACCOUNT_UUID, 1L, "2", "2");
    JsonMapper json = JsonMapper.builder().build();
    ObjectNode substituted = (ObjectNode) json.readTree(original.payload());
    ((ObjectNode) substituted.get("accountState")).put("emailVerified", false);
    ObjectNode wrongScope = (ObjectNode) json.readTree(original.payload());
    wrongScope.put("sourceScope", "account/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    for (Event invalid :
        List.of(
            new Event(
                original.outboxStreamKey(),
                "33333333-3333-4333-8333-333333333333",
                1L,
                original.eventId(),
                original.eventDigest(),
                original.payload()),
            new Event(
                original.outboxStreamKey(),
                original.requestId(),
                1L,
                original.eventId() + "other",
                original.eventDigest(),
                original.payload()),
            new Event(
                original.outboxStreamKey(),
                original.requestId(),
                1L,
                original.eventId(),
                "sha256:" + "a".repeat(64),
                original.payload()),
            new Event(
                original.outboxStreamKey(),
                original.requestId(),
                2L,
                original.eventId(),
                original.eventDigest(),
                original.payload()),
            withPayload(original, substituted.toString().getBytes(StandardCharsets.UTF_8)),
            withPayload(original, wrongScope.toString().getBytes(StandardCharsets.UTF_8)),
            withPayload(
                original,
                (" " + new String(original.payload(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8)))) {
      assertThatThrownBy(() -> structuralSnapshot(invalid, 2L, 2L))
          .isInstanceOf(IllegalStateException.class);
    }
    Event foreign =
        securityStateEvent(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 1L, "2", "2");
    assertThatThrownBy(() -> structuralSnapshot(foreign, 2L, 2L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void structuralSecurityStateCountersRequireCurrentEqualityAndRetainedNotAhead() {
    Event generationMismatch = securityStateEvent(ACCOUNT_UUID, 1L, "3", "2");
    Event versionMismatch = securityStateEvent(ACCOUNT_UUID, 1L, "2", "3");
    Event arbitraryPrecision =
        securityStateEvent(ACCOUNT_UUID, 1L, "922337203685477580812345678901234567890", "2");
    AccountSourceSnapshot current =
        structuralSnapshot(securityStateEvent(ACCOUNT_UUID, 2L, "3", "3"), 3L, 3L);
    for (Event invalid : List.of(generationMismatch, versionMismatch, arbitraryPrecision)) {
      assertThatThrownBy(() -> structuralSnapshot(invalid, 2L, 2L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("counters contradict or lead");
    }
    assertThatThrownBy(() -> new AccountSourceEventReadback(current, arbitraryPrecision))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("counters contradict or lead");
  }

  @Test
  void structurallyValidSecurityStateWithoutReceiptCannotPassOwnerReadback() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts = mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            resets,
            logouts,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountSecurityStateOperationRepository.class));
    Event event = securityStateEvent(ACCOUNT_UUID, 1L, "2", "2");
    assertThat(structuralSnapshot(event, 2L, 2L).latestEvent()).contains(event);
    when(outbox.readCheckpoint(event.outboxStreamKey()))
        .thenReturn(
            Optional.of(
                new Checkpoint(event.outboxStreamKey(), 1L, event.eventId(), event.eventDigest())));
    when(outbox.findEvent(event.outboxStreamKey(), 1L)).thenReturn(Optional.of(event));

    assertThatThrownBy(() -> readback.requireCurrentLatest(account(), accountState(2L, 2L, 2L, 2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no immutable operation receipt");
    assertThatThrownBy(
            () -> readback.requireRetainedEvent(account(), event, accountState(3L, 3L, 3L, 3L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no immutable operation receipt");
    verifyNoInteractions(resets, logouts);
  }

  private AccountSourceSnapshot structuralSnapshot(
      Event event, long generation, long sourceVersion) {
    return new AccountSourceSnapshot(
        ACCOUNT_UUID,
        accountState(generation, sourceVersion, 2L, 2L),
        "account:auth-authority:v1:account/" + ACCOUNT_UUID,
        event.outboxSequence(),
        Optional.of(event));
  }

  private Event withPayload(Event original, byte[] payload) {
    return new Event(
        original.outboxStreamKey(),
        original.requestId(),
        original.outboxSequence(),
        original.eventId(),
        original.eventDigest(),
        payload);
  }

  private Event securityStateEvent(
      UUID accountUuid, long sequence, String generation, String sourceVersion) {
    String requestId = "11111111-1111-4111-8111-111111111111";
    String stream = "account:auth-authority:v1:account/" + accountUuid;
    var event =
        AccountSecurityStateAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry(
                    "schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId",
                    AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId),
                Map.entry("requestId", requestId),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("sourceScope", "account/" + accountUuid),
                Map.entry("outboxStreamKey", stream),
                Map.entry("outboxSequence", Long.toString(sequence)),
                Map.entry("accountAuthorityGeneration", generation),
                Map.entry("sourceVersion", sourceVersion),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        generation,
                        "outboxStreamKey",
                        stream,
                        "outboxSequence",
                        Long.toString(sequence))),
                Map.entry("mutationKinds", List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED")),
                Map.entry(
                    "accountState",
                    Map.of(
                        "emailVerified",
                        true,
                        "loginAuthModes",
                        List.of("PASSWORD"),
                        "globalRoles",
                        List.of(),
                        "lifecycleState",
                        "ACTIVE"))));
    return new Event(
        stream,
        requestId,
        sequence,
        event.eventId(),
        event.eventDigest(),
        event.canonicalJsonUtf8());
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
}
