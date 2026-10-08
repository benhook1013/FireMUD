package net.firedevops.firemud.common.authoring;

import java.util.Objects;
import java.util.UUID;

/** Closed v1 reviewed-base grammar; a genesis receipt is never an authored commit. */
public record DraftBaseReference(Kind kind, UUID identity) {
  public static final String SCHEMA = "game-design-draft-base-reference/v1";

  public enum Kind {
    GENESIS,
    AUTHORED_COMMIT
  }

  public DraftBaseReference {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(identity, "identity");
    if (identity.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Reviewed base identity must be nonnil");
    }
  }

  public static DraftBaseReference parse(String value) {
    Objects.requireNonNull(value, "value");
    Kind kind = value.startsWith("genesis:") ? Kind.GENESIS : Kind.AUTHORED_COMMIT;
    String token = kind == Kind.GENESIS ? value.substring(8) : value;
    if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("Canonical v1 reviewed base required");
    }
    return new DraftBaseReference(kind, UUID.fromString(token));
  }

  public String canonicalValue() {
    return (kind == Kind.GENESIS ? "genesis:" : "") + identity;
  }
}
