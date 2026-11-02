package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.Request;
import org.junit.jupiter.api.Test;

class AccountTenantCreationIntentRepositoryTest {
  @Test
  void originalIntentUsesRequestEncodingWithAbsentAndPresentDescription() {
    assertEncodingMatchesRequest(null);
    assertEncodingMatchesRequest("A tenant description");
  }

  private static void assertEncodingMatchesRequest(String description) {
    Request request =
        new Request(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
            UUID.fromString("40000000-0000-4000-8000-000000000004"),
            "test-game",
            "source-game",
            "Game",
            description,
            UUID.fromString("50000000-0000-4000-8000-000000000005"),
            UUID.fromString("60000000-0000-4000-8000-000000000006"),
            UUID.fromString("70000000-0000-4000-8000-000000000007"),
            1,
            true);
    byte[] requestPayload = AccountTenantCreationIntentRepository.canonicalRequestPayload(request);
    OriginalIntent original =
        new OriginalIntent(
            request.requestId(),
            request.creationOperationId(),
            request.gameDesignCreationRequestId(),
            request.initiatingAccountId(),
            request.targetNamespace(),
            request.sourceGameTenantKey(),
            request.name(),
            request.description(),
            request.recentReauthenticationEvidenceId(),
            request.creatorPartyId(),
            request.consentOperationId(),
            request.consentStatementVersion(),
            request.affirmativeConsent(),
            "sha256:" + "a".repeat(64),
            requestPayload,
            "sha256:" + "b".repeat(64),
            Instant.EPOCH);

    assertThat(AccountTenantCreationIntentRepository.canonicalRequestPayload(original))
        .containsExactly(requestPayload);
  }
}
