package com.familyx.mercbot.merc;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gives and removes merc roles to match each merc's eligibility (scope doc 3 roles table, 5.2):
 * Merc, 10/10 Merits, Stricken and the tier roles.
 */
@Service
public class RoleSync {

    private static final Logger log = LoggerFactory.getLogger(RoleSync.class);

    /** Tier roles gained and lost by one sync, for DMs. */
    public record Result(List<String> gained, List<String> lost, List<String> held) {
        public static final Result NONE = new Result(List.of(), List.of(), List.of());
    }

    private final JDA jda;
    private final BotProperties props;
    private final Layout layout;
    private final TierRepository tiers;
    private final MercRepository mercs;
    private final FamilyFactionRepository factions;
    private final FactionPerkService perks;
    private final BotSettings settings;

    public RoleSync(JDA jda, BotProperties props, Layout layout, TierRepository tiers, MercRepository mercs,
                    FamilyFactionRepository factions, FactionPerkService perks, BotSettings settings) {
        this.jda = jda;
        this.props = props;
        this.layout = layout;
        this.tiers = tiers;
        this.mercs = mercs;
        this.factions = factions;
        this.perks = perks;
        this.settings = settings;
    }

    /** The facts eligibility is decided on. */
    public Eligibility.Facts facts(Merc m) {
        return new Eligibility.Facts(m.meritsOk(), perks.has(m.factionId()), m.strickenActive(),
                mercs.optedOut(m.tornId()));
    }

    /** Tier ids this merc should hold right now (none if they've left or aren't in a family faction). */
    public Set<Integer> tiersFor(Merc m, List<Tier> active, Set<Integer> optedOut) {
        if (!m.active() || m.factionId() == null || !factions.isFamily(m.factionId())) {
            return Set.of();
        }
        Eligibility.Facts f = new Eligibility.Facts(m.meritsOk(), perks.has(m.factionId()), m.strickenActive(),
                optedOut);
        return Eligibility.tiersToHold(active, f, settings.exclusiveTiers());
    }

    /** Brings one merc's Discord roles in line with the database. */
    public Result sync(Merc m) {
        Guild guild = jda.getGuildById(props.guildId());
        if (guild == null) {
            return Result.NONE;
        }
        Member member;
        try {
            member = guild.retrieveMemberById(m.discordId()).complete();
        } catch (ErrorResponseException e) {
            log.info("Merc {} isn't in the server; roles not synced", m.tornId());
            return Result.NONE;
        }
        List<Tier> active = tiers.findActive();
        Set<Integer> hold = tiersFor(m, active, mercs.optedOut(m.tornId()));
        boolean inService = m.active() && m.factionId() != null && factions.isFamily(m.factionId());

        Set<Role> add = new HashSet<>();
        Set<Role> remove = new HashSet<>();
        want(guild, layout.get(Layout.ROLE_MERC), inService, add, remove);
        want(guild, layout.get(Layout.ROLE_MERITS), inService && m.meritsOk(), add, remove);
        want(guild, layout.get(Layout.ROLE_STRICKEN), inService && m.strickenActive(), add, remove);

        List<String> gained = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        List<String> held = new ArrayList<>();
        for (Tier t : active) {
            if (t.roleId() == null) {
                continue;
            }
            Role role = guild.getRoleById(t.roleId());
            if (role == null) {
                continue;
            }
            boolean should = hold.contains(t.id());
            boolean has = member.getRoles().contains(role);
            if (should) {
                held.add(t.name());
            }
            if (should && !has) {
                add.add(role);
                gained.add(t.name());
            } else if (!should && has) {
                remove.add(role);
                lost.add(t.name());
            }
        }
        add.removeIf(member.getRoles()::contains);
        remove.removeIf(r -> !member.getRoles().contains(r));
        if (!add.isEmpty() || !remove.isEmpty()) {
            guild.modifyMemberRoles(member, add, remove).complete();
        }
        return new Result(gained, lost, held);
    }

    private static void want(Guild guild, Optional<Long> roleId, boolean should, Set<Role> add, Set<Role> remove) {
        Role role = roleId.map(id -> guild.getRoleById(id)).orElse(null);
        if (role == null) {
            return;
        }
        (should ? add : remove).add(role);
    }

    /** Re-syncs every merc in the background (after a tier's requirements or the exclusive setting change). */
    public void syncAllInBackground(Runnable then) {
        Thread.ofVirtual().start(() -> {
            for (Merc m : mercs.findActive()) {
                try {
                    sync(m);
                } catch (Exception e) {
                    log.warn("Role sync for merc {} failed: {}", m.tornId(), e.getMessage());
                }
            }
            if (then != null) {
                then.run();
            }
        });
    }

    /** How many mercs hold each tier (by tier id), worked out from the database. */
    public Map<Integer, Integer> holderCounts() {
        List<Tier> active = tiers.findActive();
        Map<Long, Set<Integer>> optOuts = mercs.allOptedOut();
        Map<Integer, Integer> counts = new java.util.HashMap<>();
        active.forEach(t -> counts.put(t.id(), 0));
        for (Merc m : mercs.findActive()) {
            for (int id : tiersFor(m, active, optOuts.getOrDefault(m.tornId(), Set.of()))) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }
}
