package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import org.junit.jupiter.api.Test;

/** Controlled byte-contract proof only; fixture source bytes do not authenticate an owner. */
class EntityCharacterCreateBindingsTest {
  @Test
  void strictOriginalPreservesEmptyInputAndAuthoredGenericCore() throws Exception {
    var original = original();
    var action = EntityCharacterCreateBindings.requireOriginal(reference(original), original);
    assertThat(action.mutation().canonicalCreationInput()).isEqualTo("{}");
    assertThat(action.mutation().authoredSource().core().canonicalJson()).contains("Wayfarer");
    assertThatThrownBy(
            () ->
                EntityCharacterCreateBindings.requireOriginal(
                    reference(original),
                    original.toBuilder()
                        .setAuthenticatedAccountId(UUID.randomUUID().toString())
                        .build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    assertThatThrownBy(
            () ->
                EntityCharacterCreateBindings.requireOriginal(
                    reference(original),
                    original.toBuilder().setOriginalSourceReadback(ByteString.EMPTY).build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
  }

  @Test
  void protectionRequiresExactOriginalOperationSourceAndFence() throws Exception {
    var original = original();
    var accountRef = protection();
    var request =
        CommitProtectedCharacterCreateRequest.newBuilder()
            .setOriginal(reference(original))
            .setAccountProtection(accountRef)
            .setGameSessionProtection(protection())
            .setWorldProtection(protection())
            .build();
    var proof =
        ReadOriginalCharacterCreateProtectionResponse.newBuilder()
            .setOriginal(original)
            .setAccountProtectionId(accountRef.getProtectionId())
            .setAccountProtectionFence(accountRef.getProtectionFence())
            .setAcquiredAt("2026-10-11T12:35:00Z")
            .setOriginalMembershipSnapshot(ByteString.copyFromUtf8("fixture-membership"))
            .setOriginalEntitlementSnapshot(ByteString.copyFromUtf8("fixture-entitlement"))
            .build();
    EntityCharacterCreateBindings.requireAccountProtection(request, original, proof);
    assertThatThrownBy(
            () ->
                EntityCharacterCreateBindings.requireAccountProtection(
                    request,
                    original,
                    proof.toBuilder()
                        .setOriginal(
                            original.toBuilder()
                                .setOriginalSourceReadback(
                                    ByteString.copyFromUtf8("different-source")))
                        .build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    assertThatThrownBy(
            () ->
                EntityCharacterCreateBindings.requireAccountProtection(
                    request, original, proof.toBuilder().setAccountProtectionFence(2L).build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
  }

  @Test
  void unavailableGameSessionCreationAuthorityCannotBecomePositiveRosterEvidence() {
    assertThatThrownBy(
            () ->
                GameSessionCharacterCreateProtectionReader.unavailable()
                    .readOriginal(
                        ReadOriginalCharacterCreateProtectionResponse.getDefaultInstance(),
                        protection()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining("UNAVAILABLE");
  }

  @Test
  void allThreePositiveCanonicalReferencesAreRequired() throws Exception {
    var request =
        CommitProtectedCharacterCreateRequest.newBuilder()
            .setOriginal(reference(original()))
            .setAccountProtection(protection())
            .setGameSessionProtection(protection())
            .setWorldProtection(protection())
            .build();
    EntityCharacterCreateBindings.requireRequest(request);
    assertThatThrownBy(
            () ->
                EntityCharacterCreateBindings.requireRequest(
                    request.toBuilder().clearWorldProtection().build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    assertThatThrownBy(
            () ->
                EntityCharacterCreateBindings.requireRequest(
                    request.toBuilder()
                        .setGameSessionProtection(
                            request.getGameSessionProtection().toBuilder().setProtectionFence(0L))
                        .build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
  }

  private static ReadOriginalCharacterCreateResponse original() throws Exception {
    byte[] bytes;
    try (InputStream input =
        Objects.requireNonNull(
            EntityCharacterCreateBindingsTest.class.getResourceAsStream(
                "/charactercreate/entity-public-action.json"))) {
      bytes =
          new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .strip()
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    return ReadOriginalCharacterCreateResponse.newBuilder()
        .setTargetNamespace("firemud")
        .setOperationId(UUID.randomUUID().toString())
        .setCreateCharacterRequestId(UUID.randomUUID().toString())
        .setConnectScopeId("Issued.Scope-Exact_Opaque.mixedCase-01")
        .setAuthenticatedAccountId("a4000000-0000-4000-8000-000000000001")
        .setPurpose("CHARACTER_CREATE")
        .setOriginalAction(ByteString.copyFrom(bytes))
        .setMutationDigest(CharacterCreateOperatorActionCodec.mutationDigest(bytes))
        .setOriginalSourceReadback(ByteString.copyFromUtf8("controlled-source"))
        .build();
  }

  private static ReadOriginalCharacterCreateRequest reference(
      ReadOriginalCharacterCreateResponse original) {
    return ReadOriginalCharacterCreateRequest.newBuilder()
        .setTargetNamespace(original.getTargetNamespace())
        .setOperationId(original.getOperationId())
        .setMutationDigest(original.getMutationDigest())
        .build();
  }

  private static CharacterCreateProtectionReference protection() {
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(UUID.randomUUID().toString())
        .setProtectionFence(1L)
        .build();
  }
}
