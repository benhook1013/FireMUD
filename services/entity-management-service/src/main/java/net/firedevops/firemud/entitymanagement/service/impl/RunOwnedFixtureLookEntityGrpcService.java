package net.firedevops.firemud.entitymanagement.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.ReadRunOwnedFixtureGroundRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadRunOwnedFixtureGroundResponse;
import net.firedevops.firemud.entitymanagement.v1.RunOwnedFixtureLookEntityServiceGrpc;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entity-owned ground absence for the exact fresh fixture. Populated scopes require canonical
 * Entity ownership and are held. The receipt and fixed run capability qualify the scope, and the
 * actual holder tables establish absence.
 */
@GrpcService
@ConditionalOnExpression(
    "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}"
        + " and ${firemud.pre-v1.gate-waivers.enabled:false}")
public final class RunOwnedFixtureLookEntityGrpcService
    extends RunOwnedFixtureLookEntityServiceGrpc.RunOwnedFixtureLookEntityServiceImplBase {
  private static final int MAX_FIXTURE_FILE_BYTES = 16 * 1024;
  private static final String GROUND_SOURCE_EXISTS =
      "SELECT EXISTS (SELECT 1 FROM room_ground_inventory WHERE game_instance_id = ?"
          + " UNION ALL SELECT 1 FROM container_instances WHERE game_instance_id = ?"
          + " UNION ALL SELECT 1 FROM item_instances WHERE game_instance_id = ?"
          + " UNION ALL SELECT 1 FROM item_stacks WHERE game_instance_id = ?)";

  private final DSLContext dsl;
  private final TransactionTemplate transaction;
  private final String namespace;
  private final String runId;
  private final String composeProject;
  private final Path capabilityPath;
  private final Path receiptPath;

  public RunOwnedFixtureLookEntityGrpcService(
      DSLContext dsl,
      @Qualifier("transactionManager") PlatformTransactionManager transactions,
      Environment environment,
      @Value("${firemud.grpc.workload-namespace:}") String namespace,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String composeProject,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:}") String capabilityPath,
      @Value("${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH:}") String receiptPath) {
    if (!PreV1GateWaivers.from(environment).enabled()
        || !GrpcPeerIdentity.isValidNamespace(namespace)
        || runId.isBlank()
        || composeProject.isBlank()) {
      throw new IllegalArgumentException("A run-owned fixture Entity scope is required");
    }
    this.dsl = dsl;
    transaction = new TransactionTemplate(transactions);
    transaction.setReadOnly(true);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setTimeout(2);
    this.namespace = namespace;
    this.runId = runId;
    this.composeProject = composeProject;
    this.capabilityPath = Path.of(capabilityPath);
    this.receiptPath = Path.of(receiptPath);
  }

  @Override
  public void readRunOwnedFixtureGround(
      ReadRunOwnedFixtureGroundRequest request,
      StreamObserver<ReadRunOwnedFixtureGroundResponse> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (!peer.isService("game-logic-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    final WorldRunOwnedFixtureLifecycleEvidence world;
    var room = request.getRoom();
    try {
      if (!request.hasRoom()
          || !request.getUnknownFields().asMap().isEmpty()
          || !room.getUnknownFields().asMap().isEmpty()
          || !room.getRoomInstanceId().matches("R-[1-9][0-9]{0,18}")) {
        throw new IllegalArgumentException("An exact fixture room is required");
      }
      world =
          WorldRunOwnedFixtureLifecycleEvidence.fromStored(
              request.getWorldFixtureLifecycle().toByteArray());
      if (!world.request().canonicalTenantId().toString().equals(room.getTenantId())
          || !world.request().canonicalGameInstanceId().toString().equals(room.getGameInstanceId())
          || !world.request().targetNamespace().equals(namespace)
          || !"ACTIVE".equals(world.lifecycleStatus())) {
        throw new IllegalArgumentException("The fixture room differs from the current World scope");
      }
    } catch (IllegalArgumentException invalid) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      var verified = readFixture();
      if (!verified
              .creationEvidence()
              .canonicalTenantId()
              .equals(world.request().canonicalTenantId())
          || !verified
              .fixturePolicySeal()
              .evidenceDigest()
              .equals(world.fixtureProvenanceDigest())) {
        observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
        return;
      }
      // The instance UUID is globally unique. Inspect every actual holder family at that UUID
      // without adopting a numeric tenant or guessing the ownership of any populated row.
      String game = room.getGameInstanceId();
      Boolean populated =
          transaction.execute(
              status -> {
                dsl.execute("SET LOCAL statement_timeout = '1000ms'");
                return (Boolean) dsl.fetchValue(GROUND_SOURCE_EXISTS, game, game, game, game);
              });
      if (!Boolean.FALSE.equals(populated)
          || !verified.fixturePolicySeal().equals(readFixture().fixturePolicySeal())) {
        observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        return;
      }
    } catch (IOException | RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
      return;
    }
    observer.onNext(
        ReadRunOwnedFixtureGroundResponse.newBuilder()
            .setRoom(room)
            .setEntitySnapshotId(
                room.getTenantId()
                    + ":"
                    + room.getGameInstanceId()
                    + ":"
                    + room.getRoomInstanceId())
            .setEmptyGroundVerified(true)
            .build());
    observer.onCompleted();
  }

  private RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt readFixture() throws IOException {
    var capability = RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(read(capabilityPath));
    if (!runId.equals(capability.runId())
        || !composeProject.equals(capability.composeProjectName())) {
      throw new IllegalStateException("Fixture capability does not belong to this run");
    }
    return RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(
        read(receiptPath), capability);
  }

  private static byte[] read(Path path) throws IOException {
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(MAX_FIXTURE_FILE_BYTES + 1);
      if (bytes.length > MAX_FIXTURE_FILE_BYTES) {
        throw new IllegalStateException("Fixture source exceeds its wire bound");
      }
      return bytes;
    }
  }
}
