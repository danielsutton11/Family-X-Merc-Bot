package com.familyx.mercbot.request;

import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.ContractRepository.Contract;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** The requester's DM record of a placed contract (what they agreed to). */
public final class ContractSummary {

    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);

    private ContractSummary() {
    }

    public static MessageEmbed build(Contract c, Draft d, RequestService.Quote q, boolean live) {
        EmbedBuilder eb = new EmbedBuilder().setColor(live ? Panels.GOOD : Panels.PRIVATE)
                .setAuthor("CONTRACT " + c.ref())
                .setTitle(live ? "✅  Your contract is live" : "⏳  Waiting for a leader")
                .setDescription((live ? "Here's what you agreed to. Updates on this contract will arrive here."
                        : "Here's what you asked for. You'll get another message here when a leader approves or "
                        + "rejects it. The price is fixed at what you accepted.") + Panels.GAP);

        String targets;
        if (c.mercMe()) {
            targets = "You\n-# " + d.requesterName + " [" + d.requesterTornId + "]";
        } else if (c.factionTarget()) {
            targets = c.targetFactionName() + "\n-# " + (c.allMembers() ? "all " + q.targetCount() + " members"
                    : q.targetCount() + " picked members");
        } else {
            targets = q.targetCount() == 1 ? d.players.getFirst().name() + "\n-# [" + d.players.getFirst().id() + "]"
                    : q.targetCount() + " players";
        }
        eb.addField("🎯  " + (c.mercMe() ? "Target" : "Targets"), targets + Panels.GAP, true);
        eb.addField(Panels.tierIcon(q.tier()) + "  Tier", q.tier().name() + "\n-# " + c.attack().label + Panels.GAP, true);
        if (c.mode() == Mode.HITS) {
            eb.addField("🔢  Hits", c.hitsPerTarget() + (c.mercMe() ? "" : " per target") + Panels.GAP, true);
        } else {
            String from = c.startsAt() == null || !c.startsAt().isAfter(Instant.now()) ? "From now"
                    : "From " + SHOW.format(c.startsAt()) + " UTC";
            String to = c.endsAt() == null ? "no end" : "until " + SHOW.format(c.endsAt()) + " UTC";
            eb.addField("⏱️  When", from + "\n-# " + to + Panels.GAP, true);
        }
        if (!c.mercMe()) {
            Conditions k = c.conditions();
            eb.addField("🎛️  Conditions", "Status: **" + k.statusLabel() + "** · Revivable: **"
                    + (k.allowRevivable() ? "Hit" : "Don't hit") + "**"
                    + (c.attack() == Attack.HOSPITALIZE ? " · Early discharge: **"
                    + (k.allowEarlyDischarge() ? "Hit" : "Skip") + "**" : "") + Panels.GAP, false);
        }
        StringBuilder price = new StringBuilder("**").append(Money.full(q.perHit())).append("** per hit");
        if (q.total() != null) {
            price.append(" · total **").append(Money.full(q.total())).append("**");
        }
        price.append("\n-# ");
        if (q.discountPct().signum() > 0) {
            price.append(q.discountLabel()).append(" −").append(q.discountPct().stripTrailingZeros().toPlainString())
                    .append("% applied. ");
        }
        price.append(q.total() != null ? "Invoiced when the contract ends." : "Billed per hit as they land.");
        if (c.mercMe()) {
            // Same warning as the Merc me quote.
            if (d.requesterRevivable) {
                price.append("\n-# ⚠️ You're revivable. If someone revives you, that hit is still billed.");
            }
        } else if (q.silverPerHit() != null) {
            price.append(" Revivable targets are billed at Silver (").append(Money.shortForm(q.silverPerHit())).append(").");
        }
        eb.addField("💰  Price", price.toString(), false);
        return eb.build();
    }

}
