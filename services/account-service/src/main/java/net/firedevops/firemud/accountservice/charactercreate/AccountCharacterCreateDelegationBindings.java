package net.firedevops.firemud.accountservice.charactercreate;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.grpc.Status;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact receiving checks for Account's creation delegation; none authenticate owner content. */
final class AccountCharacterCreateDelegationBindings {
  static final int MAX_ACCOUNT_ORIGINAL_BYTES = 36 * 1024 * 1024;
  static final int MAX_ACCOUNT_PROTECTION_BYTES = 40 * 1024 * 1024;
  static final int MAX_PROTECTION_BYTES = 48 * 1024 * 1024;
  static final int MAX_TERMINAL_BYTES = 192 * 1024 * 1024;
  private static final int MAX_TARGET_EVIDENCE_BYTES = 2_105_344;
  private static final int MAX_WORLD_LIFECYCLE_EVIDENCE_BYTES = 4 * 1024 * 1024;

  private AccountCharacterCreateDelegationBindings() {}

  static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Creation owner calls require no ambient Account transaction");
    }
  }

  static String namespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) throw invalid();
    return namespace;
  }

  static ReadOriginalCharacterCreateProtectionResponse account(
      String namespace,
      AccountCharacterCreateIntent intent,
      AccountCharacterCreateRepository.Protection protection) {
    Objects.requireNonNull(intent);
    Objects.requireNonNull(protection);
    if (!namespace.equals(intent.targetNamespace()) || !intent.sameBinding(protection.intent()))
      throw invalid();
    var original =
        ReadOriginalCharacterCreateResponse.newBuilder()
            .setTargetNamespace(namespace)
            .setOperationId(intent.operationId().toString())
            .setCreateCharacterRequestId(intent.createCharacterRequestId().toString())
            .setConnectScopeId(intent.connectScopeId())
            .setAuthenticatedAccountId(intent.accountId().toString())
            .setPurpose("CHARACTER_CREATE")
            .setOriginalAction(ByteString.copyFrom(intent.actionBytes()))
            .setMutationDigest(intent.mutationDigest())
            .setOriginalSourceReadback(ByteString.copyFrom(intent.originalSourceReadback()))
            .build();
    var result =
        ReadOriginalCharacterCreateProtectionResponse.newBuilder()
            .setOriginal(original)
            .setAccountProtectionId(protection.protectionId().toString())
            .setAccountProtectionFence(protection.protectionFence())
            .setAcquiredAt(protection.acquiredAt().toString())
            .setOriginalMembershipSnapshot(ByteString.copyFrom(protection.membershipSnapshot()))
            .setOriginalEntitlementSnapshot(ByteString.copyFrom(protection.entitlementSnapshot()))
            .build();
    requireAccount(namespace, result);
    return result;
  }

  static void requireAccount(
      String namespace, ReadOriginalCharacterCreateProtectionResponse account) {
    exact(account, MAX_ACCOUNT_PROTECTION_BYTES);
    if (!account.hasOriginal()) throw invalid();
    var original = account.getOriginal();
    exact(original, MAX_ACCOUNT_ORIGINAL_BYTES);
    id(original.getOperationId());
    id(original.getCreateCharacterRequestId());
    AccountCharacterCreateIntent.requireConnectScope(original.getConnectScopeId());
    id(original.getAuthenticatedAccountId());
    id(account.getAccountProtectionId());
    instant(account.getAcquiredAt());
    if (!namespace.equals(original.getTargetNamespace())
        || !"CHARACTER_CREATE".equals(original.getPurpose())
        || account.getAccountProtectionFence() <= 0
        || account.getOriginalMembershipSnapshot().isEmpty()
        || account.getOriginalMembershipSnapshot().size() > 1048576
        || account.getOriginalEntitlementSnapshot().isEmpty()
        || account.getOriginalEntitlementSnapshot().size() > 65536
        || original.getOriginalAction().isEmpty()
        || original.getOriginalAction().size() > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES
        || original.getOriginalSourceReadback().isEmpty()
        || original.getOriginalSourceReadback().size()
            > AccountCharacterCreateIntent.MAX_SOURCE_READBACK_BYTES) throw invalid();
    var bytes = original.getOriginalAction().toByteArray();
    var action = CharacterCreateOperatorActionCodec.decode(bytes);
    if (!Arrays.equals(bytes, CharacterCreateOperatorActionCodec.encode(action))
        || !namespace.equals(action.scope().targetNamespace())
        || !original.getAuthenticatedAccountId().equals(action.target().accountId())
        || !original
            .getMutationDigest()
            .equals(CharacterCreateOperatorActionCodec.mutationDigest(action))) throw invalid();
  }

  static ReadOriginalCharacterCreateRequest original(
      ReadOriginalCharacterCreateProtectionResponse account) {
    return ReadOriginalCharacterCreateRequest.newBuilder()
        .setTargetNamespace(account.getOriginal().getTargetNamespace())
        .setOperationId(account.getOriginal().getOperationId())
        .setMutationDigest(account.getOriginal().getMutationDigest())
        .build();
  }

  static ReadOriginalCharacterCreateProtectionRequest selector(
      ReadOriginalCharacterCreateProtectionResponse account) {
    return ReadOriginalCharacterCreateProtectionRequest.newBuilder()
        .setOriginal(original(account))
        .setAccountProtectionId(account.getAccountProtectionId())
        .setAccountProtectionFence(account.getAccountProtectionFence())
        .build();
  }

  static void gameSession(
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateTargetProtection target) {
    exact(target, MAX_PROTECTION_BYTES);
    if (!target.hasAccountProtection()
        || !account.equals(target.getAccountProtection())
        || target.getGameSessionProtectionFence() <= 0
        || target.getOriginalTargetEvidence().isEmpty()
        || target.getOriginalTargetEvidence().size() > MAX_TARGET_EVIDENCE_BYTES) throw invalid();
    id(target.getGameSessionProtectionId());
    instant(target.getAcquiredAt());
    try {
      var snapshot = CharacterCreateTargetSnapshot.parseFrom(target.getOriginalTargetEvidence());
      exact(snapshot, MAX_TARGET_EVIDENCE_BYTES);
      var action =
          CharacterCreateOperatorActionCodec.decode(
              account.getOriginal().getOriginalAction().toByteArray());
      if (!snapshot.toByteString().equals(target.getOriginalTargetEvidence())
          || snapshot.getSchemaVersion() != 1
          || !account.getOriginal().getOriginalAction().equals(snapshot.getOriginalAction())
          || !account.getOriginal().getTargetNamespace().equals(snapshot.getTargetNamespace())
          || !account.getOriginal().getOperationId().equals(snapshot.getOperationId())
          || !account.getOriginal().getMutationDigest().equals(snapshot.getMutationDigest())
          || !action.scope().tenantId().equals(snapshot.getCanonicalTenantId())
          || !action.target().realmId().equals(snapshot.getRealmId())
          || !action.target().gameInstanceId().equals(snapshot.getCanonicalGameInstanceId())
          || !action.target().versionId().equals(snapshot.getCanonicalVersionId())
          || !action
              .scope()
              .playableStateNamespaceId()
              .equals(snapshot.getPlayableStateNamespaceId())
          || !action
              .expectedVersion()
              .catalogRevision()
              .equals(Long.toString(snapshot.getCatalogRevision()))
          || !action
              .expectedVersion()
              .pointerVersion()
              .equals(Long.toString(snapshot.getPointerVersion()))
          || !action
              .expectedVersion()
              .activeLifecycleEpoch()
              .equals(Long.toString(snapshot.getActiveWorldEpoch()))
          || !snapshot.getCatalogReceiptDigest().matches("sha256:[0-9a-f]{64}")
          || !snapshot.getInitialAdmissionOwnerProofDigest().matches("sha256:[0-9a-f]{64}")
          || !snapshot.getAdmissionPointerSnapshotDigest().matches("[0-9a-f]{64}")
          || snapshot.getInitialAdmissionRequestId().isBlank()
          || snapshot.getInitialAdmissionRequestId().length() > 1024
          || !snapshot
              .getInitialAdmissionRequestId()
              .equals(snapshot.getInitialAdmissionRequestId().strip())) throw invalid();
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid();
    }
  }

  static void world(
      ReadOriginalCharacterCreateProtectionResponse account,
      WorldCharacterCreateLifecycleProtection world) {
    exact(world, MAX_PROTECTION_BYTES);
    if (!world.hasAccountProtection()
        || !account.equals(world.getAccountProtection())
        || world.getWorldProtectionFence() <= 0
        || world.getOriginalLifecycleEvidence().isEmpty()
        || world.getOriginalLifecycleEvidence().size() > MAX_WORLD_LIFECYCLE_EVIDENCE_BYTES)
      throw invalid();
    id(world.getWorldProtectionId());
    instant(world.getAcquiredAt());
    var action =
        CharacterCreateOperatorActionCodec.decode(
            account.getOriginal().getOriginalAction().toByteArray());
    var lifecycle =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(
            world.getOriginalLifecycleEvidence().toByteArray());
    var target = lifecycle.request();
    var source = action.mutation().authoredSource();
    var launch = lifecycle.launchBinding();
    if (!account.getOriginal().getOperationId().equals(target.readRequestId().toString())
        || !action.scope().targetNamespace().equals(target.targetNamespace())
        || !action.scope().tenantId().equals(target.canonicalTenantId().toString())
        || !action
            .scope()
            .playableStateNamespaceId()
            .equals(target.playableStateNamespaceId().toString())
        || !action.mutation().catalog().stateScope().equals(target.playableStateScope())
        || !action.target().gameInstanceId().equals(target.canonicalGameInstanceId().toString())
        || !action.target().versionId().equals(target.canonicalVersionId().toString())
        || !action.target().worldSlug().equals(target.worldSlug())
        || !target.publicProduction()
        || !"ACTIVE".equals(lifecycle.lifecycleStatus())
        || !action
            .expectedVersion()
            .activeLifecycleEpoch()
            .equals(Long.toString(lifecycle.lifecycleEpoch()))
        || !source
            .worldSourceOperationId()
            .equals(launch.descriptor().authoredWorldSourceOperationId().toString())
        || !source
            .worldSourceEvidenceDigest()
            .equals(launch.descriptor().authoredWorldSourceEvidenceDigest())
        || !source.selectedCommitId().equals(launch.releaseAttestation().commitId())
        || !source.publicationWorkflowId().equals(launch.releaseAttestation().publishWorkflowId()))
      throw invalid();
  }

  static CommitProtectedCharacterCreateRequest request(
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateTargetProtection target,
      WorldCharacterCreateLifecycleProtection world) {
    return CommitProtectedCharacterCreateRequest.newBuilder()
        .setOriginal(original(account))
        .setAccountProtection(
            reference(account.getAccountProtectionId(), account.getAccountProtectionFence()))
        .setGameSessionProtection(
            reference(target.getGameSessionProtectionId(), target.getGameSessionProtectionFence()))
        .setWorldProtection(
            reference(world.getWorldProtectionId(), world.getWorldProtectionFence()))
        .build();
  }

  static void terminal(
      CommitProtectedCharacterCreateRequest request,
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateTargetProtection target,
      WorldCharacterCreateLifecycleProtection world,
      EntityCharacterCreateTerminal terminal) {
    exact(terminal, MAX_TERMINAL_BYTES);
    if (!terminal.hasOriginalRequest()
        || !request.equals(terminal.getOriginalRequest())
        || !terminal.hasAccountOriginal()
        || !account.getOriginal().equals(terminal.getAccountOriginal())
        || !account.toByteString().equals(terminal.getOriginalAccountProtection())
        || !target.toByteString().equals(terminal.getOriginalGameSessionProtection())
        || !world.toByteString().equals(terminal.getOriginalWorldProtection())
        || terminal.getTerminalFence() <= 0
        || !terminal.getEntitySourceReceiptDigest().matches("sha256:[0-9a-f]{64}")) throw invalid();
    // Equality to the already-checked original request and carriers includes every nested field,
    // full retained source bytes, protection identifier/fence and acquisition timestamp.
    id(terminal.getEntityIntakeRequestId());
    id(terminal.getTerminalId());
    instant(terminal.getCompletedAt());
    if (terminal.getOutcome()
        == EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED) {
      id(terminal.getCharacterId());
      if (!terminal.getAbortRequestId().isEmpty()) throw invalid();
    } else if (terminal.getOutcome()
        == EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED) {
      id(terminal.getAbortRequestId());
      if (!terminal.getCharacterId().isEmpty()) throw invalid();
    } else throw invalid();
  }

  static void exact(Message message, int maximum) {
    if (message == null
        || !message.getUnknownFields().asMap().isEmpty()
        || message.getSerializedSize() > maximum) throw invalid();
  }

  static void id(String value) {
    if (value == null) throw invalid();
    try {
      var id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) throw invalid();
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  static void instant(String value) {
    try {
      if (!Instant.parse(value).toString().equals(value)) throw invalid();
    } catch (java.time.DateTimeException malformed) {
      throw invalid();
    }
  }

  private static CharacterCreateProtectionReference reference(String id, long fence) {
    id(id);
    if (fence <= 0) throw invalid();
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(id)
        .setProtectionFence(fence)
        .build();
  }

  static RuntimeException invalid() {
    return Status.FAILED_PRECONDITION
        .withDescription("Exact original CHARACTER_CREATE owner binding required")
        .asRuntimeException();
  }
}
