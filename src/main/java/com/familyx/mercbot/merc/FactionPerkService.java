package com.familyx.mercbot.merc;

import com.familyx.mercbot.faction.FamilyFactionRepository;
import com.familyx.mercbot.faction.FamilyFactionRepository.FamilyFaction;
import com.familyx.mercbot.torn.TornKeyPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Whether each family faction holds Hospitalization X (scope doc 5.1, 10.4). Read with one of
 * that faction's leader keys; if none works, the last known answer is kept.
 */
@Service
public class FactionPerkService {

    private static final Logger log = LoggerFactory.getLogger(FactionPerkService.class);

    private final FamilyFactionRepository factions;
    private final TornKeyPool pool;

    public FactionPerkService(FamilyFactionRepository factions, TornKeyPool pool) {
        this.factions = factions;
        this.pool = pool;
    }

    /** Re-reads the perk for one faction and returns the (possibly unchanged) answer. */
    public boolean refresh(long factionId) {
        boolean known = factions.find(factionId).map(FamilyFaction::hospPerkOk).orElse(false);
        try {
            boolean ok = TornChecks.hasHospitalizationX(
                    pool.call(TornKeyPool.Scope.faction(factionId), "/faction/upgrades", Map.of()));
            factions.setHospPerk(factionId, ok);
            return ok;
        } catch (Exception e) {
            log.info("Couldn't check Hospitalization X for faction {} ({}); keeping {}", factionId,
                    e.getMessage(), known);
            return known;
        }
    }

    public void refreshAll() {
        for (FamilyFaction f : factions.findActive()) {
            refresh(f.tornFactionId());
        }
    }

    public boolean has(Long factionId) {
        return factionId != null && factions.find(factionId).map(FamilyFaction::hospPerkOk).orElse(false);
    }
}
