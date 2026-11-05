package com.familyx.mercbot.keys;

/** Published when a key stops working, so the Discord layer can DM its owner and check faction minimums. */
public record KeyUnhealthyEvent(long keyId, long ownerDiscordId, Long factionId, ApiKeyRepository.Kind kind,
                                String reason) {
}
