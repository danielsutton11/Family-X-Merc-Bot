package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.LeaderPanelService;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.keys.ApiKeyRepository;
import com.familyx.mercbot.keys.ApiKeyRepository.FactionKeyCount;
import com.familyx.mercbot.keys.ApiKeyRepository.Kind;
import com.familyx.mercbot.keys.ApiKeyRepository.StoredKey;
import com.familyx.mercbot.keys.ApiKeyService;
import com.familyx.mercbot.keys.ApiKeyService.AddResult;
import com.familyx.mercbot.settings.BotSettings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Leader panel → Keys (scope doc 6.1 and 10.2): per-faction key health, adding your own key,
 * removing your keys. Key values are never shown back. All steps update one private message.
 */
@Component
public class KeysHandler implements InteractionHandler {

    static final String KEY_USE_STATEMENT = """
            **How your key is used**
            • **Access:** a Limited key with faction API access.
            • **Used for:** your faction's attacks, news, balance, upgrades and members, public lookups of \
            contract targets, and BSP estimates if your key has a BSP subscription.
            • **Stored:** encrypted; never shown back to anyone.
            • **Shared with:** BSP's server, only if your key has an active BSP subscription.
            • **Removed:** any time, with *Remove my keys* below.""";

    private final ApiKeyService keyService;
    private final ApiKeyRepository repo;
    private final FamilyFactionRepository factions;
    private final BotSettings settings;
    private final AuditService audit;
    private final LeaderPanelService leaderPanel;

    public KeysHandler(ApiKeyService keyService, ApiKeyRepository repo, FamilyFactionRepository factions,
                       BotSettings settings, AuditService audit, LeaderPanelService leaderPanel) {
        this.keyService = keyService;
        this.repo = repo;
        this.factions = factions;
        this.settings = settings;
        this.audit = audit;
        this.leaderPanel = leaderPanel;
    }

    @Override
    public String action() {
        return "keys";
    }

    @Override
    public Access.Level access() {
        return Access.Level.FAMILY_LEADER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "view" -> view(user, null).show(event);
            case "add" -> event.replyModal(Modal.create(CustomId.of("keys", "submit"), "Add your leader API key")
                    .addComponents(ActionRow.of(TextInput.create("key", "Torn API key", TextInputStyle.SHORT)
                            .setMinLength(16).setMaxLength(16).setRequired(true)
                            .setPlaceholder("16 letters and numbers").build()))
                    .build()).queue();
            case "remove" -> {
                int count = repo.findByOwner(user, Kind.LEADER).size();
                if (count == 0) {
                    view(user, "You haven't added any leader keys.").show(event);
                    return;
                }
                Screen.of(new EmbedBuilder().setColor(Panels.WARN).setTitle("Remove your keys?")
                                .setDescription("Remove your " + count + " leader key" + (count == 1 ? "" : "s")
                                        + "? The bot stops using them straight away.").build(),
                        ActionRow.of(
                                Button.danger(CustomId.of("keys", "removeconfirm"), "Remove"),
                                Button.secondary(CustomId.of("keys", "view"), "Cancel")))
                        .show(event);
            }
            case "removeconfirm" -> {
                List<StoredKey> before = repo.findByOwner(user, Kind.LEADER);
                int removed = keyService.removeLeaderKeys(user);
                audit.log(user, "LEADER_KEY_REMOVED", "api_key", Map.of("count", before.size()), null,
                        "Removed " + removed + " leader key" + (removed == 1 ? "" : "s"));
                leaderPanel.refresh();
                view(user, "✅ Removed " + removed + " key" + (removed == 1 ? "" : "s") + ".").show(event);
            }
            default -> view(user, "Unknown option.").show(event);
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        String key = event.getValue("key") == null ? "" : event.getValue("key").getAsString();
        AddResult result = keyService.addLeaderKey(key, user);
        String notice = switch (result) {
            case AddResult.Rejected r -> "❌ " + r.reason();
            case AddResult.Added a -> {
                audit.log(user, "LEADER_KEY_ADDED", "api_key:" + a.keyId(), null,
                        Map.of("faction", a.faction().tornFactionId(), "bsp", a.bspActive()),
                        "Added a leader key for " + a.faction().name() + (a.bspActive() ? " (BSP active)" : ""));
                leaderPanel.refresh();
                yield "✅ Added your key for **" + a.faction().name() + "** (" + a.tornName() + "). "
                        + "BSP subscription: " + (a.bspActive() ? "**active**" : "not active") + ".";
            }
        };
        view(user, notice).show(hook);
    }

    private Screen view(long user, String notice) {
        int min = settings.minLeaderKeysPerFaction();
        Map<Long, FactionKeyCount> counts = repo.leaderKeyCountsByFaction();
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setAuthor("MERC MANAGERS")
                .setTitle("🔑  Leader API keys");
        int tiles = 0;
        for (FamilyFaction f : factions.findActive()) {
            FactionKeyCount c = counts.getOrDefault(f.tornFactionId(), new FactionKeyCount(f.tornFactionId(), 0, 0, 0));
            String dot = c.healthy() >= min ? "🟢" : c.healthy() > 0 ? "🟠" : "🔴";
            eb.addField(dot + "  " + f.name(), "**" + c.healthy() + " / " + min + "** keys"
                    + (c.total() > c.healthy() ? "\n-# " + (c.total() - c.healthy()) + " not working" : "")
                    + "\n-# " + (c.bsp() > 0 ? c.bsp() + " with BSP" : "no BSP"), true);
            tiles++;
        }
        for (int i = tiles % 3; i != 0 && i < 3; i++) {
            eb.addBlankField(true);
        }
        List<StoredKey> mine = repo.findByOwner(user, Kind.LEADER);
        String yours = mine.isEmpty() ? "You haven't added a key." : mine.stream()
                .map(k -> (k.healthy() ? "✅ working" : "❌ " + k.unhealthyReason()) + (k.bspActive() ? " · BSP" : ""))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        eb.addField("👤  Your keys", yours, false);
        eb.setDescription((notice == null ? "" : notice + "\n\n") + KEY_USE_STATEMENT);
        return Screen.of(eb.build(), ActionRow.of(
                Button.primary(CustomId.of("keys", "add"), "Add my key").withEmoji(Emoji.fromUnicode("➕")),
                Button.danger(CustomId.of("keys", "remove"), "Remove my keys").withEmoji(Emoji.fromUnicode("🗑️"))));
    }
}
