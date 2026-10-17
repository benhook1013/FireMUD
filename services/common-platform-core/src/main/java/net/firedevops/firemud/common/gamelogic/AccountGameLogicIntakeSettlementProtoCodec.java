package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeRequest;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed complete request echo and immutable canonical Account receipt transport. */
public final class AccountGameLogicIntakeSettlementProtoCodec {
  private AccountGameLogicIntakeSettlementProtoCodec() {}

  public static SettleGameLogicIntakeRequest toRequest(
      AccountGameLogicIntakeSettlementReadEvidence.Request request) {
    return SettleGameLogicIntakeRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalIntakeAuthorization(ByteString.copyFrom(request.binding().canonicalBytes()))
        .setIntakeAuthorizationDigest(request.binding().digest())
        .build();
  }

  public static AccountGameLogicIntakeSettlementReadEvidence.Request fromRequest(
      SettleGameLogicIntakeRequest request) {
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getOriginalIntakeAuthorization().size() > 4 * 1024 * 1024)
      throw new IllegalArgumentException("Unknown or oversized original intake authorization");
    DraftAuthorizationFenceBinding.canonicalUuid(request.getReadRequestId());
    var binding =
        GameLogicIntakeAuthorizationBinding.fromStored(
            request.getOriginalIntakeAuthorization().toByteArray());
    if (!binding.digest().equals(request.getIntakeAuthorizationDigest()))
      throw new IllegalArgumentException("Changed original intake digest");
    return new AccountGameLogicIntakeSettlementReadEvidence.Request(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        UUID.fromString(request.getReadRequestId()),
        binding);
  }

  public static SettleGameLogicIntakeResponse toResponse(
      AccountGameLogicIntakeSettlementReadEvidence evidence) {
    return SettleGameLogicIntakeResponse.newBuilder()
        .setRequest(toRequest(evidence.request()))
        .setOriginalSettlementReceipt(ByteString.copyFrom(evidence.receipt().canonicalBytes()))
        .setSettlementReceiptDigest(evidence.receipt().digest())
        .build();
  }

  public static AccountGameLogicIntakeSettlementReadEvidence fromResponse(
      AccountGameLogicIntakeSettlementReadEvidence.Request request,
      SettleGameLogicIntakeResponse response) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !toRequest(request).equals(response.getRequest())
        || response.getOriginalSettlementReceipt().size()
            > AccountGameLogicIntakeSettlementEvidence.MAX_CANONICAL_BYTES)
      throw new IllegalArgumentException("Changed or oversized settlement evidence");
    var receipt =
        AccountGameLogicIntakeSettlementEvidence.fromStored(
            response.getOriginalSettlementReceipt().toByteArray());
    if (!receipt.digest().equals(response.getSettlementReceiptDigest()))
      throw new IllegalArgumentException("Changed settlement receipt digest");
    return new AccountGameLogicIntakeSettlementReadEvidence(request, receipt);
  }
}
