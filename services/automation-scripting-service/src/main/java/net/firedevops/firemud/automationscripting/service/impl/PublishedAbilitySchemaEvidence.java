package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Optional;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;

final class PublishedAbilitySchemaEvidence {
  private static final String GAME_LOGIC = "GAME_LOGIC";
  private static final Pattern CONTENT_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern ABILITY_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  private PublishedAbilitySchemaEvidence() {}

  static Optional<String> read(PublishedReleaseBundle bundle, long versionId) {
    if (versionId <= 0 || bundle.getVersionId() != versionId || bundle.getIsScriptOnly()) {
      return Optional.empty();
    }
    ParticipantDigest owner = null;
    for (ParticipantDigest participant : bundle.getParticipantDigestsList()) {
      if (GAME_LOGIC.equals(participant.getParticipantKey())) {
        if (owner != null) {
          return Optional.empty();
        }
        owner = participant;
      } else if (participant.hasAbilitySchemaDigest()) {
        return Optional.empty();
      }
    }
    if (owner == null
        || !Long.toString(versionId).equals(owner.getScopeValue())
        || owner.getDigestSchemaVersion() != 1
        || owner.getAppliedCommitId().isBlank()
        || !CONTENT_DIGEST.matcher(owner.getContentDigest()).matches()
        || !owner.hasAbilitySchemaDigest()
        || !ABILITY_DIGEST.matcher(owner.getAbilitySchemaDigest()).matches()) {
      return Optional.empty();
    }
    return Optional.of(owner.getAbilitySchemaDigest());
  }
}
