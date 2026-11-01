package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantRequest;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantResponse;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantCreationServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled RPC responses; configured peer pinning is not physical mTLS execution proof. */
class GameDesignAuthorizedTenantCreationClientTest {
  @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }

  @Test
  void sendsCompleteExactOriginalWithBoundedDeadlineAndExplicitDescriptionPresence() throws Exception {
    for (String description : new String[] {null, ""}) {
      var f = new Fixture(description);
      when(f.stub.createAuthorizedTenant(any())).thenAnswer(invocation -> {
        var request = invocation.<CreateAuthorizedTenantRequest>getArgument(0);
        assertThat(request.getCreateRequestId()).isEqualTo(f.call.toString());
        assertThat(request.hasDescription()).isEqualTo(description != null);
        assertThat(request.getCreationRequestDigest()).isEqualTo(f.binding.creationRequestDigest());
        return f.response();
      });
      assertThat(f.send()).isEqualTo(f.receipt);
      verify(f.stub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }
  }

  @Test
  void rejectsChangedEchoNestedReceiptUnknownStatusFieldsAndOversize() throws Exception {
    List<UnaryOperator<CreateAuthorizedTenantResponse>> mutations = List.of(
        r -> r.toBuilder().setCreateRequestId(UUID.randomUUID().toString()).build(),
        r -> r.toBuilder().setTargetNamespace("other").build(),
        r -> r.toBuilder().setCreationRequestId(UUID.randomUUID().toString()).build(),
        r -> r.toBuilder().setAccountAuthorizationOperationId(UUID.randomUUID().toString()).build(),
        r -> r.toBuilder().setInitiatingAccountId(UUID.randomUUID().toString()).build(),
        r -> r.toBuilder().setCreationRequestDigest("sha256:" + "b".repeat(64)).build(),
        r -> r.toBuilder().setAccountAuthorizationDigest("sha256:" + "b".repeat(64)).build(),
        r -> r.toBuilder().setStatusValue(99).build(),
        r -> r.toBuilder().clearCreatedReceipt().build(),
        r -> r.toBuilder().setCreatedReceipt(r.getCreatedReceipt().toBuilder().setCanonicalTenantId(UUID.randomUUID().toString())).build(),
        r -> r.toBuilder().setCreatedReceipt(r.getCreatedReceipt().toBuilder().setSourceGameTenantKey("other")).build());
    for (var mutation : mutations) {
      var f = new Fixture(null);
      when(f.stub.createAuthorizedTenant(any())).thenReturn(mutation.apply(f.response()));
      assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
    }
    var f = new Fixture(null);
    var unknown = UnknownFieldSet.newBuilder().addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
    when(f.stub.createAuthorizedTenant(any())).thenReturn(f.response().toBuilder().setUnknownFields(unknown).build());
    assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
    when(f.stub.createAuthorizedTenant(any())).thenReturn(f.response().toBuilder()
        .setCreatedReceipt(f.response().getCreatedReceipt().toBuilder().setUnknownFields(unknown)).build());
    assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
    when(f.stub.createAuthorizedTenant(any())).thenReturn(f.response().toBuilder().setCreateRequestId("x".repeat(4097)).build());
    assertThatThrownBy(f::send).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void neverRetriesLostRepliesAndRefusesAmbientSqlOrExpiredDispatchBeforeNetwork() throws Exception {
    var f = new Fixture(null);
    var failure = Status.UNAVAILABLE.asRuntimeException();
    when(f.stub.createAuthorizedTenant(any())).thenThrow(failure);
    assertThatThrownBy(f::send).isSameAs(failure);
    verify(f.stub).createAuthorizedTenant(any());
    var untouched = new Fixture(null);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(untouched::send).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.clear();
    assertThatThrownBy(() -> untouched.client.create(untouched.call, untouched.binding, "fresh", "Game", null, 1L))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(untouched.stub);
  }

  @Test
  void requiresFileMtlsAndPinsExactSameNamespaceGameDesignServer() throws Exception {
    var tls = tls(); tls.setPlaintext(true);
    var channels = mock(GrpcChannelFactory.class);
    assertThatThrownBy(() -> new GameDesignAuthorizedTenantCreationClient(new ServiceEndpointsProperties(), tls, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var f = new Fixture(null);
    Field field = GameDesignAuthorizedTenantCreationClient.class.getDeclaredField("peer"); field.setAccessible(true);
    Field expected = GrpcServerPeerIdentityClientInterceptor.class.getDeclaredField("expectedPeerUri"); expected.setAccessible(true);
    assertThat(expected.get(field.get(f.client))).isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
    verifyNoInteractions(channels);
  }

  private static CommonGrpcClientProperties tls() {
    var tls = new CommonGrpcClientProperties(); tls.setPlaintext(false);
    tls.setCertChain("certs/account.crt"); tls.setPrivateKey("certs/account.key"); tls.setCaCert("certs/ca.crt"); return tls;
  }

  private static final class Fixture {
    final UUID call = UUID.randomUUID(), request = UUID.randomUUID(), operation = UUID.randomUUID(), tenant = UUID.randomUUID();
    final Binding binding;
    final FreshTenantCreationEvidence receipt;
    final String description;
    final TenantCreationServiceGrpc.TenantCreationServiceBlockingStub stub = mock(TenantCreationServiceGrpc.TenantCreationServiceBlockingStub.class);
    final GameDesignAuthorizedTenantCreationClient client;
    Fixture(String description) throws Exception {
      this.description = description;
      String digest = GameTenantCreationDigest.requestDigest("test", request, "fresh", "Game", description);
      binding = new Binding("test", request, UUID.randomUUID(), UUID.randomUUID(), digest, "sha256:" + "a".repeat(64));
      receipt = new FreshTenantCreationEvidence(1, "test", request, operation, digest, tenant, 9, "fresh", "NEW_GAME_ROW",
          GameTenantCreationDigest.evidenceDigest("test", request, operation, digest, tenant, 9, "fresh", "NEW_GAME_ROW"));
      client = new GameDesignAuthorizedTenantCreationClient(new ServiceEndpointsProperties(), tls(), mock(GrpcChannelFactory.class), "test");
      Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub"); field.setAccessible(true); field.set(client, stub);
      when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(stub);
    }
    FreshTenantCreationEvidence send() { return client.create(call, binding, "fresh", "Game", description, System.currentTimeMillis() + 60000); }
    CreateAuthorizedTenantResponse response() {
      return CreateAuthorizedTenantResponse.newBuilder().setSchemaVersion(1).setCreateRequestId(call.toString())
          .setTargetNamespace("test").setCreationRequestId(request.toString())
          .setAccountAuthorizationOperationId(binding.accountAuthorizationOperationId().toString())
          .setInitiatingAccountId(binding.initiatingAccountId().toString()).setCreationRequestDigest(binding.creationRequestDigest())
          .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
          .setStatus(OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED)
          .setCreatedReceipt(ResolveFreshTenantCreationResponse.newBuilder().setSchemaVersion(1).setTargetNamespace("test")
              .setCreationRequestId(request.toString()).setOperationId(operation.toString()).setRequestDigest(binding.creationRequestDigest())
              .setCanonicalTenantId(tenant.toString()).setSourceGameRowId(9).setSourceGameTenantKey("fresh")
              .setProvenanceKind("NEW_GAME_ROW").setEvidenceDigest(receipt.evidenceDigest())).build();
    }
  }
}
