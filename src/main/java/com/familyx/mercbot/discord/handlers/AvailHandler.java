package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.TierAvailability;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Component;

/** "Turn availability on" in the availability-off DM (scope doc 5.1). */
@Component
public class AvailHandler implements InteractionHandler {

    private final MercRepository mercs;
    private final TierAvailability availability;

    public AvailHandler(MercRepository mercs, TierAvailability availability) {
        this.mercs = mercs;
        this.availability = availability;
    }

    @Override
    public String action() {
        return "availon";
    }

    @Override
    public Access.Level access() {
        return Access.Level.ANYONE;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        var merc = mercs.findByDiscord(event.getUser().getIdLong());
        if (merc.isEmpty()) {
            event.reply("You're not signed up as a merc.").setEphemeral(true).queue();
            return;
        }
        mercs.setAvailability(merc.get().tornId(), true, null);
        availability.evaluate();
        event.editMessage(new MessageEditBuilder().setReplace(true)
                .setContent("🟢 Availability is on. You'll get pings again.").build()).queue();
    }
}
