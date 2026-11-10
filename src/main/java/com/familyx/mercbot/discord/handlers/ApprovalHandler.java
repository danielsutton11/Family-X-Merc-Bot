package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Approve and Reject on the #merc-inbox approval card (scope doc 6.2). The card is removed once
 * handled; the requester is told by DM and the decision goes in #merc-audit.
 */
@Component
public class ApprovalHandler implements InteractionHandler {

    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final Dms dms;
    private final AuditService audit;
    private final com.familyx.mercbot.contract.ControlPanelService panels;
    private final com.familyx.mercbot.contract.ContractClock clock;

    public ApprovalHandler(ContractRepository contracts, RequestRepositories requesters, Dms dms, AuditService audit,
                           com.familyx.mercbot.contract.ControlPanelService panels,
                           com.familyx.mercbot.contract.ContractClock clock) {
        this.panels = panels;
        this.clock = clock;
        this.contracts = contracts;
        this.requesters = requesters;
        this.dms = dms;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "appr";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long contractId = id.longArg(0);
        switch (id.sub()) {
            case "yes" -> {
                event.deferReply(true).queue();
                decide(contractId, true, event.getUser().getIdLong(), null, event.getMessage());
                event.getHook().editOriginal(outcome(contractId, true))
                        .queue(done -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
            }
            case "no" -> event.replyModal(Modal.create(CustomId.of("appr", "reason", contractId), "Reject this request")
                    .addComponents(Label.of("Reason (sent to the requester)", TextInput.create("reason", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(500).build()))
                    .build()).queue();
            default -> event.reply("Unknown option.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        long contractId = id.longArg(0);
        event.deferReply(true).queue();
        String reason = event.getValue("reason") == null ? "" : event.getValue("reason").getAsString().trim();
        decide(contractId, false, event.getUser().getIdLong(), reason, event.getMessage());
        event.getHook().editOriginal(outcome(contractId, false))
                .queue(done -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
    }

    public void decide(long contractId, boolean approve, long leader, String reason, Message card) {
        Optional<Contract> found = contracts.find(contractId);
        if (found.isEmpty() || !contracts.decide(contractId, approve, leader, reason)) {
            if (card != null) {
                card.delete().queue(null, ignored -> { });
            }
            return;
        }
        Contract c = found.get();
        if (card != null) {
            card.delete().queue(null, ignored -> { });
        }
        panels.refresh(c.id());
        if (approve) {
            Thread.ofVirtual().start(clock::run);
        }
        audit.log(leader, approve ? "CONTRACT_APPROVED" : "CONTRACT_REJECTED", "contract:" + c.id(), null,
                approve ? null : Map.of("reason", reason),
                (approve ? "Approved **" : "Rejected **") + c.ref() + "**" + (approve ? "" : ": " + reason));
        requesters.requester(c.requesterId()).ifPresent(r -> dms.send(r.discordId(), approve
                ? com.familyx.mercbot.discord.Dms.Clean.after("APPROVED", java.time.Duration.ofHours(1)) : com.familyx.mercbot.discord.Dms.Clean.after("REJECTED", java.time.Duration.ofDays(7)), approve
                ? new EmbedBuilder().setColor(Panels.GOOD).setAuthor("CONTRACT " + c.ref())
                .setTitle("✅  Your contract is approved and live")
                .setDescription("Updates on your contract will arrive here.").build()
                : new EmbedBuilder().setColor(Panels.BAD).setAuthor("CONTRACT " + c.ref())
                .setTitle("✖️  Your request wasn't approved")
                .setDescription(reason + "\n-# Nothing has been billed.").build()));
    }

    private static String outcome(long contractId, boolean approved) {
        return approved ? "✅ Approved. The requester has been told." : "✖️ Rejected. The requester has been told why.";
    }
}
