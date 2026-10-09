package net.firedevops.firemud.common.gamelogic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact preliminary Account source-read scope. Never authorizes Game Logic retention. */
public record GameLogicIntakeSourceReadScope(
    String targetNamespace,
    UUID operationId,
    UUID fenceId,
    UUID intakeRequestId,
    UUID actorAccountId,
    DraftCommitBinding selected,
    String intendedReader,
    String purpose) {
  public static final String SCHEMA = "account-game-logic-intake-source-read/v1";
  public static final String PURPOSE = "GAME_LOGIC_INTAKE_SOURCE";
  private static final int MAX_BYTES = 4194304;

  public GameLogicIntakeSourceReadScope {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace))
      throw new IllegalArgumentException("Canonical scope namespace required");
    for (var id : List.of(operationId, fenceId, intakeRequestId, actorAccountId))
      DraftAuthorizationFenceBinding.requireUuid(id);
    Objects.requireNonNull(selected);
    if (!("spiffe://firemud/ns/" + targetNamespace + "/sa/account-service").equals(intendedReader)
        || !PURPOSE.equals(purpose))
      throw new IllegalArgumentException("Exact preliminary Account reader and purpose required");
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, targetNamespace);
    for (var id : List.of(operationId, fenceId, intakeRequestId, actorAccountId))
      DraftAuthorizationFenceBinding.frame(out, id.toString());
    DraftAuthorizationFenceBinding.frame(out, selected.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, selected.digest());
    DraftAuthorizationFenceBinding.frame(out, intendedReader);
    DraftAuthorizationFenceBinding.frame(out, purpose);
    if (out.size() > MAX_BYTES) throw new IllegalArgumentException("Source scope exceeds 4 MiB");
    return out.toByteArray();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static GameLogicIntakeSourceReadScope fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("Invalid source scope size");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    var namespace = reader.text();
    var operation = uuid(reader.text());
    var fence = uuid(reader.text());
    var request = uuid(reader.text());
    var actor = uuid(reader.text());
    var selected =
        DraftCommitBinding.fromStored(
            new String(reader.bytes(), StandardCharsets.UTF_8), reader.text());
    var result =
        new GameLogicIntakeSourceReadScope(
            namespace, operation, fence, request, actor, selected, reader.text(), reader.text());
    reader.requireEnd();
    if (!Arrays.equals(bytes, result.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical source scope");
    return result;
  }

  private static UUID uuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }
}
