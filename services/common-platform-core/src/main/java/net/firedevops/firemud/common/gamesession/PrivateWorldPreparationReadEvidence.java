package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/** Complete point-in-time source facts. Construction supplies no actor or continuing authority. */
public final class PrivateWorldPreparationReadEvidence {
  private PrivateWorldPreparationReadEvidence() {}

  public record Selection(
      UUID requestId,
      Long expectedHeadRevision,
      UUID expectedCurrentLifecycleId,
      long headRevision,
      String requestDigest,
      Instant selectedAt,
      ByteString originalRequest) {
    public Selection {
      uuid(requestId);
      if ((expectedHeadRevision == null) != (expectedCurrentLifecycleId == null)
          || (expectedHeadRevision != null && expectedHeadRevision <= 0)
          || headRevision
              != (expectedHeadRevision == null ? 1 : Math.addExact(expectedHeadRevision, 1)))
        throw invalid();
      if (expectedCurrentLifecycleId != null) uuid(expectedCurrentLifecycleId);
      digest(requestDigest);
      time(selectedAt);
      bytes(originalRequest, requestDigest);
      originalRequest = ByteString.copyFrom(originalRequest.toByteArray());
    }

    @Override
    public ByteString originalRequest() {
      return ByteString.copyFrom(originalRequest.toByteArray());
    }
  }

  public record Preparation(
      UUID requestId,
      UUID lifecycleId,
      UUID namespaceId,
      long generation,
      String state,
      String initializationMode,
      String requestDigest,
      Instant createdAt,
      Instant forkExpiresAt,
      ByteString originalRequest) {
    public Preparation {
      uuid(requestId);
      uuid(lifecycleId);
      uuid(namespaceId);
      if (lifecycleId.equals(namespaceId)
          || generation != 1
          || !"PREPARING".equals(state)
          || !"FRESH".equals(initializationMode)) throw invalid();
      digest(requestDigest);
      time(createdAt);
      time(forkExpiresAt);
      if (!createdAt.isBefore(forkExpiresAt)) throw invalid();
      bytes(originalRequest, requestDigest);
      originalRequest = ByteString.copyFrom(originalRequest.toByteArray());
    }

    @Override
    public ByteString originalRequest() {
      return ByteString.copyFrom(originalRequest.toByteArray());
    }
  }

  public record Source(
      UUID catalogRealmId,
      UUID catalogRequestId,
      long catalogRevision,
      String catalogReceiptDigest,
      UUID intakeOperationId,
      String intakeReceiptDigest,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String policySetDigest,
      String policyCarrierDigest,
      RealmPolicyPublishedEvidence.Policy targetPolicy) {
    public Source {
      for (UUID value :
          new UUID[] {
            catalogRealmId,
            catalogRequestId,
            intakeOperationId,
            sourceOperationId,
            canonicalVersionId
          }) uuid(value);
      if (catalogRevision != 1) throw invalid();
      for (String value :
          new String[] {
            catalogReceiptDigest,
            intakeReceiptDigest,
            sourceEvidenceDigest,
            publishedReleaseBundleDigest,
            policySetDigest,
            policyCarrierDigest
          }) digest(value);
      text(publishedReleaseBundleRef, 1024);
      Objects.requireNonNull(targetPolicy);
      var policy = targetPolicy.source().policy();
      if (!policy.visible()
          || policy.publicProduction()
          || policy.stateScope() != RealmEntryPolicy.StateScope.ISOLATED) throw invalid();
    }
  }

  /** Transport projection of the existing allocated target, never a second target authority. */
  public record Target(
      Scope scope,
      UUID realmId,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      UUID namespaceId,
      String characterCreationPolicy,
      long catalogRevision,
      UUID creationRequestId,
      String requestDigest,
      String receiptDigest,
      UUID preparationRequestId,
      UUID lifecycleId,
      long generation,
      String state,
      UUID sourceCatalogRealmId,
      UUID targetPolicyId,
      String targetPolicyDigest,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest) {
    public Target {
      Objects.requireNonNull(scope);
      for (UUID value :
          new UUID[] {
            realmId,
            namespaceId,
            creationRequestId,
            preparationRequestId,
            lifecycleId,
            sourceCatalogRealmId,
            targetPolicyId,
            canonicalVersionId
          }) uuid(value);
      for (String value :
          new String[] {
            requestDigest, receiptDigest, targetPolicyDigest, publishedReleaseBundleDigest
          }) digest(value);
      text(realmDisplayName, 512);
      if (realmDisplayName.codePointCount(0, realmDisplayName.length()) > 128) throw invalid();
      text(publishedReleaseBundleRef, 1024);
      RealmEntryPolicy.EntryPolicy.valueOf(characterCreationPolicy);
      if (visible
          || publicProduction
          || !"ISOLATED".equals(stateScope)
          || catalogRevision < 1
          || generation != 1
          || !"PREPARING".equals(state)
          || lifecycleId.equals(namespaceId)
          || realmId.equals(sourceCatalogRealmId)
          || realmId.equals(targetPolicyId)) throw invalid();
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  /** Retained Game Session allocation provenance; never runtime or continuing authority. */
  public record RuntimeAllocation(
      UUID requestId, UUID gameInstanceId, String requestDigest, String receiptDigest) {
    public RuntimeAllocation {
      uuid(requestId);
      uuid(gameInstanceId);
      digest(requestDigest);
      digest(receiptDigest);
    }
  }

  /** Canonical allocation digest binding; this pure value construction grants no authority. */
  public static RuntimeAllocation runtimeAllocation(
      Scope scope,
      Selection selection,
      Preparation preparation,
      Target target,
      UUID requestId,
      UUID gameInstanceId) {
    uuid(requestId);
    uuid(gameInstanceId);
    String requestDigest =
        sha256(
            framed(
                "game-session-private-fresh-runtime-request/v1",
                requestId.toString(),
                preparation.requestId().toString(),
                preparation.lifecycleId().toString(),
                target.realmId().toString(),
                Long.toString(target.catalogRevision()),
                target.creationRequestId().toString(),
                target.receiptDigest(),
                selection.requestId().toString(),
                Long.toString(selection.headRevision()),
                scope.targetNamespace(),
                scope.tenantId().toString(),
                scope.worldSlug(),
                scope.realmSlug(),
                target.requestDigest(),
                preparation.requestDigest(),
                preparation.namespaceId().toString(),
                Long.toString(preparation.generation()),
                target.canonicalVersionId().toString(),
                target.publishedReleaseBundleRef(),
                target.publishedReleaseBundleDigest(),
                selection.requestDigest()));
    return new RuntimeAllocation(
        requestId,
        gameInstanceId,
        requestDigest,
        sha256(
            framed(
                "game-session-private-fresh-runtime-receipt/v1",
                requestId.toString(),
                requestDigest,
                gameInstanceId.toString())));
  }

  public record Snapshot(
      Scope scope,
      Selection selection,
      Preparation preparation,
      Source source,
      Target target,
      RuntimeAllocation runtimeAllocation,
      Instant observedAt) {
    public Snapshot {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(selection);
      Objects.requireNonNull(preparation);
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
      Objects.requireNonNull(runtimeAllocation);
      if (runtimeAllocation.gameInstanceId().equals(target.realmId())
          || runtimeAllocation.gameInstanceId().equals(preparation.lifecycleId())
          || runtimeAllocation.gameInstanceId().equals(preparation.namespaceId())
          || !runtimeAllocation.equals(
              PrivateWorldPreparationReadEvidence.runtimeAllocation(
                  scope,
                  selection,
                  preparation,
                  target,
                  runtimeAllocation.requestId(),
                  runtimeAllocation.gameInstanceId()))) throw invalid();
      time(observedAt);
      var policy = source.targetPolicy().source().policy();
      if (!scope.equals(target.scope())
          || !scope.worldSlug().equals(policy.worldSlug())
          || !scope.realmSlug().equals(policy.realmSlug())
          || !preparation.requestId().equals(target.preparationRequestId())
          || !preparation.lifecycleId().equals(target.lifecycleId())
          || !preparation.namespaceId().equals(target.namespaceId())
          || !source.catalogRealmId().equals(target.sourceCatalogRealmId())
          || !source.targetPolicy().policyId().equals(target.targetPolicyId())
          || !source.targetPolicy().policyDigest().equals(target.targetPolicyDigest())
          || !source.canonicalVersionId().equals(target.canonicalVersionId())
          || !source.publishedReleaseBundleRef().equals(target.publishedReleaseBundleRef())
          || !source.publishedReleaseBundleDigest().equals(target.publishedReleaseBundleDigest())
          || !policy.realmDisplayName().equals(target.realmDisplayName())
          || !policy.entryPolicy().name().equals(target.characterCreationPolicy())
          || preparation.createdAt().isAfter(selection.selectedAt())
          || selection.selectedAt().isAfter(observedAt)
          || !observedAt.isBefore(preparation.forkExpiresAt())) throw invalid();
      if (!preparation
              .originalRequest()
              .equals(ByteString.copyFrom(preparationRequest(scope, preparation, source)))
          || !selection
              .originalRequest()
              .equals(ByteString.copyFrom(selectionRequest(scope, selection, preparation))))
        throw invalid();
    }

    public void requireCurrentAt(Instant now) {
      if (now == null || now.isBefore(observedAt) || !now.isBefore(preparation.forkExpiresAt()))
        throw invalid();
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  public record Evidence(PrivateWorldPreparationReadGrpcCodec.Request request, Snapshot snapshot) {
    public Evidence {
      Objects.requireNonNull(request);
      Objects.requireNonNull(snapshot);
      if (!request.scope().equals(snapshot.scope())
          || !request.expectedPreparationRequestId().equals(snapshot.preparation().requestId()))
        throw invalid();
    }
  }

  private static byte[] preparationRequest(Scope scope, Preparation p, Source s) {
    return framed(
        "game-session-initial-fresh-playtest-request/v1",
        p.requestId().toString(),
        scope.targetNamespace(),
        scope.tenantId().toString(),
        scope.worldSlug(),
        scope.realmSlug(),
        s.catalogRealmId().toString(),
        s.catalogRequestId().toString(),
        Long.toString(s.catalogRevision()),
        s.intakeOperationId().toString(),
        s.canonicalVersionId().toString(),
        s.publishedReleaseBundleRef(),
        s.publishedReleaseBundleDigest(),
        p.initializationMode(),
        p.forkExpiresAt().toString());
  }

  private static byte[] selectionRequest(Scope scope, Selection selection, Preparation p) {
    var out = new ByteArrayOutputStream();
    for (String value :
        new String[] {
          "game-session-current-private-playtest-selection/v1",
          selection.requestId().toString(),
          scope.targetNamespace(),
          scope.tenantId().toString(),
          scope.worldSlug(),
          scope.realmSlug(),
          p.requestId().toString(),
          p.lifecycleId().toString(),
          selection.expectedHeadRevision() == null ? "ABSENT" : "PRESENT"
        }) DraftAuthorizationFenceBinding.frame(out, value);
    if (selection.expectedHeadRevision() != null) {
      DraftAuthorizationFenceBinding.frame(out, selection.expectedHeadRevision().toString());
      DraftAuthorizationFenceBinding.frame(out, selection.expectedCurrentLifecycleId().toString());
    }
    return out.toByteArray();
  }

  static byte[] framed(String... values) {
    var out = new ByteArrayOutputStream();
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
    return out.toByteArray();
  }

  private static void bytes(ByteString bytes, String expectedDigest) {
    if (bytes == null
        || bytes.isEmpty()
        || bytes.size() > 4096
        || !sha256(bytes.toByteArray()).equals(expectedDigest)) throw invalid();
  }

  public static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static void uuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0))) throw invalid();
  }

  private static void digest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw invalid();
  }

  private static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
  }

  private static void time(Instant value) {
    if (value == null
        || value.getEpochSecond() < -62135596800L
        || value.getEpochSecond() > 253402300799L) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Exact complete non-admitting private World source required");
  }
}
