package com.familyx.mercbot.request;

import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.request.ContractRepository.Contract;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The "Approval needed" card in #merc-inbox for requests that aren't auto-accepted (scope doc 6.2, 7.9). */
@Component
public class ApprovalCards {

    private final ObjectProvider<JDA> jda;
    private final Layout layout;
    private final ContractRepository contracts;

    public ApprovalCards(ObjectProvider<JDA> jda, Layout layout, ContractRepository contracts) {
        this.jda = jda;
        this.layout = layout;
        this.contracts = contracts;
    }

    public void post(Contract c, Draft d, RequestService.Quote q, int score) {
        JDA api = jda.getIfAvailable();
        TextChannel inbox = api == null ? null
                : layout.get(Layout.CH_INBOX).map(id -> api.getTextChannelById(id)).orElse(null);
        if (inbox == null) {
            return;
        }
        String targets = c.factionTarget()
                ? d.factionName + (c.allMembers() ? "\n-# all " + q.targetCount() + " members" : "\n-# " + q.targetCount() + " picked members")
                : q.targetCount() + (q.targetCount() == 1 ? " player" : " players");
        String price = q.total() != null
                ? Money.shortForm(q.total()) + "\n-# " + q.targetCount() + " × " + c.hitsPerTarget() + " hit"
                + (c.hitsPerTarget() == 1 ? "" : "s")
                : Money.shortForm(q.perHit()) + " per hit\n-# billed as hits land";
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER)
                .setAuthor("APPROVAL NEEDED · " + c.ref())
                .setTitle("🧾  " + d.requesterName + " [" + d.requesterTornId + "]"
                        + (d.requesterFactionName == null ? "" : " · " + d.requesterFactionName))
                .setDescription("Reputation " + score + Panels.GAP)
                .addField("🎯  Targets", targets + Panels.GAP, true)
                .addField(Panels.tierIcon(q.tier()) + "  Tier", q.tier().name() + "\n-# " + c.attack().label + Panels.GAP, true)
                .addField("💰  Price", price + Panels.GAP, true)
                .addField("🎛️  Conditions", "Status: **" + c.conditions().statusLabel() + "** · Revivable: **"
                        + (c.conditions().allowRevivable() ? "Hit" : "Don't hit") + "**"
                        + (c.attack() == Choices.Attack.HOSPITALIZE ? " · Early discharge: **"
                        + (c.conditions().allowEarlyDischarge() ? "Hit" : "Skip") + "**" : ""), false);
        if (c.notes() != null && !c.notes().isBlank()) {
            eb.addField("📝  Notes", c.notes(), false);
        }
        inbox.sendMessage(new MessageCreateBuilder().setEmbeds(eb.build())
                        .setComponents(ActionRow.of(
                                Button.primary(CustomId.of("appr", "yes", c.id()), "Approve").withEmoji(Emoji.fromUnicode("✅")),
                                Button.danger(CustomId.of("appr", "no", c.id()), "Reject").withEmoji(Emoji.fromUnicode("✖️"))))
                        .build())
                .queue(m -> contracts.setApprovalMessage(c.id(), m.getIdLong()));
    }
}
