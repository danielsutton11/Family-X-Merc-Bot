package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Money;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.ForTarget;
import com.familyx.mercbot.request.Choices.Kind;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Choices.Posture;
import com.familyx.mercbot.request.Choices.Status;
import com.familyx.mercbot.request.Conditions;
import com.familyx.mercbot.request.ContractRepository;
import com.familyx.mercbot.request.ContractRepository.Contract;
import com.familyx.mercbot.request.Draft;
import com.familyx.mercbot.request.RequestService;
import com.familyx.mercbot.request.RequestService.AcceptResult;
import com.familyx.mercbot.request.RequestService.Gate;
import com.familyx.mercbot.request.RequestService.Quote;
import com.familyx.mercbot.request.RequestService.QuoteResult;
import com.familyx.mercbot.request.Scenarios;
import com.familyx.mercbot.request.TornLookups.Player;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request a contract, Merc me and Repeat last (scope doc 4.1–4.3). One private message is
 * updated in place from step 1 to the quote. Drafts live in memory until accepted.
 */
@Component
public class RequestHandler implements InteractionHandler {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    private static final Color BLUE = Panels.PRIVATE;
    private static final Color GOLD = new Color(0xFFD700);
    private static final int LIST_SIZE = 25;
    private static final int MAX_LISTS = 4;

    private final RequestService service;
    private final ContractRepository contracts;
    private final Map<Long, Draft> drafts = new ConcurrentHashMap<>();
    private final Map<Long, Quote> quotes = new ConcurrentHashMap<>();

    public RequestHandler(RequestService service, ContractRepository contracts) {
        this.service = service;
        this.contracts = contracts;
    }

    @Override
    public String action() {
        return "req";
    }

    // ------------------------------------------------------------------ buttons

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "start", "mercme", "repeat" -> begin(event, id.sub());
            case "next" -> withDraft(event, d -> {
                if (!d.step1Complete()) {
                    step1(d, "Pick every option first.").show(event);
                } else {
                    // Times go in this box when the defaults are already known (not for another
                    // faction, whose war status needs its ID first).
                    Scenarios.Defaults preview = d.mode == Mode.DURATION && d.forTarget.kind != Kind.FACTION
                            ? service.previewDefaults(d) : null;
                    event.replyModal(detailsModal(d, preview)).queue();
                }
            });
            case "cancel" -> {
                drafts.remove(user);
                quotes.remove(user);
                event.editMessage(new net.dv8tion.jda.api.utils.messages.MessageEditBuilder().setReplace(true)
                                .setContent("Request cancelled. Nothing was placed.").build())
                        .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            }
            case "back1" -> withDraft(event, d -> {
                d.mercMe = false;
                d.repeatOfRef = null;
                d.resetDetails();
                step1(d, null).show(event);
            });
            case "restart", "who0" -> withDraft(event, d -> {
                d.mercMe = false;
                d.repeatOfRef = null;
                d.resetDetails();
                who(d).show(event);
            });
            case "pickmode" -> withDraft(event, d -> {
                d.allMembers = false;
                members(d, null).show(event);
            });
            case "all" -> withDraft(event, d -> {
                d.allMembers = true;
                d.picked.clear();
                members(d, null).show(event);
            });
            case "nowar" -> withDraft(event, d -> {
                d.confirmedNoWar = true;
                showQuote(event, d);
            });
            case "quote" -> withDraft(event, d -> showQuote(event, d));
            case "conds" -> withDraft(event, d -> conditions(d, null).show(event));
            case "awaybtn" -> withDraft(event, d -> event.replyModal(awayModal(d)).queue());
            case "times" -> withDraft(event, d -> {
                Scenarios.Defaults preview = service.previewDefaults(d);
                event.replyModal(timesModal(d.start, d.end, preview, "c")).queue();
            });
            case "timesfirst" -> withDraft(event, d -> {
                Scenarios.Defaults preview = service.previewDefaults(d);
                event.replyModal(timesModal(preview.start(), preview.end(), preview, "q")).queue();
            });
            case "hits" -> withDraft(event, d -> event.replyModal(hitsModal(d)).queue());
            case "accept" -> withDraft(event, d -> accept(event, d));
            default -> event.reply("Unknown option.").setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        }
    }

    private void begin(ButtonInteractionEvent event, String how) {
        long user = event.getUser().getIdLong();
        InteractionHook hook = Screen.deferInPlace(event);
        Gate gate = service.gate(user, event.getMember());
        if (gate instanceof Gate.Blocked b) {
            closing(hook, notice(Panels.BAD, "❌  " + b.title(), b.reason()), AutoDismiss.READING);
            return;
        }
        Draft d = ((Gate.Ok) gate).draft();
        switch (how) {
            case "mercme" -> {
                if (!service.mercMeAvailable()) {
                    closing(hook, notice(Panels.WARN, "🎯  Merc me is unavailable",
                            "No Gold mercs right now."), AutoDismiss.SHORT);
                    return;
                }
                service.prepareMercMe(d);
                drafts.put(user, d);
                showQuote(hook, d);
            }
            case "repeat" -> {
                List<Contract> recent = contracts.recentFor(d.requesterTornId, 5);
                if (recent.isEmpty()) {
                    closing(hook, notice(BLUE, "🔁  Nothing to repeat",
                            "You haven't placed a contract yet. Use **Request a contract**."), AutoDismiss.SHORT);
                    return;
                }
                drafts.put(user, d);
                repeatPicker(recent, null).show(hook);
            }
            default -> {
                prefillFromLast(d);
                drafts.put(user, d);
                who(d).show(hook);
            }
        }
    }

    /** Step 1 starts from the requester's last choices where possible (still offered). */
    private void prefillFromLast(Draft d) {
        contracts.lastFor(d.requesterTornId).filter(c -> !c.mercMe()).ifPresent(c -> {
            if (service.offeredTiers().stream().anyMatch(t -> t.id() == c.tierId())) {
                d.tierId = c.tierId();
            }
            d.attack = c.attack();
            d.mode = c.mode();
        });
    }

    // ------------------------------------------------------------------ menus

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        String v = event.getValues().isEmpty() ? null : event.getValues().getFirst();
        switch (id.sub()) {
            case "tier" -> withDraft(event, d -> {
                d.tierId = Integer.parseInt(v);
                d.chosenInStep1.add("tier");
                step1(d, null).show(event);
            });
            case "repeatpick" -> withDraft(event, d -> {
                InteractionHook hook = Screen.deferInPlace(event);
                Draft repeated = service.repeat(d, Long.parseLong(v));
                if (repeated == null) {
                    repeatPicker(contracts.recentFor(d.requesterTornId, 5),
                            "❌ That contract can't be repeated: its tier was retired or its targets can't be found.")
                            .show(hook);
                    return;
                }
                drafts.put(event.getUser().getIdLong(), repeated);
                showQuote(hook, repeated);
            });
            case "who" -> withDraft(event, d -> {
                d.factionRequest = "faction".equals(v);
                if (d.forTarget != null && d.forTarget.myFaction != d.factionRequest) {
                    d.forTarget = null;
                }
                step1(d, null).show(event);
            });
            case "for" -> withDraft(event, d -> {
                d.forTarget = ForTarget.valueOf(v);
                InteractionHook hook = Screen.deferInPlace(event); // the war check can take a moment
                service.applyStep1Defaults(d);
                step1(d, null).show(hook);
            });
            case "attack" -> withDraft(event, d -> {
                d.attack = Attack.valueOf(v);
                d.chosenInStep1.add("attack");
                step1(d, null).show(event);
            });
            case "mode" -> withDraft(event, d -> {
                d.mode = Mode.valueOf(v);
                d.chosenInStep1.add("mode");
                step1(d, null).show(event);
            });
            case "pick" -> withDraft(event, d -> {
                int list = Integer.parseInt(id.arg(0));
                List<Player> chunk = chunk(d.factionMembers, list);
                chunk.forEach(p -> d.picked.remove(p.id()));
                event.getValues().forEach(s -> d.picked.add(Long.parseLong(s)));
                d.allMembers = false;
                members(d, null).show(event);
            });
            case "posture" -> withDraft(event, d -> {
                d.posture = Posture.valueOf(v);
                d.conditions = null;
                d.scenario = null;
                d.quotedAt = null;
                showQuote(event, d);
            });
            case "status" -> withDraft(event, d -> {
                Status s = Status.valueOf(v);
                if (s == Status.AWAY) {
                    event.replyModal(awayModal(d)).queue();
                    return;
                }
                d.conditions = base(d).withStatus(s, Conditions.DEFAULT_AWAY_MINUTES);
                conditions(d, null).show(event);
            });
            case "reviv" -> withDraft(event, d -> {
                d.conditions = base(d).withRevivable("hit".equals(v));
                conditions(d, null).show(event);
            });
            case "early" -> withDraft(event, d -> {
                d.conditions = base(d).withEarlyDischarge("hit".equals(v));
                conditions(d, null).show(event);
            });
            default -> event.reply("Unknown option.").setEphemeral(true).queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
        }
    }

    /** Conditions to edit: the current ones, or permissive ones when the requester must pick everything. */
    private static Conditions base(Draft d) {
        return d.conditions != null ? d.conditions
                : new Conditions(Status.ANY, Conditions.DEFAULT_AWAY_MINUTES, true, d.attack == Attack.HOSPITALIZE);
    }

    // ------------------------------------------------------------------ boxes

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        Draft d = drafts.get(user);
        if (d == null) {
            expired(Screen.deferInPlace(event));
            return;
        }
        InteractionHook hook = Screen.deferInPlace(event);
        switch (id.sub()) {
            case "details" -> details(event, hook, d);
            case "awaymin" -> {
                Integer minutes = intValue(event, "minutes");
                if (minutes == null || minutes < 1 || minutes > 1440) {
                    conditions(d, "❌ Enter a whole number of minutes from 1 to 1440.").show(hook);
                    return;
                }
                d.conditions = base(d).withStatus(Status.AWAY, minutes);
                conditions(d, null).show(hook);
            }
            case "timesubmit" -> {
                boolean toQuote = "q".equals(id.arg(0));
                String error = readTimes(event, d);
                if (error != null) {
                    (toQuote ? members(d, "❌ " + error) : conditions(d, "❌ " + error)).show(hook);
                    return;
                }
                d.start = d.startInput != null ? d.startInput : Instant.now();
                d.end = d.endInput;
                if (toQuote) {
                    showQuote(hook, d);
                } else {
                    conditions(d, "🕒 Times saved.").show(hook);
                }
            }
            case "hitssubmit" -> {
                Integer hits = intValue(event, "hits");
                if (hits == null || hits < 1 || hits > 100) {
                    (d.mercMe ? quoteScreenOrError(d, "❌ Hits should be a number from 1 to 100.")
                            : conditions(d, "❌ Hits should be a number from 1 to 100.")).show(hook);
                    return;
                }
                d.hits = hits;
                d.hitsInput = hits;
                quoteScreenOrError(d, null).show(hook);
            }
            default -> step1(d, "Unknown option.").show(hook);
        }
    }

    private void details(ModalInteractionEvent event, InteractionHook hook, Draft d) {
        d.resetDetails();
        d.notes = text(event, "notes");
        if (d.notes != null && d.notes.isBlank()) {
            d.notes = null;
        }
        if (d.mode == Mode.HITS) {
            Integer hits = intValue(event, "hits");
            if (hits == null || hits < 1 || hits > 100) {
                step1(d, "❌ Hits per target should be a number from 1 to 100.").show(hook);
                return;
            }
            d.hitsInput = hits;
        } else if (d.forTarget.kind != Kind.FACTION) {
            String error = readTimes(event, d);
            if (error != null) {
                step1(d, "❌ " + error).show(hook);
                return;
            }
        }
        String error;
        switch (d.forTarget.kind) {
            case PLAYERS -> {
                error = service.loadPlayers(d, text(event, "players"));
                if (error != null) {
                    step1(d, "❌ " + error).show(hook);
                    return;
                }
                showQuote(hook, d);
            }
            case FACTION -> {
                Integer factionId = intValue(event, "faction");
                if (factionId == null || factionId <= 0) {
                    step1(d, "❌ Enter the target faction's ID (a number).").show(hook);
                    return;
                }
                error = service.loadFaction(d, factionId);
                if (error != null) {
                    step1(d, "❌ " + error).show(hook);
                    return;
                }
                members(d, null).show(hook);
            }
            case OWN_FACTION -> {
                if (d.requesterFactionId == null) {
                    step1(d, "❌ You're not in a faction.").show(hook);
                    return;
                }
                error = service.loadFaction(d, d.requesterFactionId);
                if (error != null) {
                    step1(d, "❌ " + error).show(hook);
                    return;
                }
                members(d, null).show(hook);
            }
        }
    }

    // ------------------------------------------------------------------ accept

    private void accept(ButtonInteractionEvent event, Draft d) {
        long user = event.getUser().getIdLong();
        Quote q = quotes.get(user);
        if (q == null) {
            showQuote(event, d);
            return;
        }
        InteractionHook hook = Screen.deferInPlace(event);
        AcceptResult result = service.accept(d, q, user);
        switch (result) {
            case AcceptResult.Expired e -> {
                d.quotedAt = null;
                Screen s = quoteScreenOrError(d, "⏱️ That quote expired, so here's a fresh one.");
                s.show(hook);
            }
            case AcceptResult.Denied den -> quoteScreenOrError(d, "❌ " + den.reason()).show(hook);
            case AcceptResult.Placed p -> {
                drafts.remove(user);
                quotes.remove(user);
                Contract c = p.contract();
                closing(hook, p.live()
                                ? notice(Panels.GOOD, "✅  Your contract is live", "You'll get updates on **" + c.ref()
                                + "** by DM.").setAuthor("CONTRACT " + c.ref())
                                : notice(BLUE, "⏳  Waiting for a leader", "You'll get a DM as soon as it's approved "
                                + "or rejected. The price is fixed at what you accepted.").setAuthor("CONTRACT " + c.ref()),
                        AutoDismiss.REPORT);
            }
        }
    }

    // ------------------------------------------------------------------ screens

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC);

    /** Repeat: the requester's last 5 contracts to choose from. */
    private Screen repeatPicker(List<Contract> recent, String notice) {
        EmbedBuilder eb = new EmbedBuilder().setColor(BLUE).setAuthor("REPEAT A CONTRACT")
                .setTitle("🔁  Which contract?")
                .setDescription((notice == null ? "" : notice + "\n\n") + "Your last " + recent.size()
                        + (recent.size() == 1 ? " contract" : " contracts, newest first")
                        + ". Pick one to get a fresh quote with the same choices." + Panels.GAP);
        StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("req", "repeatpick"))
                .setPlaceholder("Choose a contract…");
        for (Contract c : recent) {
            java.util.Optional<Tier> tier = service.tier(c.tierId());
            int count = contracts.targetCount(c.id());
            String target = c.mercMe() ? "Merc me"
                    : c.factionTarget() ? c.targetFactionName() + " (" + count + ")"
                    : count == 1 ? contracts.firstTargetName(c.id()).orElse("1 player") : count + " players";
            String label = c.ref() + " · " + tier.map(Tier::name).orElse("Retired tier") + " · " + target;
            String amount = c.mode() == Mode.HITS
                    ? c.hitsPerTarget() + " hit" + (c.hitsPerTarget() == 1 ? "" : "s") + (count > 1 ? " each" : "")
                    : "Duration";
            String desc = (c.myFaction() ? "Faction request" : "Personal request") + " · " + c.attack().label + " · "
                    + amount + (c.createdAt() == null ? "" : " · " + DAY.format(c.createdAt()));
            menu.addOption(trim(label, 100), Long.toString(c.id()), trim(desc, 100),
                    Emoji.fromUnicode(tier.map(Panels::tierIcon).orElse("🏷️")));
        }
        return Screen.of(eb.build(), ActionRow.of(menu.build()),
                ActionRow.of(Button.secondary(CustomId.of("req", "cancel"), "Cancel").withEmoji(Emoji.fromUnicode("✖️"))));
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** The first screen: Personal request or Faction request. Picking one goes straight to step 1. */
    private Screen who(Draft d) {
        EmbedBuilder eb = new EmbedBuilder().setColor(BLUE).setAuthor("NEW CONTRACT")
                .setTitle("⚔️  Who's this request for?")
                .setDescription("**Personal request**: your own. Personal discounts apply.\n"
                        + "**Faction request**: on behalf of your faction. Your faction's discount applies." + Panels.GAP);
        StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("req", "who")).setPlaceholder("Choose…")
                .addOption("Personal request", "personal", null, Emoji.fromUnicode("👤"));
        if (d.requesterFactionId != null) {
            menu.addOption("Faction request", "faction",
                    d.requesterFactionName == null ? null : "For " + d.requesterFactionName, Emoji.fromUnicode("🏰"));
        }
        return Screen.of(eb.build(), ActionRow.of(menu.build()),
                ActionRow.of(Button.secondary(CustomId.of("req", "cancel"), "Cancel").withEmoji(Emoji.fromUnicode("✖️"))));
    }

    private Screen step1(Draft d, String notice) {
        if (d.factionRequest == null) {
            return who(d);
        }
        List<Tier> offered = service.offeredTiers();
        String forLine = d.factionRequest
                ? "For: **Faction request**" + (d.requesterFactionName == null ? "" : " (" + d.requesterFactionName + ")")
                : "For: **Personal request**";
        EmbedBuilder eb = new EmbedBuilder().setColor(BLUE).setAuthor("NEW CONTRACT · STEP 1 OF 3")
                .setTitle("⚔️  What do you need?")
                .setDescription((notice == null ? "" : notice + "\n\n") + forLine + "\nPick each option, then press Next."
                        + Panels.GAP);
        if (offered.isEmpty()) {
            return Screen.of(notice(Panels.WARN, "No tiers are available right now", "Check back soon.").build(),
                    ActionRow.of(Button.secondary(CustomId.of("req", "cancel"), "Close")));
        }
        StringSelectMenu.Builder tier = StringSelectMenu.create(CustomId.of("req", "tier")).setPlaceholder("Tier…");
        for (Tier t : offered) {
            tier.addOption("Tier: " + t.name() + " · " + Money.shortForm(t.pricePerHit()) + " per hit",
                    Integer.toString(t.id()), Panels.tierWhoShort(t), Emoji.fromUnicode(Panels.tierIcon(t)));
        }
        if (d.tierId != null && offered.stream().anyMatch(t -> t.id() == d.tierId)) {
            tier.setDefaultValues(Integer.toString(d.tierId));
        }
        StringSelectMenu.Builder forT = StringSelectMenu.create(CustomId.of("req", "for")).setPlaceholder("Target…");
        for (ForTarget f : ForTarget.values()) {
            if (f.myFaction == d.factionRequest) {
                forT.addOption(f.label, f.name(), null, Emoji.fromUnicode("🎯"));
            }
        }
        if (d.forTarget != null) {
            forT.setDefaultValues(d.forTarget.name());
        }
        StringSelectMenu.Builder attack = StringSelectMenu.create(CustomId.of("req", "attack")).setPlaceholder("Attack type…")
                .addOption("Attack: Hospitalize", Attack.HOSPITALIZE.name(), "Put targets in hospital", Emoji.fromUnicode("🏥"))
                .addOption("Attack: Mug", Attack.MUG.name(), "Keep war targets in rotation/Just Mug", Emoji.fromUnicode("💸"));
        if (d.attack != null) {
            attack.setDefaultValues(d.attack.name());
        }
        StringSelectMenu.Builder mode = StringSelectMenu.create(CustomId.of("req", "mode")).setPlaceholder("Mode…")
                .addOption("Mode: Hit count", Mode.HITS.name(), "A set number of hits per target", Emoji.fromUnicode("🔢"))
                .addOption("Mode: Duration", Mode.DURATION.name(), "Hit whenever possible between two times",
                        Emoji.fromUnicode("⏱️"));
        if (d.mode != null) {
            mode.setDefaultValues(d.mode.name());
        }
        Button next = Button.primary(CustomId.of("req", "next"), "Next: details").withEmoji(Emoji.fromUnicode("➡️"));
        return Screen.of(eb.build(), ActionRow.of(forT.build()), ActionRow.of(tier.build()), ActionRow.of(attack.build()),
                ActionRow.of(mode.build()),
                ActionRow.of(Button.secondary(CustomId.of("req", "who0"), "Back").withEmoji(Emoji.fromUnicode("⬅️")),
                        Button.secondary(CustomId.of("req", "cancel"), "Cancel").withEmoji(Emoji.fromUnicode("✖️")),
                        d.step1Complete() ? next : next.asDisabled()));
    }

    private Modal detailsModal(Draft d, Scenarios.Defaults preview) {
        List<Label> rows = new ArrayList<>();
        if (d.forTarget.kind == Kind.PLAYERS) {
            rows.add(Label.of("Player IDs or profile links (up to 25)", TextInput.create("players", TextInputStyle.PARAGRAPH)
                    .setRequired(true).setPlaceholder("e.g. 1561637 or https://www.torn.com/profiles.php?XID=1561637")
                    .setMaxLength(2000).build()));
        } else if (d.forTarget.kind == Kind.FACTION) {
            rows.add(Label.of("Target faction ID", TextInput.create("faction", TextInputStyle.SHORT)
                    .setRequired(true).setPlaceholder("e.g. 12345").setMaxLength(10).build()));
        }
        if (d.mode == Mode.HITS) {
            rows.add(Label.of("Hits per target", TextInput.create("hits", TextInputStyle.SHORT)
                    .setRequired(true).setValue("1").setMaxLength(3).build()));
        } else if (preview != null) {
            rows.add(startInput(preview.start(), preview));
            rows.add(endInput(preview.end(), preview));
        }
        rows.add(Label.of("Notes for leaders (optional)", TextInput.create("notes", TextInputStyle.PARAGRAPH)
                .setRequired(false).setMaxLength(500).build()));
        return Modal.create(CustomId.of("req", "details"), "Contract details").addComponents(rows).build();
    }

    /** The times box, filled in with {@code start}/{@code end}; labels say what the default is. */
    private Modal timesModal(Instant start, Instant end, Scenarios.Defaults preview, String then) {
        return Modal.create(CustomId.of("req", "timesubmit", then), "Contract times")
                .addComponents(startInput(start, preview), endInput(end, preview))
                .build();
    }

    private static Label startInput(Instant start, Scenarios.Defaults preview) {
        return Label.of("Start (UTC) · default: " + preview.startNote(), TextInput.create("start", TextInputStyle.SHORT)
                .setRequired(false).setPlaceholder("e.g. 2026-11-08 18:00, or now").setMaxLength(16)
                .setValue(start == null || !start.isAfter(Instant.now()) ? "now"
                        : INPUT.format(start.atOffset(ZoneOffset.UTC))).build());
    }

    private static Label endInput(Instant end, Scenarios.Defaults preview) {
        return Label.of("End (UTC) · default: " + preview.endNote(), TextInput.create("end", TextInputStyle.SHORT)
                .setRequired(false).setPlaceholder("e.g. 2026-11-09 18:00, or blank for no end").setMaxLength(16)
                .setValue(end == null ? null : INPUT.format(end.atOffset(ZoneOffset.UTC))).build());
    }

    private static Modal awayModal(Draft d) {
        int current = d.conditions != null && d.conditions.status() == Status.AWAY
                ? d.conditions.awayMinutes() : Conditions.DEFAULT_AWAY_MINUTES;
        return Modal.create(CustomId.of("req", "awaymin"), "Idle or offline for how long?")
                .addComponents(Label.of("Minutes since their last action", TextInput.create("minutes", TextInputStyle.SHORT).setRequired(true).setMaxLength(4)
                        .setValue(Integer.toString(current)).build()))
                .build();
    }

    private Modal hitsModal(Draft d) {
        return Modal.create(CustomId.of("req", "hitssubmit"), "Change hits")
                .addComponents(Label.of("Hits per target", TextInput.create("hits", TextInputStyle.SHORT)
                        .setRequired(true).setMaxLength(3).setValue(Integer.toString(d.hits)).build()))
                .build();
    }

    private Screen members(Draft d, String notice) {
        String picked = d.allMembers ? "All members" + (d.factionMembers.size() > 0 ? " (" + d.factionMembers.size()
                + ", and anyone who joins later)" : "")
                : d.picked.isEmpty() ? "Nobody yet" : d.picked.size() + " picked";
        EmbedBuilder eb = new EmbedBuilder().setColor(BLUE).setAuthor("NEW CONTRACT · STEP 2 OF 3")
                .setTitle("👥  " + d.factionName + " [" + d.factionId + "] · " + d.factionMembers.size() + " members")
                .setDescription((notice == null ? "" : notice + "\n\n") + (d.allMembers ? "Every member is targeted. Press **Pick members** to choose some instead."
                        : "Pick members from the lists below, or press **All members** to target everyone.") + Panels.GAP)
                .addField("Picked", picked, false);
        List<ActionRow> rows = new ArrayList<>();
        int lists = Math.min(MAX_LISTS, (d.factionMembers.size() + LIST_SIZE - 1) / LIST_SIZE);
        boolean tooBig = d.factionMembers.size() > MAX_LISTS * LIST_SIZE;
        if (tooBig) {
            eb.addField("\u200b", "-# This faction is too big to pick members one by one, so only All members is available.",
                    false);
        } else if (!d.allMembers) {
            // The A–Z lists only appear once "Pick members" is pressed.
            for (int i = 0; i < lists; i++) {
                List<Player> chunk = chunk(d.factionMembers, i);
                StringSelectMenu.Builder menu = StringSelectMenu.create(CustomId.of("req", "pick", i))
                        .setPlaceholder(range(chunk) + " (" + chunk.size() + ")").setRequiredRange(0, chunk.size());
                Set<String> selected = new HashSet<>();
                for (Player p : chunk) {
                    menu.addOption(p.name(), Long.toString(p.id()), "Level " + p.level());
                    if (!d.allMembers && d.picked.contains(p.id())) {
                        selected.add(Long.toString(p.id()));
                    }
                }
                menu.setDefaultValues(selected);
                rows.add(ActionRow.of(menu.build()));
            }
        }
        boolean timesNext = d.mode == Mode.DURATION && d.forTarget.kind == Kind.FACTION && !d.timesGiven;
        Button quote = timesNext
                ? Button.primary(CustomId.of("req", "timesfirst"), "Next: times").withEmoji(Emoji.fromUnicode("🕒"))
                : Button.primary(CustomId.of("req", "quote"), "Get quote").withEmoji(Emoji.fromUnicode("➡️"));
        rows.add(ActionRow.of(
                Button.secondary(CustomId.of("req", "back1"), "Back").withEmoji(Emoji.fromUnicode("⬅️")),
                d.allMembers
                        ? (tooBig ? Button.secondary(CustomId.of("req", "pickmode"), "Pick members").asDisabled()
                        : Button.secondary(CustomId.of("req", "pickmode"), "Pick members")).withEmoji(Emoji.fromUnicode("☑️"))
                        : Button.secondary(CustomId.of("req", "all"), "All members").withEmoji(Emoji.fromUnicode("👥")),
                d.allMembers || !d.picked.isEmpty() ? quote : quote.asDisabled()));
        return new Screen(eb.build(), rows);
    }

    private void showQuote(GenericComponentInteractionCreateEvent event, Draft d) {
        InteractionHook hook = Screen.deferInPlace(event);
        showQuote(hook, d);
    }

    private void showQuote(InteractionHook hook, Draft d) {
        quoteScreenOrError(d, null).show(hook);
    }

    /** Builds the quote, or the denial / no-war question instead. */
    private Screen quoteScreenOrError(Draft d, String notice) {
        QuoteResult result = service.quote(d);
        return switch (result) {
            case QuoteResult.Denied den -> Screen.of(notice(Panels.BAD, "❌  This request isn't allowed", den.reason()).build(),
                    ActionRow.of(Button.secondary(CustomId.of("req", "back1"), "Back").withEmoji(Emoji.fromUnicode("⬅️"))));
            case QuoteResult.ConfirmNoWar w -> Screen.of(notice(Panels.WARN, "⚠️  " + w.factionName()
                            + " has no war scheduled or active",
                            "Are you sure? The contract will use war defaults, starting now.").build(),
                    ActionRow.of(Button.secondary(CustomId.of("req", "back1"), "Back").withEmoji(Emoji.fromUnicode("⬅️")),
                            Button.primary(CustomId.of("req", "nowar"), "Yes, continue")));
            case QuoteResult.Ready r -> {
                quotes.put(d.requesterDiscordId, r.quote());
                yield quote(d, r.quote(), notice);
            }
        };
    }

    private Screen quote(Draft d, Quote q, String notice) {
        Tier t = q.tier();
        EmbedBuilder eb = new EmbedBuilder().setColor(new Color(t.colour()));
        StringBuilder desc = new StringBuilder();
        if (notice != null) {
            desc.append(notice).append("\n\n");
        }
        if (d.repeatOfRef != null && !d.changes.isEmpty()) {
            desc.append("**Changed since last time:**\n");
            d.changes.forEach(c -> desc.append("• ").append(c).append('\n'));
            desc.append('\n');
        }
        desc.append("Valid until ").append(SHOW.format(q.expiresAt())).append(" UTC.").append(Panels.GAP);
        eb.setAuthor(d.mercMe ? "MERC ME · ALWAYS GOLD" : d.repeatOfRef != null ? "REPEAT OF " + d.repeatOfRef
                        : "NEW CONTRACT · STEP 3 OF 3")
                .setTitle(d.mercMe ? "🎯  Your quote: hit me" : "🧾  Your quote")
                .setDescription(desc.toString());

        // targets / tier / when
        String targets;
        if (d.mercMe) {
            targets = "You\n-# " + d.requesterName + " [" + d.requesterTornId + "]";
        } else if (d.forTarget.kind == Kind.PLAYERS) {
            List<Player> ps = d.players;
            targets = ps.size() == 1 ? ps.getFirst().name() + "\n-# [" + ps.getFirst().id() + "]"
                    : ps.size() + " players\n-# " + String.join(", ", ps.stream().limit(4).map(Player::name).toList())
                    + (ps.size() > 4 ? "…" : "");
        } else {
            targets = d.factionName + "\n-# " + (d.allMembers ? "all " + q.targetCount() + " members" : q.targetCount()
                    + " picked members");
        }
        eb.addField("🎯  " + (d.mercMe ? "Target" : "Targets"), targets + Panels.GAP, true);
        eb.addField(Panels.tierIcon(t) + "  Tier", t.name() + "\n-# " + d.attack.label + Panels.GAP, true);
        if (d.mode == Mode.HITS) {
            eb.addField("🔢  Hits", d.hits + (d.mercMe ? "" : " per target") + Panels.GAP, true);
        } else {
            String from = d.start == null || !d.start.isAfter(Instant.now()) ? "From now" : "From " + SHOW.format(d.start);
            String to = d.end == null ? "no end" : "until " + SHOW.format(d.end) + " UTC";
            eb.addField("⏱️  When", from + "\n-# " + to + Panels.GAP, true);
        }

        // conditions
        boolean needsConditions = d.conditions == null;
        if (!d.mercMe) {
            String conds = needsConditions
                    ? "⚠️ Your faction's war is active, so there are no defaults. Press **Change conditions** and pick each one."
                    : "Status: **" + d.conditions.statusLabel() + "** · Revivable: **"
                    + (d.conditions.allowRevivable() ? "Hit" : "Don't hit") + "**"
                    + (d.attack == Attack.HOSPITALIZE ? " · Early discharge: **"
                    + (d.conditions.allowEarlyDischarge() ? "Hit" : "Skip") + "**" : "");
            if (!needsConditions && q.silverPerHit() != null) {
                conds += "\n-# Revivable targets are billed at Silver (" + Money.shortForm(q.silverPerHit()) + ").";
            }
            eb.addField("🎛️  Conditions", conds + Panels.GAP, false);
        }

        // price
        StringBuilder price = new StringBuilder();
        if (q.discountPct().signum() > 0) {
            price.append(Money.full(q.unitPrice())).append(" per hit\n").append(q.discountLabel()).append(" −")
                    .append(q.discountPct().stripTrailingZeros().toPlainString()).append("% → **")
                    .append(Money.full(q.perHit())).append(" per hit**");
        } else {
            price.append("**").append(Money.full(q.perHit())).append("** per hit");
        }
        if (q.total() != null) {
            price.append("\nTotal: **").append(Money.full(q.total())).append("**");
            if (!d.mercMe) {
                price.append("\n-# ").append(q.targetCount()).append(" target").append(q.targetCount() == 1 ? "" : "s")
                        .append(" × ").append(d.hits).append(" hit").append(d.hits == 1 ? "" : "s");
            }
        } else {
            price.append("\n-# Billed per hit as they land. Invoiced when the contract ends, or weekly while it runs.");
        }
        if (d.mercMe && d.requesterRevivable) {
            price.append("\n-# ⚠️ You're revivable. If someone revives you, that hit is still billed.");
        }
        eb.addField("💰  Price", price.toString(), false);

        List<ActionRow> rows = new ArrayList<>();
        if (d.scenario == Scenarios.Scenario.G) {
            rows.add(ActionRow.of(StringSelectMenu.create(CustomId.of("req", "posture"))
                    .addOption("Posture: Defensive", Posture.DEFENSIVE.name(), "Protect them during their war")
                    .addOption("Posture: Offensive", Posture.OFFENSIVE.name(), "Hit them before their war")
                    .setDefaultValues((d.posture == null ? Posture.DEFENSIVE : d.posture).name()).build()));
        }
        Button accept = Button.primary(CustomId.of("req", "accept"), "Accept").withEmoji(Emoji.fromUnicode("✅"));
        List<Button> buttons = new ArrayList<>();
        buttons.add(needsConditions ? accept.asDisabled() : accept);
        if (d.mercMe) {
            buttons.add(Button.secondary(CustomId.of("req", "hits"), "Change hits").withEmoji(Emoji.fromUnicode("🔢")));
        } else {
            buttons.add(Button.secondary(CustomId.of("req", "conds"), "Change conditions").withEmoji(Emoji.fromUnicode("🎛️")));
        }
        if (d.repeatOfRef != null && !d.mercMe) {
            buttons.add(Button.secondary(CustomId.of("req", "restart"), "Start from step 1").withEmoji(Emoji.fromUnicode("✏️")));
        }
        buttons.add(Button.secondary(CustomId.of("req", "cancel"), "Cancel").withEmoji(Emoji.fromUnicode("✖️")));
        rows.add(ActionRow.of(buttons));
        return new Screen(eb.build(), rows);
    }

    private Screen conditions(Draft d, String notice) {
        Conditions c = d.conditions;
        EmbedBuilder eb = new EmbedBuilder().setColor(GOLD).setAuthor("YOUR QUOTE").setTitle("🎛️  Conditions")
                .setDescription((notice == null ? "" : notice + "\n\n")
                        + "Changes apply to this quote. Tier and attack type need a new request." + Panels.GAP);
        if (d.mode == Mode.DURATION) {
            String from = d.start == null || !d.start.isAfter(Instant.now()) ? "now" : SHOW.format(d.start) + " UTC";
            eb.addField("⏱️  When", "From " + from + ", " + (d.end == null ? "no end" : "until " + SHOW.format(d.end) + " UTC"),
                    false);
        } else {
            eb.addField("🔢  Hits", d.hits + " per target", false);
        }
        boolean gold = service.goldTier().map(g -> g.id() == d.tierId).orElse(false);

        StringSelectMenu.Builder status = StringSelectMenu.create(CustomId.of("req", "status")).setPlaceholder("Status…")
                .addOption("Status: Any", Status.ANY.name())
                .addOption("Status: Online", Status.ONLINE.name())
                .addOption(c != null && c.status() == Status.AWAY ? "Status: Idle/Offline " + c.awayMinutes() + "+ min"
                        : "Status: Idle/Offline for X+ minutes", Status.AWAY.name(), "You choose the minutes");
        if (c != null) {
            status.setDefaultValues(c.status().name());
        }
        StringSelectMenu.Builder reviv = StringSelectMenu.create(CustomId.of("req", "reviv")).setPlaceholder("Revivable targets…")
                .addOption("Revivable: Hit" + (gold ? " (billed at Silver on Gold)" : ""), "hit")
                .addOption("Revivable: Don't hit", "skip");
        if (c != null) {
            reviv.setDefaultValues(c.allowRevivable() ? "hit" : "skip");
        }
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(status.build()));
        rows.add(ActionRow.of(reviv.build()));
        if (d.attack == Attack.HOSPITALIZE) {
            StringSelectMenu.Builder early = StringSelectMenu.create(CustomId.of("req", "early"))
                    .setPlaceholder("Early discharge…")
                    .addOption("Early discharge: Hit", "hit")
                    .addOption("Early discharge: Skip", "skip");
            if (c != null) {
                early.setDefaultValues(c.allowEarlyDischarge() ? "hit" : "skip");
            }
            rows.add(ActionRow.of(early.build()));
        }
        List<Button> buttons = new ArrayList<>();
        buttons.add(d.mode == Mode.DURATION
                ? Button.secondary(CustomId.of("req", "times"), "Change times").withEmoji(Emoji.fromUnicode("🕒"))
                : Button.secondary(CustomId.of("req", "hits"), "Change hits").withEmoji(Emoji.fromUnicode("🔢")));
        if (c != null && c.status() == Status.AWAY) {
            // Picking the already-selected status again doesn't reach the bot, so minutes get their own button.
            buttons.add(Button.secondary(CustomId.of("req", "awaybtn"), "Change minutes").withEmoji(Emoji.fromUnicode("⏳")));
        }
        buttons.add(Button.primary(CustomId.of("req", "quote"), "Back to quote").withEmoji(Emoji.fromUnicode("⬅️")));
        rows.add(ActionRow.of(buttons));
        return new Screen(eb.build(), rows);
    }

    // ------------------------------------------------------------------ helpers

    private void withDraft(GenericComponentInteractionCreateEvent event, java.util.function.Consumer<Draft> work) {
        long user = event.getUser().getIdLong();
        Draft d = drafts.get(user);
        if (d == null) {
            expired(Screen.deferInPlace(event));
            return;
        }
        work.accept(d);
    }

    private void expired(InteractionHook hook) {
        closing(hook, notice(BLUE, "⏱️  This request has expired",
                "Start again from the request panel."), AutoDismiss.SHORT);
    }

    private static EmbedBuilder notice(Color colour, String title, String text) {
        return new EmbedBuilder().setColor(colour).setTitle(title).setDescription(text);
    }

    /** Shows a message with no buttons and removes it after {@code seconds}. */
    private static void closing(InteractionHook hook, EmbedBuilder embed, int seconds) {
        hook.editOriginal(new net.dv8tion.jda.api.utils.messages.MessageEditBuilder().setReplace(true)
                        .setEmbeds(embed.build()).build())
                .queue(done -> AutoDismiss.after(hook, seconds));
    }

    private static List<Player> chunk(List<Player> all, int index) {
        int from = index * LIST_SIZE;
        return from >= all.size() ? List.of() : all.subList(from, Math.min(all.size(), from + LIST_SIZE));
    }

    private static String range(List<Player> chunk) {
        if (chunk.isEmpty()) {
            return "—";
        }
        String first = initial(chunk.getFirst().name());
        String last = initial(chunk.getLast().name());
        return first.equals(last) ? first : first + "–" + last;
    }

    /** A name's first letter for list labels; names starting with a symbol or number show as "#". */
    private static String initial(String name) {
        return !name.isEmpty() && Character.isLetter(name.charAt(0)) ? name.substring(0, 1).toUpperCase() : "#";
    }

    private static String text(ModalInteractionEvent event, String field) {
        return event.getValue(field) == null ? "" : event.getValue(field).getAsString().trim();
    }

    private static Integer intValue(ModalInteractionEvent event, String field) {
        try {
            return Integer.parseInt(text(event, field));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads start and end: blank or "now" start means now; blank or "none" end means no end. */
    private static String readTimes(ModalInteractionEvent event, Draft d) {
        String start = text(event, "start");
        String end = text(event, "end");
        try {
            d.startInput = start.isBlank() || "now".equalsIgnoreCase(start) ? null
                    : LocalDateTime.parse(start, INPUT).toInstant(ZoneOffset.UTC);
            d.endInput = end.isBlank() || "none".equalsIgnoreCase(end) ? null
                    : LocalDateTime.parse(end, INPUT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return "Times should look like 2026-11-08 18:00 (UTC).";
        }
        d.timesGiven = true;
        Instant effectiveStart = d.startInput == null ? Instant.now() : d.startInput;
        if (d.endInput != null && !d.endInput.isAfter(effectiveStart)) {
            return "The end has to be after the start.";
        }
        return null;
    }
}
