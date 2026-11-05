package com.familyx.mercbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment-driven settings. Everything leaders can change at runtime lives in the
 * {@code settings} table instead; these are deployment-level values only.
 */
@ConfigurationProperties("mercbot")
public record BotProperties(
        String discordToken,
        long guildId,
        long mercManagerRoleId,
        long verifiedRoleId,
        long familyXRoleId,
        long requestsChannelId,
        String keyMaster,
        String bspVersion,
        int callsPerKeyPerMinute) {
}
