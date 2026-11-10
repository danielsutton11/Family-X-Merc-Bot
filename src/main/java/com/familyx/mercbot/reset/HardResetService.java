package com.familyx.mercbot.reset;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.LeaderPanelService;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.merc.TierAvailability;
import com.familyx.mercbot.payout.PayoutService;
import com.familyx.mercbot.report.LeaderboardService;
import com.familyx.mercbot.torn.TornKeyPool;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hard reset (a leader tool for wiping test data). Settings, tiers, prices, family factions,
 * leader keys, payees and the channels and pinned panels are never touched. The database part
 * runs in one transaction; the Discord tidy-up (channel cards, every DM the bot sent) runs in
 * the background afterwards.
 */
@Service
public class HardResetService {

    private static final Logger log = LoggerFactory.getLogger(HardResetService.class);

    /** What can be wiped. Requesters and Mercs need Contracts and money too (contracts point at both). */
    public enum Group {
        CONTRACTS("Contracts and money"), REQUESTERS("Requesters"), MERCS("Mercs"), AUDIT("Audit log"),
        DMS("Every DM the bot has sent");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    public record Preview(Map<String, Long> counts, long paidInvoices, long paidAmount, long credited, int dmPeople) {
        public boolean money() {
            return paidInvoices > 0 || credited > 0;
        }
    }

    private final JdbcClient jdbc;
    private final MercRepository mercs;
    private final RoleSync roles;
    private final TierAvailability availability;
    private final TornKeyPool keyPool;
    private final FamilyFactionRepository factions;
    private final AuditService audit;
    private final Layout layout;
    private final BotProperties props;
    private final ObjectProvider<JDA> jda;
    private final ObjectProvider<BoardService> boards;
    private final ObjectProvider<PayoutService> payouts;
    private final ObjectProvider<LeaderboardService> leaderboard;
    private final ObjectProvider<LeaderPanelService> leaderPanel;
    private final ObjectProvider<HardResetService> self;

    public HardResetService(JdbcClient jdbc, MercRepository mercs, RoleSync roles, TierAvailability availability,
                            TornKeyPool keyPool, FamilyFactionRepository factions, AuditService audit, Layout layout,
                            BotProperties props, ObjectProvider<JDA> jda, ObjectProvider<BoardService> boards,
                            ObjectProvider<PayoutService> payouts, ObjectProvider<LeaderboardService> leaderboard,
                            ObjectProvider<LeaderPanelService> leaderPanel, ObjectProvider<HardResetService> self) {
        this.jdbc = jdbc;
        this.mercs = mercs;
        this.roles = roles;
        this.availability = availability;
        this.keyPool = keyPool;
        this.factions = factions;
        this.audit = audit;
        this.layout = layout;
        this.props = props;
        this.jda = jda;
        this.boards = boards;
        this.payouts = payouts;
        this.leaderboard = leaderboard;
        this.leaderPanel = leaderPanel;
        this.self = self;
    }

    /** Requesters and Mercs bring Contracts and money with them. */
    public static Set<Group> normalise(Set<Group> picked) {
        Set<Group> g = picked.isEmpty() ? EnumSet.noneOf(Group.class) : EnumSet.copyOf(picked);
        if (g.contains(Group.REQUESTERS) || g.contains(Group.MERCS)) {
            g.add(Group.CONTRACTS);
        }
        return g;
    }

    // ------------------------------------------------------------------ preview

    public Preview preview(Set<Group> groups) {
        Map<String, Long> counts = new LinkedHashMap<>();
        long paidInvoices = 0;
        long paidAmount = 0;
        long credited = 0;
        if (groups.contains(Group.CONTRACTS)) {
            counts.put("contracts", count("SELECT count(*) FROM contract"));
            counts.put("hits", count("SELECT count(*) FROM hit"));
            counts.put("invoices", count("SELECT count(*) FROM invoice"));
            counts.put("payout strings", count("SELECT count(*) FROM payout"));
            counts.put("settlements", count("SELECT count(*) FROM settlement"));
            counts.put("messages", count("SELECT count(*) FROM inbox_message"));
            counts.put("claims", count("SELECT count(*) FROM claim"));
            paidInvoices = count("SELECT count(*) FROM invoice WHERE amount_paid > 0");
            paidAmount = count("SELECT coalesce(sum(amount_paid), 0) FROM invoice");
            credited = count("SELECT coalesce(sum(amount), 0) FROM hit_share WHERE state = 'CREDITED'");
        }
        if (groups.contains(Group.REQUESTERS)) {
            counts.put("requesters", count("SELECT count(*) FROM requester"));
            counts.put("discounts", count("SELECT count(*) FROM discount WHERE pct > 0"));
            counts.put("blacklisted requesters", count("SELECT count(*) FROM requester WHERE blacklisted"));
        }
        if (groups.contains(Group.MERCS)) {
            counts.put("mercs", count("SELECT count(*) FROM merc"));
            counts.put("merc keys", count("SELECT count(*) FROM api_key WHERE kind = 'MERC'"));
            counts.put("blacklisted mercs", count("SELECT count(*) FROM merc_blacklist"));
        }
        if (groups.contains(Group.AUDIT)) {
            counts.put("audit entries", count("SELECT count(*) FROM audit_event"));
        }
        int people = groups.contains(Group.DMS) ? dmRecipients().size() : 0;
        return new Preview(counts, paidInvoices, paidAmount, credited, people);
    }

    /** Counts for the menu's descriptions, whatever is picked. */
    public Map<Group, Long> sizes() {
        Map<Group, Long> m = new LinkedHashMap<>();
        m.put(Group.CONTRACTS, count("SELECT count(*) FROM contract"));
        m.put(Group.REQUESTERS, count("SELECT count(*) FROM requester"));
        m.put(Group.MERCS, count("SELECT count(*) FROM merc"));
        m.put(Group.AUDIT, count("SELECT count(*) FROM audit_event"));
        m.put(Group.DMS, (long) dmRecipients().size());
        return m;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ------------------------------------------------------------------ run

    /** Runs the reset: Discord roles first, then the database in one go, then the tidy-up in the background. */
    public Map<String, Long> run(Set<Group> picked, long by) {
        Set<Group> groups = normalise(picked);
        Map<String, Long> counts = preview(groups).counts();
        Set<Long> dmPeople = groups.contains(Group.DMS) ? dmRecipients() : Set.of();
        List<long[]> knownDms = groups.contains(Group.CONTRACTS) && !groups.contains(Group.DMS) ? knownDms() : List.of();
        if (groups.contains(Group.MERCS)) {
            removeMercRoles();
        }
        self.getObject().wipe(groups);
        keyPool.invalidate();
        String what = String.join(", ", groups.stream().map(g -> g.label).toList());
        audit.log(by, "HARD_RESET", "reset", null, Map.of("groups", what), "🧨 Ran a hard reset: " + what);
        refreshEverything();
        Thread.ofVirtual().name("hard-reset-tidy").start(() -> tidy(groups, dmPeople, knownDms));
        log.info("Hard reset by {}: {}", by, what);
        return counts;
    }

    @Transactional
    public void wipe(Set<Group> groups) {
        if (groups.contains(Group.CONTRACTS)) {
            for (String sql : List.of(
                    "DELETE FROM payout_line",
                    "DELETE FROM hit_share",
                    "DELETE FROM payout",
                    "DELETE FROM reputation_event WHERE contract_id IS NOT NULL OR invoice_id IS NOT NULL",
                    "DELETE FROM faction_news_seen",
                    "DELETE FROM outside_attack_event",
                    "DELETE FROM outside_alert",
                    "DELETE FROM inbox_message",
                    "DELETE FROM target_override",
                    "DELETE FROM claim_noshow",
                    "DELETE FROM hit",
                    "DELETE FROM claim",
                    "DELETE FROM settlement",
                    "DELETE FROM invoice",
                    "DELETE FROM contract_target",
                    "DELETE FROM contract",
                    "DELETE FROM dm_message",
                    "UPDATE merc SET consecutive_noshows = 0, claim_cooldown_until = NULL, noshow_card_id = NULL, "
                            + "noshow_ignored_at = NULL",
                    "ALTER SEQUENCE contract_ref_seq RESTART WITH 1")) {
                jdbc.sql(sql).update();
            }
        }
        if (groups.contains(Group.REQUESTERS)) {
            jdbc.sql("DELETE FROM reputation_event").update();
            jdbc.sql("DELETE FROM discount").update();
            jdbc.sql("DELETE FROM requester").update();
        }
        if (groups.contains(Group.MERCS)) {
            jdbc.sql("DELETE FROM merc_tier_optin").update();
            jdbc.sql("DELETE FROM api_key WHERE kind = 'MERC'").update();
            jdbc.sql("DELETE FROM merc_blacklist").update();
            jdbc.sql("DELETE FROM merc").update();
        }
        if (groups.contains(Group.AUDIT)) {
            jdbc.sql("DELETE FROM audit_event").update();
        }
    }

    // ------------------------------------------------------------------ Discord

    /**
     * Everyone the bot may have DMed: requesters, mercs, key owners, tracked DMs and anyone in the
     * audit log (Merc Managers and leaders). Database only: listing role members from Discord
     * needs the member-list permission and can take longer than Discord allows for a button.
     */
    private Set<Long> dmRecipients() {
        Set<Long> ids = new HashSet<>(jdbc.sql("""
                        SELECT discord_id FROM requester WHERE discord_id > 0
                        UNION SELECT discord_id FROM merc WHERE discord_id > 0
                        UNION SELECT owner_discord_id FROM api_key WHERE owner_discord_id IS NOT NULL
                        UNION SELECT user_id FROM dm_message
                        UNION SELECT actor_discord_id FROM audit_event WHERE actor_discord_id IS NOT NULL""")
                .query(Long.class).list());
        JDA api = jda.getIfAvailable();
        Guild guild = api == null ? null : api.getGuildById(props.guildId());
        Role managers = guild == null ? null : guild.getRoleById(props.mercManagerRoleId());
        if (managers != null) {
            guild.getMembersWithRoles(managers).forEach(m -> ids.add(m.getIdLong())); // whoever is cached; never waits
        }
        return ids;
    }

    /** Control panels and tracked DMs (user, message), for when every DM isn't being cleared. */
    private List<long[]> knownDms() {
        List<long[]> out = new ArrayList<>(jdbc.sql("""
                        SELECT r.discord_id, c.dm_panel_message_id FROM contract c JOIN requester r ON r.torn_id = c.requester_id
                        WHERE c.dm_panel_message_id IS NOT NULL""")
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).list());
        out.addAll(jdbc.sql("SELECT user_id, message_id FROM dm_message")
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).list());
        return out;
    }

    private void removeMercRoles() {
        for (Merc m : mercs.findActive()) {
            try {
                mercs.markLeft(m.tornId());
                mercs.findByTorn(m.tornId()).ifPresent(roles::sync);
            } catch (Exception e) {
                log.info("Couldn't remove roles for merc {}: {}", m.tornId(), e.getMessage());
            }
        }
        availability.evaluate();
    }

    private void refreshEverything() {
        try {
            boards.getObject().refresh();
            payouts.getObject().refreshAll();
            leaderboard.getObject().refresh();
            leaderPanel.getObject().refresh();
        } catch (Exception e) {
            log.warn("Refresh after the hard reset failed: {}", e.getMessage());
        }
    }

    /** Channel cards and DMs. Discord limits the pace, so this can take a few minutes. */
    private void tidy(Set<Group> groups, Set<Long> dmPeople, List<long[]> knownDms) {
        JDA api = jda.getIfAvailable();
        if (api == null) {
            return;
        }
        if (groups.contains(Group.CONTRACTS)) {
            Set<Long> keep = new HashSet<>();
            layout.get(Layout.MSG_LEADERS).ifPresent(keep::add);
            factions.findActive().forEach(f -> layout.get(Layout.payoutsMessage(f.tornFactionId())).ifPresent(keep::add));
            for (String ch : List.of(Layout.CH_RESULTS, Layout.CH_INBOX, Layout.CH_LEADERS, Layout.CH_PAYOUTS)) {
                layout.get(ch).map(api::getTextChannelById).ifPresent(c -> purgeChannel(api, c, keep));
            }
        }
        int deleted = 0;
        if (groups.contains(Group.DMS)) {
            for (long user : dmPeople) {
                deleted += purgeDm(api, user);
            }
        } else {
            for (long[] dm : knownDms) {
                try {
                    api.openPrivateChannelById(dm[0]).flatMap(ch -> ch.deleteMessageById(dm[1])).complete();
                    deleted++;
                } catch (Exception ignored) {
                    // already gone, or DMs closed
                }
            }
        }
        log.info("Hard reset tidy-up finished: {} DMs deleted", deleted);
    }

    /** The bot's own messages from the last 14 days (Discord's bulk-delete limit), except pinned panels. */
    private void purgeChannel(JDA api, TextChannel ch, Set<Long> keep) {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofDays(13).plusHours(23));
            List<Message> mine = new ArrayList<>();
            for (Message m : ch.getIterableHistory().cache(false)) {
                if (m.getTimeCreated().isBefore(cutoff) || mine.size() >= 2000) {
                    break;
                }
                if (m.getAuthor().getIdLong() == api.getSelfUser().getIdLong() && !keep.contains(m.getIdLong())
                        && !m.isPinned()) {
                    mine.add(m);
                }
            }
            if (!mine.isEmpty()) {
                ch.purgeMessages(mine);
            }
        } catch (Exception e) {
            log.info("Couldn't tidy {}: {}", ch.getName(), e.getMessage());
        }
    }

    /** Every message the bot sent in one person's DMs, of any age. */
    private int purgeDm(JDA api, long user) {
        int n = 0;
        try {
            PrivateChannel ch = api.openPrivateChannelById(user).complete();
            long self = api.getSelfUser().getIdLong();
            for (Message m : ch.getIterableHistory().cache(false)) {
                if (m.getAuthor().getIdLong() == self) {
                    try {
                        m.delete().complete();
                        n++;
                    } catch (Exception ignored) {
                        // already gone
                    }
                }
            }
        } catch (Exception e) {
            log.info("Couldn't tidy DMs with {}: {}", user, e.getMessage());
        }
        return n;
    }
}
