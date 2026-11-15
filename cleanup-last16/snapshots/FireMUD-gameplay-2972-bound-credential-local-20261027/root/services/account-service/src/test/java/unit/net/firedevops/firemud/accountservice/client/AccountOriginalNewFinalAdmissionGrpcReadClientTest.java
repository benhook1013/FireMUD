package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec;
import org.junit.jupiter.api.Test;

/** Controlled immutable Account selector fixtures for the explicit mTLS read client mapping. */
class AccountOriginalNewFinalAdmissionGrpcReadClientTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void sendsOnlyTheAccountAnchorsRequiredByTheProtectedFinalRead() {
    UUID lineage = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    var expected =
        new AccountOriginalNewFinalAdmissionReadClient.ExpectedOriginalAdmission(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            5L,
            HASH,
            lineage,
            tokenJti,
            HASH,
            1L,
            9L,
            HASH,
            HASH,
            HASH,
            HASH);

    var selector = AccountOriginalNewFinalAdmissionGrpcReadClient.toSelector(expected);
    String request =
        CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toRequest(selector)
            .getExpectedOriginalAdmissionJson(0)
            .toStringUtf8();

    assertThat(selector.rebindHandleSha256()).isEqualTo(HASH);
    assertThat(request)
        .contains("rebindHandleSha256")
        .doesNotContain(lineage.toString(), tokenJti.toString(), "lineageId", "tokenJti", "compactJwt");
  }
}
