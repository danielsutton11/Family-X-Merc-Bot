package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.request.TornLookups;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.settings.BotSettings.Payee;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Settings → Payments (scope doc 9, Payments group): payees (picked from Capacity X's leader
 * role), the mercs pool account, when invoices fall due, and the settlement period.
 */
@Component
public class PaymentSettingsHandler implements InteractionHandler {

    private final BotSettings settings;
    private final FamilyFactionRepository factions;
    private final TornLookups torn;
    private final AuditService audit;

    public PaymentSettingsHandler(BotSettings settings, FamilyFactionRepository factions, TornLookups torn,
                                  AuditService audit) {
        this.settings = settings;
        this.factions = factions;
        this.torn = torn;
        this.audit = audit;
    }

    @Override
    public String action() {
        return "payset";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "payees" -> {
                InteractionHook hook = Screen.deferInPlace(event);
                payeePicker(event.getGuild(), null).show(hook);
            }
            case "numbers" -> event.replyModal(Modal.create(CustomId.of("payset", "save",
                            Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Payment settings")
                    .addComponents(
                            Label.of("Mercs pool account (Torn ID)", TextInput.create("pool", TextInputStyle.SHORT)
                                    .setRequired(true).setMaxLength(10).setValue(Long.toString(settings.poolAccountId())).build()),
                            Label.of("Payment due within (hours)", TextInput.create("due", TextInputStyle.SHORT)
                                    .setRequired(true).setMaxLength(4).setValue(Integer.toString(settings.paymentDueHours())).build()))
                    .build()).queue();
            default -> screen(null).show(event);
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        if ("period".equals(id.sub())) {
            String before = settings.settlementPeriod();
            String after = event.getValues().getFirst();
            settings.set(BotSettings.SETTLEMENT, after, user);
            audit.log(user, "SETTING_CHANGED", "setting:" + BotSettings.SETTLEMENT, Map.of("value", before),
                    Map.of("value", after), "Changed the settlement period to **" + label(after) + "**");
            screen("✅ Saved.").show(event);
            return;
        }
        // payees
        InteractionHook hook = Screen.deferInPlace(event);
        List<Payee> chosen = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String v : event.getValues()) {
            long discordId = Long.parseLong(v);
            try {
                var tornId = torn.tornIdForDiscord(discordId);
                if (tornId.isEmpty()) {
                    failed.add("<@" + discordId + ">");
                    continue;
                }
                String name = torn.player(tornId.get()).name();
                chosen.add(new Payee(tornId.get(), name, discordId));
            } catch (Exception e) {
                failed.add("<@" + discordId + ">");
            }
        }
        List<Payee> before = settings.payees();
        settings.set(BotSettings.PAYEES, chosen, user);
        audit.log(user, "SETTING_CHANGED", "setting:" + BotSettings.PAYEES, before, chosen,
                "Set the payees: " + names(chosen));
        screen("✅ Payees saved." + (failed.isEmpty() ? ""
                : "\n⚠️ Not added (no Torn account linked to their Discord): " + String.join(", ", failed))).show(hook);
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        long user = event.getUser().getIdLong();
        try {
            long pool = Long.parseLong(event.getValue("pool").getAsString().trim());
            int due = Integer.parseInt(event.getValue("due").getAsString().trim());
            if (pool <= 0 || due < 1 || due > 720) {
                throw new NumberFormatException();
            }
            String poolName = settings.poolAccountName();
            if (pool != settings.poolAccountId()) {
                try {
                    poolName = torn.player(pool).name();
                } catch (Exception e) {
                    poolName = "Player " + pool;
                }
            }
            Map<String, Object> before = Map.of("pool", settings.poolAccountId(), "dueHours", settings.paymentDueHours());
            settings.set(BotSettings.POOL_ACCOUNT, pool, user);
            settings.set(BotSettings.POOL_NAME, poolName, user);
            settings.set(BotSettings.DUE_HOURS, due, user);
            audit.log(user, "SETTING_CHANGED", "settings:payments", before, Map.of("pool", pool, "dueHours", due),
                    "Changed the payment settings: pool **" + poolName + " [" + pool + "]**, due within **" + due + " h**");
            screen("✅ Saved.").show(hook);
        } catch (Exception e) {
            screen("❌ The pool account should be a Torn ID and the due time 1 to 720 hours. Nothing was changed.").show(hook);
        }
    }

    private Screen screen(String notice) {
        List<Payee> payees = settings.payees();
        String body = (notice == null ? "" : notice + "\n\n")
                + "Payees: **" + (payees.isEmpty() ? "none set" : names(payees)) + "**\n"
                + "-# Requesters are asked to pay these in turn. Picked from Capacity X's leader role.\n"
                + "Mercs pool account: **" + settings.poolAccountName() + " [" + settings.poolAccountId() + "]**\n"
                + "Payment due within: **" + settings.paymentDueHours() + " h**\n"
                + "Settlement period: **" + label(settings.settlementPeriod()) + "**\n"
                + "-# Contracts with no end get an interim invoice at the close of each period." + Panels.GAP;
        StringSelectMenu period = StringSelectMenu.create(CustomId.of("payset", "period"))
                .addOption("Settlement: per contract", "PER_CONTRACT", "Interim invoices still go out weekly")
                .addOption("Settlement: weekly", "WEEKLY", "Closes every Monday 00:00 UTC")
                .addOption("Settlement: monthly", "MONTHLY", "Closes on the 1st, 00:00 UTC")
                .setDefaultValues(settings.settlementPeriod())
                .build();
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS")
                        .setTitle("💳  Payments").setDescription(body).build(),
                ActionRow.of(period),
                ActionRow.of(
                        Button.primary(CustomId.of("payset", "payees"), "Edit payees").withEmoji(Emoji.fromUnicode("👤")),
                        Button.secondary(CustomId.of("payset", "numbers"), "Edit numbers").withEmoji(Emoji.fromUnicode("✏️")),
                        Button.secondary(CustomId.of("settings", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    /** Capacity X leaders as a multi-pick; the current payees are pre-selected. */
    private Screen payeePicker(Guild guild, String notice) {
        Long roleId = factions.find(settings.paymentFactionId()).map(FamilyFactionRepository.FamilyFaction::leaderRoleId)
                .orElse(null);
        Role role = guild == null || roleId == null ? null : guild.getRoleById(roleId);
        if (role == null) {
            return screen("❌ Capacity X has no leader role set. Set it in Settings → Factions first.");
        }
        List<Member> leaders;
        try {
            leaders = guild.findMembersWithRoles(role).get();
        } catch (Exception e) {
            return screen("❌ Couldn't load the members of " + role.getName() + ". Try again.");
        }
        if (leaders.isEmpty()) {
            return screen("❌ Nobody has the " + role.getName() + " role.");
        }
        List<String> current = settings.payees().stream().map(p -> Long.toString(p.discordId())).toList();
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("payset", "pick"))
                .setPlaceholder("Pick the payees…").setRequiredRange(1, Math.min(25, leaders.size()));
        List<String> defaults = new ArrayList<>();
        for (Member m : leaders.stream().limit(25).toList()) {
            String v = m.getId();
            pick.addOption(m.getEffectiveName(), v, m.getUser().getName(), Emoji.fromUnicode("👤"));
            if (current.contains(v)) {
                defaults.add(v);
            }
        }
        pick.setDefaultValues(defaults);
        return Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("SETTINGS · PAYMENTS")
                        .setTitle("👤  Payees").setDescription((notice == null ? "" : notice + "\n\n")
                                + "Pick who requesters pay, from **" + role.getName() + "**. Each needs a Torn account "
                                + "linked to their Discord." + Panels.GAP).build(),
                ActionRow.of(pick.build()),
                ActionRow.of(Button.secondary(CustomId.of("payset", "view"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
    }

    private static String names(List<Payee> payees) {
        return payees.isEmpty() ? "none" : payees.stream().map(p -> p.name() + " [" + p.tornId() + "]")
                .collect(Collectors.joining(", "));
    }

    private static String label(String period) {
        return switch (period) {
            case "PER_CONTRACT" -> "Per contract";
            case "MONTHLY" -> "Monthly";
            default -> "Weekly";
        };
    }
}
