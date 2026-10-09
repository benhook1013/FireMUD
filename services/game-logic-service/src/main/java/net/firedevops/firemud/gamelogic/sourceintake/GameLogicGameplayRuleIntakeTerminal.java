package net.firedevops.firemud.gamelogic.sourceintake;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;

/** Durable owner terminal. Empty/absent storage is represented separately from ABORTED. */
public final class GameLogicGameplayRuleIntakeTerminal {
  public static final String SCHEMA = "game-logic-gameplay-rule-intake-terminal/v1";

  public enum Outcome {
    RETAINED,
    ABORTED
  }

  private final GameLogicGameplayRuleIntakeOperation operation;
  private final Outcome outcome;
  private final byte[] authorizationBytes;
  private final byte[] selectedSourceBytes;
  private final byte[] manifestBytes;

  private GameLogicGameplayRuleIntakeTerminal(
      GameLogicGameplayRuleIntakeOperation operation,
      Outcome outcome,
      byte[] authorizationBytes,
      byte[] selectedSourceBytes,
      byte[] manifestBytes) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.outcome = Objects.requireNonNull(outcome, "outcome");
    this.authorizationBytes = copy(authorizationBytes);
    this.selectedSourceBytes = nullableCopy(selectedSourceBytes);
    this.manifestBytes = nullableCopy(manifestBytes);
    if (!Arrays.equals(this.authorizationBytes, operation.authorizationBytes())) {
      throw new IllegalArgumentException("Terminal authorization differs from its operation");
    }
    if (outcome == Outcome.RETAINED) {
      if (this.selectedSourceBytes == null || this.manifestBytes == null) {
        throw new IllegalArgumentException("Retained terminal requires source and manifest bytes");
      }
      var source =
          new GameplayRuleSelectedSource(
              new String(this.selectedSourceBytes, StandardCharsets.UTF_8));
      if (!Arrays.equals(this.selectedSourceBytes, source.canonicalBytes())
          || !Arrays.equals(
              this.selectedSourceBytes, operation.authorization().source().canonicalBytes())
          || !Arrays.equals(
              this.manifestBytes,
              source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException(
            "Retained source or manifest differs from authorization");
      }
    } else if (this.selectedSourceBytes != null || this.manifestBytes != null) {
      throw new IllegalArgumentException("Aborted terminal cannot claim retained source");
    }
  }

  public static GameLogicGameplayRuleIntakeTerminal retained(
      GameLogicGameplayRuleIntakeOperation operation, byte[] sourceBytes, byte[] manifestBytes) {
    return new GameLogicGameplayRuleIntakeTerminal(
        operation, Outcome.RETAINED, operation.authorizationBytes(), sourceBytes, manifestBytes);
  }

  public static GameLogicGameplayRuleIntakeTerminal aborted(
      GameLogicGameplayRuleIntakeOperation operation) {
    return new GameLogicGameplayRuleIntakeTerminal(
        operation, Outcome.ABORTED, operation.authorizationBytes(), null, null);
  }

  public GameLogicGameplayRuleIntakeOperation operation() {
    return operation;
  }

  public Outcome outcome() {
    return outcome;
  }

  public byte[] authorizationBytes() {
    return authorizationBytes.clone();
  }

  public byte[] selectedSourceBytes() {
    return nullableCopy(selectedSourceBytes);
  }

  public byte[] manifestBytes() {
    return nullableCopy(manifestBytes);
  }

  public String authorizationDigest() {
    return operation.authorization().digest();
  }

  public String selectedSourceDigest() {
    return selectedSourceBytes == null ? null : GameplayRuleManifest.sha256(selectedSourceBytes);
  }

  public String manifestDigest() {
    return manifestBytes == null ? null : GameplayRuleManifest.sha256(manifestBytes);
  }

  public byte[] canonicalBytes() {
    var output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, outcome.name());
    DraftAuthorizationFenceBinding.frame(output, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, authorizationBytes);
    frameOptional(output, selectedSourceBytes);
    frameOptional(output, manifestBytes);
    return output.toByteArray();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static GameLogicGameplayRuleIntakeTerminal fromStored(byte[] original) {
    var reader = new DraftAuthorizationFenceBinding.FrameReader(original);
    reader.expect(SCHEMA);
    Outcome outcome = Outcome.valueOf(reader.text());
    var operation = GameLogicGameplayRuleIntakeOperation.fromStored(reader.bytes());
    byte[] authorization = reader.bytes();
    byte[] source = readOptional(reader);
    byte[] manifest = readOptional(reader);
    reader.requireEnd();
    var terminal =
        new GameLogicGameplayRuleIntakeTerminal(
            operation, outcome, authorization, source, manifest);
    if (!Arrays.equals(original, terminal.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical GL intake terminal");
    }
    return terminal;
  }

  private static void frameOptional(ByteArrayOutputStream output, byte[] value) {
    DraftAuthorizationFenceBinding.frame(output, value == null ? "ABSENT" : "PRESENT");
    if (value != null) DraftAuthorizationFenceBinding.frame(output, value);
  }

  private static byte[] readOptional(DraftAuthorizationFenceBinding.FrameReader reader) {
    String presence = reader.text();
    if ("ABSENT".equals(presence)) return null;
    if (!"PRESENT".equals(presence)) {
      throw new IllegalArgumentException("Invalid optional GL intake terminal frame");
    }
    return reader.bytes();
  }

  private static byte[] copy(byte[] value) {
    return Objects.requireNonNull(value, "bytes").clone();
  }

  private static byte[] nullableCopy(byte[] value) {
    return value == null ? null : value.clone();
  }
}
