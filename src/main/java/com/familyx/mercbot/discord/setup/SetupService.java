package com.familyx.mercbot.discord.setup;

import com.familyx.mercbot.discord.Fancy;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Panels.Panel;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IPermissionHolder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds the Discord layout from scope doc section 3. Safe to re-run: existing channels,
 * roles and messages are found (by stored ID, then by name) and repaired rather than duplicated.
 * Runs off the gateway thread, so it uses blocking {@code complete()} calls.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private static final EnumSet<Permission> READ =
            EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
    private static final EnumSet<Permission> NO_POSTING =
            EnumSet.of(Permission.MESSAGE_SEND, Permission.CREATE_PUBLIC_THREADS, Permission.CREATE_PRIVATE_THREADS);
    private static final EnumSet<Permission> BOT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_MANAGE);

    /** What setup did, for the reply to the leader. */
    public record Report(List<String> created, List<String> repaired, List<String> problems) {
        public Report() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /** One permission override: who, what to allow, what to deny. */
    private record Override(IPermissionHolder holder, EnumSet<Permission> allow, EnumSet<Permission> deny) { }

    private final Layout layout;
    private final TierRepository tiers;
    private final FamilyFactionRepository factions;
    private final Panels panels;
    private final BotProperties props;

    public SetupService(Layout layout, TierRepository tiers, FamilyFactionRepository factions, Panels panels,
                        BotProperties props) {
        this.layout = layout;
        this.tiers = tiers;
        this.factions = factions;
        this.panels = panels;
        this.props = props;
    }

    public Report run(Guild guild) {
        Report report = new Report();

        Role everyone = guild.getPublicRole();
        Role leaders = requiredRole(guild, props.mercManagerRoleId(), "FAMILY_X_MERC_MANAGER_ROLE_ID", report);
        Role familyX = requiredRole(guild, props.familyXRoleId(), "FAMILY_X_ROLE_ID", report);
        if (leaders == null || familyX == null) {
            return report;
        }
        IPermissionHolder bot = guild.getSelfMember();

        // ---- roles
        Role merc = ensureRole(guild, Layout.ROLE_MERC, "Merc", null, report);
        ensureRole(guild, Layout.ROLE_MERITS, "10/10 Merits", null, report);
        ensureRole(guild, Layout.ROLE_STRICKEN, "Stricken", new Color(0xD64545), report);

        // ---- categories
        Category service = ensureCategory(guild, Layout.CAT_SERVICE, "Family X Merc Service", report);
        Category leaderCat = ensureCategory(guild, Layout.CAT_LEADERS, "Family X Merc Managers", report);

        // ---- merc-facing channels
        TextChannel signup = ensureChannel(guild, Layout.CH_SIGNUP, "merc-signup", service, report, List.of(
                new Override(everyone, none(), EnumSet.of(Permission.VIEW_CHANNEL)),
                new Override(familyX, READ, NO_POSTING),
                new Override(leaders, READ, NO_POSTING),
                new Override(bot, BOT, none())));

        syncTiers(guild, service, leaders, report);

        List<Override> mercsReadOnly = List.of(
                new Override(everyone, none(), EnumSet.of(Permission.VIEW_CHANNEL)),
                new Override(merc, READ, NO_POSTING),
                new Override(leaders, READ, NO_POSTING),
                new Override(bot, BOT, none()));
        ensureChannel(guild, Layout.CH_RESULTS, "merc-results", service, report, mercsReadOnly);
        TextChannel leaderboard = ensureChannel(guild, Layout.CH_LEADERBOARD, "merc-leaderboard", service, report,
                mercsReadOnly);

        // ---- leader channels
        TextChannel leaderChannel = ensureChannel(guild, Layout.CH_LEADERS, "merc-leaders", leaderCat, report,
                leaderPanelOverrides(guild, everyone, leaders, bot));
        List<Override> leadersReadOnly = List.of(
                new Override(everyone, none(), EnumSet.of(Permission.VIEW_CHANNEL)),
                new Override(leaders, READ, NO_POSTING),
                new Override(bot, BOT, none()));
        ensureChannel(guild, Layout.CH_INBOX, "merc-inbox", leaderCat, report, leadersReadOnly);
        ensureChannel(guild, Layout.CH_AUDIT, "merc-audit", leaderCat, report, leadersReadOnly);
        TextChannel payouts = ensureChannel(guild, Layout.CH_PAYOUTS, "merc-payouts", leaderCat, report,
                payoutOverrides(guild, everyone, leaders, bot));

        // ---- pinned panels
        TextChannel requests = guild.getTextChannelById(props.requestsChannelId());
        if (requests == null) {
            report.problems().add("The request channel (MERC_REQUESTS_CHANNEL_ID) wasn't found, so the guide and "
                    + "request panel weren't posted.");
        } else {
            // Only the bot's own access is set here; everyone else's settings on this channel are left alone.
            requests.getManager().putPermissionOverride(guild.getSelfMember(), BOT, none()).complete();
            removeMessage(requests, Layout.MSG_GUIDE, "old request guide (now part of the request panel)", report);
            ensureMessage(requests, Layout.MSG_REQUEST_PANEL, panels.requestPanel(), "Request panel", report);
        }
        ensureMessage(signup, Layout.MSG_SIGNUP, panels.signupPanel(), "Sign-up panel", report);
        ensureMessage(leaderboard, Layout.MSG_LEADERBOARD, panels.leaderboard(), "Leaderboard", report);
        ensureMessage(leaderChannel, Layout.MSG_LEADERS, panels.leaderPanel(), "Leader panel", report);
        for (FamilyFaction f : factions.findActive()) {
            ensureMessage(payouts, Layout.payoutsMessage(f.tornFactionId()), panels.payouts(f),
                    f.name() + " payouts", report);
        }
        syncPayouts(guild); // drops access for any faction leader role that has since been replaced
        return report;
    }

    /**
     * Makes sure every active tier has its role, board channel and board message, named after
     * the tier. Called by setup and whenever a tier is added or renamed in Tiers & pricing.
     */
    public Report syncTiers(Guild guild) {
        Report report = new Report();
        Role leaders = guild.getRoleById(props.mercManagerRoleId());
        Category service = layout.get(Layout.CAT_SERVICE).map(id -> guild.getCategoryById(id)).orElse(null);
        if (leaders == null || service == null) {
            report.problems().add("Run /merc-setup first.");
            return report;
        }
        syncTiers(guild, service, leaders, report);
        return report;
    }

    private void syncTiers(Guild guild, Category service, Role leaders, Report report) {
        IPermissionHolder bot = guild.getSelfMember();
        for (Tier tier : tiers.findActive()) {
            Role tierRole = ensureTierRole(guild, tier, report);
            TextChannel board = ensureTierChannel(guild, tier, service, report, List.of(
                    new Override(guild.getPublicRole(), none(), EnumSet.of(Permission.VIEW_CHANNEL)),
                    new Override(tierRole, READ, NO_POSTING),
                    new Override(leaders, READ, NO_POSTING),
                    new Override(bot, BOT, none())));
            Long messageId = ensureMessage(board, Optional.ofNullable(tier.boardMessageId()), panels.tierBoard(tier),
                    "#" + board.getName() + " board", report);
            tiers.setDiscordIds(tier.id(), tierRole.getIdLong(), board.getIdLong(), messageId);
            if (tierRole.getColorRaw() != tier.colour()) {
                tierRole.getManager().setColor(tier.colour()).complete();
            }
        }
    }

    /** Deletes a retired tier's role and board channel. */
    public void removeTier(Guild guild, Tier tier) {
        if (tier.channelId() != null) {
            TextChannel channel = guild.getTextChannelById(tier.channelId());
            if (channel != null) {
                channel.delete().complete();
            }
        }
        if (tier.roleId() != null) {
            Role role = guild.getRoleById(tier.roleId());
            if (role != null) {
                role.delete().complete();
            }
        }
    }

    /**
     * Re-applies the permissions that depend on faction leader roles (#merc-leaders and #merc-payouts)
     * and posts any missing faction payout embeds. Called after a family faction or its leader role changes.
     */
    public void syncPayouts(Guild guild) {
        Role leaders = guild.getRoleById(props.mercManagerRoleId());
        if (leaders == null) {
            return;
        }
        layout.get(Layout.CH_LEADERS).map(id -> guild.getTextChannelById(id)).ifPresent(ch ->
                applyOverridesExactly(ch, leaderPanelOverrides(guild, guild.getPublicRole(), leaders, guild.getSelfMember())));
        Optional<TextChannel> channel = layout.get(Layout.CH_PAYOUTS).map(id -> guild.getTextChannelById(id));
        if (channel.isEmpty()) {
            return;
        }
        Report ignored = new Report();
        applyOverridesExactly(channel.get(), payoutOverrides(guild, guild.getPublicRole(), leaders, guild.getSelfMember()));
        for (FamilyFaction f : factions.findActive()) {
            ensureMessage(channel.get(), Layout.payoutsMessage(f.tornFactionId()), panels.payouts(f),
                    f.name() + " payouts", ignored);
        }
    }

    /**
     * #merc-leaders: bot embeds only. Merc Managers and faction leaders can see it and use the panel,
     * but nobody can post. Each button still checks who clicked, so Settings etc. stay Merc Manager only.
     */
    private List<Override> leaderPanelOverrides(Guild guild, Role everyone, Role leaders, IPermissionHolder bot) {
        List<Override> list = new ArrayList<>(List.of(
                new Override(everyone, none(), EnumSet.of(Permission.VIEW_CHANNEL)),
                new Override(leaders, READ, NO_POSTING),
                new Override(bot, BOT, none())));
        list.addAll(factionLeaderReadOnly(guild, leaders));
        return list;
    }

    private List<Override> payoutOverrides(Guild guild, Role everyone, Role leaders, IPermissionHolder bot) {
        List<Override> list = new ArrayList<>(List.of(
                new Override(everyone, none(), EnumSet.of(Permission.VIEW_CHANNEL)),
                new Override(leaders, READ, NO_POSTING),
                new Override(bot, BOT, none())));
        list.addAll(factionLeaderReadOnly(guild, leaders));
        return list;
    }

    /** Read-only access for every family faction's leader role (skipping the Merc Manager role itself). */
    private List<Override> factionLeaderReadOnly(Guild guild, Role leaders) {
        List<Override> list = new ArrayList<>();
        for (FamilyFaction f : factions.findActive()) {
            if (f.leaderRoleId() != null) {
                Role r = guild.getRoleById(f.leaderRoleId());
                if (r != null && !r.equals(leaders)) {
                    list.add(new Override(r, READ, NO_POSTING));
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ roles

    private Role requiredRole(Guild guild, long id, String envName, Report report) {
        Role role = id == 0 ? null : guild.getRoleById(id);
        if (role == null) {
            report.problems().add(envName + " doesn't point at a role in this server.");
        }
        return role;
    }

    private Role ensureRole(Guild guild, String key, String name, Color colour, Report report) {
        Optional<Role> existing = layout.get(key).map(id -> guild.getRoleById(id))
                .or(() -> guild.getRolesByName(name, false).stream().findFirst());
        if (existing.isPresent()) {
            layout.put(key, existing.get().getIdLong());
            return existing.get();
        }
        Role role = guild.createRole().setName(name).setColor(colour).setMentionable(false).complete();
        layout.put(key, role.getIdLong());
        report.created().add("@" + name);
        return role;
    }

    private Role ensureTierRole(Guild guild, Tier tier, Report report) {
        Optional<Role> existing = Optional.ofNullable(tier.roleId()).map(id -> guild.getRoleById(id))
                .or(() -> guild.getRolesByName(tier.name(), false).stream().findFirst());
        if (existing.isPresent()) {
            Role role = existing.get();
            if (!role.getName().equals(tier.name())) {
                role.getManager().setName(tier.name()).complete();
                report.repaired().add("@" + tier.name() + " renamed");
            }
            return role;
        }
        Role role = guild.createRole().setName(tier.name()).setColor(new Color(tier.colour()))
                .setMentionable(false).complete();
        report.created().add("@" + tier.name());
        return role;
    }

    // ------------------------------------------------------------------ channels

    private Category ensureCategory(Guild guild, String key, String name, Report report) {
        Optional<Category> existing = layout.get(key).map(id -> guild.getCategoryById(id))
                .or(() -> guild.getCategoriesByName(name, true).stream().findFirst())
                .or(() -> guild.getCategoriesByName(Fancy.category(name), true).stream().findFirst());
        if (existing.isPresent()) {
            Category cat = existing.get();
            String styled = Fancy.category(name);
            if (!cat.getName().equals(styled)) {
                cat.getManager().setName(styled).complete();
                report.repaired().add(styled + " category renamed");
            }
            layout.put(key, cat.getIdLong());
            return cat;
        }
        Category cat = guild.createCategory(Fancy.category(name)).complete();
        layout.put(key, cat.getIdLong());
        report.created().add(name + " category");
        return cat;
    }

    private TextChannel ensureChannel(Guild guild, String key, String name, Category category, Report report,
                                      List<Override> overrides) {
        Optional<TextChannel> existing = layout.get(key).map(id -> guild.getTextChannelById(id))
                .or(() -> guild.getTextChannelsByName(name, true).stream().findFirst())
                .or(() -> guild.getTextChannelsByName(Fancy.channel(name), true).stream().findFirst());
        TextChannel channel = existing.isPresent()
                ? repair(existing.get(), name, category, overrides, report)
                : create(guild, name, category, overrides, report);
        layout.put(key, channel.getIdLong());
        return channel;
    }

    private TextChannel ensureTierChannel(Guild guild, Tier tier, Category category, Report report,
                                          List<Override> overrides) {
        String name = tier.channelName();
        Optional<TextChannel> existing = Optional.ofNullable(tier.channelId()).map(id -> guild.getTextChannelById(id))
                .or(() -> guild.getTextChannelsByName(name, true).stream().findFirst())
                .or(() -> guild.getTextChannelsByName(Fancy.channel(name), true).stream().findFirst());
        return existing.isPresent()
                ? repair(existing.get(), name, category, overrides, report)
                : create(guild, name, category, overrides, report);
    }

    private TextChannel create(Guild guild, String name, Category category, List<Override> overrides, Report report) {
        name = Fancy.channel(name);
        var action = guild.createTextChannel(name, category);
        for (Override o : overrides) {
            action = action.addPermissionOverride(o.holder(), o.allow(), o.deny());
        }
        TextChannel channel = action.complete();
        report.created().add("#" + name);
        return channel;
    }

    private TextChannel repair(TextChannel channel, String name, Category category, List<Override> overrides,
                               Report report) {
        // Channels are kept in the server's style ("┃ᴍᴇʀᴄ・ꜱɪɢɴᴜᴘ"); plain names are renamed to it.
        String styled = Fancy.channel(name);
        TextChannelManager manager = channel.getManager();
        boolean changed = false;
        if (!channel.getName().equals(styled)) {
            manager = manager.setName(styled);
            changed = true;
        }
        if (category != null && !category.equals(channel.getParentCategory())) {
            manager = manager.setParent(category);
            changed = true;
        }
        if (changed) {
            manager.complete();
            report.repaired().add("#" + styled + " moved or renamed");
        }
        applyOverrides(channel, overrides);
        return channel;
    }

    /**
     * Like {@link #applyOverrides} but also drops role overrides that aren't in the list, so a faction's
     * old leader role loses access when it changes. Only used on channels whose access the bot owns.
     */
    private void applyOverridesExactly(TextChannel channel, List<Override> overrides) {
        Set<Long> keep = new HashSet<>();
        for (Override o : overrides) {
            keep.add(o.holder().getIdLong());
        }
        TextChannelManager manager = channel.getManager();
        for (var existing : channel.getRolePermissionOverrides()) {
            if (!keep.contains(existing.getIdLong())) {
                manager = manager.removePermissionOverride(existing.getIdLong());
            }
        }
        for (Override o : overrides) {
            manager = manager.putPermissionOverride(o.holder(), o.allow(), o.deny());
        }
        manager.complete();
    }

    private void applyOverrides(TextChannel channel, List<Override> overrides) {
        TextChannelManager manager = channel.getManager();
        for (Override o : overrides) {
            manager = manager.putPermissionOverride(o.holder(), o.allow(), o.deny());
        }
        manager.complete();
    }

    // ------------------------------------------------------------------ messages

    private void ensureMessage(TextChannel channel, String key, Panel panel, String label, Report report) {
        Long id = ensureMessage(channel, layout.get(key), panel, label, report);
        layout.put(key, id);
    }

    /** Deletes a panel that is no longer used, if it is still there, and forgets its ID. */
    private void removeMessage(TextChannel channel, String key, String label, Report report) {
        Optional<Long> id = layout.get(key);
        if (id.isEmpty()) {
            return;
        }
        try {
            channel.deleteMessageById(id.get()).complete();
            report.repaired().add("Removed " + label);
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                throw e;
            }
        }
        layout.remove(key);
    }

    /** Edits the stored message if it still exists, otherwise posts a new one. Returns its ID. */
    private Long ensureMessage(TextChannel channel, Optional<Long> storedId, Panel panel, String label, Report report) {
        if (storedId.isPresent()) {
            try {
                channel.editMessageById(storedId.get(), new MessageEditBuilder()
                        .setEmbeds(panel.embed()).setComponents(panel.rows()).build()).complete();
                return storedId.get();
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                    throw e;
                }
                log.info("{} was deleted; posting it again", label);
            }
        }
        long id = channel.sendMessage(new MessageCreateBuilder()
                .setEmbeds(panel.embed()).setComponents(panel.rows()).build()).complete().getIdLong();
        report.created().add(label);
        return id;
    }

    private static EnumSet<Permission> none() {
        return EnumSet.noneOf(Permission.class);
    }

    /** Small helper kept for readability in callers that build reports. */
    static String summarise(Report r) {
        Map<String, List<String>> parts = new LinkedHashMap<>();
        parts.put("Created", r.created());
        parts.put("Repaired", r.repaired());
        StringBuilder sb = new StringBuilder();
        parts.forEach((title, items) -> {
            if (!items.isEmpty()) {
                sb.append("**").append(title).append(":** ").append(String.join(", ", items)).append('\n');
            }
        });
        if (sb.isEmpty() && r.problems().isEmpty()) {
            sb.append("Everything was already in place; permissions and panels refreshed.\n");
        }
        Consumer<String> problem = p -> sb.append("⚠️ ").append(p).append('\n');
        r.problems().forEach(problem);
        return sb.toString().trim();
    }
}
