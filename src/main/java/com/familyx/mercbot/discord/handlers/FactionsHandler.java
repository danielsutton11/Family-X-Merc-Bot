package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.LeaderPanelService;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.setup.SetupService;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.torn.TornKeyPool;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Settings → Factions: which factions are in Family X, and which Discord role leads each
 * one (that role sees and handles its faction's payouts).
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
            case "view" -> event.replyEmbeds(view()).setComponents(buttons()).setEphemeral(true).queue();
            case "add" -> event.replyModal(Modal.create(CustomId.of("factions", "addsubmit"), "Add a family faction")
                    .addComponents(
                            ActionRow.of(TextInput.create("id", "Torn faction ID", TextInputStyle.SHORT)
                                    .setRequired(true).setMaxLength(10).setPlaceholder("e.g. 9357").build()),
                            ActionRow.of(TextInput.create("name", "Name (looked up if left blank)", TextInputStyle.SHORT)
                                    .setRequired(false).setMaxLength(50).build()))
                    .build()).queue();
            case "role" -> event.reply("Which faction's leader role do you want to set?")
                    .setComponents(ActionRow.of(factionPicker("pickrole", false))).setEphemeral(true).queue();
            case "remove" -> event.reply("Which faction should leave Family X? Its history is kept.")
                    .setComponents(ActionRow.of(factionPicker("doremove", true))).setEphemeral(true).queue();
            default -> event.reply("Unknown option.").setEphemeral(true).queue();
        }
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        event.deferReply(true).queue();
        String rawId = event.getValue("id").getAsString().trim();
        long factionId;
        try {
            factionId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            event.getHook().editOriginal("❌ \"" + rawId + "\" isn't a faction ID.").queue();
            return;
        }
        String name = event.getValue("name") == null ? "" : event.getValue("name").getAsString().trim();
        if (name.isBlank()) {
            name = lookupName(factionId);
        }
        if (name == null || name.isBlank()) {
            event.getHook().editOriginal("❌ Couldn't look up that faction's name (no keys yet?). "
                    + "Add it again and type the name.").queue();
            return;
        }
        factions.upsert(factionId, name);
        audit.log(event.getUser().getIdLong(), "FACTION_ADDED", "family_faction:" + factionId, null,
                Map.of("name", name), "Added **" + name + "** [" + factionId + "] to Family X");
        if (event.getGuild() != null) {
            setup.syncPayouts(event.getGuild());
        }
        leaderPanel.refresh();
        event.getHook().editOriginal("✅ Added **" + name + "**. Now pick its leader role:")
                .setComponents(ActionRow.of(rolePicker(factionId))).queue();
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        long factionId = Long.parseLong(event.getValues().getFirst());
        switch (id.sub()) {
            case "pickrole" -> event.editMessage("Pick the leader role:")
                    .setComponents(ActionRow.of(rolePicker(factionId))).queue();
            case "doremove" -> {
                if (factionId == HOME_FACTION) {
                    event.editMessage("Capacity X is the home faction and can't be removed.").setComponents().queue();
                    return;
                }
                String name = factions.find(factionId).map(FamilyFaction::name).orElse("Faction " + factionId);
                factions.deactivate(factionId);
                audit.log(event.getUser().getIdLong(), "FACTION_REMOVED", "family_faction:" + factionId,
                        Map.of("name", name), null, "Removed **" + name + "** from Family X");
                if (event.getGuild() != null) {
                    setup.syncPayouts(event.getGuild());
                }
                leaderPanel.refresh();
                event.editMessage("Removed **" + name + "**. Its leader keys stay stored but are no longer counted.")
                        .setComponents().queue();
            }
            default -> event.reply("Unknown option.").setEphemeral(true).queue();
        }
    }

    @Override
    public void entitySelect(EntitySelectInteractionEvent event, CustomId id) {
        long factionId = id.longArg(0);
        Role role = event.getMentions().getRoles().getFirst();
        Long before = factions.find(factionId).map(FamilyFaction::leaderRoleId).orElse(null);
        factions.setLeaderRole(factionId, role.getIdLong());
        String name = factions.find(factionId).map(FamilyFaction::name).orElse("Faction " + factionId);
        audit.log(event.getUser().getIdLong(), "FACTION_LEADER_ROLE", "family_faction:" + factionId,
                before == null ? null : Map.of("role", before), Map.of("role", role.getIdLong()),
                "Set " + name + "'s leader role to " + role.getAsMention());
        if (event.getGuild() != null) {
            setup.syncPayouts(event.getGuild());
        }
        event.editMessage("✅ " + role.getAsMention() + " now leads **" + name + "** and can see #merc-payouts.")
                .setComponents().queue();
    }

    private MessageEmbed view() {
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.LEADER).setTitle("Family X factions");
        for (FamilyFaction f : factions.findActive()) {
            eb.addField(f.name() + " [" + f.tornFactionId() + "]",
                    f.leaderRoleId() == null ? "⚠️ No leader role set" : "Leaders: <@&" + f.leaderRoleId() + ">",
                    false);
        }
        return eb.build();
    }

    private ActionRow buttons() {
        return ActionRow.of(
                Button.primary(CustomId.of("factions", "add"), "Add faction"),
                Button.secondary(CustomId.of("factions", "role"), "Set leader role"),
                Button.danger(CustomId.of("factions", "remove"), "Remove faction"));
    }

    private StringSelectMenu factionPicker(String sub, boolean excludeHome) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("factions", sub))
                .setPlaceholder("Choose a faction");
        List<FamilyFaction> list = factions.findActive();
        for (FamilyFaction f : list) {
            if (excludeHome && f.tornFactionId() == HOME_FACTION) {
                continue;
            }
            menu.addOption(f.name(), Long.toString(f.tornFactionId()));
        }
        if (menu.getOptions().isEmpty()) {
            menu.addOption("No factions to choose", "0").setDisabled(true);
        }
        return menu.build();
    }

    private EntitySelectMenu rolePicker(long factionId) {
        return EntitySelectMenu.create(CustomId.of("factions", "setrole", factionId), EntitySelectMenu.SelectTarget.ROLE)
                .setPlaceholder("Choose the leader role").setRequiredRange(1, 1).build();
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
