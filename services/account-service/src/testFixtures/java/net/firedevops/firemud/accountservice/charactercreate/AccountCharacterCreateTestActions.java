package net.firedevops.firemud.accountservice.charactercreate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;

/** Controlled public source bytes derived from the shared offline base vector, not owner proof. */
public final class AccountCharacterCreateTestActions {
  private AccountCharacterCreateTestActions() {}

  public static AccountCharacterCreateIntent intent(
      String namespace, UUID accountId, UUID tenantId, Instant expiresAt) {
    try (InputStream input =
        Objects.requireNonNull(
            AccountCharacterCreateTestActions.class.getResourceAsStream(
                "/charactercreate/public-action.json"))) {
      String raw =
          new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .strip()
              .replace(
                  "\"targetNamespace\":\"firemud\"", "\"targetNamespace\":\"" + namespace + "\"")
              .replace("a4000000-0000-4000-8000-000000000001", accountId.toString())
              .replace("a1000000-0000-4000-8000-000000000001", tenantId.toString())
              .replace("2026-10-11T12:39:56.987654321Z", expiresAt.toString());
      byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
      return new AccountCharacterCreateIntent(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "Issued.Scope.Mixed_CASE-" + UUID.randomUUID(),
          accountId,
          bytes,
          CharacterCreateOperatorActionCodec.mutationDigest(bytes),
          "controlled-owner-source-readback".getBytes(StandardCharsets.UTF_8));
    } catch (IOException failure) {
      throw new IllegalStateException("Controlled CHARACTER_CREATE fixture unavailable", failure);
    }
  }
}
