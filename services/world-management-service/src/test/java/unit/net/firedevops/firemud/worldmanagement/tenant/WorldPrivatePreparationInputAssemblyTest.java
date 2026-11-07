package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldPrivatePreparationInputAssemblyTest {
  @TempDir Path directory;

  @Test
  void twoActualPeerReadsRetainWholeSourceWithDistinctCorrelationsAndNoAdmission()
      throws Exception {
    var facts = WorldPrivatePreparationSourceClientMtlsTest.snapshot();
    try (var f =
        new WorldPrivatePreparationSourceClientMtlsTest.Harness(
            directory,
            "gs",
            WorldPrivatePreparationSourceClientMtlsTest.CLOCK,
            (request, index) -> PrivateWorldPreparationReadGrpcCodec.toResponse(request, facts))) {
      var inputs =
          new WorldPrivatePreparationInputAssembly(
                  f.client, WorldPrivatePreparationSourceClientMtlsTest.CLOCK)
              .assemble(facts.scope(), facts.preparation().requestId());
      assertThat(inputs.snapshot()).isEqualTo(facts);
      assertThat(inputs.firstRead().request().readRequestId())
          .isNotEqualTo(inputs.committedReadback().request().readRequestId());
      assertThat(f.requests).hasSize(2);
      assertThat(inputs.admissionAllowed()).isFalse();
    }
  }

  @Test
  void changedTargetReceiptBetweenActualReadsRejectsAssembly() throws Exception {
    var facts = WorldPrivatePreparationSourceClientMtlsTest.snapshot();
    try (var f =
        new WorldPrivatePreparationSourceClientMtlsTest.Harness(
            directory,
            "gs",
            Clock.fixed(
                WorldPrivatePreparationSourceClientMtlsTest.NOW.plusSeconds(2), ZoneOffset.UTC),
            (request, index) ->
                PrivateWorldPreparationReadGrpcCodec.toResponse(
                    request,
                    index == 1
                        ? facts
                        : WorldPrivatePreparationSourceClientMtlsTest.changedReceipt(facts)))) {
      assertThatThrownBy(
              () ->
                  new WorldPrivatePreparationInputAssembly(
                          f.client,
                          Clock.fixed(
                              WorldPrivatePreparationSourceClientMtlsTest.NOW.plusSeconds(2),
                              ZoneOffset.UTC))
                      .assemble(facts.scope(), facts.preparation().requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("changed between committed reads");
      assertThat(f.requests).hasSize(2);
    }
  }

  @Test
  void wrongExpectedOriginalAndExpiredSourceNeverProduceInputs() throws Exception {
    var facts = WorldPrivatePreparationSourceClientMtlsTest.snapshot();
    try (var f =
        new WorldPrivatePreparationSourceClientMtlsTest.Harness(
            directory.resolve("original"),
            "gs",
            WorldPrivatePreparationSourceClientMtlsTest.CLOCK,
            (request, index) -> PrivateWorldPreparationReadGrpcCodec.toResponse(request, facts))) {
      assertThatThrownBy(
              () ->
                  new WorldPrivatePreparationInputAssembly(
                          f.client, WorldPrivatePreparationSourceClientMtlsTest.CLOCK)
                      .assemble(facts.scope(), UUID.randomUUID()))
          .isInstanceOf(RuntimeException.class);
    }
    Clock expired = Clock.fixed(facts.preparation().forkExpiresAt(), ZoneOffset.UTC);
    try (var f =
        new WorldPrivatePreparationSourceClientMtlsTest.Harness(
            directory.resolve("expired"),
            "gs",
            expired,
            (request, index) -> PrivateWorldPreparationReadGrpcCodec.toResponse(request, facts))) {
      assertThatThrownBy(
              () ->
                  new WorldPrivatePreparationInputAssembly(f.client, expired)
                      .assemble(facts.scope(), facts.preparation().requestId()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
