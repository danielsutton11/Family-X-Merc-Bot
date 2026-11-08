package com.familyx.mercbot.contract;

import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.board.PingService;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The contract clock (scope doc 10.4): starts contracts when their time comes (results line and
 * a new-contract ping), completes them when their time or hits run out, and keeps control panels
 * current. Runs every minute.
 */
@Component
public class ContractClock {

    private static final Logger log = LoggerFactory.getLogger(ContractClock.class);
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);

    private final ContractRepository contracts;
    private final TierRepository tiers;
    private final ResultsFeed feed;
    private final PingService pings;
    private final ControlPanelService panels;
    private final BoardService boards;

    public ContractClock(ContractRepository contracts, TierRepository tiers, ResultsFeed feed, PingService pings,
                         ControlPanelService panels, BoardService boards) {
        this.contracts = contracts;
        this.tiers = tiers;
        this.feed = feed;
        this.pings = pings;
        this.panels = panels;
        this.boards = boards;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        try {
            run();
        } catch (Exception e) {
            log.warn("Contract clock failed: {}", e.getMessage());
        }
    }

    /** Also called straight after a contract goes live, so its start isn't delayed by up to a minute. */
    public synchronized void run() {
        boolean changed = false;
        for (Contract c : contracts.toAnnounce()) {
            contracts.markAnnounced(c.id());
            announceStart(c);
            changed = true;
        }
        for (Contract c : contracts.pastEnd()) {
            if (contracts.setStatus(c.id(), List.of("ACTIVE", "PAUSED"), "COMPLETED")) {
                feed.post("✅ **" + c.ref() + "** completed");
                changed = true;
            }
        }
        for (Contract c : contracts.hitsComplete()) {
            if (contracts.setStatus(c.id(), List.of("ACTIVE", "PAUSED"), "COMPLETED")) {
                int[] p = contracts.hitProgress(c.id());
                feed.post("✅ **" + c.ref() + "** completed · " + p[0] + " of " + p[1] + " hits");
                changed = true;
            }
        }
        for (Contract c : contracts.livePanels()) {
            panels.refresh(c.id());
        }
        if (changed) {
            boards.refresh();
        }
    }

    private void announceStart(Contract c) {
        Tier tier = tiers.find(c.tierId()).orElse(null);
        String tierName = tier == null ? "Tier" : tier.name();
        int n = contracts.targetCount(c.id());
        String target = c.mercMe() ? "Merc me"
                : c.factionTarget() ? c.targetFactionName() : n == 1 ? contracts.firstTargetName(c.id()).orElse("1 player")
                : n + " players";
        feed.post("🟢 **" + c.ref() + "** started · " + tierName + " · " + target
                + (c.factionTarget() ? " (" + n + " targets)" : ""));
        if (tier != null) {
            String details = n + (n == 1 ? " target" : " targets") + " · " + c.attack().label
                    + (c.mercMe() ? "" : " · " + c.conditions().statusLabel())
                    + (c.mode() == Mode.HITS ? " · " + c.hitsPerTarget() + (c.hitsPerTarget() == 1 ? " hit" : " hits")
                    + (n == 1 ? "" : " each")
                    : c.endsAt() == null ? " · no end" : " · until " + SHOW.format(c.endsAt()) + " UTC");
            pings.newContract(tier, c.ref(), target, details);
        }
        panels.refresh(c.id());
    }
}
