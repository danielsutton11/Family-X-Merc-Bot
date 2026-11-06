package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.AutoDismiss;

import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.springframework.stereotype.Component;

/** The private Help guide on the request panel (scope doc 4.1). */
@Component
public class HelpHandler implements InteractionHandler {

    private final Panels panels;

    public HelpHandler(Panels panels) {
        this.panels = panels;
    }

    @Override
    public String action() {
        return "help";
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        event.replyEmbeds(panels.requestHelp()).setEphemeral(true)
                .queue(hook -> AutoDismiss.after(hook, AutoDismiss.READING));
    }
}
