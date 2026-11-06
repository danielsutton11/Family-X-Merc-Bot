package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.AutoDismiss;

import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Placeholder for panel buttons whose features arrive in later build phases. */
@Component
public class ComingSoonHandler implements InteractionHandler {

    private static final Map<String, String> PHASES = Map.of(
            "2", "merc sign-up and tiers",
            "3", "requests and boards",
            "4", "hits, invoices and payouts",
            "5", "leader tools and reports");

    @Override
    public String action() {
        return "soon";
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        String what = PHASES.getOrDefault(id.sub(), "a later update");
        event.reply("This isn't switched on yet. It arrives with **" + what + "** (build phase " + id.sub() + ").")
                .setEphemeral(true).queue(hook -> AutoDismiss.after(hook, AutoDismiss.SHORT));
    }
}
