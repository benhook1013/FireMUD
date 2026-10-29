package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionNeverAdmittedGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled parser, repository, codec and callback failure tests; native owner composition is a
 * separate PostgreSQL test.
 */
class CanonicalGameplayAdmissionDecisionReadGrpcServiceTest {
  @Test
  void deniesNonAccountCallerBeforeParsingOrRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    doThrow(Status.PERMISSION_DENIED.asRuntimeException()).when(guard).requireAccountCaller();
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
          .getCanonicalGameplayAdmissionDecision(request, observer);
      codec.verify(
          () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request), never());
    }

    assertFailure(observer, Status.Code.PERMISSION_DENIED, null);
    verify(guard).requireAccountCaller();
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAmbientTransactionBeforeParsingOrRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
            .getCanonicalGameplayAdmissionDecision(request, observer);
        codec.verify(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request), never());
      }
      assertFailure(observer, Status.Code.FAILED_PRECONDITION, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void rejectsSynchronizationWithoutTransactionBeforeParsingOrRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
            .getCanonicalGameplayAdmissionDecision(request, observer);
        codec.verify(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request), never());
      }
      assertFailure(observer, Status.Code.FAILED_PRECONDITION, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void invalidAndOversizedRequestsFailBeforeRepositoryRead() {
    var invalid = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    var oversized =
        GetCanonicalGameplayAdmissionDecisionRequest.newBuilder()
            .mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        100,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(
                                ByteString.copyFrom(
                                    new byte
                                        [CanonicalGameplayAdmissionDecisionReadGrpcCodec
                                                .MAX_REQUEST_WIRE_BYTES
                                            + 1]))
                            .build())
                    .build())
            .build();

    for (var request : new GetCanonicalGameplayAdmissionDecisionRequest[] {invalid, oversized}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
      var observer = observer();
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
          .getCanonicalGameplayAdmissionDecision(request, observer);

      assertFailure(observer, Status.Code.INVALID_ARGUMENT, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository);
    }
  }

  @Test
  void negativeDecisionDeniesCallerBeforeParsingOrOwnerReads() {
    var fixture = validNegativeReadFixture();
    doThrow(Status.PERMISSION_DENIED.asRuntimeException())
        .when(fixture.guard())
        .requireAccountCaller();
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      receiveNegative(fixture);
      codec.verifyNoInteractions();
    }
    assertNegativeFailure(fixture.observer(), Status.Code.PERMISSION_DENIED, null);
    verify(fixture.guard()).requireAccountCaller();
    verifyNoInteractions(fixture.repository(), fixture.closureOwner());
  }

  @Test
  void negativeDecisionRefusesAmbientTransactionAndSynchronizationBeforeParsingOrOwnerReads() {
    for (boolean synchronizationOnly : new boolean[] {false, true}) {
      var fixture = validNegativeReadFixture();
      try {
        if (synchronizationOnly) TransactionSynchronizationManager.initSynchronization();
        else TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
            .isEqualTo(!synchronizationOnly);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive())
            .isEqualTo(synchronizationOnly);
        try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
          receiveNegative(fixture);
          codec.verifyNoInteractions();
        }
        assertNegativeFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
        verify(fixture.guard()).requireAccountCaller();
        verifyNoInteractions(fixture.repository(), fixture.closureOwner());
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
  }

  @Test
  void negativeDecisionSourceAbsenceAndConflictAreDefinitiveFailedPreconditions() {
    for (boolean callbackRead : new boolean[] {false, true}) {
      var fixture = validNegativeReadFixture();
      var conflict =
          new CanonicalGameplayBindingInventoryConflictException("private missing source");
      if (callbackRead) {
        continueNegativeRead(fixture);
        when(fixture
                .repository()
                .readOriginalNewAbandonmentSource(fixture.selector().transitionId()))
            .thenThrow(conflict);
      } else {
        when(fixture
                .closureOwner()
                .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any()))
            .thenThrow(conflict);
      }
      parseAndReceiveNegative(fixture);
      assertNegativeFailure(
          fixture.observer(), Status.Code.FAILED_PRECONDITION, "private missing source");
      verify(fixture.closureOwner())
          .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any());
    }
  }

  @Test
  void negativeDecisionChangedReadbackAbandonmentAndLeaseAreDefinitiveFailedPreconditions() {
    for (int changed = 0; changed < 3; changed++) {
      var fixture = validNegativeReadFixture();
      continueNegativeRead(fixture);
      if (changed == 0) when(fixture.readback().matches(fixture.source())).thenReturn(false);
      else if (changed == 1) when(fixture.source().abandonmentId()).thenReturn(UUID.randomUUID());
      else {
        var otherLease = mock(AccountGameplayAdmissionLeaseEvidence.class);
        when(otherLease.canonicalJson()).thenReturn("different-original-lease");
        when(fixture.source().candidate().leaseEvidence()).thenReturn(otherLease);
      }
      parseAndReceiveNegative(fixture);
      assertNegativeFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
      verify(fixture.repository())
          .readOriginalNewAbandonmentSource(fixture.selector().transitionId());
      verify(fixture.closureOwner())
          .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any());
    }
  }

  @Test
  void negativeDecisionMissingAndWithdrawnQualificationRemainUnavailable() {
    for (boolean withdrawn : new boolean[] {false, true}) {
      var fixture = validNegativeReadFixture();
      var redis = mock(RedisClient.class);
      var catalog = mock(RedisScriptCatalog.class);
      var tags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
      var qualification = mock(CanonicalGameplayOriginalNewClosureQualification.class);
      String binding =
          HexFormat.of()
              .formatHex(fixture.source().candidate().bindingIdentity().bindingRef().bytes());
      if (withdrawn) {
        var lease = mock(CanonicalGameplayOriginalNewClosureQualification.Lease.class);
        when(lease.fence()).thenReturn(1L);
        when(lease.scope())
            .thenReturn(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.FAMILY_SCOPE);
        when(lease.protectedBindingId()).thenReturn(binding);
        doThrow(new IllegalStateException("private withdrawn qualification"))
            .when(lease)
            .requireCurrent();
        when(qualification.acquire(any(), eq(binding))).thenReturn(Optional.of(lease));
      } else when(qualification.acquire(any(), eq(binding))).thenReturn(Optional.empty());
      var owner =
          new CanonicalGameplayOriginalNewCandidateClosureRedisOwner(
              redis, catalog, fixture.repository(), tags, qualification);
      try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
        codec
            .when(
                () ->
                    AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                        fixture.request()))
            .thenReturn(fixture.selector());
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
                fixture.repository(), fixture.guard(), "test", owner)
            .getOriginalNewNeverAdmittedDecision(fixture.request(), fixture.observer());
      }
      assertNegativeFailure(
          fixture.observer(), Status.Code.UNAVAILABLE, "private withdrawn qualification");
      verifyNoInteractions(redis, catalog, tags);
    }
  }

  @Test
  void negativeDecisionWithoutClosureOwnerRemainsUnavailableWithoutRepositoryRead() {
    var fixture = validNegativeReadFixture();
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(
              () ->
                  AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                      fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getOriginalNewNeverAdmittedDecision(fixture.request(), fixture.observer());
    }
    assertNegativeFailure(fixture.observer(), Status.Code.UNAVAILABLE, null);
    verifyNoInteractions(fixture.repository(), fixture.closureOwner());
  }

  @Test
  void negativeDecisionWithoutRetainedClosureIsDefinitiveFailedPrecondition() {
    var fixture = validNegativeReadFixture();
    when(fixture.source().state())
        .thenReturn(CanonicalGameplayOriginalNewAbandonmentSource.State.ABANDONMENT_SELECTED);
    var redis = mock(RedisClient.class);
    var catalog = mock(RedisScriptCatalog.class);
    var tags = mock(CanonicalGameplayTenantGameplayTagRepository.class);
    var qualification = mock(CanonicalGameplayOriginalNewClosureQualification.class);
    var owner =
        new CanonicalGameplayOriginalNewCandidateClosureRedisOwner(
            redis, catalog, fixture.repository(), tags, qualification);
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(
              () ->
                  AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                      fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test", owner)
          .getOriginalNewNeverAdmittedDecision(fixture.request(), fixture.observer());
    }
    assertNegativeFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
    verifyNoInteractions(redis, catalog, tags, qualification);
  }

  @Test
  void negativeDecisionSqlAndRedisOutagesRemainUnavailable() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new CannotCreateTransactionException("private SQL transaction failure"),
          new DataAccessResourceFailureException("private SQL connection failure"),
          new TransientDataAccessResourceException("private transient SQL failure"),
          new org.jooq.exception.DataAccessException(
              "private jOOQ connection failure", new SQLException("connection lost", "08006")),
          new InvalidDataAccessResourceUsageException(
              "private Spring connection failure", new SQLException("connection lost", "08006")),
          new RedisConnectionException("private Redis connection failure"),
          new RedisCommandTimeoutException("private Redis timeout")
        }) {
      var fixture = validNegativeReadFixture();
      when(fixture
              .closureOwner()
              .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any()))
          .thenThrow(failure);
      parseAndReceiveNegative(fixture);
      assertNegativeFailure(fixture.observer(), Status.Code.UNAVAILABLE, failure.getMessage());
    }
  }

  @Test
  void negativeDecisionUnexpectedRepositoryFailuresRemainInternal() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new IllegalStateException("private unexpected state"),
          new IllegalArgumentException("private repository bug"),
          new org.jooq.exception.DataAccessException(
              "private SQL programming failure", new SQLException("syntax", "42000"))
        }) {
      var fixture = validNegativeReadFixture();
      continueNegativeRead(fixture);
      when(fixture.repository().readOriginalNewAbandonmentSource(fixture.selector().transitionId()))
          .thenThrow(failure);
      parseAndReceiveNegative(fixture);
      assertNegativeFailure(fixture.observer(), Status.Code.INTERNAL, failure.getMessage());
    }
  }

  @Test
  void negativeDecisionEncodingFailuresRemainInternalIncludingAvailabilityShapedExceptions() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new IllegalStateException("private negative encoding failure"),
          new DataAccessResourceFailureException("private unexpected encoding dependency")
        }) {
      var fixture = validNegativeReadFixture();
      continueNegativeRead(fixture);
      try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
        codec
            .when(
                () ->
                    AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                        fixture.request()))
            .thenReturn(fixture.selector());
        codec
            .when(
                () ->
                    AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(any(), any()))
            .thenThrow(failure);
        receiveNegative(fixture);
      }
      assertNegativeFailure(fixture.observer(), Status.Code.INTERNAL, failure.getMessage());
    }
  }

  @Test
  void negativeDecisionEncodesInsideClosureContinuationAndPublishesOnlyAfterItReturns() {
    var fixture = validNegativeReadFixture();
    var response = GetOriginalNewNeverAdmittedDecisionResponse.getDefaultInstance();
    doAnswer(
            invocation -> {
              Function<CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback, ?>
                  callback = invocation.getArgument(1);
              Object encoded = callback.apply(fixture.readback());
              assertThat(encoded).isSameAs(response);
              verifyNoInteractions(fixture.observer());
              return encoded;
            })
        .when(fixture.closureOwner())
        .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any());
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(
              () ->
                  AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                      fixture.request()))
          .thenReturn(fixture.selector());
      codec
          .when(
              () -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(any(), any()))
          .thenReturn(response);
      receiveNegative(fixture);
      var evidence =
          ArgumentCaptor.forClass(
              AccountGameplayAdmissionNeverAdmittedGrpcCodec.NegativeDecisionEvidence.class);
      codec.verify(
          () ->
              AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(
                  eq(fixture.selector()), evidence.capture()));
      assertThat(evidence.getValue().transitionId()).isEqualTo(fixture.selector().transitionId());
      assertThat(evidence.getValue().abandonmentId()).isEqualTo(fixture.selector().abandonmentId());
      assertThat(evidence.getValue().originalLeaseEvidence())
          .isSameAs(fixture.selector().originalLeaseEvidence());
      assertThat(evidence.getValue().closureDigest()).isEqualTo("a".repeat(64));
    }
    verify(fixture.observer()).onNext(response);
    verify(fixture.observer()).onCompleted();
    verify(fixture.observer(), never()).onError(any());
  }

  @Test
  void negativeDecisionFinalQualificationWithdrawalDiscardsEncodedResponse() {
    var fixture = validNegativeReadFixture();
    doAnswer(
            invocation -> {
              Function<CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback, ?>
                  callback = invocation.getArgument(1);
              callback.apply(fixture.readback());
              throw new CanonicalGameplayOriginalNewCandidateClosureRedisOwner
                  .UnavailableException();
            })
        .when(fixture.closureOwner())
        .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any());
    var response = GetOriginalNewNeverAdmittedDecisionResponse.getDefaultInstance();
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(
              () ->
                  AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                      fixture.request()))
          .thenReturn(fixture.selector());
      codec
          .when(
              () -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(any(), any()))
          .thenReturn(response);
      receiveNegative(fixture);
      codec.verify(
          () -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(any(), any()));
    }
    assertNegativeFailure(fixture.observer(), Status.Code.UNAVAILABLE, null);
  }

  @Test
  void negativeDecisionOnNextFailureSignalsOneErrorAndCompletionFailureHasNoSecondTerminal() {
    for (boolean completionFailure : new boolean[] {false, true}) {
      var fixture = validNegativeReadFixture();
      continueNegativeRead(fixture);
      var response = GetOriginalNewNeverAdmittedDecisionResponse.getDefaultInstance();
      var failure = new IllegalStateException("private negative observer failure");
      if (completionFailure) doThrow(failure).when(fixture.observer()).onCompleted();
      else doThrow(failure).when(fixture.observer()).onNext(response);
      try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
        codec
            .when(
                () ->
                    AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                        fixture.request()))
            .thenReturn(fixture.selector());
        codec
            .when(
                () ->
                    AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(any(), any()))
            .thenReturn(response);
        if (completionFailure) assertThatThrownBy(() -> receiveNegative(fixture)).isSameAs(failure);
        else receiveNegative(fixture);
      }
      verify(fixture.observer()).onNext(response);
      if (completionFailure) {
        verify(fixture.observer()).onCompleted();
        verify(fixture.observer(), never()).onError(any());
      } else {
        var terminal = ArgumentCaptor.forClass(Throwable.class);
        verify(fixture.observer()).onError(terminal.capture());
        assertThat(Status.fromThrowable(terminal.getValue()).getCode())
            .isEqualTo(Status.Code.INTERNAL);
        assertThat(terminal.getValue()).hasMessageNotContaining(failure.getMessage());
        verify(fixture.observer(), never()).onCompleted();
      }
    }
  }

  @Test
  void negativeDecisionRejectsNullAndOversizedRequestsBeforeParsingOrOwnerReads() {
    var oversized =
        GetOriginalNewNeverAdmittedDecisionRequest.newBuilder()
            .mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        100,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(
                                ByteString.copyFrom(
                                    new byte
                                        [AccountGameplayAdmissionNeverAdmittedGrpcCodec
                                                .MAX_WIRE_BYTES
                                            + 1]))
                            .build())
                    .build())
            .build();

    for (var request : new GetOriginalNewNeverAdmittedDecisionRequest[] {null, oversized}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
      var closureOwner = mock(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.class);
      var observer = negativeObserver();
      try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
                repository, guard, "test", closureOwner)
            .getOriginalNewNeverAdmittedDecision(request, observer);
        codec.verify(
            () -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(request),
            never());
      }

      assertNegativeFailure(observer, Status.Code.INVALID_ARGUMENT, null);
      verify(guard).requireAccountCaller();
      verifyNoInteractions(repository, closureOwner);
    }
  }

  @Test
  void unexpectedNegativeDecisionParserFailureIsSanitizedInternalBeforeOwnerReads() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var closureOwner = mock(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.class);
    var observer = negativeObserver();
    var request = GetOriginalNewNeverAdmittedDecisionRequest.getDefaultInstance();
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(() -> AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(request))
          .thenThrow(new IllegalStateException("private negative parser details"));
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test", closureOwner)
          .getOriginalNewNeverAdmittedDecision(request, observer);
    }

    assertNegativeFailure(observer, Status.Code.INTERNAL, "private negative parser details");
    verify(guard).requireAccountCaller();
    verifyNoInteractions(repository, closureOwner);
  }

  @Test
  void rejectsMismatchedLeaseKindNamespaceAndCallerBeforeRepositoryRead() {
    var cases =
        new Runnable[] {
          () -> {
            var fixture = validReadFixture();
            when(fixture.selector().expectedLeaseEvidence().leaseKind())
                .thenReturn(
                    net.firedevops.firemud.common.account.admission
                        .AccountGameplayAdmissionLeaseEvidence.LeaseKind.RESUME);
            assertDeniedLease(fixture);
          },
          () -> {
            var fixture = validReadFixture();
            when(fixture.selector().expectedLeaseEvidence().carrier())
                .thenReturn(Map.of("targetNamespace", "other", "callerWorkload", accountPeer()));
            assertDeniedLease(fixture);
          },
          () -> {
            var fixture = validReadFixture();
            when(fixture.selector().expectedLeaseEvidence().carrier())
                .thenReturn(Map.of("targetNamespace", "test", "callerWorkload", "other"));
            assertDeniedLease(fixture);
          }
        };

    for (var testCase : cases) testCase.run();
  }

  @Test
  void unexpectedRepositoryFailureTerminatesOnceWithSanitizedInternal() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new IllegalArgumentException("private repository programming details"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(
        fixture.observer(), Status.Code.INTERNAL, "private repository programming details");
    verify(fixture.repository())
        .readAdmissionDecision(
            fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence());
  }

  @Test
  void repositoryConflictRemainsFailedPrecondition() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new CanonicalGameplayBindingInventoryConflictException("decision changed"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
  }

  @Test
  void repositoryIllegalStateConflictRemainsFailedPrecondition() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new IllegalStateException("stale decision"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.FAILED_PRECONDITION, null);
  }

  @Test
  void databaseOutageRemainsUnavailable() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.UNAVAILABLE, null);
  }

  @Test
  void unexpectedResponseEncodingFailureTerminatesOnceWithInternalWithoutAnswer() {
    var fixture = validReadFixture();
    when(fixture
            .repository()
            .readAdmissionDecision(
                fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence()))
        .thenReturn(fixture.decision());
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
                      any(), any(), any(), any(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("private response codec details"));
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.INTERNAL, "private response codec details");
    verify(fixture.repository())
        .readAdmissionDecision(
            fixture.decision().bindingDecisionId(), fixture.decision().leaseEvidence());
  }

  @Test
  void unexpectedParserFailureTerminatesOnceWithSanitizedInternalBeforeRepositoryRead() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(() -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request))
          .thenThrow(new IllegalStateException("private parser details"));
      new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
          .getCanonicalGameplayAdmissionDecision(request, observer);
    }
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(failure.getValue()).hasMessageNotContaining("private parser details");
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
    verifyNoInteractions(repository);
  }

  @Test
  void onNextFailureSignalsOneErrorButCompletionFailureEscapesWithoutSecondTerminal() {
    for (boolean completionFailure : new boolean[] {false, true}) {
      var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
      var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
      var observer = observer();
      var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
      var source = CanonicalGameplayAdmissionAbortOwnerTest.source(false);
      var decision = source.originalDecision();
      var lease = decision.leaseEvidence();
      when(lease.carrier())
          .thenReturn(
              Map.of(
                  "targetNamespace",
                  "test",
                  "callerWorkload",
                  "spiffe://firemud/ns/test/sa/game-session-service"));
      var selector = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence.class);
      when(selector.expectedLeaseEvidence()).thenReturn(lease);
      var decisionId = decision.bindingDecisionId();
      when(selector.bindingDecisionId()).thenReturn(decisionId);
      when(repository.readAdmissionDecision(decision.bindingDecisionId(), lease))
          .thenReturn(decision);
      var response = GetCanonicalGameplayAdmissionDecisionResponse.getDefaultInstance();
      var callbackFailure = new IllegalStateException("observer callback failed");
      if (completionFailure) doThrow(callbackFailure).when(observer).onCompleted();
      else doThrow(callbackFailure).when(observer).onNext(response);
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        codec
            .when(() -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request))
            .thenReturn(selector);
        codec
            .when(
                () ->
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
                        any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(response);
        Runnable receive =
            () ->
                new CanonicalGameplayAdmissionDecisionReadGrpcService(repository, guard, "test")
                    .getCanonicalGameplayAdmissionDecision(request, observer);
        if (completionFailure) assertThatThrownBy(receive::run).isSameAs(callbackFailure);
        else receive.run();
      }
      verify(observer).onNext(response);
      if (completionFailure) {
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());
      } else {
        verify(observer).onError(any());
        verify(observer, never()).onCompleted();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer() {
    return mock(StreamObserver.class);
  }

  private static ReadFixture validReadFixture() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var observer = observer();
    var request = GetCanonicalGameplayAdmissionDecisionRequest.getDefaultInstance();
    var decision = CanonicalGameplayAdmissionAbortOwnerTest.source(false).originalDecision();
    var lease = decision.leaseEvidence();
    when(lease.carrier())
        .thenReturn(
            Map.of(
                "targetNamespace",
                "test",
                "callerWorkload",
                "spiffe://firemud/ns/test/sa/game-session-service"));
    var selector = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence.class);
    when(selector.expectedLeaseEvidence()).thenReturn(lease);
    var decisionId = decision.bindingDecisionId();
    when(selector.bindingDecisionId()).thenReturn(decisionId);
    return new ReadFixture(repository, guard, observer, request, selector, decision);
  }

  private static void assertDeniedLease(ReadFixture fixture) {
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(fixture.request()))
          .thenReturn(fixture.selector());
      new CanonicalGameplayAdmissionDecisionReadGrpcService(
              fixture.repository(), fixture.guard(), "test")
          .getCanonicalGameplayAdmissionDecision(fixture.request(), fixture.observer());
    }
    assertFailure(fixture.observer(), Status.Code.PERMISSION_DENIED, null);
    verify(fixture.guard()).requireAccountCaller();
    verifyNoInteractions(fixture.repository());
  }

  private static String accountPeer() {
    return "spiffe://firemud/ns/test/sa/game-session-service";
  }

  @SuppressWarnings("unchecked")
  private static StreamObserver<GetOriginalNewNeverAdmittedDecisionResponse> negativeObserver() {
    return mock(StreamObserver.class);
  }

  private static NegativeReadFixture validNegativeReadFixture() {
    var repository = mock(CanonicalGameplayBindingInventoryRepository.class);
    var guard = mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    var closureOwner = mock(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.class);
    var original = CanonicalGameplayAdmissionAbortOwnerTest.source(false);
    var decision = original.originalDecision();
    var lease = decision.leaseEvidence();
    when(lease.carrier())
        .thenReturn(Map.of("targetNamespace", "test", "callerWorkload", accountPeer()));
    when(lease.canonicalJson()).thenReturn("exact-original-lease");
    var candidate = mock(CanonicalGameplayOriginalNewCandidate.class);
    when(candidate.leaseEvidence()).thenReturn(lease);
    var bindingIdentity = decision.candidate();
    var targetEvidence = decision.targetEvidence();
    when(candidate.bindingIdentity()).thenReturn(bindingIdentity);
    when(candidate.targetEvidence()).thenReturn(targetEvidence);
    when(candidate.canonicalBytes()).thenReturn(new byte[] {1, 2, 3});
    var transition = original.transition();
    when(candidate.transition())
        .thenReturn(
            new CanonicalGameplayOriginalNewCandidate.TransitionSource(
                transition.transitionId(),
                transition.bindingRef(),
                transition.bindingGeneration(),
                transition.issuerReservationId(),
                transition.reservationFence(),
                transition.candidateAccountIndexFence()));
    var source = mock(CanonicalGameplayOriginalNewAbandonmentSource.class);
    UUID abandonment = UUID.fromString("abababab-abab-4bab-8bab-abababababab");
    when(source.abandonmentId()).thenReturn(abandonment);
    when(source.candidate()).thenReturn(candidate);
    when(source.state())
        .thenReturn(CanonicalGameplayOriginalNewAbandonmentSource.State.NEVER_ADMITTED_CLOSED);
    when(repository.readOriginalNewAbandonmentSource(transition.transitionId())).thenReturn(source);
    var readback = mock(CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback.class);
    when(readback.matches(source)).thenReturn(true);
    when(readback.digest()).thenReturn("sha256:" + "a".repeat(64));
    var selector =
        new AccountGameplayAdmissionNeverAdmittedGrpcCodec.DecisionRequest(
            lease, transition.transitionId(), abandonment);
    return new NegativeReadFixture(
        repository,
        guard,
        closureOwner,
        negativeObserver(),
        GetOriginalNewNeverAdmittedDecisionRequest.getDefaultInstance(),
        selector,
        source,
        readback);
  }

  private static void continueNegativeRead(NegativeReadFixture fixture) {
    doAnswer(
            invocation -> {
              Function<CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback, ?>
                  callback = invocation.getArgument(1);
              return callback.apply(fixture.readback());
            })
        .when(fixture.closureOwner())
        .withCurrentClosedCandidate(eq(fixture.selector().transitionId()), any());
  }

  private static void parseAndReceiveNegative(NegativeReadFixture fixture) {
    try (var codec = mockStatic(AccountGameplayAdmissionNeverAdmittedGrpcCodec.class)) {
      codec
          .when(
              () ->
                  AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(
                      fixture.request()))
          .thenReturn(fixture.selector());
      receiveNegative(fixture);
    }
  }

  private static void receiveNegative(NegativeReadFixture fixture) {
    new CanonicalGameplayAdmissionDecisionReadGrpcService(
            fixture.repository(), fixture.guard(), "test", fixture.closureOwner())
        .getOriginalNewNeverAdmittedDecision(fixture.request(), fixture.observer());
  }

  private static void assertNegativeFailure(
      StreamObserver<GetOriginalNewNeverAdmittedDecisionResponse> observer,
      Status.Code expectedCode,
      String privateDetail) {
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(expectedCode);
    if (privateDetail != null) {
      assertThat(failure.getValue()).hasMessageNotContaining(privateDetail);
    }
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }

  private static void assertFailure(
      StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer,
      Status.Code expectedCode,
      String privateDetail) {
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(expectedCode);
    if (privateDetail != null) {
      assertThat(failure.getValue()).hasMessageNotContaining(privateDetail);
    }
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }

  private record ReadFixture(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard guard,
      StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer,
      GetCanonicalGameplayAdmissionDecisionRequest request,
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence selector,
      CanonicalGameplayAdmissionDecision decision) {}

  private record NegativeReadFixture(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard guard,
      CanonicalGameplayOriginalNewCandidateClosureRedisOwner closureOwner,
      StreamObserver<GetOriginalNewNeverAdmittedDecisionResponse> observer,
      GetOriginalNewNeverAdmittedDecisionRequest request,
      AccountGameplayAdmissionNeverAdmittedGrpcCodec.DecisionRequest selector,
      CanonicalGameplayOriginalNewAbandonmentSource source,
      CanonicalGameplayOriginalNewCandidateClosureRedisOwner.Readback readback) {}
}
