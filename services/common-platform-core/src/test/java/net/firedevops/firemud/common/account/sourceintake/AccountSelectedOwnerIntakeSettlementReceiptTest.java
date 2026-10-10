package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import org.junit.jupiter.api.Test;

/** Synthetic upstream value tests; mocks make no Account or Automation authentication claim. */
class AccountSelectedOwnerIntakeSettlementReceiptTest {
  private static final byte[] BINDING_BYTES = {1, 2, 3, 4};
  private static final String BINDING_DIGEST = DraftAuthorizationFenceBinding.digest(BINDING_BYTES);
  private static final byte[] OWNER_RECEIPT_BYTES = {5, 6, 7};
  private static final String OWNER_RECEIPT_DIGEST =
      DraftAuthorizationFenceBinding.digest(OWNER_RECEIPT_BYTES);

  @Test
  void embedsCompleteImmutableEvidenceAndIgnoresOnlyFreshReadCorrelation() {
    var binding = binding();
    var ownerReceipt = ownerReceipt(OWNER_RECEIPT_BYTES, OWNER_RECEIPT_DIGEST);
    var firstEvidence = evidence(binding, ownerReceipt);
    var first = AccountSelectedOwnerIntakeSettlementReceipt.create(firstEvidence);

    var reader = new DraftAuthorizationFenceBinding.FrameReader(first.canonicalBytes());
    reader.expect(AccountSelectedOwnerIntakeSettlementReceipt.DOMAIN);
    reader.expect("1");
    assertThat(reader.bytes()).containsExactly(BINDING_BYTES);
    reader.expect(BINDING_DIGEST);
    reader.expect("1");
    reader.expect("test");
    String firstReadRequestId = reader.text();
    reader.expect("spiffe://firemud/ns/test/sa/account-service");
    reader.expect("AUTOMATION_INTAKE_TERMINAL_READ");
    assertThat(reader.bytes()).containsExactly(BINDING_BYTES);
    reader.expect(BINDING_DIGEST);
    assertThat(reader.bytes()).containsExactly(OWNER_RECEIPT_BYTES);
    reader.expect(OWNER_RECEIPT_DIGEST);
    reader.requireEnd();

    var retryEvidence = evidence(binding, ownerReceipt);
    var retried = AccountSelectedOwnerIntakeSettlementReceipt.create(retryEvidence);
    assertThat(retryEvidence.request().readRequestId().toString()).isNotEqualTo(firstReadRequestId);
    assertThat(first.sameImmutableOwnerReceipt(retryEvidence)).isTrue();
    assertThat(retried.canonicalBytes()).isNotEqualTo(first.canonicalBytes());

    var changedOwnerReceipt =
        ownerReceipt(
            new byte[] {8, 9, 10}, DraftAuthorizationFenceBinding.digest(new byte[] {8, 9, 10}));
    assertThat(first.sameImmutableOwnerReceipt(evidence(binding, changedOwnerReceipt))).isFalse();

    byte[] exposed = first.canonicalBytes();
    exposed[0] ^= 0x7f;
    assertThat(first.canonicalBytes()).isNotEqualTo(exposed);
    assertThatThrownBy(() -> AccountSelectedOwnerIntakeSettlementReceipt.fromStored(exposed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsAutomationBoundsAndFramesWhileEntityUsesItsOwnTypedTerminal() {
    assertThat(AccountSelectedOwnerIntakeSettlementReceipt.AUTOMATION_MAX_BYTES)
        .isEqualTo(92356608);
    assertThat(AccountSelectedOwnerIntakeSettlementReceipt.ENTITY_MAX_BYTES).isEqualTo(117506048);
    var binding = binding();
    when(binding.owner()).thenReturn(Owner.ENTITY_MANAGEMENT);
    when(binding.schema()).thenReturn("account-entity-intake-authorization/v1");
    when(binding.purpose()).thenReturn("ENTITY_INTAKE_RETENTION");
    var owner = mock(EntityEmptySelectedSourceIntakeReceipt.class);
    when(owner.targetNamespace()).thenReturn("test");
    when(owner.authorizationBindingBytes()).thenReturn(BINDING_BYTES.clone());
    when(owner.authorizationBindingDigest()).thenReturn(BINDING_DIGEST);
    when(owner.outcome()).thenReturn("COMMITTED_EMPTY");
    when(owner.canonicalBytes()).thenReturn(OWNER_RECEIPT_BYTES.clone());
    when(owner.receiptDigest()).thenReturn(OWNER_RECEIPT_DIGEST);
    var first =
        new EntitySelectedSourceIntakeTerminalReadEvidence(
            EntitySelectedSourceIntakeTerminalReadEvidence.Request.create("test", binding), owner);
    var receipt = AccountSelectedOwnerIntakeSettlementReceipt.create(first);
    var reader = new DraftAuthorizationFenceBinding.FrameReader(receipt.canonicalBytes());
    reader.expect(AccountSelectedOwnerIntakeSettlementReceipt.DOMAIN);
    reader.expect("1");
    assertThat(reader.bytes()).isEqualTo(BINDING_BYTES);
    reader.expect(BINDING_DIGEST);
    reader.expect("1");
    reader.expect("test");
    reader.expect(first.request().readRequestId().toString());
    reader.expect("spiffe://firemud/ns/test/sa/account-service");
    reader.expect("ENTITY_INTAKE_TERMINAL_READ");
    assertThat(reader.bytes()).isEqualTo(BINDING_BYTES);
    reader.expect(BINDING_DIGEST);
    assertThat(reader.bytes()).isEqualTo(OWNER_RECEIPT_BYTES);
    reader.expect(OWNER_RECEIPT_DIGEST);
    reader.requireEnd();
    var retry =
        new EntitySelectedSourceIntakeTerminalReadEvidence(
            EntitySelectedSourceIntakeTerminalReadEvidence.Request.create("test", binding), owner);
    assertThat(receipt.sameImmutableOwnerReceipt(retry)).isTrue();
    assertThat(receipt.entityTerminalEvidence()).isSameAs(first);
    assertThatThrownBy(receipt::terminalEvidence).isInstanceOf(IllegalStateException.class);
    var changedOwner = mock(EntityEmptySelectedSourceIntakeReceipt.class);
    when(changedOwner.targetNamespace()).thenReturn("test");
    when(changedOwner.authorizationBindingBytes()).thenReturn(BINDING_BYTES.clone());
    when(changedOwner.outcome()).thenReturn("COMMITTED_EMPTY");
    when(changedOwner.canonicalBytes()).thenReturn(new byte[] {9});
    when(changedOwner.receiptDigest())
        .thenReturn(DraftAuthorizationFenceBinding.digest(new byte[] {9}));
    assertThat(
            receipt.sameImmutableOwnerReceipt(
                new EntitySelectedSourceIntakeTerminalReadEvidence(retry.request(), changedOwner)))
        .isFalse();
  }

  private static SelectedOwnerIntakeAuthorizationBinding binding() {
    var binding = mock(SelectedOwnerIntakeAuthorizationBinding.class);
    when(binding.canonicalBytes()).thenReturn(BINDING_BYTES.clone());
    when(binding.digest()).thenReturn(BINDING_DIGEST);
    when(binding.owner()).thenReturn(Owner.AUTOMATION_SCRIPTING);
    when(binding.schema()).thenReturn("account-automation-intake-authorization/v1");
    when(binding.purpose()).thenReturn("AUTOMATION_INTAKE_RETENTION");
    when(binding.targetNamespace()).thenReturn("test");
    when(binding.operationId()).thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(binding.fenceId()).thenReturn(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    when(binding.intakeRequestId())
        .thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    return binding;
  }

  private static AutomationEmptySelectedSourceIntakeReceipt ownerReceipt(
      byte[] bytes, String digest) {
    var receipt = mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    when(receipt.targetNamespace()).thenReturn("test");
    when(receipt.authorizationBindingBytes()).thenReturn(BINDING_BYTES.clone());
    when(receipt.authorizationBindingDigest()).thenReturn(BINDING_DIGEST);
    when(receipt.outcome()).thenReturn("COMMITTED_EMPTY");
    when(receipt.canonicalBytes()).thenReturn(bytes.clone());
    when(receipt.receiptDigest()).thenReturn(digest);
    return receipt;
  }

  private static AutomationSelectedSourceIntakeTerminalReadEvidence evidence(
      SelectedOwnerIntakeAuthorizationBinding binding,
      AutomationEmptySelectedSourceIntakeReceipt receipt) {
    return new AutomationSelectedSourceIntakeTerminalReadEvidence(
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create("test", binding),
        receipt);
  }
}
