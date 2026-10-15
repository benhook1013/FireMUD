package net.firedevops.firemud.gamesession.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import org.junit.jupiter.api.Test;

class InitialGameplayLoginJournalSnapshotTest {
  private static final InitialGameplayLoginAttemptCoordinates ORIGINAL =
      new InitialGameplayLoginAttemptCoordinates(uuid(1), uuid(2), 7);

  @Test
  void coordinatesRejectNonV4OrNonPositiveAttemptFence() {
    assertThatThrownBy(() -> new InitialGameplayLoginAttemptCoordinates(new UUID(0, 0), uuid(2), 7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InitialGameplayLoginAttemptCoordinates(uuid(1), uuid(2), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void serializedFrozenRequestContainsOnlyOriginalIdentityAndOptionalCompleteWitness()
      throws Exception {
    var witness = witness();
    var request = InitialGameplayLoginJournalSnapshot.request(ORIGINAL, witness);
    assertThat(request.getAllFields().keySet())
        .extracting(field -> field.getName())
        .containsExactlyInAnyOrder("request_id", "caller_context_id", "token_witness");
    assertThat(request.getTokenWitness().getAllFields().keySet())
        .extracting(field -> field.getName())
        .containsExactlyInAnyOrder(
            "token_jti", "token_sha256", "token_generation", "issuance_fence");
    assertThat(
            net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest.parseFrom(
                request.toByteArray()))
        .isEqualTo(request);
    assertThat(new String(request.toByteArray(), StandardCharsets.ISO_8859_1))
        .doesNotContain("password", "credential", "auth_token", "eyJ", "request_digest");
    assertThat(InitialGameplayLoginJournalSnapshot.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly(
            "coordinates",
            "abandoned",
            "verifiedWitness",
            "frozenRetirementRequest",
            "retainedReceipt");
  }

  @Test
  void absentFrozenWitnessCanCoexistWithSeparatelyRetainedLateWitness() {
    var request = InitialGameplayLoginJournalSnapshot.request(ORIGINAL, null);
    var snapshot =
        new InitialGameplayLoginJournalSnapshot(
            ORIGINAL, true, Optional.of(witness()), Optional.of(request), Optional.empty());
    assertThat(snapshot.frozenRetirementRequest().orElseThrow().hasTokenWitness()).isFalse();
    assertThat(snapshot.verifiedWitness()).contains(witness());
  }

  @Test
  void rejectsUnabandonedFreezeAndChangedOriginalCoordinates() {
    var request = InitialGameplayLoginJournalSnapshot.request(ORIGINAL, null);
    assertThatThrownBy(
            () ->
                new InitialGameplayLoginJournalSnapshot(
                    ORIGINAL, false, Optional.empty(), Optional.of(request), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    var changed = request.toBuilder().setCallerContextId(uuid(9).toString()).build();
    assertThatThrownBy(
            () ->
                new InitialGameplayLoginJournalSnapshot(
                    ORIGINAL, true, Optional.empty(), Optional.of(changed), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsIncompleteWitnessAndMismatchedClosedDisposalPair() {
    assertThatThrownBy(
            () ->
                InitialGameplayLoginJournalSnapshot.request(
                    ORIGINAL, witness().toBuilder().clearTokenSha256().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new InitialGameplayLoginDisposalResult(
                    uuid(1),
                    uuid(2),
                    uuid(3),
                    InitialGameplayLoginDisposalResult.Disposition.PENDING,
                    InitialGameplayLoginDisposalResult.ProofKind.PHYSICAL_TOKEN_DENIAL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InitialGameplayLoginTokenWitness witness() {
    return InitialGameplayLoginTokenWitness.newBuilder()
        .setTokenJti(uuid(4).toString())
        .setTokenSha256("a".repeat(64))
        .setTokenGeneration(1)
        .setIssuanceFence(19)
        .build();
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
  }
}
