package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PublishedAbilitySchemaEvidenceTest {
  private static final String ABILITY_DIGEST = "sha256:" + "a".repeat(64);
  private static final ParticipantDigest OWNER =
      ParticipantDigest.newBuilder()
          .setParticipantKey("GAME_LOGIC")
          .setScopeValue("7")
          .setAppliedCommitId("commit-1")
          .setDigestSchemaVersion(1)
          .setContentDigest("b".repeat(64))
          .setAbilitySchemaDigest(ABILITY_DIGEST)
          .build();
  private static final PublishedReleaseBundle FULL_BUNDLE = bundle(OWNER).build();

  @Test
  void readsTheDedicatedDigestForTheExactBaseVersion() {
    assertThat(PublishedAbilitySchemaEvidence.read(FULL_BUNDLE, 7L)).contains(ABILITY_DIGEST);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unavailableEvidence")
  void refusesUnavailableEvidence(String description, PublishedReleaseBundle bundle) {
    assertThat(PublishedAbilitySchemaEvidence.read(bundle, 7L)).as(description).isEmpty();
  }

  private static Stream<Arguments> unavailableEvidence() {
    return Stream.of(
        Arguments.of("different version", FULL_BUNDLE.toBuilder().setVersionId(8L).build()),
        Arguments.of("script-only bundle", FULL_BUNDLE.toBuilder().setIsScriptOnly(true).build()),
        Arguments.of(
            "different scope", bundle(OWNER.toBuilder().setScopeValue("8").build()).build()),
        Arguments.of(
            "unsupported schema",
            bundle(OWNER.toBuilder().setDigestSchemaVersion(2).build()).build()),
        Arguments.of(
            "missing commit", bundle(OWNER.toBuilder().clearAppliedCommitId().build()).build()),
        Arguments.of(
            "missing dedicated digest",
            bundle(OWNER.toBuilder().clearAbilitySchemaDigest().build()).build()),
        Arguments.of(
            "invalid dedicated digest",
            bundle(OWNER.toBuilder().setAbilitySchemaDigest("not-a-digest").build()).build()),
        Arguments.of(
            "duplicate Game Logic", FULL_BUNDLE.toBuilder().addParticipantDigests(OWNER).build()),
        Arguments.of(
            "another participant claims dedicated evidence",
            FULL_BUNDLE.toBuilder()
                .addParticipantDigests(OWNER.toBuilder().setParticipantKey("AUTOMATION_SCRIPTING"))
                .build()));
  }

  private static PublishedReleaseBundle.Builder bundle(ParticipantDigest owner) {
    return PublishedReleaseBundle.newBuilder().setVersionId(7L).addParticipantDigests(owner);
  }
}
