package net.firedevops.firemud.common.gamelogic;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestResponse;

/**
 * Bounded exact protobuf mapping for the separate full-version publication source selection.
 *
 * <p>This codec preserves and correlates the Account authorization bytes and the exact retained
 * Game Logic terminal. Byte integrity alone does not authenticate Account issuance, prove that Game
 * Logic actually retained the terminal, grant authority, or establish publication approval. Callers
 * may decode the terminal namespace against their configured authenticated namespace via {@link
 * #fromResponse(GameLogicPublicationSourceReadBinding, GetDraftDesignDigestResponse, String)}; the
 * two-argument overload preserves the terminal's namespace but does not authenticate it.
 *
 * <p>{@link #MAX_WIRE_BYTES} bounds the serialized protobuf message itself. It does not include any
 * transport envelope or future protocol framing.
 */
public final class GameLogicPublicationSourceReadGrpcCodec {
  /** Maximum serialized size of this protobuf request or response. */
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;

  /** Maximum source binding bytes, including its canonical frame overhead. */
  public static final int MAX_BINDING_BYTES = GameLogicPublicationSourceReadBinding.MAX_TOTAL_BYTES;

  /** Maximum canonical original retained-terminal bytes. */
  public static final int MAX_TERMINAL_BYTES =
      GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES;

  public static final int DIGEST_SCHEMA_VERSION = 1;
  public static final String CANONICALIZATION = "RFC8785";

  private GameLogicPublicationSourceReadGrpcCodec() {}

  /** Encodes a request for this binding's exact full-version publication selection. */
  public static GetDraftDesignDigestRequest toRequest(
      GameLogicPublicationSourceReadBinding binding) {
    Objects.requireNonNull(binding, "binding");
    requireFullVersion(binding);
    byte[] bindingBytes = binding.canonicalBytes();
    requireBindingSize(bindingBytes.length);
    PublicationDigestRequestBinding publication = binding.publicationRequest();
    int expectedWireSize =
        stringFieldSize(1, publication.tenantId())
            + stringFieldSize(2, publication.versionId())
            + stringFieldSize(5, publication.publishRequestId())
            + stringFieldSize(6, publication.derivedWorkflowIdentity())
            + stringFieldSize(7, publication.requestDigest())
            + bytesFieldSize(8, bindingBytes.length)
            + stringFieldSize(9, binding.digest());
    requireWireSize(expectedWireSize);

    GetDraftDesignDigestRequest request =
        GetDraftDesignDigestRequest.newBuilder()
            .setTenantId(publication.tenantId())
            .setVersionId(publication.versionId())
            .setBaseVersionId(publication.baseVersionId())
            .setPublishRequestId(publication.publishRequestId())
            .setDerivedWorkflowIdentity(publication.derivedWorkflowIdentity())
            .setRequestDigest(publication.requestDigest())
            .setSourceReadBinding(ByteString.copyFrom(bindingBytes))
            .setSourceReadBindingDigest(binding.digest())
            .build();
    requireWireSize(request.getSerializedSize());
    return request;
  }

  /** Validates the unchanged publication request fields and reconstructs the exact binding. */
  public static GameLogicPublicationSourceReadBinding fromRequest(
      GetDraftDesignDigestRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireSize(request.getSerializedSize());
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Unknown publication digest request fields");
    }
    requireBindingSize(request.getSourceReadBinding().size());
    if (request.getScopeCase() != GetDraftDesignDigestRequest.ScopeCase.VERSION_ID
        || !request.getBaseVersionId().isEmpty()) {
      throw new IllegalArgumentException("Only full-version publication reads are supported");
    }

    PublicationDigestRequestBinding publication =
        PublicationDigestRequestBinding.forScope(
            PublicationDigestRequestBinding.ScopeKind.FULL_VERSION,
            request.getTenantId(),
            request.getVersionId(),
            request.getBaseVersionId(),
            "",
            request.getPublishRequestId());
    publication.validateSupplied(request.getDerivedWorkflowIdentity(), request.getRequestDigest());
    GameLogicPublicationSourceReadBinding binding =
        GameLogicPublicationSourceReadBinding.fromStored(
            publication, request.getSourceReadBinding().toByteArray());
    if (!binding.digest().equals(request.getSourceReadBindingDigest())) {
      throw new IllegalArgumentException("Changed publication source-read binding digest");
    }
    return binding;
  }

  /**
   * Encodes an owner-readback terminal. The caller must supply the genuine terminal returned by its
   * Game Logic owner read; this codec verifies its retained content correlation but cannot
   * establish storage provenance by inspecting a value.
   */
  public static GetDraftDesignDigestResponse toResponse(
      GameLogicPublicationSourceReadBinding binding,
      GameLogicGameplayRuleIntakeTerminal genuineTerminal) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(genuineTerminal, "genuineTerminal");
    requireFullVersion(binding);
    requireExactRetainedTerminal(binding, genuineTerminal, null);

    byte[] bindingBytes = binding.canonicalBytes();
    byte[] terminalBytes = genuineTerminal.canonicalBytes();
    requireBindingSize(bindingBytes.length);
    requireTerminalSize(terminalBytes.length);
    GameLogicIntakeAuthorizationBinding authorization = binding.authorization();
    byte[] manifestBytes = genuineTerminal.manifestBytes();
    String manifestDigest = GameplayRuleManifest.sha256(manifestBytes);
    String abilitySchemaDigest =
        GameplayAbilitySchemaProjection.digest(authorization.source().manifest());
    String tenantId = binding.publicationRequest().tenantId();
    String versionId = binding.publicationRequest().versionId();
    String commitId = authorization.source().binding().commitId().toString();
    String terminalDigest = genuineTerminal.digest();

    int expectedWireSize =
        stringFieldSize(1, tenantId)
            + stringFieldSize(3, commitId)
            + stringFieldSize(4, manifestDigest)
            + CodedOutputStream.computeInt32Size(5, DIGEST_SCHEMA_VERSION)
            + stringFieldSize(7, versionId)
            + bytesFieldSize(10, bindingBytes.length)
            + stringFieldSize(11, binding.digest())
            + bytesFieldSize(12, terminalBytes.length)
            + stringFieldSize(13, terminalDigest)
            + stringFieldSize(14, abilitySchemaDigest)
            + stringFieldSize(15, CANONICALIZATION);
    requireWireSize(expectedWireSize);

    GetDraftDesignDigestResponse response =
        GetDraftDesignDigestResponse.newBuilder()
            .setTenantId(tenantId)
            .setAppliedCommitId(commitId)
            .setContentDigest(manifestDigest)
            .setDigestSchemaVersion(DIGEST_SCHEMA_VERSION)
            .setVersionId(versionId)
            .setBaseVersionId("")
            .setSourceReadBinding(ByteString.copyFrom(bindingBytes))
            .setSourceReadBindingDigest(binding.digest())
            .setRetainedIntakeTerminal(ByteString.copyFrom(terminalBytes))
            .setRetainedIntakeTerminalDigest(terminalDigest)
            .setAbilitySchemaDigest(abilitySchemaDigest)
            .setCanonicalization(CANONICALIZATION)
            .build();
    requireWireSize(response.getSerializedSize());
    return response;
  }

  /**
   * Decodes exact selection and retained-content evidence without authenticating its producer. For
   * an authenticated caller namespace check, use the three-argument overload.
   */
  public static Evidence fromResponse(
      GameLogicPublicationSourceReadBinding expectedBinding,
      GetDraftDesignDigestResponse response) {
    return decodeResponse(expectedBinding, response, null);
  }

  /** Decodes the response while also enforcing the caller's configured GL namespace. */
  public static Evidence fromResponse(
      GameLogicPublicationSourceReadBinding expectedBinding,
      GetDraftDesignDigestResponse response,
      String expectedNamespace) {
    Objects.requireNonNull(expectedNamespace, "expectedNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(expectedNamespace)) {
      throw new IllegalArgumentException("Canonical expected Game Logic namespace required");
    }
    return decodeResponse(expectedBinding, response, expectedNamespace);
  }

  private static Evidence decodeResponse(
      GameLogicPublicationSourceReadBinding expectedBinding,
      GetDraftDesignDigestResponse response,
      String expectedNamespace) {
    Objects.requireNonNull(expectedBinding, "expectedBinding");
    Objects.requireNonNull(response, "response");
    requireFullVersion(expectedBinding);
    requireWireSize(response.getSerializedSize());
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Unknown publication digest response fields");
    }
    rejectNonemptyError(response);
    requireBindingSize(response.getSourceReadBinding().size());
    requireTerminalSize(response.getRetainedIntakeTerminal().size());
    requireResponseScope(expectedBinding, response);
    requireExactBindingEcho(expectedBinding, response);

    GameLogicGameplayRuleIntakeTerminal terminal =
        GameLogicGameplayRuleIntakeTerminal.fromStored(
            response.getRetainedIntakeTerminal().toByteArray());
    requireExactRetainedTerminal(expectedBinding, terminal, expectedNamespace);
    String terminalDigest = terminal.digest();
    if (!terminalDigest.equals(response.getRetainedIntakeTerminalDigest())) {
      throw new IllegalArgumentException("Changed retained intake terminal digest");
    }

    byte[] manifestBytes = terminal.manifestBytes();
    String manifestDigest = GameplayRuleManifest.sha256(manifestBytes);
    String abilitySchemaDigest =
        GameplayAbilitySchemaProjection.digest(expectedBinding.authorization().source().manifest());
    if (!manifestDigest.equals(response.getContentDigest())
        || !abilitySchemaDigest.equals(response.getAbilitySchemaDigest())
        || response.getDigestSchemaVersion() != DIGEST_SCHEMA_VERSION
        || !CANONICALIZATION.equals(response.getCanonicalization())) {
      throw new IllegalArgumentException("Changed retained publication digest evidence");
    }

    return new Evidence(
        expectedBinding,
        terminal,
        manifestDigest,
        abilitySchemaDigest,
        DIGEST_SCHEMA_VERSION,
        CANONICALIZATION);
  }

  private static void requireResponseScope(
      GameLogicPublicationSourceReadBinding binding, GetDraftDesignDigestResponse response) {
    PublicationDigestRequestBinding publication = binding.publicationRequest();
    GameLogicIntakeAuthorizationBinding authorization = binding.authorization();
    String selectedVersionRowId =
        Long.toString(authorization.source().binding().target().gameDesignVersionRowId());
    if (response.getScopeCase() != GetDraftDesignDigestResponse.ScopeCase.VERSION_ID
        || !response.getBaseVersionId().isEmpty()
        || !publication.tenantId().equals(response.getTenantId())
        || !authorization.tenantId().toString().equals(response.getTenantId())
        || !publication.versionId().equals(response.getVersionId())
        || !selectedVersionRowId.equals(response.getVersionId())
        || !authorization
            .source()
            .binding()
            .commitId()
            .toString()
            .equals(response.getAppliedCommitId())) {
      throw new IllegalArgumentException("Changed typed publication source scope");
    }
  }

  private static void requireExactBindingEcho(
      GameLogicPublicationSourceReadBinding binding, GetDraftDesignDigestResponse response) {
    if (!binding.digest().equals(response.getSourceReadBindingDigest())) {
      throw new IllegalArgumentException("Changed publication source-read binding digest");
    }
    byte[] expectedBytes = binding.canonicalBytes();
    byte[] suppliedBytes = response.getSourceReadBinding().toByteArray();
    if (!Arrays.equals(expectedBytes, suppliedBytes)) {
      throw new IllegalArgumentException("Substituted publication source-read binding");
    }
  }

  private static void requireExactRetainedTerminal(
      GameLogicPublicationSourceReadBinding binding,
      GameLogicGameplayRuleIntakeTerminal terminal,
      String expectedNamespace) {
    GameLogicIntakeAuthorizationBinding authorization = binding.authorization();
    if (terminal.outcome() != GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED
        || !Arrays.equals(authorization.canonicalBytes(), terminal.authorizationBytes())
        || !Arrays.equals(
            authorization.canonicalBytes(), terminal.operation().authorization().canonicalBytes())
        || !Arrays.equals(
            authorization.source().canonicalBytes(), terminal.selectedSourceBytes())) {
      throw new IllegalArgumentException(
          "Terminal is not the exact retained source for this publication binding");
    }
    if (expectedNamespace != null
        && !expectedNamespace.equals(terminal.operation().targetNamespace())) {
      throw new IllegalArgumentException("Retained terminal namespace differs from caller scope");
    }
  }

  private static void requireFullVersion(GameLogicPublicationSourceReadBinding binding) {
    if (binding.publicationRequest().scopeKind()
        != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException("Only full-version publication reads are supported");
    }
  }

  private static void rejectNonemptyError(GetDraftDesignDigestResponse response) {
    if (!response.hasError()) return;
    var error = response.getError();
    if (!error.getUnknownFields().asMap().isEmpty()
        || !error.getCode().isEmpty()
        || !error.getMessage().isEmpty()) {
      throw new IllegalArgumentException("Publication digest response carries an error");
    }
  }

  private static int stringFieldSize(int fieldNumber, String value) {
    return CodedOutputStream.computeStringSize(fieldNumber, value);
  }

  private static int bytesFieldSize(int fieldNumber, int length) {
    return CodedOutputStream.computeTagSize(fieldNumber)
        + CodedOutputStream.computeUInt32SizeNoTag(length)
        + length;
  }

  private static void requireBindingSize(int length) {
    if (length <= 0 || length > MAX_BINDING_BYTES) {
      throw new IllegalArgumentException("Invalid publication source-read binding size");
    }
  }

  private static void requireTerminalSize(int length) {
    if (length <= 0 || length > MAX_TERMINAL_BYTES) {
      throw new IllegalArgumentException("Invalid retained intake terminal size");
    }
  }

  private static void requireWireSize(int size) {
    if (size < 0 || size > MAX_WIRE_BYTES) {
      throw new IllegalArgumentException("Publication digest protobuf exceeds 24 MiB");
    }
  }

  /** Closed immutable value containing exact retained-source and derived digest evidence. */
  public record Evidence(
      GameLogicPublicationSourceReadBinding binding,
      GameLogicGameplayRuleIntakeTerminal terminal,
      String manifestDigest,
      String abilitySchemaDigest,
      int digestSchemaVersion,
      String canonicalization) {
    public Evidence {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(terminal, "terminal");
      Objects.requireNonNull(manifestDigest, "manifestDigest");
      Objects.requireNonNull(abilitySchemaDigest, "abilitySchemaDigest");
      Objects.requireNonNull(canonicalization, "canonicalization");
      requireFullVersion(binding);
      requireExactRetainedTerminal(binding, terminal, null);
      if (digestSchemaVersion != DIGEST_SCHEMA_VERSION
          || !CANONICALIZATION.equals(canonicalization)
          || !GameplayRuleManifest.sha256(terminal.manifestBytes()).equals(manifestDigest)
          || !GameplayAbilitySchemaProjection.digest(binding.authorization().source().manifest())
              .equals(abilitySchemaDigest)) {
        throw new IllegalArgumentException("Invalid retained publication digest evidence");
      }
    }
  }
}
