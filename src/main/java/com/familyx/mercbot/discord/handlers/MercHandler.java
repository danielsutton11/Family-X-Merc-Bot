package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import com.familyx.mercbot.discord.Screen;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.merc.Eligibility;
import com.familyx.mercbot.merc.FactionPerkService;
import com.familyx.mercbot.merc.MercRepository;
import com.familyx.mercbot.merc.MercRepository.DmPref;
import com.familyx.mercbot.merc.MercRepository.Merc;
import com.familyx.mercbot.merc.MercRepository.PingPref;
import com.familyx.mercbot.merc.MercService;
import com.familyx.mercbot.merc.MercService.CheckResult;
import com.familyx.mercbot.merc.RoleSync;
import com.familyx.mercbot.merc.TierAvailability;
import com.familyx.mercbot.tier.TierRepository;
import com.familyx.mercbot.tier.TierRepository.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * #merc-signup: Join, the sign-up result, and My profile (scope doc 5.1). Every step after the
 * first click updates one private message in place.
 */
@Component
public class MercHandler implements InteractionHandler {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
    private static final java.awt.Color TEAL = new java.awt.Color(0x3FA39A);

    private final MercRepository mercs;
    private final MercService service;
    private final RoleSync roles;
    private final TierAvailability availability;
    private final TierRepository tiers;
    private final FamilyFactionRepository factions;
    private final FactionPerkService perks;

    public MercHandler(MercRepository mercs, MercService service, RoleSync roles, TierAvailability availability,
                       TierRepository tiers, FamilyFactionRepository factions, FactionPerkService perks) {
        this.mercs = mercs;
        this.service = service;
        this.roles = roles;
        this.availability = availability;
        this.tiers = tiers;
        this.factions = factions;
        this.perks = perks;
    }

    @Override
    public String action() {
        return "merc";
    }

    // ------------------------------------------------------------------ buttons

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        switch (id.sub()) {
            case "join" -> event.replyModal(keyModal("Join the merc service")).queue();
            case "replacekey" -> event.replyModal(keyModal("Replace your merc key")).queue();
            case "profile" -> profileOrJoin(user, null).show(event);
            case "stricken" -> withMercSlow(user, event, m -> {
                boolean on = !m.strickenOptIn();
                if (on && !m.meritsOk()) {
                    return profile(m, "Stricken needs 10/10 merits first.");
                }
                mercs.setStricken(m.tornId(), on);
                if (on) {
                    // Stricken and Gold go together: turning Stricken on also ticks the Stricken tiers.
                    List<Integer> strickenTiers = tiers.findActive().stream().filter(Tier::requiresStricken)
                            .map(Tier::id).toList();
                    mercs.setOptIns(m.tornId(), Set.copyOf(strickenTiers), strickenTiers);
                }
                Merc updated = mercs.findByTorn(m.tornId()).orElseThrow();
                RoleSync.Result r = roles.sync(updated);
                availability.evaluate();
                return profile(updated, "⚡ Stricken turned " + (on ? "on" : "off") + "." + rolesNote(r));
            });
            case "recheck" -> {
                Optional<Merc> merc = mercs.findByDiscord(user).filter(Merc::active);
                if (merc.isEmpty()) {
                    notJoined().show(event);
                    return;
                }
                InteractionHook hook = Screen.deferInPlace(event);
                String problem = service.recheck(merc.get());
                Merc updated = mercs.findByTorn(merc.get().tornId()).orElseThrow();
                if (problem != null) {
                    profile(updated, "❌ " + problem).show(hook);
                    return;
                }
                RoleSync.Result r = roles.sync(updated);
                availability.evaluate();
                profile(updated, "🔄 Checked just now." + rolesNote(r)).show(hook);
            }
            case "leave" -> withMerc(user, event, m -> Screen.of(new EmbedBuilder().setColor(Panels.WARN)
                            .setTitle("🚪  Leave the merc service?")
                            .setDescription("Your merc roles are removed and your stored key is deleted. "
                                    + "You can join again any time.").build(),
                    ActionRow.of(
                            Button.danger(CustomId.of("merc", "leaveconfirm"), "Leave"),
                            Button.secondary(CustomId.of("merc", "profile"), "Cancel"))));
            case "leaveconfirm" -> {
                Optional<Merc> merc = mercs.findByDiscord(user).filter(Merc::active);
                if (merc.isEmpty()) {
                    notJoined().show(event);
                    return;
                }
                event.deferEdit().queue();
                service.leave(merc.get());
                event.getHook().editOriginal(new MessageEditBuilder().setReplace(true)
                                .setEmbeds(new EmbedBuilder().setColor(TEAL).setTitle("👋  You've left the merc service")
                                        .setDescription("Your roles and key have been removed.").build())
                                .build())
                        .queue(done -> AutoDismiss.after(event.getHook(), AutoDismiss.SHORT));
            }
            default -> profileOrJoin(user, "Unknown option.").show(event);
        }
    }

    // ------------------------------------------------------------------ the key box

    @Override
    public void modal(ModalInteractionEvent event, CustomId id) {
        InteractionHook hook = Screen.deferInPlace(event);
        String key = event.getValue("key") == null ? "" : event.getValue("key").getAsString();
        CheckResult result = service.join(key, event.getUser().getIdLong());
        result(result).show(hook);
    }

    // ------------------------------------------------------------------ profile menus

    @Override
    public void stringSelect(StringSelectInteractionEvent event, CustomId id) {
        long user = event.getUser().getIdLong();
        if ("tiers".equals(id.sub())) {
            withMercSlow(user, event, m -> {
                Set<Integer> wanted = event.getValues().stream().map(Integer::parseInt).collect(Collectors.toSet());
                List<Tier> active = tiers.findActive();
                // Only the tiers that were in the menu change. Stricken tiers (Gold) are only listed
                // while Stricken is on, and unticking one turns Stricken off instead.
                List<Integer> plain = active.stream().filter(t -> !t.requiresStricken()).map(Tier::id).toList();
                mercs.setOptIns(m.tornId(), wanted, plain);
                String extra = "";
                boolean strickenDropped = m.strickenActive() && active.stream()
                        .anyMatch(t -> t.requiresStricken() && !wanted.contains(t.id()));
                if (strickenDropped) {
                    mercs.setStricken(m.tornId(), false);
                    extra = " ⚡ Stricken turned off.";
                }
                Merc updated = reload(m);
                RoleSync.Result r = roles.sync(updated);
                availability.evaluate();
                return profile(updated, "🏷️ Tier choices saved." + extra + rolesNote(r));
            });
            return;
        }
        withMerc(user, event, m -> switch (id.sub()) {
            case "avail" -> {
                String v = event.getValues().getFirst();
                if ("on".equals(v)) {
                    mercs.setAvailability(m.tornId(), true, null);
                    yield profile(reload(m), "🟢 Availability on.");
                }
                Instant until = switch (v) {
                    case "1h" -> Instant.now().plus(Duration.ofHours(1));
                    case "3h" -> Instant.now().plus(Duration.ofHours(3));
                    case "8h" -> Instant.now().plus(Duration.ofHours(8));
                    case "24h" -> Instant.now().plus(Duration.ofHours(24));
                    default -> null;
                };
                mercs.setAvailability(m.tornId(), false, until);
                yield profile(reload(m), "🔴 Availability off" + (until == null ? " until you turn it on."
                        : " until " + TIME.format(until) + " UTC."));
            }
            case "pings" -> {
                mercs.setPingPref(m.tornId(), PingPref.valueOf(event.getValues().getFirst()));
                yield profile(reload(m), "🔔 Ping setting saved.");
            }
            case "dms" -> {
                mercs.setDmPref(m.tornId(), DmPref.valueOf(event.getValues().getFirst()));
                yield profile(reload(m), "✉️ Hit DM setting saved.");
            }
            default -> profile(m, "Unknown option.");
        });
    }

    // ------------------------------------------------------------------ screens

    private Modal keyModal(String title) {
        return Modal.create(CustomId.of("merc", "submit"), title)
                .addComponents(ActionRow.of(TextInput.create("key", "Torn API key (Limited)", TextInputStyle.SHORT)
                        .setMinLength(16).setMaxLength(16).setRequired(true)
                        .setPlaceholder("16 letters and numbers").build()))
                .build();
    }

    private Screen result(CheckResult r) {
        if (!r.ok()) {
            EmbedBuilder eb = new EmbedBuilder().setColor(Panels.BAD).setAuthor("MERC SIGN-UP")
                    .setTitle("❌  Sign-up didn't finish");
            String checks = String.join("\n", r.lines());
            eb.setDescription((checks.isBlank() ? "" : checks + "\n") + "-# " + r.fix() + Panels.GAP);
            return Screen.of(eb.build(), ActionRow.of(Button.primary(CustomId.of("merc", "join"), "Try again")
                    .withEmoji(Emoji.fromUnicode("🔁"))));
        }
        Merc m = r.merc();
        List<String> roleNames = new ArrayList<>(List.of("@Merc"));
        if (m.meritsOk()) {
            roleNames.add("@10/10 Merits");
        }
        if (m.strickenActive()) {
            roleNames.add("@Stricken");
        }
        r.roles().held().forEach(t -> roleNames.add("@" + t));
        List<Tier> active = tiers.findActive();
        String hint = Eligibility.strickenWouldUnlock(active, roles.facts(m))
                ? "\n-# Turn on Stricken in your profile to qualify for a higher tier." : "";
        EmbedBuilder eb = new EmbedBuilder().setColor(TEAL).setAuthor("MERC SIGN-UP")
                .setTitle("✅  Welcome to the merc service, " + m.name())
                .setDescription("You're signed up and your roles are on." + Panels.GAP)
                .addField("Checks", String.join("\n", r.lines()) + Panels.GAP, false)
                .addField("Roles given", String.join(" · ", roleNames) + hint, false);
        return Screen.of(eb.build(), ActionRow.of(Button.primary(CustomId.of("merc", "profile"), "Open my profile")
                .withEmoji(Emoji.fromUnicode("👤"))));
    }

    private Screen notJoined() {
        return Screen.of(new EmbedBuilder().setColor(TEAL).setTitle("👤  You haven't joined yet")
                        .setDescription("Join the merc service to get a profile.").build(),
                ActionRow.of(Button.primary(CustomId.of("merc", "join"), "Join").withEmoji(Emoji.fromUnicode("✅"))));
    }

    private Screen profileOrJoin(long discordId, String notice) {
        return mercs.findByDiscord(discordId).filter(Merc::active).map(m -> profile(m, notice)).orElseGet(this::notJoined);
    }

    private Screen profile(Merc m, String notice) {
        List<Tier> active = tiers.findActive();
        Eligibility.Facts facts = roles.facts(m);
        Set<Integer> held = roles.tiersFor(m, active, facts.optedOut());
        Optional<Tier> top = active.stream().filter(t -> held.contains(t.id())).max(Comparator.comparingInt(Tier::rank));
        boolean strickenUnlocks = Eligibility.strickenWouldUnlock(active, facts);
        String factionName = Optional.ofNullable(m.factionId()).flatMap(factions::find).map(FamilyFaction::name)
                .orElse("No faction");
        boolean perk = perks.has(m.factionId());

        EmbedBuilder eb = new EmbedBuilder().setColor(TEAL).setAuthor("MY MERC PROFILE")
                .setTitle("👤  " + m.name() + " [" + m.tornId() + "]")
                .setDescription((notice == null ? "" : notice + "\n\n") + factionName
                        + (m.joinedAt() == null ? "" : " · signed up " + DAY.format(m.joinedAt())) + Panels.GAP);

        String availValue = m.available() ? "On\n-# getting pings"
                : m.availableUntil() == null ? "Off\n-# until you turn it on"
                : "Off\n-# until " + TIME.format(m.availableUntil()) + " UTC";
        eb.addField((m.available() ? "🟢" : "🔴") + "  Availability", availValue + Panels.GAP, true);
        eb.addField((top.map(Panels::tierIcon).orElse("➖")) + "  Tier",
                top.map(Tier::name).orElse("None") + "\n-# "
                        + (strickenUnlocks ? "Stricken unlocks a higher tier"
                        : top.isPresent() ? "highest you qualify for" : "no tier you've chosen fits")
                        + Panels.GAP, true);
        eb.addField("🔑  Key", (service.keyWorking(m) ? "✅ working" : "❌ not working") + "\n-# checked daily"
                + Panels.GAP, true);
        eb.addField("🎖️  Merits", (m.meritsOk() ? "10/10 ✅" : "Not 10/10") + "\n-# checked daily" + Panels.GAP, true);
        eb.addField("🏥  Faction perk", (perk ? "Hospitalization X ✅" : "No Hospitalization X") + "\n-# checked daily"
                + Panels.GAP, true);
        eb.addField("⚡  Stricken", (m.strickenActive() ? "On" : "Off") + "\n-# "
                + (!m.meritsOk() ? "needs 10/10 merits" : strickenUnlocks ? "turn on for a higher tier"
                : m.strickenActive() ? "counts for Gold" : "optional") + Panels.GAP, true);
        eb.addField("🔔  Notifications", "Pings: **" + pingLabel(m.pingPref()) + "**  ·  Hit DMs: **"
                + dmLabel(m.dmPref()) + "**", false);

        // Menus show the current choice as the selected value.
        StringSelectMenu avail = StringSelectMenu.create(CustomId.of("merc", "avail"))
                .addOption("Availability: On", "on")
                .addOption("Off for 1 hour", "1h")
                .addOption("Off for 3 hours", "3h")
                .addOption("Off for 8 hours", "8h")
                .addOption("Off for 24 hours", "24h")
                .addOption("Off until I turn it on", "off")
                .setDefaultValues(m.available() ? "on" : "off")
                .build();
        if (!m.available() && m.availableUntil() != null) {
            avail = avail.createCopy().setDefaultValues(List.of()).setPlaceholder(
                    "Availability: off until " + TIME.format(m.availableUntil()) + " UTC").build();
        }

        // Stricken tiers (Gold) only appear here while Stricken is on (Stricken on = Gold on).
        List<Tier> menuTiers = active.stream().filter(t -> !t.requiresStricken() || m.strickenActive()).toList();
        StringSelectMenu.Builder tierMenu = StringSelectMenu.create(CustomId.of("merc", "tiers"))
                .setPlaceholder("Tiers I want: none").setRequiredRange(0, Math.max(1, menuTiers.size()));
        Set<String> wanted = new HashSet<>();
        for (Tier t : menuTiers) {
            tierMenu.addOption("Tier: " + t.name(), Integer.toString(t.id()), Panels.tierWhoShort(t),
                    Emoji.fromUnicode(Panels.tierIcon(t)));
            if (!facts.optedOut().contains(t.id())) {
                wanted.add(Integer.toString(t.id()));
            }
        }
        tierMenu.setDefaultValues(wanted);

        StringSelectMenu pings = StringSelectMenu.create(CustomId.of("merc", "pings"))
                .addOption("Pings: All (new contracts and targets back)", PingPref.ALL.name())
                .addOption("Pings: New contracts only", PingPref.NEW_ONLY.name())
                .addOption("Pings: Off", PingPref.OFF.name())
                .setDefaultValues(m.pingPref().name()).build();
        StringSelectMenu dms = StringSelectMenu.create(CustomId.of("merc", "dms"))
                .addOption("Hit DMs: Every hit", DmPref.EVERY_HIT.name())
                .addOption("Hit DMs: Hourly summary", DmPref.HOURLY.name())
                .setDefaultValues(m.dmPref().name()).build();

        List<Button> buttons = new ArrayList<>();
        if (m.meritsOk()) {
            buttons.add(m.strickenOptIn()
                    ? Button.secondary(CustomId.of("merc", "stricken"), "Turn off Stricken").withEmoji(Emoji.fromUnicode("⚡"))
                    : Button.primary(CustomId.of("merc", "stricken"), "Turn on Stricken").withEmoji(Emoji.fromUnicode("⚡")));
        }
        buttons.add(Button.secondary(CustomId.of("merc", "recheck"), "Re-check now").withEmoji(Emoji.fromUnicode("🔄")));
        buttons.add(Button.secondary(CustomId.of("merc", "replacekey"), "Replace key").withEmoji(Emoji.fromUnicode("🔑")));
        buttons.add(Button.danger(CustomId.of("merc", "leave"), "Leave the service").withEmoji(Emoji.fromUnicode("🚪")));

        return Screen.of(eb.build(), ActionRow.of(avail), ActionRow.of(tierMenu.build()), ActionRow.of(pings),
                ActionRow.of(dms), ActionRow.of(buttons));
    }

    // ------------------------------------------------------------------ helpers

    private Merc reload(Merc m) {
        return mercs.findByTorn(m.tornId()).orElse(m);
    }

    private void withMerc(long user, net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent event,
                          java.util.function.Function<Merc, Screen> work) {
        mercs.findByDiscord(user).filter(Merc::active).map(work).orElseGet(this::notJoined).show(event);
    }

    /** Same as {@link #withMerc} for work that calls Discord or Torn: defers first so it can't time out. */
    private void withMercSlow(long user,
                              net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent event,
                              java.util.function.Function<Merc, Screen> work) {
        Optional<Merc> merc = mercs.findByDiscord(user).filter(Merc::active);
        if (merc.isEmpty()) {
            notJoined().show(event);
            return;
        }
        InteractionHook hook = Screen.deferInPlace(event);
        work.apply(merc.get()).show(hook);
    }

    private static String rolesNote(RoleSync.Result r) {
        List<String> parts = new ArrayList<>();
        if (!r.gained().isEmpty()) {
            parts.add("Gained @" + String.join(", @", r.gained()));
        }
        if (!r.lost().isEmpty()) {
            parts.add("removed @" + String.join(", @", r.lost()));
        }
        return parts.isEmpty() ? "" : " " + String.join("; ", parts) + ".";
    }

    private static String pingLabel(PingPref p) {
        return switch (p) {
            case ALL -> "All";
            case NEW_ONLY -> "New contracts only";
            case OFF -> "Off";
        };
    }

    private static String dmLabel(DmPref p) {
        return switch (p) {
            case EVERY_HIT -> "Every hit";
            case HOURLY -> "Hourly summary";
        };
    }
}
