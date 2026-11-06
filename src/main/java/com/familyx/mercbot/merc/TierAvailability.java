package com.familyx.mercbot.merc;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.PanelRefresher;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Auto-disable empty tiers" (scope doc 5.2): a tier nobody holds is hidden from the request
 * panel until someone qualifies, and each Merc Manager gets a DM when that happens.
 */
@Service
public class TierAvailability {

    private final TierRepository tiers;
    private final RoleSync roles;
    private final BotSettings settings;
    private final PanelRefresher panels;
    private final AuditService audit;
    private final Dms dms;
    private final JDA jda;
    private final BotProperties props;

    public TierAvailability(TierRepository tiers, RoleSync roles, BotSettings settings, PanelRefresher panels,
                            AuditService audit, Dms dms, JDA jda, BotProperties props) {
        this.tiers = tiers;
        this.roles = roles;
        this.settings = settings;
        this.panels = panels;
        this.audit = audit;
        this.dms = dms;
        this.jda = jda;
        this.props = props;
    }

    /** Re-works out which tiers are hidden; tells Merc Managers about any change. */
    public synchronized void evaluate() {
        boolean autoDisable = settings.autoDisableEmptyTiers();
        Map<Integer, Integer> holders = roles.holderCounts();
        List<String> hidden = new ArrayList<>();
        List<String> restored = new ArrayList<>();
        for (Tier t : tiers.findActive()) {
            boolean shouldHide = autoDisable && holders.getOrDefault(t.id(), 0) == 0;
            if (shouldHide == t.autoHidden()) {
                continue;
            }
            tiers.setAutoHidden(t.id(), shouldHide);
            (shouldHide ? hidden : restored).add(t.name());
            audit.log(null, shouldHide ? "TIER_AUTO_HIDDEN" : "TIER_AUTO_RESTORED", "tier:" + t.id(), null, null,
                    shouldHide ? "Hid **" + t.name() + "** from the request panel (no mercs hold it)"
                            : "Restored **" + t.name() + "** to the request panel");
        }
        if (hidden.isEmpty() && restored.isEmpty()) {
            return;
        }
        panels.tiersChanged();
        if (autoDisable) {
            notifyManagers(hidden, restored);
        }
    }

    private void notifyManagers(List<String> hidden, List<String> restored) {
        StringBuilder text = new StringBuilder();
        if (!hidden.isEmpty()) {
            text.append("💤 Hidden from the request panel because nobody holds the role: **")
                    .append(String.join(", ", hidden)).append("**.\n");
        }
        if (!restored.isEmpty()) {
            text.append("✅ Back on the request panel now a merc qualifies: **")
                    .append(String.join(", ", restored)).append("**.\n");
        }
        if (!hidden.isEmpty()) {
            text.append("-# Hidden tiers come back automatically as soon as a merc qualifies.");
        }
        String title = hidden.isEmpty()
                ? "✅  " + String.join(", ", restored) + (restored.size() == 1 ? " is" : " are") + " back on the request panel"
                : "💤  " + String.join(", ", hidden) + (hidden.size() == 1 ? " is" : " are") + " hidden from the request panel";
        var embed = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERC MANAGERS").setTitle(title)
                .setDescription(text.toString().trim()).build();

        Guild guild = jda.getGuildById(props.guildId());
        Role managers = guild == null ? null : guild.getRoleById(props.mercManagerRoleId());
        if (managers == null) {
            return;
        }
        guild.findMembersWithRoles(managers).onSuccess(members ->
                members.forEach(m -> dms.send(m.getIdLong(), embed)));
    }
}
