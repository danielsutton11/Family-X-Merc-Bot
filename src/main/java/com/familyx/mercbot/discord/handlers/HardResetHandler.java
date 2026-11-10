package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.reset.HardResetService;
import com.familyx.mercbot.reset.HardResetService.Group;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings → Hard reset (a leader tool for wiping test data). Step 1 previews exactly what goes;
 * step 2 asks for a typed phrase, a different one when real money would be wiped. Merc Managers
 * only. The picked groups travel in the custom ids as a short code, e.g. "CRD".
 */
@Component
public class HardResetHandler implements InteractionHandler {

    public static final String PHRASE = "RESET MERC BOT";
    public static final String MONEY_PHRASE = "RESET WITH MONEY";
    private static final Set<Group> DEFAULT = EnumSet.of(Group.CONTRACTS, Group.REQUESTERS, Group.DMS);

    private final HardResetService service;

    public HardResetHandler(HardResetService service) {
        this.service = service;
    }

    @Override
    public String action() {
        return "reset";
    }

    @Override
    public Access.Level access() {
        return Access.Level.MERC_MANAGER;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "view" -> slow(event, () -> preview(DEFAULT, null));
            case "go" -> {
                Set<Group> groups = decode(id.arg(0));
                boolean money = service.preview(HardResetService.normalise(groups)).money();
                String phrase = money ? MONEY_PHRASE : PHRASE;
                event.replyModal(Modal.create(CustomId.of("reset", "confirm", id.arg(0),
                                        Long.toString(System.nanoTime() % 1_000_000_000L, 36)), "Hard reset · step 2 of 2")
                        .addComponents(Label.of("Type " + phrase + " to confirm",
                                TextInput.create("phrase", TextInputStyle.SHORT).setRequired(true).setMaxLength(30)
                                        .setPlaceholder(phrase).build()))
                        .build()).queue();
            }
            case "cancel" -> slow(event, () -> Screen.of(new EmbedBuilder().setColor(Panels.LEADER).setAuthor("HARD RESET")
                    .setTitle("Cancelled").setDescription("Nothing was deleted.").build()));
            default -> slow(event, () -> preview(DEFAULT, null));
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        Set<Group> picked = event.getValues().stream().map(Group::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Group.class)));
        slow(event, () -> preview(picked, null));
    }

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        Set<Group> groups = HardResetService.normalise(decode(id.arg(0)));
        boolean money = service.preview(groups).money();
        String typed = event.getValue("phrase") == null ? "" : event.getValue("phrase").getAsString().strip();
        String needed = money ? MONEY_PHRASE : PHRASE;
        if (!typed.equalsIgnoreCase(needed)) {
            preview(groups, "❌ That didn't match **" + needed + "**, so nothing was deleted.").show(hook);
            return;
        }
        Map<String, Long> done = service.run(groups, event.getUser().getIdLong());
        String summary = done.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        Screen.of(new EmbedBuilder().setColor(Panels.GOOD).setAuthor("HARD RESET").setTitle("✅  Reset complete")
                .setDescription((summary.isEmpty() ? "Nothing needed deleting." : "Deleted " + summary + ".")
                        + (groups.contains(Group.CONTRACTS) ? " Contract numbers restart at MC-0001." : "")
                        + "\n\nThe Discord tidy-up is running and can take a few minutes"
                        + (groups.contains(Group.DMS) ? ", longer with lots of DMs" : "") + ". Boards, payouts and the "
                        + "leaderboard redraw on their own.").build()).show(hook);
    }

    // ------------------------------------------------------------------ screens

    private Screen preview(Set<Group> picked, String notice) {
        Set<Group> groups = HardResetService.normalise(picked);
        HardResetService.Preview p = service.preview(groups);
        StringBuilder t = new StringBuilder("```\n");
        for (Group g : Group.values()) {
            t.append(groups.contains(g) ? "✅ " : "⬜ ").append(g.label).append('\n');
        }
        t.append("```");
        StringBuilder d = new StringBuilder(notice == null ? "" : notice + "\n\n");
        d.append(t);
        if (!p.counts().isEmpty()) {
            d.append("\n**Deleted:** ").append(p.counts().entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                    .collect(Collectors.joining(" · ")));
        }
        if (groups.contains(Group.CONTRACTS)) {
            d.append("\nContract numbers restart at MC-0001.");
        }
        if (groups.contains(Group.DMS)) {
            d.append("\n**DMs:** every message the bot sent to ").append(p.dmPeople()).append(p.dmPeople() == 1 ? " person" : " people")
                    .append(", any age, including invoices and receipts.");
        }
        if (p.money()) {
            d.append("\n\n⚠️ **Includes real money:** ").append(p.paidInvoices()).append(p.paidInvoices() == 1 ? " invoice" : " invoices")
                    .append(" with payments (").append(Money.full(p.paidAmount())).append(") and ").append(Money.full(p.credited()))
                    .append(" of credited shares. You'll need to type **").append(MONEY_PHRASE).append("**.");
        }
        d.append("\n\n-# ").append(groups.contains(Group.CONTRACTS) ? "Discord: the results feed, inbox cards, cards in the "
                + "leaders channel and payout pings from the last 14 days go too. " : "")
                .append("Settings, tiers, prices, factions, leader keys, payees and channels stay. Take a Railway "
                        + "Postgres backup first if you might want anything back.");
        Map<Group, Long> sizes = service.sizes();
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("reset", "pick")).setPlaceholder("Pick what to wipe…")
                .setRequiredRange(1, Group.values().length);
        for (Group g : Group.values()) {
            String desc = switch (g) {
                case CONTRACTS -> sizes.get(g) + " contracts, with hits, invoices and payouts";
                case REQUESTERS -> sizes.get(g) + " requesters (needs Contracts and money)";
                case MERCS -> sizes.get(g) + " mercs; roles removed (needs Contracts and money)";
                case AUDIT -> sizes.get(g) + " entries; the audit channel stays";
                case DMS -> "to " + sizes.get(g) + " people, any age";
            };
            pick.addOption(g.label, g.name(), desc);
        }
        pick.setDefaultValues(groups.stream().map(Group::name).toList());
        boolean any = !groups.isEmpty();
        Button go = Button.danger(CustomId.of("reset", "go", encode(groups)), "Continue…").withEmoji(Emoji.fromUnicode("🧨"));
        return Screen.of(new EmbedBuilder().setColor(Panels.BAD).setAuthor("HARD RESET · STEP 1 OF 2")
                        .setTitle("🧨  This will permanently delete").setDescription(d.toString()).build(),
                ActionRow.of(pick.build()),
                ActionRow.of(any ? go : go.asDisabled(), Button.secondary(CustomId.of("reset", "cancel"), "Cancel")));
    }

    private interface Work {
        Screen build();
    }

    private static void slow(net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent event,
                             Work work) {
        if (event.getMessage().isEphemeral()) {
            InteractionHook hook = Screen.deferInPlace(event);
            work.build().show(hook);
        } else {
            work.build().show(event);
        }
    }

    /** "CRMAD"-style code: one letter per group, so the choice fits in a custom id. */
    static String encode(Set<Group> groups) {
        String s = groups.stream().map(g -> g == Group.MERCS ? "M" : g.name().substring(0, 1)).collect(Collectors.joining());
        return s.isEmpty() ? "-" : s;
    }

    static Set<Group> decode(String code) {
        Set<Group> out = EnumSet.noneOf(Group.class);
        for (char c : code.toCharArray()) {
            Arrays.stream(Group.values()).filter(g -> (g == Group.MERCS ? 'M' : g.name().charAt(0)) == c).findFirst()
                    .ifPresent(out::add);
        }
        return out;
    }
}
