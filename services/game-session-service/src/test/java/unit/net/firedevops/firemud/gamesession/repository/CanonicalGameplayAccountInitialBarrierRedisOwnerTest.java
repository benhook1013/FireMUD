package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountInitialBarrierRedisScriptContribution;
import org.junit.jupiter.api.Test;

class CanonicalGameplayAccountInitialBarrierRedisOwnerTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void missingPhysicalValueAfterPossibleDispatchRefusesWithoutFreshScriptAttempt()
      throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var original = new InitialGameplayLoginAttemptCoordinates(requestId, callerContextId, 17L);
    var snapshot =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
            initializerId,
            requestId,
            callerContextId,
            17L,
            accountId,
            tenantId,
            namespaceId,
            DIGEST,
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.valueOf(8),
            BigInteger.valueOf(4),
            DIGEST,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            1_100L,
            31_000L,
            null,
            DIGEST);

    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.beginOrRead(any(), any(), any(), any())).thenReturn(snapshot);
    when(repository.readCurrent(requestId)).thenReturn(snapshot);
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    when(tenantTags.assignmentForInitialBarrierTarget(requestId)).thenReturn(assignment);
    when(assignment.isInitialBarrierTarget()).thenReturn(true);
    when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
    when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
    when(assignment.accountId()).thenReturn(accountId);
    when(assignment.tenantId()).thenReturn(tenantId);
    when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);
    when(assignment.tenantGameplayTag()).thenReturn("g8-" + "b".repeat(32));

    var observation = mock(CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation.class);
    when(observation.ownerKind())
        .thenReturn(
            CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER);
    when(observation.operationId()).thenReturn(initializerId);
    when(observation.requestId()).thenReturn(requestId);
    when(observation.originalCallerContextId()).thenReturn(callerContextId);
    when(observation.originalAttemptFence()).thenReturn(17L);
    when(observation.accountId()).thenReturn(accountId);
    when(observation.tenantId()).thenReturn(tenantId);
    when(observation.playableStateNamespaceId()).thenReturn(namespaceId);
    when(observation.sourceControlState()).thenReturn("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
    when(observation.sourceControlEpoch()).thenReturn("8");
    when(observation.inventorySnapshotRevision()).thenReturn("4");
    when(observation.accountLoginEvidenceDigest()).thenReturn(DIGEST);
    when(observation.sourceControlDigest()).thenReturn(DIGEST);
    when(observation.barrierTargetDigest()).thenReturn(DIGEST);
    when(observation.incarnationDigest()).thenReturn(DIGEST);
    when(observation.acceptedAttestationDigest()).thenReturn(DIGEST);
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    when(receiver.observeForInitialAccountBarrier(requestId)).thenReturn(observation);

    RedisClient redis = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisAsyncCommands<byte[], byte[]> commands = mock(RedisAsyncCommands.class);
    when(redis.connect(any(ByteArrayCodec.class))).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.async()).thenReturn(commands);
    doReturn(future("gamesession_coord_app")).when(commands).aclWhoami();
    doReturn(future("none")).when(commands).type(any(byte[].class));
    RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);

    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);
    var proof =
        owner.initializeOnce(
            original, mock(CanonicalGameplayLoginResult.class), tenantId, namespaceId);

    assertThat(proof)
        .isEqualTo(
            new CanonicalGameplayAccountInitialOpenProof.Refused(
                CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN));
    verify(repository).beginOrRead(eq(original), any(), eq(tenantId), eq(namespaceId));
    verify(commands).type(any(byte[].class));
    verifyNoInteractions(catalog);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void possibleDispatchIsPersistedBeforeEvalAndMissingAfterwardNeverGetsAFreshCreate()
      throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var original = new InitialGameplayLoginAttemptCoordinates(requestId, callerContextId, 17L);
    var never =
        snapshot(
            initializerId,
            original,
            accountId,
            tenantId,
            namespaceId,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED);
    var possible =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
            initializerId,
            requestId,
            callerContextId,
            17L,
            accountId,
            tenantId,
            namespaceId,
            DIGEST,
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.valueOf(8),
            BigInteger.valueOf(4),
            DIGEST,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            1_100L,
            31_000L,
            null,
            DIGEST);
    var before = initialObservation(possible);

    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.beginOrRead(any(), any(), any(), any())).thenReturn(never);
    when(repository.recordDispatchPossible(requestId, before)).thenReturn(possible);
    when(repository.readCurrent(requestId)).thenReturn(possible);
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    when(tenantTags.assignmentForInitialBarrierTarget(requestId)).thenReturn(assignment);
    when(assignment.isInitialBarrierTarget()).thenReturn(true);
    when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
    when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
    when(assignment.accountId()).thenReturn(accountId);
    when(assignment.tenantId()).thenReturn(tenantId);
    when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);
    when(assignment.tenantGameplayTag()).thenReturn("g8-" + "b".repeat(32));
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    when(receiver.observeForInitialAccountBarrier(requestId)).thenReturn(before);

    RedisClient redis = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisAsyncCommands<byte[], byte[]> commands = mock(RedisAsyncCommands.class);
    when(connection.isOpen()).thenReturn(true);
    when(connection.async()).thenReturn(commands);
    doReturn(future("gamesession_coord_app")).when(commands).aclWhoami();
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              verify(repository, org.mockito.Mockito.never()).recordDispatchPossible(any(), any());
              return future(
                  java.util.HexFormat.of()
                      .formatHex(
                          java.security.MessageDigest.getInstance("SHA-1")
                              .digest(invocation.getArgument(0, byte[].class))));
            });
    when(commands.evalsha(any(), any(), any(byte[][].class), any(byte[][].class)))
        .thenAnswer(
            ignored -> {
              verify(repository).recordDispatchPossible(requestId, before);
              throw new IllegalStateException("controlled unknown EVAL outcome");
            });
    AtomicInteger connectionAttempts = new AtomicInteger();
    when(redis.connect(any(ByteArrayCodec.class)))
        .thenAnswer(
            ignored -> {
              if (connectionAttempts.getAndIncrement() == 0) return connection;
              throw new IllegalStateException("controlled lost readback connection");
            });
    RedisScriptCatalog catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayAccountInitialBarrierRedisScriptContribution()));
    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);

    var proof =
        owner.initializeOnce(
            original, mock(CanonicalGameplayLoginResult.class), tenantId, namespaceId);

    assertThat(proof)
        .isEqualTo(
            new CanonicalGameplayAccountInitialOpenProof.Refused(
                CanonicalGameplayAccountInitialOpenProof.Reason.REMOTE_OUTCOME_UNCERTAIN));
    assertThat(connectionAttempts.get()).isEqualTo(2);
    verify(repository).recordDispatchPossible(requestId, before);
    verify(commands).evalsha(any(), any(), any(byte[][].class), any(byte[][].class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void exactSameOriginalOpenReplayIsReadOnlyAndReturnsCurrentTypedProof() throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    String tag = "g8-" + "b".repeat(32);
    var original = new InitialGameplayLoginAttemptCoordinates(requestId, callerContextId, 17L);
    var snapshot =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
            initializerId,
            requestId,
            callerContextId,
            17L,
            accountId,
            tenantId,
            namespaceId,
            DIGEST,
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.valueOf(8),
            BigInteger.valueOf(4),
            DIGEST,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            1_100L,
            31_000L,
            DIGEST,
            DIGEST);
    var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    when(assignment.isInitialBarrierTarget()).thenReturn(true);
    when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
    when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
    when(assignment.accountId()).thenReturn(accountId);
    when(assignment.tenantId()).thenReturn(tenantId);
    when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);
    when(assignment.tenantGameplayTag()).thenReturn(tag);
    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.beginOrRead(any(), any(), any(), any())).thenReturn(snapshot);
    when(repository.readCurrent(requestId)).thenReturn(snapshot);
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    when(tenantTags.assignmentForInitialBarrierTarget(requestId)).thenReturn(assignment);
    var observation = initialObservation(snapshot);
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    when(receiver.observeForInitialAccountBarrier(requestId)).thenReturn(observation);

    var expected =
        CanonicalGameplayAccountInitialBarrierValue.open(
            accountId, tenantId, namespaceId, tag, initializerId, BigInteger.valueOf(8), DIGEST);
    Map<byte[], byte[]> raw = new HashMap<>();
    expected
        .fields()
        .forEach(
            (field, value) ->
                raw.put(
                    field.getBytes(StandardCharsets.US_ASCII),
                    value.getBytes(StandardCharsets.US_ASCII)));
    RedisClient redis = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisAsyncCommands<byte[], byte[]> commands = mock(RedisAsyncCommands.class);
    when(redis.connect(any(ByteArrayCodec.class))).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.async()).thenReturn(commands);
    doReturn(future("gamesession_coord_app")).when(commands).aclWhoami();
    doReturn(future("hash")).when(commands).type(any(byte[].class));
    doReturn(future(-1L)).when(commands).pttl(any(byte[].class));
    doReturn(future(9L)).when(commands).hlen(any(byte[].class));
    doReturn(future(raw)).when(commands).hgetall(any(byte[].class));
    RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);

    var proof =
        owner.initializeOnce(
            original, mock(CanonicalGameplayLoginResult.class), tenantId, namespaceId);

    assertThat(proof).isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
    var current = (CanonicalGameplayAccountInitialOpenProof.CurrentOpen) proof;
    assertThat(current.originalRequestId()).isEqualTo(requestId);
    assertThat(current.sourceControlEpoch()).isEqualTo(BigInteger.valueOf(8));
    assertThat(current.exactFields()).isEqualTo(expected.fields());
    verify(repository, org.mockito.Mockito.times(2)).readCurrent(requestId);
    verify(repository).beginOrRead(eq(original), any(), eq(tenantId), eq(namespaceId));
    verify(repository, org.mockito.Mockito.times(3)).requireNoAmbientTransaction();
    verify(receiver, org.mockito.Mockito.times(2)).observeForInitialAccountBarrier(requestId);
    verifyNoInteractions(catalog);
    verify(commands).hgetall(any(byte[].class));
    verify(commands, org.mockito.Mockito.never())
        .evalsha(any(), any(), any(byte[][].class), any(byte[][].class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void installedOpenCanBeResolvedByExactTargetAndOnlyPerformsFreshReadback() throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    String tag = "g8-" + "b".repeat(32);
    var snapshot =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
            initializerId,
            requestId,
            callerContextId,
            17L,
            accountId,
            tenantId,
            namespaceId,
            DIGEST,
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.valueOf(8),
            BigInteger.valueOf(4),
            DIGEST,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            1_100L,
            31_000L,
            DIGEST,
            DIGEST);
    var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    when(assignment.isInitialBarrierTarget()).thenReturn(true);
    when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
    when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
    when(assignment.accountId()).thenReturn(accountId);
    when(assignment.tenantId()).thenReturn(tenantId);
    when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);
    when(assignment.tenantGameplayTag()).thenReturn(tag);
    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.readInstalledOpenForTarget(accountId, tenantId, namespaceId))
        .thenReturn(snapshot);
    when(repository.readCurrent(requestId)).thenReturn(snapshot);
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    when(tenantTags.assignmentForInitialBarrierTarget(requestId)).thenReturn(assignment);
    var observation = initialObservation(snapshot);
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    when(receiver.observeForInitialAccountBarrier(requestId)).thenReturn(observation);

    var expected =
        CanonicalGameplayAccountInitialBarrierValue.open(
            accountId, tenantId, namespaceId, tag, initializerId, BigInteger.valueOf(8), DIGEST);
    Map<byte[], byte[]> raw = new HashMap<>();
    expected
        .fields()
        .forEach(
            (field, value) ->
                raw.put(
                    field.getBytes(StandardCharsets.US_ASCII),
                    value.getBytes(StandardCharsets.US_ASCII)));
    RedisClient redis = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisAsyncCommands<byte[], byte[]> commands = mock(RedisAsyncCommands.class);
    when(redis.connect(any(ByteArrayCodec.class))).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.async()).thenReturn(commands);
    doReturn(future("gamesession_coord_app")).when(commands).aclWhoami();
    doReturn(future("hash")).when(commands).type(any(byte[].class));
    doReturn(future(-1L)).when(commands).pttl(any(byte[].class));
    doReturn(future(9L)).when(commands).hlen(any(byte[].class));
    doReturn(future(raw)).when(commands).hgetall(any(byte[].class));
    RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);

    var proof = owner.verifyCurrentOpenForTarget(accountId, tenantId, namespaceId);

    assertThat(proof).isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
    var current = (CanonicalGameplayAccountInitialOpenProof.CurrentOpen) proof;
    assertThat(current.originalRequestId()).isEqualTo(requestId);
    assertThat(current.exactFields()).isEqualTo(expected.fields());
    verify(repository).readInstalledOpenForTarget(accountId, tenantId, namespaceId);
    verify(repository, org.mockito.Mockito.times(2)).readCurrent(requestId);
    verify(tenantTags).assignmentForInitialBarrierTarget(requestId);
    verify(receiver, org.mockito.Mockito.times(2)).observeForInitialAccountBarrier(requestId);
    verifyNoInteractions(catalog);
    verify(commands).hgetall(any(byte[].class));
    verify(commands, org.mockito.Mockito.never())
        .evalsha(any(), any(), any(byte[][].class), any(byte[][].class));
  }

  @Test
  void missingAmbiguousAndStaleTargetResolutionNeverReachPhysicalReadOrCreate() {
    UUID missingAccount = UUID.randomUUID();
    UUID ambiguousAccount = UUID.randomUUID();
    UUID staleAccount = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.readInstalledOpenForTarget(missingAccount, tenantId, namespaceId))
        .thenThrow(new IllegalStateException("no installed target"));
    when(repository.readInstalledOpenForTarget(ambiguousAccount, tenantId, namespaceId))
        .thenThrow(new IllegalStateException("multiple installed targets"));
    when(repository.readInstalledOpenForTarget(staleAccount, tenantId, namespaceId))
        .thenThrow(new IllegalStateException("NO_ACTIVE source changed"));
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    RedisClient redis = mock(RedisClient.class);
    RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);

    var missing = owner.verifyCurrentOpenForTarget(missingAccount, tenantId, namespaceId);
    var ambiguous = owner.verifyCurrentOpenForTarget(ambiguousAccount, tenantId, namespaceId);
    var stale = owner.verifyCurrentOpenForTarget(staleAccount, tenantId, namespaceId);

    var refused =
        new CanonicalGameplayAccountInitialOpenProof.Refused(
            CanonicalGameplayAccountInitialOpenProof.Reason.SOURCE_CONTROL_UNAVAILABLE);
    assertThat(missing).isEqualTo(refused);
    assertThat(ambiguous).isEqualTo(refused);
    assertThat(stale).isEqualTo(refused);
    verify(repository).readInstalledOpenForTarget(missingAccount, tenantId, namespaceId);
    verify(repository).readInstalledOpenForTarget(ambiguousAccount, tenantId, namespaceId);
    verify(repository).readInstalledOpenForTarget(staleAccount, tenantId, namespaceId);
    verifyNoInteractions(tenantTags, receiver, redis, catalog);
  }

  @Test
  void nonInstalledTargetIsRefusedWithoutFresheningOrRecreatingIt() {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var never =
        snapshot(
            UUID.randomUUID(),
            new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 17L),
            accountId,
            tenantId,
            namespaceId,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED);
    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.readInstalledOpenForTarget(accountId, tenantId, namespaceId)).thenReturn(never);
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    RedisClient redis = mock(RedisClient.class);
    RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);

    var proof = owner.verifyCurrentOpenForTarget(accountId, tenantId, namespaceId);

    assertThat(proof)
        .isEqualTo(
            new CanonicalGameplayAccountInitialOpenProof.Refused(
                CanonicalGameplayAccountInitialOpenProof.Reason.TARGET_NEVER_DISPATCHED));
    verify(repository).readInstalledOpenForTarget(accountId, tenantId, namespaceId);
    verifyNoInteractions(tenantTags, receiver, redis, catalog);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void ambiguousDispatchRetryRequiresExactOpenReadbackBeforeCompletingOriginalReceipt()
      throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    UUID initializerId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    String tag = "g8-" + "b".repeat(32);
    var original = new InitialGameplayLoginAttemptCoordinates(requestId, callerContextId, 17L);
    var pending =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
            initializerId,
            requestId,
            callerContextId,
            17L,
            accountId,
            tenantId,
            namespaceId,
            DIGEST,
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.valueOf(8),
            BigInteger.valueOf(4),
            DIGEST,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            1_100L,
            31_000L,
            null,
            DIGEST);
    var installed =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
            initializerId,
            requestId,
            callerContextId,
            17L,
            accountId,
            tenantId,
            namespaceId,
            DIGEST,
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.valueOf(8),
            BigInteger.valueOf(4),
            DIGEST,
            CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED,
            DIGEST,
            DIGEST,
            DIGEST,
            1_000L,
            1_100L,
            31_000L,
            DIGEST,
            DIGEST);
    var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    when(assignment.isInitialBarrierTarget()).thenReturn(true);
    when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
    when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
    when(assignment.accountId()).thenReturn(accountId);
    when(assignment.tenantId()).thenReturn(tenantId);
    when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);
    when(assignment.tenantGameplayTag()).thenReturn(tag);
    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    when(repository.beginOrRead(any(), any(), any(), any())).thenReturn(pending);
    when(repository.readCurrent(requestId)).thenReturn(pending);
    when(repository.acceptOpenReadback(any(), any())).thenReturn(installed);
    var tenantTags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    when(tenantTags.assignmentForInitialBarrierTarget(requestId)).thenReturn(assignment);
    var observation = initialObservation(pending);
    var receiver = mock(CanonicalGameplayProtectedIncarnationReceiver.class);
    when(receiver.observeForInitialAccountBarrier(requestId)).thenReturn(observation);

    var expected =
        CanonicalGameplayAccountInitialBarrierValue.open(
            accountId, tenantId, namespaceId, tag, initializerId, BigInteger.valueOf(8), DIGEST);
    Map<byte[], byte[]> raw = new HashMap<>();
    expected
        .fields()
        .forEach(
            (field, value) ->
                raw.put(
                    field.getBytes(StandardCharsets.US_ASCII),
                    value.getBytes(StandardCharsets.US_ASCII)));
    RedisClient redis = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisAsyncCommands<byte[], byte[]> commands = mock(RedisAsyncCommands.class);
    when(redis.connect(any(ByteArrayCodec.class))).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.async()).thenReturn(commands);
    doReturn(future("gamesession_coord_app")).when(commands).aclWhoami();
    doReturn(future("hash")).when(commands).type(any(byte[].class));
    doReturn(future(-1L)).when(commands).pttl(any(byte[].class));
    doReturn(future(9L)).when(commands).hlen(any(byte[].class));
    doReturn(future(raw)).when(commands).hgetall(any(byte[].class));
    RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
    var owner =
        new CanonicalGameplayAccountInitialBarrierRedisOwner(
            redis, catalog, repository, tenantTags, receiver);

    var proof =
        owner.initializeOnce(
            original, mock(CanonicalGameplayLoginResult.class), tenantId, namespaceId);

    assertThat(proof).isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
    var current = (CanonicalGameplayAccountInitialOpenProof.CurrentOpen) proof;
    assertThat(current.originalRequestId()).isEqualTo(requestId);
    assertThat(current.exactFields()).isEqualTo(expected.fields());
    verify(repository).acceptOpenReadback(eq(requestId), any());
    verify(repository).readCurrent(requestId);
    verify(receiver, org.mockito.Mockito.times(2)).observeForInitialAccountBarrier(requestId);
    verifyNoInteractions(catalog);
    verify(commands).hgetall(any(byte[].class));
    verify(commands, org.mockito.Mockito.never())
        .evalsha(any(), any(), any(byte[][].class), any(byte[][].class));
  }

  private static CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot
      snapshot(
          UUID initializerId,
          InitialGameplayLoginAttemptCoordinates original,
          UUID accountId,
          UUID tenantId,
          UUID namespaceId,
          CanonicalGameplayAccountInitialBarrierInitializerRepository.State state) {
    return new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot(
        initializerId,
        original.requestId(),
        original.callerContextId(),
        original.expectedAttemptFence(),
        accountId,
        tenantId,
        namespaceId,
        DIGEST,
        "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
        BigInteger.valueOf(8),
        BigInteger.valueOf(4),
        DIGEST,
        state,
        null,
        null,
        null,
        0L,
        0L,
        0L,
        null,
        DIGEST);
  }

  private static CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation
      initialObservation(
          CanonicalGameplayAccountInitialBarrierInitializerRepository.InitializerSnapshot
              snapshot) {
    var observation = mock(CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation.class);
    when(observation.ownerKind())
        .thenReturn(
            CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER);
    when(observation.operationId()).thenReturn(snapshot.initializerId());
    when(observation.requestId()).thenReturn(snapshot.originalRequestId());
    when(observation.originalCallerContextId()).thenReturn(snapshot.originalCallerContextId());
    when(observation.originalAttemptFence()).thenReturn(snapshot.originalAttemptFence());
    when(observation.accountId()).thenReturn(snapshot.accountId());
    when(observation.tenantId()).thenReturn(snapshot.tenantId());
    when(observation.playableStateNamespaceId()).thenReturn(snapshot.playableStateNamespaceId());
    when(observation.sourceControlState()).thenReturn(snapshot.sourceControlState());
    when(observation.sourceControlEpoch()).thenReturn(snapshot.sourceControlEpoch().toString());
    when(observation.inventorySnapshotRevision())
        .thenReturn(snapshot.inventorySnapshotRevision().toString());
    when(observation.accountLoginEvidenceDigest())
        .thenReturn(snapshot.accountLoginEvidenceDigest());
    when(observation.sourceControlDigest()).thenReturn(snapshot.sourceControlDigest());
    when(observation.barrierTargetDigest()).thenReturn(snapshot.targetDigest());
    when(observation.incarnationDigest()).thenReturn(DIGEST);
    when(observation.acceptedAttestationDigest()).thenReturn(DIGEST);
    when(observation.payloadDigest()).thenReturn(DIGEST);
    when(observation.issuedAtEpochMillis()).thenReturn(1_000L);
    when(observation.observedAtEpochMillis()).thenReturn(1_100L);
    when(observation.expiresAtEpochMillis()).thenReturn(31_000L);
    return observation;
  }

  private static <T> RedisFuture<T> future(T value) throws Exception {
    RedisFuture<T> future = mock(RedisFuture.class);
    when(future.get(anyLong(), any(TimeUnit.class))).thenReturn(value);
    return future;
  }
}
