package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.outside.OutsideService;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Pause contract and Ignore on the "possible defensive merc service" card (scope doc 6.3, 7.8). */
@Component
public class OutsideHandler implements InteractionHandler {

    private final OutsideService service;
    private final ContractRepository contracts;

    public OutsideHandler(OutsideService service, ContractRepository contracts) {
        this.service = service;
        this.contracts = contracts;
    }

    @Override
    public String action() {
        return "outside";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        Optional<Contract> c = contracts.find(id.longArg(0));
        if (c.isEmpty()) {
            event.reply("That contract no longer exists.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        long user = event.getUser().getIdLong();
        long card = event.getMessageIdLong();
        event.deferEdit().complete();
        switch (id.sub()) {
            case "pause" -> {
                if (!service.pause(c.get(), user, card)) {
                    event.getHook().sendMessage(c.get().ref() + " isn't live any more, so it wasn't paused.")
                            .setEphemeral(true).queue(null, ignored -> { });
                }
            }
            case "ignore" -> service.ignore(c.get(), user, card);
            default -> { }
        }
    }
}
