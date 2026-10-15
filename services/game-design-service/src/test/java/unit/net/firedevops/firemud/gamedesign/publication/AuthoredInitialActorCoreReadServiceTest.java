package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class AuthoredInitialActorCoreReadServiceTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final StartSessionRedeemedOperationProjectionClient account =
      mock(StartSessionRedeemedOperationProjectionClient.class);
  private final PublishedReleaseBundleService releases = mock(PublishedReleaseBundleService.class);
  private final StartSessionTemplateAssociationReadService associationReader =
      new StartSessionTemplateAssociationReadService(
          dsl, transactions, "world-runtime", account, releases);
  private final AuthoredInitialActorCoreReadService service =
      new AuthoredInitialActorCoreReadService(
          dsl, "world-runtime", associationReader, JsonMapper.builder().build());

  @Test
  void authenticatesExactCoreReadWorkloadBeforeRequestOrStorageChecks() {
    assertThatThrownBy(() -> service.read((ReadRequest) null))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    verifyNoInteractions(dsl, transactions, account, releases);
  }

  @Test
  void rejectsDifferentNamespaceWorkloadBeforeRequestAndOwnerStorage() {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/other-runtime/sa/entity-management-service",
            "other-runtime",
            "entity-management-service");
    withPeer(peer, () -> service.read((ReadRequest) null));
    verifyNoInteractions(dsl, transactions, account, releases);
  }

  @Test
  void deniesAuthenticatedEndUserContextAfterAuthenticatingExactWorkload() {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/world-runtime/sa/entity-management-service",
            "world-runtime",
            "entity-management-service");
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try (MockedStatic<SessionContext> sessionContext = mockStatic(SessionContext.class)) {
      sessionContext.when(SessionContext::hasAuthenticatedCallerContext).thenReturn(true);
      assertThatThrownBy(() -> service.read((ReadRequest) null))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(dsl, transactions, account, releases);
    } finally {
      context.detach(previous);
    }
  }

  private static void withPeer(GrpcPeerIdentity peer, Supplier<?> operation) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      assertThatThrownBy(operation::get)
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      context.detach(previous);
    }
  }
}
