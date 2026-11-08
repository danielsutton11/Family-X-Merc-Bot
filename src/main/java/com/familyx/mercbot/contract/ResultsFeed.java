package com.familyx.mercbot.contract;

import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.tier.TierRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * #merc-results (scope doc 5.5): one line per contract start, pause and completion (hits join in
 * phase 4). Requesters are never named here.
 */
@Component
public class ResultsFeed {

    private final ObjectProvider<JDA> jda;
    private final Layout layout;
    private final ContractRepository contracts;
    private final TierRepository tiers;

    public ResultsFeed(ObjectProvider<JDA> jda, Layout layout, ContractRepository contracts, TierRepository tiers) {
        this.jda = jda;
        this.layout = layout;
        this.contracts = contracts;
        this.tiers = tiers;
    }

    /**
     * A contract event, named so it reads without looking anything up:
     * "⏸️ **C-0004 · Capacity X** paused by the requester" over "-# Gold · Hospitalize · 98 targets · …".
     */
    public void contract(Contract c, String emoji, String happened, String detail) {
        String tier = tiers.find(c.tierId()).map(t -> t.name()).orElse("Tier");
        int n = contracts.targetCount(c.id());
        post(emoji + " **" + c.ref() + " · " + targetName(c, n) + "** " + happened
                + "\n-# " + tier + " · " + c.attack().label + " · " + n + (n == 1 ? " target" : " targets")
                + (detail == null || detail.isBlank() ? "" : " · " + detail));
    }

    /** Who the contract is on: the faction, the one player, "Merc me" or "N players". */
    public String targetName(Contract c, int n) {
        if (c.mercMe()) {
            return "Merc me";
        }
        if (c.factionTarget()) {
            return c.targetFactionName() == null ? "Faction" : c.targetFactionName();
        }
        return n == 1 ? contracts.firstTargetName(c.id()).orElse("1 player") : n + " players";
    }

    /** Hits landed so far, for end-of-contract lines. */
    public String hitsLanded(Contract c) {
        int hits = contracts.hitStats(c.id()).accepted();
        return hits + (hits == 1 ? " hit landed" : " hits landed");
    }

    public void post(String line) {
        JDA api = jda.getIfAvailable();
        if (api == null) {
            return;
        }
        layout.get(Layout.CH_RESULTS).map(id -> api.getTextChannelById(id)).ifPresent((TextChannel ch) ->
                ch.sendMessage(line).setAllowedMentions(Collections.emptySet()).queue(null, ignored -> { }));
    }
}
