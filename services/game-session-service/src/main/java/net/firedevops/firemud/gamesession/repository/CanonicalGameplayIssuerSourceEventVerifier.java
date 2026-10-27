package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Function;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;

/** Shared strict verification for Account issuer source-event checkpoint bytes. */
final class CanonicalGameplayIssuerSourceEventVerifier {
  private CanonicalGameplayIssuerSourceEventVerifier() {}

  static void requireValid(
      String issuerId,
      BigInteger generation,
      byte[] eventBytes,
      String invalidUtf8Message,
      String invalidCodecMessage,
      String checkpointMismatchMessage,
      Function<String, RuntimeException> failure) {
    final String eventJson;
    try {
      eventJson =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(eventBytes))
              .toString();
    } catch (CharacterCodingException malformed) {
      throw failure.apply(invalidUtf8Message);
    }

    final AccountAuthoritySourceEventV1Codec.SourceEvent parsed;
    try {
      parsed = AccountAuthoritySourceEventV1Codec.verify(eventJson);
    } catch (RuntimeException malformed) {
      throw failure.apply(invalidCodecMessage);
    }

    if (!(parsed instanceof IssuerEvent event)
        || !event.issuerId().equals(issuerId)
        || !new BigInteger(event.issuerAuthGeneration()).equals(generation)
        || !Arrays.equals(event.canonicalJsonUtf8(), eventBytes)) {
      throw failure.apply(checkpointMismatchMessage);
    }
  }
}
