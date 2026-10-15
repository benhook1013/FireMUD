package net.firedevops.firemud.gamesession.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalDisposition;
import net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalProofKind;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse;
import org.junit.jupiter.api.Test;

class InitialGameplayLoginDisposalResultTest {
  private static final String REQUEST = "1ee95a1e-83f2-4a63-a7ba-6288e246ac76";
  private static final String CONTEXT = "91d13625-0e03-4e46-b82e-18f69f091436";
  private static final String OPERATION = "abc56b29-78aa-4c18-b684-9763d665e4c8";
  private static final UnknownFieldSet UNKNOWN =
      UnknownFieldSet.newBuilder()
          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
          .build();

  @Test
  void acceptsOnlyClosedLifecycleEvidenceWithoutTokenOrSigner() {
    for (var disposition :
        List.of(
            InitialGameplayLoginDisposalDisposition
                .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING,
            InitialGameplayLoginDisposalDisposition
                .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_RETIRED,
            InitialGameplayLoginDisposalDisposition
                .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_NO_TOKEN)) {
      var proof =
          switch (disposition) {
            case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING ->
                InitialGameplayLoginDisposalProofKind
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_UNSPECIFIED;
            case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_RETIRED ->
                InitialGameplayLoginDisposalProofKind
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_PHYSICAL_TOKEN_DENIAL;
            case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_NO_TOKEN ->
                InitialGameplayLoginDisposalProofKind
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_NO_ISSUANCE_BINDING;
            default -> throw new AssertionError("Unexpected test disposition");
          };
      var result =
          InitialGameplayLoginDisposalResult.fromAuthenticatedResponse(
              response().toBuilder().setDisposition(disposition).setProofKind(proof).build(),
              request());
      assertThat(result.requestId().toString()).isEqualTo(REQUEST);
      assertThat(result.callerContextId().toString()).isEqualTo(CONTEXT);
      assertThat(result.disposalOperationId().toString()).isEqualTo(OPERATION);
      assertThat(result.disposition().name())
          .isEqualTo(
              disposition.name().replace("INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_", ""));
    }
    InitialGameplayLoginDisposalResult.requireOriginalRequest(
        request().toBuilder().setTokenWitness(witness()).build());
  }

  @Test
  void rejectsMismatchedMalformedAndExtendedResponses() {
    for (var response :
        List.of(
            response().toBuilder().setRequestId(OPERATION).build(),
            response().toBuilder().setCallerContextId(OPERATION).build(),
            response().toBuilder().clearDisposalOperationId().build(),
            response().toBuilder()
                .setDisposalOperationId("00000000-0000-0000-0000-000000000000")
                .build(),
            response().toBuilder().setDisposalOperationId(OPERATION.toUpperCase()).build(),
            response().toBuilder()
                .setDisposalOperationId("abc56b29-78aa-1c18-b684-9763d665e4c8")
                .build(),
            response().toBuilder().clearDisposition().build(),
            response().toBuilder().setDispositionValue(4).build(),
            response().toBuilder().setProofKindValue(3).build(),
            response().toBuilder().setUnknownFields(UNKNOWN).build())) {
      assertThatThrownBy(
              () ->
                  InitialGameplayLoginDisposalResult.fromAuthenticatedResponse(response, request()))
          .isInstanceOf(InitialGameplayLoginDisposalResult.InvalidResultException.class);
    }
    assertThatThrownBy(
            () -> InitialGameplayLoginDisposalResult.fromAuthenticatedResponse(null, request()))
        .isInstanceOf(InitialGameplayLoginDisposalResult.InvalidResultException.class);
  }

  @Test
  void rejectsCrossedOwnerNonissuanceAndPhysicalDenialProofs() {
    for (int disposition = 1; disposition <= 3; disposition++) {
      for (int proof = 0; proof <= 2; proof++) {
        if (proof == disposition - 1) continue;
        var crossed =
            response().toBuilder()
                .setDispositionValue(disposition)
                .setProofKindValue(proof)
                .build();
        assertThatThrownBy(
                () ->
                    InitialGameplayLoginDisposalResult.fromAuthenticatedResponse(
                        crossed, request()))
            .isInstanceOf(InitialGameplayLoginDisposalResult.InvalidResultException.class);
      }
    }
  }

  @Test
  void rejectsIncompleteNonOriginalAndExtendedRequests() {
    for (var request :
        List.of(
            request().toBuilder().clearRequestId().build(),
            request().toBuilder().setRequestId(REQUEST.toUpperCase()).build(),
            request().toBuilder()
                .setCallerContextId("91d13625-0e03-1e46-b82e-18f69f091436")
                .build(),
            request().toBuilder().setUnknownFields(UNKNOWN).build(),
            request().toBuilder()
                .setTokenWitness(InitialGameplayLoginTokenWitness.getDefaultInstance())
                .build(),
            request().toBuilder().setTokenWitness(witness().toBuilder().clearTokenJti()).build(),
            request().toBuilder()
                .setTokenWitness(witness().toBuilder().setTokenSha256("A".repeat(64)))
                .build(),
            request().toBuilder()
                .setTokenWitness(witness().toBuilder().setTokenGeneration(2))
                .build(),
            request().toBuilder()
                .setTokenWitness(witness().toBuilder().setIssuanceFence(0))
                .build(),
            request().toBuilder()
                .setTokenWitness(witness().toBuilder().setUnknownFields(UNKNOWN))
                .build())) {
      assertThatThrownBy(() -> InitialGameplayLoginDisposalResult.requireOriginalRequest(request))
          .isInstanceOf(InitialGameplayLoginDisposalResult.InvalidResultException.class);
    }
  }

  private static RetireInitialGameplayLoginRequest request() {
    return RetireInitialGameplayLoginRequest.newBuilder()
        .setRequestId(REQUEST)
        .setCallerContextId(CONTEXT)
        .build();
  }

  private static RetireInitialGameplayLoginResponse response() {
    return RetireInitialGameplayLoginResponse.newBuilder()
        .setRequestId(REQUEST)
        .setCallerContextId(CONTEXT)
        .setDisposalOperationId(OPERATION)
        .setDisposition(
            InitialGameplayLoginDisposalDisposition
                .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING)
        .build();
  }

  private static InitialGameplayLoginTokenWitness witness() {
    return InitialGameplayLoginTokenWitness.newBuilder()
        .setTokenJti(OPERATION)
        .setTokenSha256("a".repeat(64))
        .setTokenGeneration(1)
        .setIssuanceFence(7)
        .build();
  }
}
