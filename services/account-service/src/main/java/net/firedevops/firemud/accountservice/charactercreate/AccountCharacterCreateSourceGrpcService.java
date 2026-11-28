package net.firedevops.firemud.accountservice.charactercreate;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.CharacterCreateSourceServiceGrpc;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Deliberately unregistered exact-purpose adapter; the owner service authenticates every read. */
public final class AccountCharacterCreateSourceGrpcService
    extends CharacterCreateSourceServiceGrpc.CharacterCreateSourceServiceImplBase {
  private final AccountCharacterCreateService owner;
  private final String namespace;

  public AccountCharacterCreateSourceGrpcService(
      AccountCharacterCreateService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed canonical Account namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void readOriginalCharacterCreate(
      ReadOriginalCharacterCreateRequest request,
      StreamObserver<ReadOriginalCharacterCreateResponse> observer) {
    try {
      validate(request);
      observer.onNext(
          response(
              owner.readOriginalForOwner(
                  id(request.getOperationId()), request.getMutationDigest())));
      observer.onCompleted();
    } catch (RuntimeException failure) {
      observer.onError(failure);
    }
  }

  @Override
  public void readOriginalCharacterCreateProtection(
      ReadOriginalCharacterCreateProtectionRequest request,
      StreamObserver<ReadOriginalCharacterCreateProtectionResponse> observer) {
    try {
      if (!request.hasOriginal()
          || !request.getUnknownFields().asMap().isEmpty()
          || request.getAccountProtectionFence() <= 0L) throw invalid();
      validate(request.getOriginal());
      var protection =
          owner.readProtectionForOwner(
              id(request.getOriginal().getOperationId()),
              request.getOriginal().getMutationDigest(),
              id(request.getAccountProtectionId()),
              request.getAccountProtectionFence());
      observer.onNext(
          ReadOriginalCharacterCreateProtectionResponse.newBuilder()
              .setOriginal(response(protection.intent()))
              .setAccountProtectionId(protection.protectionId().toString())
              .setAccountProtectionFence(protection.protectionFence())
              .setAcquiredAt(protection.acquiredAt().toString())
              .setOriginalMembershipSnapshot(ByteString.copyFrom(protection.membershipSnapshot()))
              .setOriginalEntitlementSnapshot(ByteString.copyFrom(protection.entitlementSnapshot()))
              .build());
      observer.onCompleted();
    } catch (RuntimeException failure) {
      observer.onError(failure);
    }
  }

  private void validate(ReadOriginalCharacterCreateRequest request) {
    if (!namespace.equals(request.getTargetNamespace())
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.getMutationDigest().matches("[0-9a-f]{64}")) throw invalid();
    id(request.getOperationId());
  }

  private static UUID id(String value) {
    try {
      UUID id = UUID.fromString(value);
      AccountCharacterCreateIntent.requireId(id);
      if (!id.toString().equals(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static ReadOriginalCharacterCreateResponse response(AccountCharacterCreateIntent intent) {
    return ReadOriginalCharacterCreateResponse.newBuilder()
        .setTargetNamespace(intent.targetNamespace())
        .setOperationId(intent.operationId().toString())
        .setCreateCharacterRequestId(intent.createCharacterRequestId().toString())
        .setConnectScopeId(intent.connectScopeId())
        .setAuthenticatedAccountId(intent.accountId().toString())
        .setPurpose(AccountCharacterCreateIntent.PURPOSE)
        .setOriginalAction(ByteString.copyFrom(intent.actionBytes()))
        .setMutationDigest(intent.mutationDigest())
        .setOriginalSourceReadback(ByteString.copyFrom(intent.originalSourceReadback()))
        .build();
  }

  private static RuntimeException invalid() {
    return Status.INVALID_ARGUMENT
        .withDescription("Exact original CHARACTER_CREATE read binding required")
        .asRuntimeException();
  }
}
