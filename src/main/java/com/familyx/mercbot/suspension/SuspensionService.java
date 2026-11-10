package com.familyx.mercbot.suspension;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.settings.BotSettings;
import com.familyx.mercbot.suspension.SuspensionRepository.NoShow;
import com.familyx.mercbot.suspension.SuspensionRepository.State;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Claim no-show leader alerts and leader suspensions of claiming (scope doc 5.6, 6.4): the alert
 * card, Suspend claiming with a duration and reason, Lift, automatic expiry and the weekly
 * reminder for "Until turned back on".
 */
@Service
public class SuspensionService {

    private static final Logger log = LoggerFactory.getLogger(SuspensionService.class);
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    /** The suspension lengths leaders pick from. */
    public enum Length {
        H24("24 hours", Duration.ofHours(24)),
        D3("3 days", Duration.ofDays(3)),
        D7("7 days", Duration.ofDays(7)),
        INDEF("Until turned back on", null);

        public final String label;
        public final Duration duration;

        Length(String label, Duration duration) {
            this.label = label;
            this.duration = duration;
        }
    }

    private final SuspensionRepository repo;
    private final BotSettings settings;
    private final Dms dms;
    private final AuditService audit;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    public SuspensionService(SuspensionRepository repo, BotSettings settings, Dms dms, AuditService audit, Layout layout,
                             ObjectProvider<JDA> jda) {
        this.repo = repo;
        this.settings = settings;
        this.dms = dms;
        this.audit = audit;
        this.layout = layout;
        this.jda = jda;
    }

    /** "until Fri 13 Nov 14:07 UTC", or "until a leader lifts it". */
    public static String until(Instant until, boolean indefinite) {
        return indefinite || until == null || until.isAfter(Instant.parse("9000-01-01T00:00:00Z"))
                ? "until a leader lifts it" : "until " + WHEN.format(until) + " UTC";
    }

    public Optional<State> state(long mercId) {
        return repo.state(mercId);
    }

    // ------------------------------------------------------------------ the no-show alert card

    /** After each no-show: a card for leaders once the merc is over the alert setting (one card per merc). */
    public void noShowRecorded(long mercId) {
        Optional<State> s = repo.state(mercId);
        if (s.isEmpty()) {
            return;
        }
        Instant from = windowStart(s.get());
        if (repo.countSince(mercId, from) < settings.claimAlertNoShows()) {
            return;
        }
        if (s.get().cardId() != null && edit(s.get().cardId(), card(s.get()))) {
            return;
        }
        post(card(s.get())).ifPresent(id -> repo.setCard(mercId, id));
    }

    private Instant windowStart(State s) {
        Instant from = Instant.now().minus(Duration.ofHours(settings.claimAlertHours()));
        return s.ignoredAt() != null && s.ignoredAt().isAfter(from) ? s.ignoredAt() : from;
    }

    /** The card as it should look now: the no-shows with Suspend and Ignore, or the suspension with Lift. */
    public Card card(State s) {
        Instant now = Instant.now();
        String who = name(s);
        if (s.suspended(now)) {
            return new Card(new EmbedBuilder().setColor(Panels.BAD).setAuthor("CLAIM NO-SHOWS")
                    .setTitle("⛔  " + who + " · claiming suspended " + lengthLabel(s))
                    .setDescription("By " + (s.suspendedBy() == null ? "a leader" : "<@" + s.suspendedBy() + ">") + " · "
                            + until(s.suspendedUntil(), s.indefinite())
                            + (s.reason() == null ? "" : " · \"" + s.reason() + "\""))
                    .build(), List.of(ActionRow.of(Button.success(CustomId.of("noshow", "lift", s.mercId()), "Lift now")
                    .withEmoji(Emoji.fromUnicode("✅")))));
        }
        List<NoShow> list = repo.since(s.mercId(), windowStart(s), 10);
        StringBuilder table = new StringBuilder("```\n");
        int refW = list.stream().mapToInt(n -> n.ref().length()).max().orElse(7);
        int tW = Math.min(16, list.stream().mapToInt(n -> n.target().length()).max().orElse(8));
        for (NoShow n : list) {
            String target = n.target().length() > tW ? n.target().substring(0, tW - 1) + "…" : n.target();
            table.append(DAY_TIME.format(n.at())).append("  ").append(pad(n.ref(), refW)).append("  ")
                    .append(pad(target, tW)).append("  ").append(n.hitBy() == null ? "expired" : "hit by " + n.hitBy())
                    .append('\n');
        }
        table.append("```");
        int[] week = repo.weekClaims(s.mercId());
        String foot = "-# " + (s.cooldownUntil() != null && s.cooldownUntil().isAfter(now)
                ? "Automatic cooldown until " + TIME.format(s.cooldownUntil()) + " · " : "")
                + week[0] + (week[0] == 1 ? " claim" : " claims") + " this week, " + week[1] + " hit";
        StringSelectMenu.Builder pick = StringSelectMenu.create(CustomId.of("noshow", "suspend", s.mercId()))
                .setPlaceholder("Suspend claiming…");
        for (Length l : Length.values()) {
            pick.addOption(l.label, l.name(), l == Length.INDEF ? "A reminder comes every 7 days" : null,
                    Emoji.fromUnicode("⛔"));
        }
        return new Card(new EmbedBuilder().setColor(Panels.BAD).setAuthor("CLAIM NO-SHOWS")
                .setTitle("🚫  " + who + " · " + repo.countSince(s.mercId(), windowStart(s)) + " no-shows in " + settings.claimAlertHours() + " h")
                .setDescription(table + "\n" + foot).build(),
                List.of(ActionRow.of(pick.build()), ActionRow.of(Button.secondary(CustomId.of("noshow", "ignore",
                        s.mercId()), "Ignore"))));
    }

    public record Card(MessageEmbed embed, List<ActionRow> rows) { }

    public void ignore(long mercId, long by) {
        repo.state(mercId).ifPresent(s -> {
            if (s.cardId() != null) {
                delete(s.cardId());
            }
            repo.ignore(mercId);
            audit.log(by, "NOSHOW_IGNORED", "merc:" + mercId, null, null,
                    "Ignored the no-show alert for **" + name(s) + "**");
        });
    }

    // ------------------------------------------------------------------ suspend and lift

    public void suspend(long mercId, Length length, String reason, long by) {
        Optional<State> before = repo.state(mercId);
        if (before.isEmpty()) {
            return;
        }
        boolean indefinite = length == Length.INDEF;
        Instant until = indefinite ? SuspensionRepository.INDEFINITE : Instant.now().plus(length.duration);
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        repo.suspend(mercId, until, indefinite, why, by);
        State s = repo.state(mercId).orElseThrow();
        audit.log(by, "CLAIMS_SUSPENDED", "merc:" + mercId, null,
                why == null ? Map.of("length", length.label) : Map.of("length", length.label, "reason", why),
                "Suspended claiming for **" + name(s) + "** (" + length.label.toLowerCase() + ")"
                        + (why == null ? "" : ": " + why));
        dms.send(s.discordId(), com.familyx.mercbot.discord.Dms.Clean.when("SUSPENDED", "merc:" + mercId), new EmbedBuilder().setColor(Panels.BAD).setAuthor("CLAIMS")
                .setTitle("⛔  Claiming suspended " + until(until, indefinite))
                .setDescription("A leader suspended your claims" + (why == null ? "." : ": \"" + why + "\".")
                        + " Attack still works, so you can keep earning without reserving targets.")
                .build());
        refreshCard(s);
    }

    /** Lifts a suspension; false if there wasn't one. */
    public boolean lift(long mercId, Long by) {
        Optional<State> before = repo.state(mercId);
        if (before.isEmpty() || !repo.lift(mercId)) {
            return false;
        }
        State s = before.get();
        audit.log(by, "CLAIMS_LIFTED", "merc:" + mercId, null, null, (by == null ? "Claim suspension ended for **"
                : "Lifted the claim suspension for **") + name(s) + "**");
        dms.send(s.discordId(), com.familyx.mercbot.discord.Dms.Clean.after("CLAIM_AGAIN", java.time.Duration.ofHours(24)), new EmbedBuilder().setColor(Panels.GOOD).setAuthor("CLAIMS")
                .setTitle("✅  You can claim again").setDescription("Your claim suspension has ended.").build());
        note("✅ " + name(s) + "'s claim suspension " + (by == null ? "ended" : "was lifted by <@" + by + ">") + " ("
                + lengthLabel(s).replace("for ", "") + ", set by "
                + (s.suspendedBy() == null ? "a leader" : "<@" + s.suspendedBy() + ">") + ").");
        if (s.cardId() != null) {
            edit(s.cardId(), new Card(new EmbedBuilder().setColor(Panels.GOOD).setAuthor("CLAIM NO-SHOWS")
                    .setTitle("✅  " + name(s) + " · claiming back on").build(), List.of()));
            repo.setCard(mercId, null);
        }
        return true;
    }

    private void refreshCard(State s) {
        if (s.cardId() != null) {
            edit(s.cardId(), card(s));
        }
    }

    /** Timed suspensions that have run out: lifted, with the DM and a note. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void expire() {
        try {
            for (State s : repo.expired()) {
                lift(s.mercId(), null);
            }
        } catch (Exception e) {
            log.warn("Suspension expiry failed: {}", e.getMessage());
        }
    }

    /** "Until turned back on": a reminder card every 7 days with Lift now and Keep. */
    @Scheduled(cron = "0 20 * * * *", zone = "UTC")
    public void remind() {
        try {
            for (State s : repo.dueReminder()) {
                long days = s.suspendedAt() == null ? 7 : Math.max(1, Duration.between(s.suspendedAt(), Instant.now()).toDays());
                post(new Card(new EmbedBuilder().setColor(Panels.WARN).setAuthor("SUSPENSION REMINDER")
                        .setTitle("⛔  " + name(s) + " has been suspended for " + days + " days")
                        .setDescription("Set by " + (s.suspendedBy() == null ? "a leader" : "<@" + s.suspendedBy() + ">")
                                + (s.suspendedAt() == null ? "" : " on " + WHEN.format(s.suspendedAt()) + " UTC")
                                + (s.reason() == null ? "" : " · \"" + s.reason() + "\"") + ". Still needed?")
                        .build(), List.of(ActionRow.of(
                        Button.success(CustomId.of("noshow", "lift", s.mercId()), "Lift now").withEmoji(Emoji.fromUnicode("✅")),
                        Button.secondary(CustomId.of("noshow", "keep", s.mercId()), "Keep")))));
                repo.reminded(s.mercId());
            }
        } catch (Exception e) {
            log.warn("Suspension reminders failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private String lengthLabel(State s) {
        if (s.indefinite()) {
            return "until turned back on";
        }
        if (s.suspendedAt() == null || s.suspendedUntil() == null) {
            return "";
        }
        long hours = Math.round(Duration.between(s.suspendedAt(), s.suspendedUntil()).toMinutes() / 60.0);
        return "for " + (hours % 24 == 0 && hours >= 48 ? hours / 24 + " days" : hours + " hours");
    }

    public static String name(State s) {
        return (s.name() == null ? "Player" : s.name()) + " [" + s.mercId() + "]";
    }

    private TextChannel leaders() {
        JDA api = jda.getIfAvailable();
        return api == null ? null : layout.get(Layout.CH_LEADERS).map(api::getTextChannelById).orElse(null);
    }

    private Optional<Long> post(Card card) {
        TextChannel ch = leaders();
        if (ch == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ch.sendMessage(new MessageCreateBuilder().setEmbeds(card.embed()).setComponents(card.rows())
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build()).complete().getIdLong());
        } catch (Exception e) {
            log.warn("Couldn't post a card in the leaders channel: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean edit(long messageId, Card card) {
        TextChannel ch = leaders();
        if (ch == null) {
            return false;
        }
        try {
            ch.editMessageById(messageId, new MessageEditBuilder().setEmbeds(card.embed()).setComponents(card.rows())
                    .build()).complete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void delete(long messageId) {
        TextChannel ch = leaders();
        if (ch != null) {
            ch.deleteMessageById(messageId).queue(null, ignored -> { });
        }
    }

    private void note(String text) {
        TextChannel ch = leaders();
        if (ch != null) {
            ch.sendMessage(new MessageCreateBuilder().setContent(text)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build()).queue(null, ignored -> { });
        }
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
