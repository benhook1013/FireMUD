package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-confirmed composition for the exact recipient-specific retained World inventory. */
final class WorldSelectedOwnerInventoryReadService {
  private final String trustedNamespace;
  private final WorldSelectedPublicationArtifactInventoryReadService inventoryReadService;
  private final SelectedOwnerIntakeWorldClosureAuthorizationReadClient accountReadClient;

  WorldSelectedOwnerInventoryReadService(
      String trustedNamespace,
      WorldSelectedPublicationArtifactInventoryReadService inventoryReadService,
      SelectedOwnerIntakeWorldClosureAuthorizationReadClient accountReadClient) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = Objects.requireNonNull(trustedNamespace, "trustedNamespace");
    this.inventoryReadService =
        Objects.requireNonNull(inventoryReadService, "inventoryReadService");
    this.accountReadClient = Objects.requireNonNull(accountReadClient, "accountReadClient");
  }

  SelectedOwnerWorldInventoryReadEvidence read(
      SelectedOwnerWorldInventoryReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("World owner inventory read requires no ambient SQL");
    }
    if (!trustedNamespace.equals(request.targetNamespace())) {
      throw new SecurityException("World owner inventory namespace differs from this workload");
    }
    requireAuthenticatedRecipient(request);
    var accountRequest =
        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
            trustedNamespace, request.authorizationBinding());
    while (accountRequest.readRequestId().equals(request.readRequestId())) {
      accountRequest =
          SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
              trustedNamespace, request.authorizationBinding());
    }
    final SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence accountEvidence;
    try {
      accountEvidence = accountReadClient.readHeld(accountRequest);
    } catch (StatusRuntimeException failure) {
      throw new ReadFailure(
          statusFor(failure),
          "Account did not confirm the exact selected-owner World closure read");
    } catch (SecurityException denied) {
      throw new ReadFailure(
          Status.Code.PERMISSION_DENIED,
          "Account did not confirm the exact selected-owner World closure read");
    } catch (IllegalArgumentException invalid) {
      throw new ReadFailure(
          Status.Code.FAILED_PRECONDITION,
          "Account did not confirm the exact selected-owner World closure read");
    } catch (RuntimeException unavailable) {
      throw new ReadFailure(
          Status.Code.UNAVAILABLE,
          "Account did not confirm the exact selected-owner World closure read");
    }
    if (accountEvidence == null || !sameAccountRequest(accountRequest, accountEvidence.request())) {
      throw new ReadFailure(
          Status.Code.PERMISSION_DENIED,
          "Account did not confirm the exact selected-owner World closure read");
    }

    try {
      var inventory = inventoryReadService.readRetained(request.freezeEvidence());
      return new SelectedOwnerWorldInventoryReadEvidence(request, inventory);
    } catch (StatusRuntimeException failure) {
      Status.Code status = statusFor(failure);
      throw new ReadFailure(
          status,
          status == Status.Code.FAILED_PRECONDITION
              ? "Exact retained World artifact inventory is unavailable"
              : "World selected inventory owner storage is unavailable");
    } catch (SecurityException denied) {
      throw new ReadFailure(
          Status.Code.PERMISSION_DENIED, "Exact retained World artifact inventory is unavailable");
    } catch (WorldDesignPublicationFenceRepository.ConflictException
        | IllegalArgumentException inconsistent) {
      throw new ReadFailure(
          Status.Code.FAILED_PRECONDITION,
          "Exact retained World artifact inventory is unavailable");
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException unavailable) {
      throw new ReadFailure(
          Status.Code.UNAVAILABLE, "World selected inventory owner storage is unavailable");
    } catch (RuntimeException unavailable) {
      throw new ReadFailure(
          Status.Code.UNAVAILABLE, "World selected-owner authorization or inventory read failed");
    }
  }

  private void requireAuthenticatedRecipient(
      SelectedOwnerWorldInventoryReadEvidence.Request request) {
    var binding = request.authorizationBinding();
    String service = ownerService(binding.owner());
    if (service == null) {
      throw new SecurityException("Unsupported selected-owner World inventory recipient");
    }
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/" + service;
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (!expected.equals(binding.intendedReader())
        || peer == null
        || !expected.equals(peer.uri())
        || !peer.isService(service)
        || !peer.isInNamespace(trustedNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException("Exact same-namespace selected-owner workload is required");
    }
  }

  private static String ownerService(Owner owner) {
    return switch (owner) {
      case ENTITY_MANAGEMENT -> "entity-management-service";
      case AUTOMATION_SCRIPTING -> "automation-scripting-service";
      default -> null;
    };
  }

  private static Status.Code statusFor(StatusRuntimeException failure) {
    return failure.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
        ? Status.Code.FAILED_PRECONDITION
        : Status.Code.UNAVAILABLE;
  }

  static final class ReadFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Status.Code statusCode;
    private final String description;

    private ReadFailure(Status.Code statusCode, String description) {
      super(description);
      this.statusCode = statusCode;
      this.description = description;
    }

    Status.Code statusCode() {
      return statusCode;
    }

    String description() {
      return description;
    }
  }

  private static boolean sameAccountRequest(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request expected,
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request actual) {
    return actual != null
        && expected.schemaVersion() == actual.schemaVersion()
        && expected.targetNamespace().equals(actual.targetNamespace())
        && expected.readRequestId().equals(actual.readRequestId())
        && expected.intendedReader().equals(actual.intendedReader())
        && expected.closureReadPurpose().equals(actual.closureReadPurpose())
        && Arrays.equals(expected.binding().canonicalBytes(), actual.binding().canonicalBytes());
  }
}
