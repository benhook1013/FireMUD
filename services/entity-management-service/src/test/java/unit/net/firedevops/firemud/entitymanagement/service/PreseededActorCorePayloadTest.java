package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PreseededActorCorePayloadTest {
  @Test
  void rejectsMissingBlankOverlongOrControlCharacterDisplayNames() {
    assertThatThrownBy(
            () -> new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PreseededActorCorePayload(
                    PreseededActorCorePayload.ActorKind.PLAYER, "x".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PreseededActorCorePayload(
                    PreseededActorCorePayload.ActorKind.PLAYER, "Bad\nName"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnpairedUtf16Surrogates() {
    assertThatThrownBy(
            () ->
                new PreseededActorCorePayload(
                    PreseededActorCorePayload.ActorKind.PLAYER, "Bad\uD800Name"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PreseededActorCorePayload(
                    PreseededActorCorePayload.ActorKind.PLAYER, "Bad\uDC00Name"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAWellFormedAstralNameAtTheCodePointLimit() {
    String displayName = "\uD83D\uDE00" + "x".repeat(99);

    PreseededActorCorePayload payload =
        new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, displayName);

    org.assertj.core.api.Assertions.assertThat(payload.displayName()).isEqualTo(displayName);
  }
}
