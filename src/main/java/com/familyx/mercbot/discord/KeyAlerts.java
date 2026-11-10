package com.familyx.mercbot.discord;

import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.keys.ApiKeyRepository;
import com.familyx.mercbot.keys.ApiKeyRepository.FactionKeyCount;
import com.familyx.mercbot.keys.KeyUnhealthyEvent;
import com.familyx.mercbot.settings.BotSettings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When a key fails: DM its owner, and if a faction drops below the minimum number of
 * healthy leader keys, post a card in #merc-leaders (scope doc 6.3 and section 8).
 */
@Component
public class KeyAlerts {

    private static final Logger log = LoggerFactory.getLogger(KeyAlerts.class);

    private final JDA jda;
    private final Layout layout;
    private final ApiKeyRepository keys;
    private final FamilyFactionRepository factions;
    private final BotSettings settings;
    private final LeaderPanelService leaderPanel;

    private final Dms dms;

    public KeyAlerts(JDA jda, Layout layout, ApiKeyRepository keys, FamilyFactionRepository factions,
                     BotSettings settings, LeaderPanelService leaderPanel, Dms dms) {
        this.dms = dms;
        this.jda = jda;
        this.layout = layout;
        this.keys = keys;
        this.factions = factions;
        this.settings = settings;
        this.leaderPanel = leaderPanel;
    }

    @EventListener
    public void onKeyUnhealthy(KeyUnhealthyEvent event) {
        String where = event.kind() == ApiKeyRepository.Kind.LEADER
                ? "Add a new one from **Keys** on the merc leader panel."
                : "Add a new one from **My profile** in #merc-signup.";
        // Deleted once they add a working key, or after 7 days (DM cleanup).
        dms.sendText(event.ownerDiscordId(), com.familyx.mercbot.discord.Dms.Clean.whenOr("KEY_FAILED", "owner:" + event.ownerDiscordId(), java.time.Duration.ofDays(7)),
                "⚠️ Your Torn API key stopped working for the Family X Merc Bot (" + event.reason() + "). " + where);

        if (event.kind() == ApiKeyRepository.Kind.LEADER && event.factionId() != null) {
            checkFactionMinimum(event.factionId());
        }
        leaderPanel.refresh();
    }

    private void checkFactionMinimum(long factionId) {
        int min = settings.minLeaderKeysPerFaction();
        FactionKeyCount count = keys.leaderKeyCountsByFaction().get(factionId);
        int healthy = count == null ? 0 : count.healthy();
        if (healthy >= min) {
            return;
        }
        String name = factions.find(factionId).map(FamilyFactionRepository.FamilyFaction::name)
                .orElse("Faction " + factionId);
        layout.get(Layout.CH_LEADERS).map(id -> jda.getTextChannelById(id)).ifPresent((TextChannel channel) ->
                channel.sendMessage(new MessageCreateBuilder()
                        .setEmbeds(new EmbedBuilder()
                                .setColor(Panels.WARN)
                                .setAuthor("KEY PROBLEM")
                                .setTitle(name + " is below the key minimum")
                                .setDescription(name + " has " + healthy + " healthy leader key"
                                        + (healthy == 1 ? "" : "s") + "; it needs at least " + min
                                        + ". Attack tracking and payment checks for this faction may stall.")
                                .build())
                        .setComponents(ActionRow.of(Button.primary(CustomId.of("keys", "view"), "Open Keys")))
                        .build()).queue());
    }
}
