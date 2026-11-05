package com.familyx.mercbot.discord;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.faction.FamilyFactionRepository;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Who is allowed to press what. */
@Component
public class Access {

    public enum Level {
        /** Anyone who can see the component. */
        ANYONE,
        /** Merc Managers, or a family faction's leader role. */
        FAMILY_LEADER,
        /** Merc Managers only. */
        MERC_MANAGER
    }

    private final BotProperties props;
    private final FamilyFactionRepository factions;

    public Access(BotProperties props, FamilyFactionRepository factions) {
        this.props = props;
        this.factions = factions;
    }

    public boolean allows(Level level, Member member) {
        return switch (level) {
            case ANYONE -> true;
            case MERC_MANAGER -> isMercManager(member);
            case FAMILY_LEADER -> isMercManager(member) || isFactionLeader(member);
        };
    }

    public boolean isMercManager(Member member) {
        return member != null && hasRole(member, Set.of(props.mercManagerRoleId()));
    }

    public boolean isFactionLeader(Member member) {
        if (member == null) {
            return false;
        }
        Set<Long> leaderRoles = factions.findActive().stream()
                .map(FamilyFactionRepository.FamilyFaction::leaderRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return hasRole(member, leaderRoles);
    }

    private static boolean hasRole(Member member, Set<Long> roleIds) {
        return member.getRoles().stream().map(Role::getIdLong).anyMatch(roleIds::contains);
    }
}
