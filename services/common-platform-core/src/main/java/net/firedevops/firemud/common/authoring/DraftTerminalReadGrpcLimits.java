package net.firedevops.firemud.common.authoring;

/** Shared protobuf transport bounds for exact Draft terminal-read requests and responses. */
public final class DraftTerminalReadGrpcLimits {
  public static final int MAX_BINDING_BYTES = AccountOriginalDraftOrderGrpcCodec.MAX_BINDING_BYTES;
  public static final int MAX_OWNER_READBACK_BYTES =
      WorldOriginalDraftGraphApplyEvidence.MAX_OWNER_READBACK_BYTES;
  public static final int MAX_REQUEST_WIRE_BYTES = MAX_BINDING_BYTES + 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      MAX_BINDING_BYTES + MAX_OWNER_READBACK_BYTES + 1024;

  private DraftTerminalReadGrpcLimits() {}
}
