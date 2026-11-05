package com.familyx.mercbot.discord;

import com.familyx.mercbot.config.BotProperties;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Discord connection. Listeners are attached later by {@link BotStarter},
 * so nothing here depends on the handlers (and there are no bean cycles).
 */
@Configuration
public class JdaConfig {

    @Bean(destroyMethod = "shutdown")
    public JDA jda(BotProperties props) throws InterruptedException {
        if (props.discordToken() == null || props.discordToken().isBlank()) {
            throw new IllegalStateException("DISCORD_TOKEN is not set");
        }
        // GUILD_MEMBERS is privileged: enable "Server Members Intent" in the Discord developer portal.
        // It's needed from phase 2 to add and remove merc roles.
        return JDABuilder.createLight(props.discordToken(), GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .build()
                .awaitReady();
    }
}
