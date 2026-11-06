package com.familyx.mercbot.request;

import com.familyx.mercbot.request.Choices.Attack;
import com.familyx.mercbot.request.Choices.ForTarget;
import com.familyx.mercbot.request.Choices.Mode;
import com.familyx.mercbot.request.Choices.Posture;
import com.familyx.mercbot.request.TornLookups.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A request being put together, held in memory until it's accepted (quotes last 10 minutes,
 * so nothing here needs to survive a restart).
 */
public class Draft {

    // who
    public long requesterDiscordId;
    public long requesterTornId;
    public String requesterName;
    public Long requesterFactionId;
    public String requesterFactionName;
    public boolean requesterRevivable;

    // step 1
    public Integer tierId;
    public ForTarget forTarget;
    public Attack attack;
    public Mode mode;
    public boolean mercMe;

    // step 2
    public List<Player> players = new ArrayList<>();
    public Long factionId;
    public String factionName;
    public List<Player> factionMembers = new ArrayList<>();
    public boolean allMembers = true;
    public Set<Long> picked = new LinkedHashSet<>();
    public Instant startInput;
    public Instant endInput;
    public boolean endInputGiven;
    public Integer hitsInput;
    public String notes;
    public boolean confirmedNoWar;

    // quote
    public Scenarios.Scenario scenario;
    public Posture posture;
    public Conditions conditions;
    public Instant start;
    public Instant end;
    public int hits = 1;
    public Instant quotedAt;
    public List<String> changes = new ArrayList<>();
    public String repeatOfRef;

    public boolean step1Complete() {
        return mercMe || (tierId != null && forTarget != null && attack != null && mode != null);
    }

    /** The targets the contract will hold. */
    public List<Player> targets() {
        if (forTarget != null && forTarget.kind != Choices.Kind.PLAYERS && !mercMe) {
            if (allMembers) {
                return factionMembers;
            }
            return factionMembers.stream().filter(p -> picked.contains(p.id())).toList();
        }
        return players;
    }

    /** Clears everything from step 2 on, keeping step 1's choices. */
    public void resetDetails() {
        players = new ArrayList<>();
        factionId = null;
        factionName = null;
        factionMembers = new ArrayList<>();
        allMembers = true;
        picked = new LinkedHashSet<>();
        startInput = null;
        endInput = null;
        endInputGiven = false;
        hitsInput = null;
        notes = null;
        confirmedNoWar = false;
        resetQuote();
    }

    public void resetQuote() {
        scenario = null;
        posture = null;
        conditions = null;
        start = null;
        end = null;
        hits = 1;
        quotedAt = null;
        changes = new ArrayList<>();
    }
}
