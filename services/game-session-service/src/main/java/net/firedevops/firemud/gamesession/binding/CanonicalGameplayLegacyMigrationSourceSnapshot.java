package net.firedevops.firemud.gamesession.binding;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitized structural inventory of the legacy Redis families. Raw keys and serialized values are
 * deliberately not retained; a trusted source adapter must enumerate every family itself.
 */
public record CanonicalGameplayLegacyMigrationSourceSnapshot(
    Set<Family> scannedFamilies, List<Entry> entries) {
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<Family> REQUIRED_FAMILIES =
      Set.copyOf(
          EnumSet.of(
              Family.TENANT_SESSION_CONTEXT,
              Family.SESSION_ALIAS_CONTEXT,
              Family.GAMEPLAY_IDENTITY_CONTEXT,
              Family.GAMEPLAY_NAME_CONTEXT,
              Family.MOVEMENT_EFFECT,
              Family.DURABLE_EFFECT));

  public CanonicalGameplayLegacyMigrationSourceSnapshot {
    scannedFamilies = Set.copyOf(Objects.requireNonNull(scannedFamilies, "scannedFamilies"));
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (entries.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("legacy source entries must not contain null");
    }
    if (entries.stream().map(Entry::sourceKeyDigest).distinct().count() != entries.size()) {
      throw new IllegalArgumentException("legacy source key digests must be unique per snapshot");
    }
  }

  /** The adapter must prove these concrete key families, not assert a generic SCAN was empty. */
  public boolean enumeratedEveryKnownFamily() {
    return scannedFamilies.equals(REQUIRED_FAMILIES)
        && entries.stream().noneMatch(entry -> entry.family() == Family.UNCLASSIFIED);
  }

  /**
   * V29 intentionally has no legacy numeric-to-UUID/namespace backfill. Until that exact trusted
   * mapping owner exists, no retained row may be removed or relabelled; only an enumerated empty
   * legacy cohort is reconciled.
   */
  public boolean everyEntryIsReconciled() {
    return enumeratedEveryKnownFamily() && entries.isEmpty();
  }

  public enum Family {
    TENANT_SESSION_CONTEXT,
    SESSION_ALIAS_CONTEXT,
    GAMEPLAY_IDENTITY_CONTEXT,
    GAMEPLAY_NAME_CONTEXT,
    MOVEMENT_EFFECT,
    DURABLE_EFFECT,
    UNCLASSIFIED
  }

  public enum Disposition {
    UNMAPPABLE,
    CREDENTIAL_BEARING,
    UNKNOWN
  }

  /** Contains only a key digest and safe structural classification; never an asserted mapping. */
  public record Entry(Family family, String sourceKeyDigest, Disposition disposition) {
    public Entry {
      Objects.requireNonNull(family, "family");
      Objects.requireNonNull(disposition, "disposition");
      if (sourceKeyDigest == null || !SHA256.matcher(sourceKeyDigest).matches()) {
        throw new IllegalArgumentException("sourceKeyDigest must be a canonical SHA-256 digest");
      }
    }
  }
}
