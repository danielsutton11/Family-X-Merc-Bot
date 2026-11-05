package com.familyx.mercbot.discord.setup;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.InteractionHandler;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@code /merc-setup}: the bot's only slash command, for Merc Managers. */
@Component
public class SetupHandler implements InteractionHandler {

    public static final String COMMAND = "merc-setup";

    private final SetupService setup;
    private final AuditService audit;

    public SetupHandler(SetupService setup, AuditService audit) {
        this.setup = setup;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "setup";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public boolean handlesCommand(String name) {
        return COMMAND.equals(name);
    }

    @Override
    public void command(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("Run this in the Family X server.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        SetupService.Report report = setup.run(event.getGuild());
        String summary = SetupService.summarise(report);
        audit.log(event.getUser().getIdLong(), "SETUP_RUN", "guild",
                null, Map.of("created", report.created(), "repaired", report.repaired(),
                        "problems", report.problems()),
                "Ran /merc-setup (" + report.created().size() + " created, "
                        + report.repaired().size() + " repaired)");
        event.getHook().editOriginal("**Merc setup finished.**\n" + summary).queue();
    }
}
