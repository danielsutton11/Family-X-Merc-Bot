package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.LeaderPanelService;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.discord.setup.SetupService;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.torn.TornKeyPool;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Settings → Factions: which factions are in Family X, and which Discord role leads each
 * one (that role sees and handles its faction's payouts). All steps update one private message.
 */
@Component
public class FactionsHandler implements InteractionHandler {

    /** Capacity X is the home faction and can't be removed. */
    private static final long HOME_FACTION = 9357;

    private final FamilyFactionRepository factions;
    private final TornKeyPool pool;
    private final AuditService audit;
    private final SetupService setup;
    private final LeaderPanelService leaderPanel;

    public FactionsHandler(FamilyFactionRepository factions, TornKeyPool pool, AuditService audit,
                           SetupService setup, LeaderPanelService leaderPanel) {
        this.factions = factions;
        this.pool = pool;
        this.audit = audit;
        this.setup = setup;
        this.leaderPanel = leaderPanel;
    }

    @Override
    public String action() {
        return "factions";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "view" -> list(null).show(event);
            case "add" -> event.replyModal(Modal.create(CustomId.of("factions", "addsubmit"), "Add a family faction")
                    .addComponents(
                            ActionRow.of(TextInput.create("id", "Torn faction ID", TextInputStyle.SHORT)
                                    .setRequired(true).setMaxLength(10).setPlaceholder("e.g. 9357").build()),
                            ActionRow.of(TextInput.create("name", "Name (looked up if left blank)", TextInputStyle.SHORT)
                                    .setRequired(false).setMaxLength(50).build()))
                    .build()).queue();
            case "role" -> picker("Set a leader role", "Which faction's leader role do you want to set?",
                    "pickrole", false).show(event);
            case "remove" -> picker("Remove a faction", "Which faction should leave Family X? Its history is kept.",
                    "doremove", true).show(event);
            default -> list("Unknown option.").show(event);
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        String rawId = event.getValue("id").getAsString().trim();
        long factionId;
        try {
            factionId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            list("❌ \"" + rawId + "\" isn't a faction ID.").show(hook);
            return;
        }
        String name = event.getValue("name") == null ? "" : event.getValue("name").getAsString().trim();
        if (name.isBlank()) {
            name = lookupName(factionId);
        }
        if (name == null || name.isBlank()) {
            list("❌ Couldn't look up faction " + factionId + "'s name (no keys yet?). Add it again and type the name.")
                    .show(hook);
            return;
        }
        factions.upsert(factionId, name);
        audit.log(event.getUser().getIdLong(), "FACTION_ADDED", "family_faction:" + factionId, null,
                Map.of("name", name), "Added **" + name + "** [" + factionId + "] to Family X");
        if (event.getGuild() != null) {
            setup.syncPayouts(event.getGuild());
        }
        leaderPanel.refresh();
        rolePicker(factionId, "✅ Added **" + name + "**. Now pick its leader role.").show(hook);
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        long factionId = Long.parseLong(event.getValues().getFirst());
        switch (id.sub()) {
            case "pickrole" -> rolePicker(factionId, null).show(event);
            case "doremove" -> {
                if (factionId == HOME_FACTION) {
                    list("Capacity X is the home faction and can't be removed.").show(event);
                    return;
                }
                String name = nameOf(factionId);
                factions.deactivate(factionId);
                audit.log(event.getUser().getIdLong(), "FACTION_REMOVED", "family_faction:" + factionId,
                        Map.of("name", name), null, "Removed **" + name + "** from Family X");
                if (event.getGuild() != null) {
                    setup.syncPayouts(event.getGuild());
                }
                leaderPanel.refresh();
                list("✅ Removed **" + name + "**. Its leader keys stay stored but are no longer counted.").show(event);
            }
            default -> list("Unknown option.").show(event);
        }
    }

    @Override
    public void entitySelect(EntitySelectInteractionEvent event, CustomId id) {
        long factionId = id.longArg(0);
        Role role = event.getMentions().getRoles().getFirst();
        Long before = factions.find(factionId).map(FamilyFaction::leaderRoleId).orElse(null);
        factions.setLeaderRole(factionId, role.getIdLong());
        String name = nameOf(factionId);
        audit.log(event.getUser().getIdLong(), "FACTION_LEADER_ROLE", "family_faction:" + factionId,
                before == null ? null : Map.of("role", before), Map.of("role", role.getIdLong()),
                "Set " + name + "'s leader role to " + role.getAsMention());
        if (event.getGuild() != null) {
            setup.syncPayouts(event.getGuild());
        }
        list("✅ " + role.getAsMention() + " now leads **" + name + "** and can see #merc-payouts.").show(event);
    }

    // ------------------------------------------------------------------ screens

    private Screen list(String notice) {
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setTitle("Family X factions");
        if (notice != null) {
            eb.setDescription(notice);
        }
        for (FamilyFaction f : factions.findActive()) {
            eb.addField(f.name() + " [" + f.tornFactionId() + "]",
                    f.leaderRoleId() == null ? "⚠️ No leader role set" : "Leaders: <@&" + f.leaderRoleId() + ">",
                    false);
        }
        return Screen.of(eb.build(),
                ActionRow.of(
                        Button.primary(CustomId.of("factions", "add"), "Add faction"),
                        Button.secondary(CustomId.of("factions", "role"), "Set leader role"),
                        Button.danger(CustomId.of("factions", "remove"), "Remove faction")),
                ActionRow.of(Button.secondary(CustomId.of("settings", "view"), "← Settings")));
    }

    private Screen picker(String title, String prompt, String sub, boolean excludeHome) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("factions", sub))
                .setPlaceholder("Choose a faction");
        for (FamilyFaction f : factions.findActive()) {
            if (excludeHome && f.tornFactionId() == HOME_FACTION) {
                continue;
            }
            menu.addOption(f.name(), Long.toString(f.tornFactionId()));
        }
        if (menu.getOptions().isEmpty()) {
            return list("There are no factions to choose from.");
        }
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setTitle(title).setDescription(prompt).build(),
                ActionRow.of(menu.build()),
                ActionRow.of(Button.secondary(CustomId.of("factions", "view"), "← Back")));
    }

    private Screen rolePicker(long factionId, String notice) {
        String prompt = (notice == null ? "" : notice + "\n\n") + "Pick the Discord role that leads **"
                + nameOf(factionId) + "**. It will see and handle that faction's payouts.";
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setTitle("Leader role").setDescription(prompt).build(),
                ActionRow.of(EntitySelectMenu.create(CustomId.of("factions", "setrole", factionId),
                                EntitySelectMenu.SelectTarget.ROLE)
                        .setPlaceholder("Choose the leader role").setRequiredRange(1, 1).build()),
                ActionRow.of(Button.secondary(CustomId.of("factions", "view"), "← Back")));
    }

    private String nameOf(long factionId) {
        return factions.find(factionId).map(FamilyFaction::name).orElse("Faction " + factionId);
    }

    private String lookupName(long factionId) {
        try {
            return pool.call(TornKeyPool.Scope.any(), "/faction/" + factionId + "/basic", Map.of())
                    .path("basic").path("name").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
