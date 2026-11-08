package com.familyx.mercbot.merc;

import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily merits and faction-perk check (scope doc 5.1, 10.4), and the minute-by-minute check
 * that turns timed "availability off" back on.
 */
@Component
public class MercCheckJob {

    private static final Logger log = LoggerFactory.getLogger(MercCheckJob.class);

    private final MercRepository mercs;
    private final MercService service;
    private final RoleSync roles;
    private final FactionPerkService perks;
    private final FamilyFactionRepository factions;
    private final TierAvailability availability;
    private final Dms dms;

    public MercCheckJob(MercRepository mercs, MercService service, RoleSync roles, FactionPerkService perks,
                        FamilyFactionRepository factions, TierAvailability availability, Dms dms) {
        this.mercs = mercs;
        this.service = service;
        this.roles = roles;
        this.perks = perks;
        this.factions = factions;
        this.availability = availability;
        this.dms = dms;
    }

    /** 04:37 UTC daily, twenty minutes after the key health check. */
    @Scheduled(cron = "0 37 4 * * *", zone = "UTC")
    public void daily() {
        perks.refreshAll();
        int checked = 0;
        int changed = 0;
        for (Merc before : mercs.findActive()) {
            checked++;
            boolean perkBefore = perks.has(before.factionId());
            String problem = service.recheck(before);
            if (problem != null) {
                continue; // key problems are DMed by the key health check
            }
            Merc after = mercs.findByTorn(before.tornId()).orElse(before);
            RoleSync.Result result = roles.sync(after);
            if (!result.lost().isEmpty() || !result.gained().isEmpty()) {
                changed++;
            }
            if (!result.lost().isEmpty()) {
                dms.send(after.discordId(), lapseEmbed(before, after, perkBefore, result), profileButton());
            }
        }
        availability.evaluate();
        log.info("Daily merc check: {} mercs checked, {} with role changes", checked, changed);
    }

    /** Every minute: timed "off" has run out, so availability turns back on. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void availabilityExpiry() {
        for (Merc m : mercs.availabilityExpired()) {
            mercs.setAvailability(m.tornId(), true, null);
            dms.send(m.discordId(), new EmbedBuilder().setColor(Panels.GOOD).setAuthor("FAMILY X MERCS")
                    .setTitle("🟢  Your availability is back on")
                    .setDescription("The time you set has run out, so you'll get pings again.").build(),
                    profileButton());
        }
    }

    private net.dv8tion.jda.api.entities.MessageEmbed lapseEmbed(Merc before, Merc after, boolean perkBefore,
                                                                  RoleSync.Result result) {
        String reason;
        if (after.factionId() == null || !factions.isFamily(after.factionId())) {
            reason = "Today's check found you're no longer in a Family X faction.";
        } else if (before.meritsOk() && !after.meritsOk()) {
            reason = "Today's check found your merits are no longer 10/10.";
        } else if (perkBefore && !perks.has(after.factionId())) {
            reason = "Today's check found your faction no longer has Hospitalization X.";
        } else {
            reason = "Today's check found you no longer qualify for it.";
        }
        String lost = String.join(", ", result.lost());
        return new EmbedBuilder().setColor(Panels.WARN).setAuthor("FAMILY X MERCS")
                .setTitle("⚠️  Your " + lost + " role" + (result.lost().size() == 1 ? " was" : "s were") + " removed")
                .setDescription(reason + " Once that's fixed, the next daily check (04:37 UTC) restores your roles, "
                        + "or open your profile and press **Re-check now**.")
                .build();
    }

    private static ActionRow profileButton() {
        return ActionRow.of(Button.primary(CustomId.of("merc", "profile"), "Open my profile")
                .withEmoji(Emoji.fromUnicode("👤")));
    }
}
