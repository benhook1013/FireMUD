package net.firedevops.firemud.gamelogic.sourceintake;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact namespace and Account authorization identity for one independent GL source intake. */
public record GameLogicGameplayRuleIntakeOperation(
    String targetNamespace, GameLogicIntakeAuthorizationBinding authorization) {
  public static final String SCHEMA = "game-logic-gameplay-rule-intake-operation/v1";

  public GameLogicGameplayRuleIntakeOperation {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical intake namespace required");
    }
    Objects.requireNonNull(authorization, "Original Account intake order required");
  }

  public byte[] canonicalBytes() {
    var output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, targetNamespace);
    DraftAuthorizationFenceBinding.frame(output, authorization.canonicalBytes());
    return output.toByteArray();
  }

  public byte[] authorizationBytes() {
    return authorization.canonicalBytes();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static GameLogicGameplayRuleIntakeOperation fromStored(byte[] original) {
    var reader = new DraftAuthorizationFenceBinding.FrameReader(original);
    reader.expect(SCHEMA);
    var operation =
        new GameLogicGameplayRuleIntakeOperation(
            reader.text(), GameLogicIntakeAuthorizationBinding.fromStored(reader.bytes()));
    reader.requireEnd();
    if (!Arrays.equals(original, operation.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical GL intake operation");
    }
    return operation;
  }
}
