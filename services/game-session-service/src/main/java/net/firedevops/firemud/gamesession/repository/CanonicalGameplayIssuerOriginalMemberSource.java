package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/** Original physical provenance; independent of the positive/negative cleanup purpose. */
sealed interface CanonicalGameplayIssuerOriginalMemberSource {
  CanonicalGameplayIssuerMemberV2Value memberValue();

  CanonicalGameplayIssuerInstalledAnchor anchor();

  byte[] exactMemberValueBytes();

  default UUID operationId() { return memberValue().memberOperation().operationId(); }
  default BigInteger operationFence() { return memberValue().memberOperation().operationFence(); }
  default BigInteger localMemberGeneration() { return memberValue().memberOperation().coverageGeneration(); }
  default CanonicalIssuerPartitionReservation reservation() { return memberValue().reservation(); }
  default UUID transitionId() { return reservation().transitionId(); }
  default String memberValueDigest() { return CanonicalGameplayIssuerMemberV2Codec.sha256(exactMemberValueBytes()); }
  default UUID localOperationId() { return this instanceof OrdinaryLocal ? operationId() : null; }
  default UUID bootstrapMemberOperationId() { return this instanceof InstalledBootstrap ? operationId() : null; }

  default CanonicalGameplayIssuerBindingLocalOperation ordinary() {
    if (this instanceof OrdinaryLocal local) return local.operation();
    throw new IllegalStateException("Installed bootstrap provenance is not an ordinary registration");
  }

  static CanonicalGameplayIssuerOriginalMemberSource ordinary(
      CanonicalGameplayIssuerBindingLocalOperation operation) {
    return new OrdinaryLocal(operation);
  }

  record OrdinaryLocal(CanonicalGameplayIssuerBindingLocalOperation operation)
      implements CanonicalGameplayIssuerOriginalMemberSource {
    public OrdinaryLocal { Objects.requireNonNull(operation); }
    @Override public CanonicalGameplayIssuerMemberV2Value memberValue() { return operation.memberValue(); }
    @Override public CanonicalGameplayIssuerInstalledAnchor anchor() { return operation.anchor(); }
    @Override public byte[] exactMemberValueBytes() { return operation.exactMemberValueBytes(); }
  }

  record InstalledBootstrap(
      CanonicalGameplayIssuerBootstrapMemberOperation operation,
      CanonicalGameplayIssuerInstalledAnchor anchor)
      implements CanonicalGameplayIssuerOriginalMemberSource {
    public InstalledBootstrap { Objects.requireNonNull(operation); Objects.requireNonNull(anchor); }
    @Override public CanonicalGameplayIssuerMemberV2Value memberValue() { return operation.memberValue(); }
    @Override public byte[] exactMemberValueBytes() { return operation.exactMemberValueBytes(); }
  }
}
