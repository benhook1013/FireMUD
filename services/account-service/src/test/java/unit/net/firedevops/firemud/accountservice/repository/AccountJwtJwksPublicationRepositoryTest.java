package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.MountObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PrepublicationIntent;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountJwtJwksPublicationRepositoryTest {
  private static final Binding BINDING =
      new Binding(
          "prod",
          "prod-cluster-1",
          "firemud-prod",
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final TrustFence TRUST =
      new TrustFence(
          "11111111-1111-4111-8111-111111111111",
          "22222222-2222-4222-8222-222222222222",
          "a".repeat(64),
          "trust-r1");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String JWKS = "{\"keys\":[]}";
  private static final String MARKER = "{\"phase\":\"PREPUBLISHED\"}";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void currentReadsRemainMandatoryAccountTransactions() throws Exception {
    assertMandatory("readCurrentIntent", Binding.class, TrustFence.class);
    assertMandatory("readCurrentPublication", Binding.class, TrustFence.class);
    assertMandatory(
        "recordPrepublicationIntent",
        Binding.class,
        TrustFence.class,
        net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository
            .GenerationResult.class,
        net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository
            .GenerationRequest.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class);
  }

  @Test
  void publicationRepositoryRejectsCallsOutsideAnOwningAccountTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository desired =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtJwksPublicationRepository repository =
        new AccountJwtJwksPublicationRepository(dsl, desired);

    assertThatThrownBy(() -> repository.readCurrentIntent(BINDING, TRUST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owning Account transaction");

    verifyNoInteractions(dsl, desired);
  }

  @Test
  void snapshotAndPublicDataDigestsBindEveryObservedPublicByte() {
    String first =
        AccountJwtJwksPublicationRepository.snapshotDigest(
            Map.of("jwks.json", JWKS, "other.txt", "preserve"));
    String sameDifferentOrder =
        AccountJwtJwksPublicationRepository.snapshotDigest(
            Map.of("other.txt", "preserve", "jwks.json", JWKS));
    String changed =
        AccountJwtJwksPublicationRepository.snapshotDigest(
            Map.of("jwks.json", JWKS, "other.txt", "changed"));

    assertThat(first).hasSize(64).isEqualTo(sameDifferentOrder).isNotEqualTo(changed);
    assertThat(AccountJwtJwksPublicationRepository.publicDataDigest(JWKS, MARKER))
        .isNotEqualTo(AccountJwtJwksPublicationRepository.publicDataDigest(JWKS, MARKER + " "));
  }

  @Test
  void immutableEvidenceDigestsBindIntentReceiptAndMountedCorrespondence() {
    PrepublicationIntent intent = intent();
    PublicationReceipt receipt = PublicationReceipt.create(intent, "42");
    MountObservation observation = MountObservation.create(intent, receipt);

    assertThat(intent.intentDigest()).isEqualTo(PrepublicationIntent.digest(intent));
    assertThat(receipt.receiptDigest()).isEqualTo(PublicationReceipt.digest(receipt));
    assertThat(observation.observationDigest()).isEqualTo(MountObservation.digest(observation));
    assertThat(observation.publicationReceiptDigest()).isEqualTo(receipt.receiptDigest());
    assertThat(intent.jwksJson()).doesNotContain("privateKey", "\"d\"");
  }

  @Test
  void immutableEvidenceRejectsChangedDigestAndNonAdvancingRemoteReadback() {
    PrepublicationIntent intent = intent();

    assertThatThrownBy(
            () ->
                new PrepublicationIntent(
                    intent.operationId(),
                    intent.binding(),
                    intent.operationDigest(),
                    intent.generationRequestDigest(),
                    intent.generationReceiptDigest(),
                    intent.desiredStateVersion(),
                    intent.trustFence(),
                    intent.apiBindingDigest(),
                    intent.apiConfigRevision(),
                    intent.configMapName(),
                    intent.configMapUid(),
                    intent.expectedResourceVersion(),
                    intent.expectedSnapshotDigest(),
                    intent.targetGeneration(),
                    intent.targetKid(),
                    intent.publicKeyFingerprint(),
                    intent.expectedDurableActive(),
                    intent.expectedPublishedActive(),
                    intent.jwksJson(),
                    intent.generationMarkerJson(),
                    intent.publicDataDigest(),
                    intent.generationMarkerDigest(),
                    "f".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intent digest");

    assertThatThrownBy(
            () ->
                new PrepublicationIntent(
                    intent.operationId(),
                    intent.binding(),
                    intent.operationDigest(),
                    intent.generationRequestDigest(),
                    intent.generationReceiptDigest(),
                    intent.desiredStateVersion(),
                    intent.trustFence(),
                    intent.apiBindingDigest(),
                    intent.apiConfigRevision(),
                    intent.configMapName(),
                    intent.configMapUid(),
                    intent.expectedResourceVersion(),
                    intent.expectedSnapshotDigest(),
                    intent.targetGeneration(),
                    intent.targetKid(),
                    intent.publicKeyFingerprint(),
                    intent.expectedDurableActive(),
                    intent.expectedPublishedActive(),
                    intent.jwksJson(),
                    intent.generationMarkerJson(),
                    intent.publicDataDigest(),
                    intent.generationMarkerDigest(),
                    "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intent digest");

    assertThatThrownBy(() -> PublicationReceipt.create(intent, intent.expectedResourceVersion()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resourceVersion did not advance");
  }

  private static PrepublicationIntent intent() {
    return PrepublicationIntent.create(
        OPERATION_ID,
        BINDING,
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        2L,
        TRUST,
        "e".repeat(64),
        "api-r1",
        "jwt-jwks",
        "44444444-4444-4444-8444-444444444444",
        "41",
        "f".repeat(64),
        "1",
        "jwt-1-test",
        "9".repeat(64),
        Optional.<ActiveSigner>empty(),
        Optional.<ActiveSigner>empty(),
        JWKS,
        MARKER,
        AccountJwtJwksPublicationRepository.publicDataDigest(JWKS, MARKER),
        sha256(MARKER));
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void assertMandatory(String method, Class<?>... parameterTypes) throws Exception {
    Transactional transactional =
        AccountJwtJwksPublicationRepository.class
            .getMethod(method, parameterTypes)
            .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
