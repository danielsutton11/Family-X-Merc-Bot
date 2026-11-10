package com.familyx.mercbot.inbox;

import com.familyx.mercbot.audit.AuditService;
import com.familyx.mercbot.contract.ResultsFeed;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.Dms;
import com.familyx.mercbot.discord.Layout;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.inbox.InboxRepository.Message;
import com.familyx.mercbot.payment.InvoiceRepository;
import com.familyx.mercbot.payment.InvoiceRepository.Invoice;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.RequestRepositories;
import com.familyx.mercbot.request.RequestRepositories.Requester;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Requester ↔ leader messages (scope doc 4.5): Message leaders lands as a card in the inbox
 * channel, Reply sends a DM back, and the card goes once the messages on it are answered.
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)
            .withZone(ZoneOffset.UTC);

    private final InboxRepository repo;
    private final ContractRepository contracts;
    private final RequestRepositories requesters;
    private final InvoiceRepository invoices;
    private final ResultsFeed feed;
    private final Dms dms;
    private final AuditService audit;
    private final Layout layout;
    private final ObjectProvider<JDA> jda;

    public InboxService(InboxRepository repo, ContractRepository contracts, RequestRepositories requesters,
                        InvoiceRepository invoices, ResultsFeed feed, Dms dms, AuditService audit, Layout layout,
                        ObjectProvider<JDA> jda) {
        this.repo = repo;
        this.contracts = contracts;
        this.requesters = requesters;
        this.invoices = invoices;
        this.feed = feed;
        this.dms = dms;
        this.audit = audit;
        this.layout = layout;
        this.jda = jda;
    }

    /** The Message leaders button, for DMs to the requester. */
    public static Button messageButton(long contractId) {
        return Button.secondary(CustomId.of("msg", "write", contractId), "Message leaders").withEmoji(Emoji.fromUnicode("✉️"));
    }

    /** Null when the requester can write now, else why not. */
    public String refusal(Contract c, long requesterDiscordId) {
        if (!Messaging.canMessage(c, Instant.now())) {
            return "**" + c.ref() + "** finished more than 30 days ago, so messages about it are closed.";
        }
        Optional<Instant> last = repo.lastFrom(requesterDiscordId);
        if (last.isPresent() && last.get().plus(Messaging.MIN_GAP).isAfter(Instant.now())) {
            return "You've just sent a message. You can send another in a minute.";
        }
        return null;
    }

    // ------------------------------------------------------------------ requester → leaders

    public void fromRequester(Contract c, long discordId, String name, String text) {
        repo.add(c.id(), true, discordId, name, text);
        audit.log(discordId, "MESSAGE_TO_LEADERS", "contract:" + c.id(), null, null,
                "Message from the requester on **" + c.ref() + "**: " + quote(text, 200));
        postCard(c);
    }

    /** One card per contract with every waiting message on it: a new card, or the old one edited. */
    private void postCard(Contract c) {
        List<Message> waiting = repo.waiting(c.id());
        if (waiting.isEmpty()) {
            return;
        }
        JDA api = jda.getIfAvailable();
        TextChannel ch = api == null ? null : layout.get(Layout.CH_INBOX).map(api::getTextChannelById).orElse(null);
        if (ch == null) {
            log.warn("No inbox channel: a message on {} wasn't posted", c.ref());
            return;
        }
        MessageEmbed embed = card(c, waiting);
        ActionRow row = ActionRow.of(
                Button.primary(CustomId.of("inbox", "reply", c.id()), "Reply").withEmoji(Emoji.fromUnicode("↩️")),
                Button.secondary(CustomId.of("lcon", "open", c.id()), "Open contract").withEmoji(Emoji.fromUnicode("📋")),
                Button.secondary(CustomId.of("inbox", "done", c.id()), "No reply needed").withEmoji(Emoji.fromUnicode("✔️")));
        Long existing = waiting.stream().map(Message::cardMessageId).filter(java.util.Objects::nonNull)
                .reduce((a, b) -> b).orElse(null);
        try {
            if (existing != null) {
                try {
                    ch.editMessageById(existing, new MessageEditBuilder().setEmbeds(embed).setComponents(row).build())
                            .complete();
                    repo.setCard(c.id(), existing);
                    return;
                } catch (Exception gone) {
                    // the card was deleted by hand: post a new one
                }
            }
            long id = ch.sendMessageEmbeds(embed).setComponents(row).complete().getIdLong();
            repo.setCard(c.id(), id);
        } catch (Exception e) {
            log.warn("Couldn't post the inbox card for {}: {}", c.ref(), e.getMessage());
        }
    }

    private MessageEmbed card(Contract c, List<Message> waiting) {
        Optional<Requester> r = requesters.requester(c.requesterId());
        String who = r.map(x -> (x.name() == null ? "Player" : x.name()) + " [" + x.tornId() + "]").orElse("Requester");
        StringBuilder text = new StringBuilder();
        for (Message m : waiting) {
            if (waiting.size() > 1) {
                text.append("-# ").append(WHEN.format(m.createdAt())).append(" UTC\n");
            }
            text.append(quote(m.text(), 3800 / waiting.size())).append("\n\n");
        }
        int fromThem = repo.countFromRequester(c.id());
        int earlier = fromThem - waiting.size();
        return new EmbedBuilder().setColor(Panels.PRIVATE)
                .setAuthor("MESSAGE · " + c.ref() + " · " + feed.targetName(c, contracts.targetCount(c.id())).toUpperCase())
                .setTitle("✉️  " + who)
                .setDescription(text.toString().strip())
                .addField("Contract", state(c) + Panels.GAP, true)
                .addField("Due", due(c) + Panels.GAP, true)
                .addField("Thread", Messaging.ordinal(fromThem) + " message\n-# "
                        + (earlier == 0 ? "first on this contract" : earlier + " earlier, answered") + Panels.GAP, true)
                .build();
    }

    // ------------------------------------------------------------------ leaders → requester

    /** A leader's reply: DMs the requester, closes the waiting messages and removes their card. */
    public void reply(Contract c, long leaderId, String leaderName, String text) {
        List<Message> waiting = repo.waiting(c.id());
        repo.add(c.id(), false, leaderId, leaderName, text);
        Optional<Requester> r = requesters.requester(c.requesterId());
        String quoted = waiting.isEmpty() ? null : waiting.getLast().text();
        r.ifPresent(x -> dms.send(x.discordId(), com.familyx.mercbot.discord.Dms.Clean.after("LEADER_REPLY", java.time.Duration.ofDays(30)),
                new EmbedBuilder().setColor(Panels.GOOD)
                .setAuthor("REPLY FROM LEADERS · " + c.ref())
                .setTitle("✉️  " + leaderName + " replied")
                .setDescription(text + (quoted == null ? "" : "\n\n-# You wrote: \"" + quote(quoted, 120) + "\""))
                .build(), ActionRow.of(Button.secondary(CustomId.of("msg", "write", c.id()), "Reply")
                .withEmoji(Emoji.fromUnicode("↩️")))));
        audit.log(leaderId, "MESSAGE_TO_REQUESTER", "contract:" + c.id(), null, null,
                "Replied to the requester on **" + c.ref() + "**: " + quote(text, 200));
        clear(c, leaderId);
    }

    /** No reply needed: the waiting messages are closed and the card removed. */
    public void dismiss(Contract c, long leaderId) {
        if (!repo.waiting(c.id()).isEmpty()) {
            audit.log(leaderId, "MESSAGE_NO_REPLY", "contract:" + c.id(), null, null,
                    "Marked the requester's message on **" + c.ref() + "** as needing no reply");
        }
        clear(c, leaderId);
    }

    private void clear(Contract c, long by) {
        List<Long> cards = repo.answer(c.id(), by);
        JDA api = jda.getIfAvailable();
        if (api == null) {
            return;
        }
        layout.get(Layout.CH_INBOX).map(api::getTextChannelById).ifPresent(ch ->
                cards.forEach(id -> ch.deleteMessageById(id).queue(null, ignored -> { })));
    }

    // ------------------------------------------------------------------ the conversation, for Contracts

    /** The latest five messages as a field value, or null when there are none. */
    public String conversation(Contract c) {
        List<Message> recent = repo.recent(c.id(), 5);
        if (recent.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : recent) {
            sb.append("**").append(m.authorName() == null ? (m.toLeaders() ? "Requester" : "Leader") : m.authorName())
                    .append("** · ").append(WHEN.format(m.createdAt())).append('\n')
                    .append(quote(m.text(), 150)).append('\n');
        }
        boolean waiting = recent.getLast().toLeaders() && recent.getLast().answeredAt() == null;
        int total = repo.count(c.id());
        sb.append("-# ").append(waiting ? "⏳ waiting for a reply · " : "")
                .append(total > recent.size() ? "last " + recent.size() + " of " + total : total + (total == 1 ? " message" : " messages"));
        String out = sb.toString();
        return out.length() <= 1024 ? out : out.substring(out.length() - 1020);
    }

    // ------------------------------------------------------------------ helpers

    private static String state(Contract c) {
        return switch (c.status()) {
            case "ACTIVE" -> "Live";
            case "PAUSED" -> "Paused";
            case "PENDING_APPROVAL" -> "Waiting for approval";
            case "COMPLETED" -> "Completed";
            case "ENDED" -> "Ended early";
            case "CANCELLED" -> "Cancelled";
            case "REJECTED" -> "Rejected";
            default -> c.status();
        };
    }

    private String due(Contract c) {
        List<Invoice> list = invoices.forContract(c.id());
        Optional<Invoice> open = list.stream().filter(i -> !"PAID".equals(i.status()))
                .min(Comparator.comparing(Invoice::dueAt, Comparator.nullsLast(Comparator.naturalOrder())));
        if (open.isPresent()) {
            return Money.full(open.get().due()) + "\n-# " + ("PARTIAL".equals(open.get().status()) ? "part paid · " : "")
                    + (open.get().dueAt() == null ? "open" : "due " + WHEN.format(open.get().dueAt()) + " UTC");
        }
        return list.isEmpty() ? "Nothing yet\n-# no invoice" : "Nothing\n-# invoices paid";
    }

    static String quote(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() <= max ? t : t.substring(0, Math.max(1, max - 1)) + "…";
    }
}
