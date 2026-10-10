package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstanceExecutionFixtureTest {
  private static final UUID TENANT_ID = UUID.fromString("41d2b16d-1fb7-4e74-9c54-7c647ee0c36a");
  private static final UUID ACTOR_ID = UUID.fromString("9e52a861-bcfd-4f27-a6d2-1782749c8989");
  private static final UUID TEMPLATE_OWNER_ID =
      UUID.fromString("5265c943-44c6-4b34-a629-86f0575cf4bf");

  @Test
  void emittedBundleUsesCanonicalMillisecondsAndStrictDecoderStillRejectsNanoseconds() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    Instant originalExpiry =
        Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(86_400L).plusNanos(987_654L);

    byte[] bytes =
        WorldCanonicalInstanceExecutionTestFixtures.authorityBundle(
            tuple, ACTOR_ID, originalExpiry);
    StartSessionAuthorityEvidenceBundle decoded = StartSessionAuthorityEvidenceBundle.decode(bytes);

    assertThat(decoded.expiresAt()).isEqualTo(Instant.ofEpochMilli(originalExpiry.toEpochMilli()));
    assertThat(decoded.canonicalBytes()).containsExactly(bytes);

    String canonicalExpiry = decoded.expiresAt().toString();
    String nanosecondExpiry = originalExpiry.toString();
    byte[] malformed =
        new String(bytes, StandardCharsets.UTF_8)
            .replace(canonicalExpiry, nanosecondExpiry)
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Timestamp is malformed");
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, TEMPLATE_OWNER_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic World execution fixture regression");
    return StartSessionPreAuthorizationReservationTuple.createHuman(
        "world-fixture-canonical-timestamp", ACTOR_ID, action);
  }
}
