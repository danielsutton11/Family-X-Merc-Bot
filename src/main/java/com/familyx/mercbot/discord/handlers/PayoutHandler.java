package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.Access;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.payout.PayoutEmbeds;
import com.familyx.mercbot.payout.PayoutRepository;
import com.familyx.mercbot.payout.PayoutRepository.Line;
import com.familyx.mercbot.payout.PayoutRepository.Payout;
import com.familyx.mercbot.payout.PayoutRepository.Settlement;
import com.familyx.mercbot.payout.PayoutService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The payouts channel (scope doc 6.6): Generate payout string, Mark credited…, Settle with a
 * faction (Capacity X's leaders) and Mark received (the receiving faction's leaders). Each
 * faction's buttons work only for that faction's leader role.
 */
@Component
public class PayoutHandler implements InteractionHandler {

    private final PayoutService service;
    private final PayoutRepository repo;
    private final PayoutEmbeds embeds;
    private final Access access;

    public PayoutHandler(PayoutService service, PayoutRepository repo, PayoutEmbeds embeds, Access access) {
        this.service = service;
        this.repo = repo;
        this.embeds = embeds;
        this.access = access;
    }

    @Override
    public String action() {
        return "payout";
    }

    @Override
    public Access.Level access() {
        return Access.Level.FAMILY_LEADER;
    }

    /** Only this faction's leaders; anyone else gets a short private note. */
    private boolean leaderOf(GenericComponentInteractionCreateEvent event, long faction) {
        if (access.isLeaderOf(event.getMember(), faction)) {
            return true;
        }
        event.reply("Only " + embeds.name(faction) + " leaders can do this.").setEphemeral(true)
                .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        return false;
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "gen" -> {
                long faction = id.longArg(0);
                if (!leaderOf(event, faction)) {
                    return;
                }
                Optional<Payout> p = service.generate(faction, user);
                if (p.isEmpty()) {
                    note(event, "Nothing is owed to " + embeds.name(faction) + "'s mercs right now.");
                    return;
                }
                payoutScreen(p.get(), null).show(event);
            }
            case "show" -> withPayout(event, id, p -> payoutScreen(p, null).show(event));
            case "allc" -> withPayout(event, id, p -> {
                long left = repo.lines(p.id()).stream().filter(l -> l.creditedAt() == null).count();
                Screen.of(new EmbedBuilder().setColor(Panels.WARN).setAuthor(author(p))
                                .setTitle("✏️  Mark all " + left + " remaining credited?")
                                .setDescription("Only do this if the payment script has run. The bot normally marks "
                                        + "each merc credited itself once their line shows in faction news.").build(),
                        ActionRow.of(Button.success(CustomId.of("payout", "allcyes", p.id()), "Mark all credited")
                                        .withEmoji(Emoji.fromUnicode("✅")),
                                Button.secondary(CustomId.of("payout", "show", p.id()), "Back")
                                        .withEmoji(Emoji.fromUnicode("↩️")))).show(event);
            });
            case "allcyes" -> withPayout(event, id, p -> {
                List<Long> ids = repo.lines(p.id()).stream().filter(l -> l.creditedAt() == null).map(Line::mercId).toList();
                int n = service.creditByHand(p, ids, user);
                payoutScreen(repo.payout(p.id()).orElse(p), "✅ Marked " + n + (n == 1 ? " merc" : " mercs")
                        + " credited. They've been told.").show(event);
            });
            case "cancel" -> withPayout(event, id, p -> {
                if (!"OPEN".equals(p.status())) {
                    payoutScreen(p, null).show(event);
                    return;
                }
                service.cancel(p, user);
                Screen.of(new EmbedBuilder().setColor(Panels.PRIVATE).setAuthor(author(p))
                        .setTitle("✖️  Payout string cancelled")
                        .setDescription("Uncredited shares are back in Owed. Press **Generate payout string** for a "
                                + "new one.").build()).show(event);
            });
            case "credit" -> {
                long faction = id.longArg(0);
                if (!leaderOf(event, faction)) {
                    return;
                }
                Optional<Payout> p = repo.openPayout(faction);
                if (p.isEmpty()) {
                    note(event, "Generate a payout string first. Credits are marked against its lines.");
                    return;
                }
                creditScreen(p.get(), null).show(event);
            }
            case "recv" -> {
                Optional<Settlement> s = repo.settlement(id.longArg(0));
                if (s.isEmpty()) {
                    note(event, "That settlement no longer exists.");
                    return;
                }
                if (!leaderOf(event, s.get().factionId())) {
                    return;
                }
                if (!service.markReceived(s.get(), user)) {
                    note(event, "This settlement has already been marked received.");
                    return;
                }
                event.reply("📥 Marked " + Money.full(s.get().total()) + " received. Press **Generate payout string** to "
                                + "credit your mercs.").setEphemeral(true)
                        .queue(h -> AutoDismiss.after(h, AutoDismiss.READING));
            }
            case "sent" -> {
                Optional<Settlement> s = repo.settlement(id.longArg(0));
                if (s.isEmpty()) {
                    note(event, "That settlement no longer exists.");
                    return;
                }
                if (!leaderOf(event, service.pool())) {
                    return;
                }
                var hook = Screen.deferInPlace(event);
                boolean done = service.markSent(s.get(), user);
                settlementScreen(repo.settlement(s.get().id()).orElse(s.get()), done
                        ? "✅ Marked sent. " + embeds.name(s.get().factionId()) + "'s leaders have been pinged."
                        : "It was already marked sent.").show(hook);
            }
            default -> note(event, "Unknown option.");
        }
    }

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        switch (id.sub()) {
            case "settle" -> {
                if (!leaderOf(event, service.pool())) {
                    return;
                }
                Optional<Settlement> s = repo.settlement(Long.parseLong(event.getValues().getFirst()));
                if (s.isEmpty()) {
                    note(event, "That settlement no longer exists.");
                    return;
                }
                settlementScreen(s.get(), null).show(event);
            }
            case "creditpick" -> withPayout(event, id, p -> {
                List<Long> ids = event.getValues().stream().map(Long::parseLong).toList();
                int n = service.creditByHand(p, ids, event.getUser().getIdLong());
                Payout now = repo.payout(p.id()).orElse(p);
                String notice = "✅ Marked " + n + (n == 1 ? " merc" : " mercs") + " credited. They've been told.";
                ("OPEN".equals(now.status()) ? creditScreen(now, notice) : payoutScreen(now, notice)).show(event);
            });
            default -> note(event, "Unknown option.");
        }
    }

    // ------------------------------------------------------------------ screens

    private Screen payoutScreen(Payout p, String notice) {
        List<Line> lines = repo.lines(p.id());
        List<Line> left = lines.stream().filter(l -> l.creditedAt() == null).toList();
        long leftTotal = left.stream().mapToLong(Line::amount).sum();
        StringBuilder d = new StringBuilder(notice == null ? "" : notice + "\n\n");
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.PRIVATE).setAuthor(author(p));
        if (!"OPEN".equals(p.status()) || left.isEmpty()) {
            eb.setTitle("✅  Payout #" + p.id() + " · all credited");
            d.append("Every merc on this string has been credited.");
            return Screen.of(eb.setDescription(d.toString()).build());
        }
        eb.setTitle("📋  " + Money.full(leftTotal) + " to " + left.size() + (left.size() == 1 ? " merc" : " mercs"));
        d.append("Paste this into the payment script:\n```\n").append(service.payoutString(p.id())).append("\n```\n")
                .append("Each merc is marked **Credited** once their line shows in faction news (checked every 5 min). "
                        + "Shares owed from now on go in the next string.");
        if (left.size() < lines.size()) {
            d.append("\n-# ").append(lines.size() - left.size()).append(" already credited, left out of the string above.");
        }
        return Screen.of(eb.setDescription(d.toString()).build(),
                ActionRow.of(Button.secondary(CustomId.of("payout", "allc", p.id()), "Mark all credited")
                                .withEmoji(Emoji.fromUnicode("✅")),
                        Button.danger(CustomId.of("payout", "cancel", p.id()), "Cancel this string")
                                .withEmoji(Emoji.fromUnicode("✖️"))));
    }

    private Screen creditScreen(Payout p, String notice) {
        List<Line> left = repo.lines(p.id()).stream().filter(l -> l.creditedAt() == null).limit(25).toList();
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("payout", "creditpick", p.id()))
                .setPlaceholder("Pick the mercs who've been credited…").setRequiredRange(1, Math.max(1, left.size()));
        for (Line l : left) {
            pick.addOption(PayoutEmbeds.who(l), Long.toString(l.mercId()), Money.full(l.amount()),
                    Emoji.fromUnicode("💰"));
        }
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.PRIVATE).setAuthor(author(p))
                .setTitle("✏️  Mark credited")
                .setDescription((notice == null ? "" : notice + "\n\n")
                        + "For credits the bot can't see in faction news, such as a different amount. "
                        + left.size() + (left.size() == 1 ? " merc is" : " mercs are") + " still waiting.");
        return left.isEmpty() ? Screen.of(eb.build()) : Screen.of(eb.build(), ActionRow.of(pick.build()));
    }

    private Screen settlementScreen(Settlement s, String notice) {
        String faction = embeds.name(s.factionId());
        List<PayoutRepository.SettlementLine> lines = repo.settlementLines(s.id());
        int width = lines.stream().limit(15).mapToInt(l -> label(l).length()).max().orElse(10);
        StringBuilder table = new StringBuilder("```\n");
        lines.stream().limit(15).forEach(l -> table.append(pad(label(l), width)).append("   ")
                .append(padLeft(Money.full(l.amount()), 12)).append("   ").append(l.hits())
                .append(l.hits() == 1 ? " hit   " : " hits  ").append(l.refs()).append('\n'));
        if (lines.size() > 15) {
            table.append("+").append(lines.size() - 15).append(" more\n");
        }
        table.append("```");
        EmbedBuilder eb = new EmbedBuilder().setColor(Panels.BRAND)
                .setAuthor(("SETTLEMENT · " + faction + " · " + embeds.period(s)).toUpperCase());
        String head = notice == null ? "" : notice + "\n\n";
        ActionRow[] rows = new ActionRow[0];
        switch (s.status()) {
            case "OWED" -> {
                eb.setTitle("🤝  Send " + Money.full(s.total()) + " to " + faction);
                eb.setDescription(head + table + "\nSend it to " + faction + ", then press **Mark sent**. " + faction
                        + "'s leaders get a ping and credit their mercs.");
                rows = new ActionRow[] {ActionRow.of(Button.success(CustomId.of("payout", "sent", s.id()), "Mark sent")
                        .withEmoji(Emoji.fromUnicode("📨")))};
            }
            case "SENT" -> {
                eb.setTitle("📨  " + Money.full(s.total()) + " sent to " + faction);
                eb.setDescription(head + table + "\nSent by <@" + s.sentBy() + "> on " + WHEN.format(s.sentAt()) + " UTC. Waiting for "
                        + faction + " to mark it received.");
            }
            default -> {
                eb.setTitle("📥  " + Money.full(s.total()) + " received by " + faction);
                eb.setDescription(head + table);
            }
        }
        return Screen.of(eb.build(), rows);
    }

    // ------------------------------------------------------------------ helpers

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.UK)
                    .withZone(java.time.ZoneOffset.UTC);

    private interface PayoutWork {
        void run(Payout p);
    }

    private void withPayout(GenericComponentInteractionCreateEvent event, CustomId id, PayoutWork work) {
        Optional<Payout> p = repo.payout(id.longArg(0));
        if (p.isEmpty()) {
            note(event, "That payout string no longer exists.");
            return;
        }
        if (leaderOf(event, p.get().factionId())) {
            work.run(p.get());
        }
    }

    private String author(Payout p) {
        return embeds.name(p.factionId()).toUpperCase() + " · PAYOUT #" + p.id();
    }

    private static String label(PayoutRepository.SettlementLine l) {
        return (l.name() == null ? "Player" : l.name()) + " [" + l.mercId() + "]";
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String padLeft(String s, int w) {
        return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
    }

    private static void note(GenericComponentInteractionCreateEvent event, String text) {
        event.reply(text).setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
    }
}
