package net.firedevops.firemud.common.account.sourceintake;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;

/** Immutable Account record of one exact Automation {@code COMMITTED_EMPTY} terminal read. */
public final class AccountSelectedOwnerIntakeSettlementReceipt {
  public static final String DOMAIN = "account-selected-owner-intake-settlement/v1";
  public static final int MAX_BYTES =
      2 * SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          + AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES
          + 64 * 1024;

  private final SelectedOwnerIntakeAuthorizationBinding authorizationBinding;
  private final AutomationSelectedSourceIntakeTerminalReadEvidence terminalEvidence;
  private final byte[] canonicalBytes;
  private final String digest;

  private AccountSelectedOwnerIntakeSettlementReceipt(
      SelectedOwnerIntakeAuthorizationBinding authorizationBinding,
      AutomationSelectedSourceIntakeTerminalReadEvidence terminalEvidence) {
    this.authorizationBinding = Objects.requireNonNull(authorizationBinding);
    this.terminalEvidence = Objects.requireNonNull(terminalEvidence);
    if (!Arrays.equals(
            authorizationBinding.canonicalBytes(),
            terminalEvidence.request().binding().canonicalBytes())
        || !Arrays.equals(
            authorizationBinding.canonicalBytes(),
            terminalEvidence.receipt().authorizationBindingBytes())
        || !authorizationBinding
            .digest()
            .equals(terminalEvidence.receipt().authorizationBindingDigest())
        || !"COMMITTED_EMPTY".equals(terminalEvidence.receipt().outcome())) {
      throw new IllegalArgumentException(
          "Automation terminal differs from the complete original Account authorization");
    }
    this.canonicalBytes = encode();
    if (canonicalBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException(
          "Account selected-owner settlement exceeds its size limit");
    }
    this.digest = DraftAuthorizationFenceBinding.digest(canonicalBytes);
  }

  public static AccountSelectedOwnerIntakeSettlementReceipt create(
      AutomationSelectedSourceIntakeTerminalReadEvidence terminalEvidence) {
    Objects.requireNonNull(terminalEvidence, "Automation terminal evidence is required");
    return new AccountSelectedOwnerIntakeSettlementReceipt(
        terminalEvidence.request().binding(), terminalEvidence);
  }

  /** Strictly decodes the complete immutable Account binding and Automation terminal evidence. */
  public static AccountSelectedOwnerIntakeSettlementReceipt fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException(
          "Stored Account selected-owner settlement size is invalid");
    }
    byte[] stored = bytes.clone();
    var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
    reader.expect(DOMAIN);
    if (!"1".equals(reader.text())) {
      throw new IllegalArgumentException("Unsupported Account selected-owner settlement schema");
    }
    byte[] bindingBytes =
        bounded(reader.bytes(), SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES);
    String bindingDigest = reader.text();
    var binding = SelectedOwnerIntakeAuthorizationBinding.fromStored(bindingBytes);
    if (!binding.digest().equals(bindingDigest)) {
      throw new IllegalArgumentException("Retained Account authorization digest differs");
    }

    int terminalSchemaVersion = parseSchemaVersion(reader.text());
    String namespace = reader.text();
    String readRequestIdText = reader.text();
    DraftAuthorizationFenceBinding.canonicalUuid(readRequestIdText);
    UUID readRequestId = UUID.fromString(readRequestIdText);
    String intendedReader = reader.text();
    String purpose = reader.text();
    byte[] terminalBindingBytes =
        bounded(reader.bytes(), SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES);
    String terminalBindingDigest = reader.text();
    var terminalBinding = SelectedOwnerIntakeAuthorizationBinding.fromStored(terminalBindingBytes);
    if (!binding.digest().equals(terminalBindingDigest)
        || !Arrays.equals(bindingBytes, terminalBindingBytes)) {
      throw new IllegalArgumentException("Automation terminal request changed the Account binding");
    }
    var request =
        new AutomationSelectedSourceIntakeTerminalReadEvidence.Request(
            terminalSchemaVersion, namespace, readRequestId, terminalBinding);
    if (!intendedReader.equals(request.intendedReader())
        || !purpose.equals(request.terminalReadPurpose())) {
      throw new IllegalArgumentException("Automation terminal reader or purpose differs");
    }

    byte[] ownerReceiptBytes =
        bounded(reader.bytes(), AutomationEmptySelectedSourceIntakeReceipt.MAX_BYTES);
    String ownerReceiptDigest = reader.text();
    reader.requireEnd();
    var ownerReceipt = AutomationEmptySelectedSourceIntakeReceipt.fromStored(ownerReceiptBytes);
    if (!ownerReceipt.receiptDigest().equals(ownerReceiptDigest)) {
      throw new IllegalArgumentException("Automation terminal receipt digest differs");
    }
    var evidence = new AutomationSelectedSourceIntakeTerminalReadEvidence(request, ownerReceipt);
    var result = new AccountSelectedOwnerIntakeSettlementReceipt(binding, evidence);
    if (!Arrays.equals(stored, result.canonicalBytes)) {
      throw new IllegalArgumentException("Noncanonical Account selected-owner settlement");
    }
    return result;
  }

  public SelectedOwnerIntakeAuthorizationBinding authorizationBinding() {
    return authorizationBinding;
  }

  public AutomationSelectedSourceIntakeTerminalReadEvidence terminalEvidence() {
    return terminalEvidence;
  }

  public UUID operationId() {
    return authorizationBinding.operationId();
  }

  public String targetNamespace() {
    return authorizationBinding.targetNamespace();
  }

  public byte[] ownerReceiptBytes() {
    return terminalEvidence.receipt().canonicalBytes();
  }

  public String ownerReceiptDigest() {
    return terminalEvidence.receipt().receiptDigest();
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  /** Correlation is retained on first settlement, but is not part of owner receipt identity. */
  public boolean sameImmutableOwnerReceipt(
      AutomationSelectedSourceIntakeTerminalReadEvidence requested) {
    return requested != null
        && Arrays.equals(
            authorizationBinding.canonicalBytes(), requested.request().binding().canonicalBytes())
        && Arrays.equals(
            terminalEvidence.receipt().canonicalBytes(), requested.receipt().canonicalBytes())
        && terminalEvidence.receipt().receiptDigest().equals(requested.receipt().receiptDigest());
  }

  @Override
  public boolean equals(Object value) {
    return value instanceof AccountSelectedOwnerIntakeSettlementReceipt other
        && Arrays.equals(canonicalBytes, other.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private byte[] encode() {
    var out = new ByteArrayOutputStream();
    frame(out, DOMAIN);
    frame(out, "1");
    frame(out, authorizationBinding.canonicalBytes());
    frame(out, authorizationBinding.digest());
    var request = terminalEvidence.request();
    frame(out, Integer.toString(request.schemaVersion()));
    frame(out, request.targetNamespace());
    frame(out, request.readRequestId().toString());
    frame(out, request.intendedReader());
    frame(out, request.terminalReadPurpose());
    frame(out, request.binding().canonicalBytes());
    frame(out, request.binding().digest());
    frame(out, terminalEvidence.receipt().canonicalBytes());
    frame(out, terminalEvidence.receipt().receiptDigest());
    return out.toByteArray();
  }

  private static int parseSchemaVersion(String value) {
    if (!"1".equals(value)) {
      throw new IllegalArgumentException("Unsupported Automation terminal-read schema");
    }
    return 1;
  }

  private static byte[] bounded(byte[] value, int max) {
    if (value == null || value.length == 0 || value.length > max) {
      throw new IllegalArgumentException("Selected-owner settlement evidence size is invalid");
    }
    return value;
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }
}
