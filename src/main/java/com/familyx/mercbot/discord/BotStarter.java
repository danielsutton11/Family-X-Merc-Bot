package com.familyx.mercbot.discord;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.setup.SetupHandler;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Once Spring is ready: attach the interaction router, register {@code /merc-setup} in the
 * Family X server, and refresh the leader panel so its numbers are current after a deploy.
 */
@Component
public class BotStarter {

    private static final Logger log = LoggerFactory.getLogger(BotStarter.class);

    private final JDA jda;
    private final InteractionRouter router;
    private final BotProperties props;
    private final LeaderPanelService leaderPanel;

    public BotStarter(JDA jda, InteractionRouter router, BotProperties props, LeaderPanelService leaderPanel) {
        this.jda = jda;
        this.router = router;
        this.props = props;
        this.leaderPanel = leaderPanel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        jda.addEventListener(router);

        Guild guild = jda.getGuildById(props.guildId());
        if (guild == null) {
            log.error("Bot isn't in guild {} (DISCORD_GUILD_ID). Invite it, then restart.", props.guildId());
            return;
        }
        guild.updateCommands().addCommands(
                Commands.slash(SetupHandler.COMMAND, "Build or repair the merc channels, roles and panels (Merc Managers)")
                        .setGuildOnly(true)
                        // Hidden from members without Manage Server by default; server admins can open it up
                        // to the Merc Managers role under Server Settings > Integrations. The bot also checks the role.
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED))
                .queue(ok -> log.info("Registered /{} in {}", SetupHandler.COMMAND, guild.getName()),
                        e -> log.error("Couldn't register commands: {}", e.getMessage()));

        leaderPanel.refresh();
        log.info("Merc Bot ready in {}", guild.getName());
    }
}
