package com.familyx.mercbot.discord;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single entry point for every interaction (scope doc 10.1). Routes {@code merc:<action>:...}
 * ids to the matching {@link InteractionHandler}, checks access, and turns errors into a
 * friendly private reply instead of a failed interaction.
 */
@Component
public class InteractionRouter extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(InteractionRouter.class);

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private final Map<String, InteractionHandler> handlers;
    private final List<InteractionHandler> all;
    private final Access access;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public InteractionRouter(List<InteractionHandler> handlers, Access access) {
        this.all = handlers;
        this.handlers = handlers.stream().collect(Collectors.toMap(InteractionHandler::action, Function.identity()));
        this.access = access;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        all.stream().filter(h -> h.handlesCommand(event.getName())).findFirst()
                .ifPresent(h -> dispatch(event, h, () -> h.command(event)));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        route(event, event.getComponentId(), (h, id) -> () -> h.button(event, id));
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        route(event, event.getComponentId(), (h, id) -> () -> h.stringSelect(event, id));
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        route(event, event.getComponentId(), (h, id) -> () -> h.entitySelect(event, id));
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        route(event, event.getModalId(), (h, id) -> () -> h.modal(event, id));
    }

    private void route(IReplyCallback event, String rawId,
                       java.util.function.BiFunction<InteractionHandler, CustomId, Work> work) {
        CustomId id = CustomId.parse(rawId);
        if (id == null) {
            return;
        }
        InteractionHandler handler = handlers.get(id.action());
        if (handler == null) {
            event.reply("That button is out of date. Please use the latest panel.").setEphemeral(true).queue();
            return;
        }
        dispatch(event, handler, work.apply(handler, id));
    }

    private void dispatch(IReplyCallback event, InteractionHandler handler, Work work) {
        if (!access.allows(handler.access(), event.getMember())) {
            event.reply("Sorry, only leaders can use this.").setEphemeral(true).queue();
            return;
        }
        executor.submit(() -> {
            try {
                work.run();
            } catch (Exception e) {
                log.error("Interaction {} failed", handler.action(), e);
                String msg = "Something went wrong. Leaders have been told in the logs; please try again.";
                if (event.isAcknowledged()) {
                    event.getHook().sendMessage(msg).setEphemeral(true).queue(null, ignored -> { });
                } else {
                    event.reply(msg).setEphemeral(true).queue(null, ignored -> { });
                }
            }
        });
    }
}
