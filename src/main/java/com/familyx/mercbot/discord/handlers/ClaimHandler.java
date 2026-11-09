package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.board.ClaimService;
import com.familyx.mercbot.board.ClaimService.Kind;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.Panels;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.springframework.stereotype.Component;

/**
 * Claim on a board row (scope doc 5.4). The answer is private and closes itself; the board
 * redraws straight away so everyone sees the claimer's name.
 */
@Component
public class ClaimHandler implements InteractionHandler {

    private final ClaimService claims;
    private final BoardService boards;

    public ClaimHandler(ClaimService claims, BoardService boards) {
        this.claims = claims;
        this.boards = boards;
    }

    @Override
    public String action() {
        return "claim";
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        if (!"take".equals(id.sub())) {
            event.reply("Someone already holds this claim.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        long targetId = id.longArg(0);
        event.deferReply(true).queue();
        ClaimService.Result r = claims.claim(event.getUser().getIdLong(), targetId);
        if (r.kind() == Kind.CLAIMED) {
            Thread.ofVirtual().start(boards::refresh);
        }
        String body = r.text() + (r.warnings().isEmpty() ? "" : "\n\n" + String.join("\n", r.warnings()));
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(r.kind() == Kind.CLAIMED ? (r.warnings().stream().anyMatch(w -> w.startsWith("🔴"))
                        ? Panels.BAD : Panels.GOOD) : r.kind() == Kind.BLOCKED ? Panels.BAD : Panels.WARN)
                .setTitle(r.title())
                .setDescription(body);
        event.getHook().editOriginalEmbeds(eb.build())
                .queue(done -> AutoDismiss.after(event.getHook(),
                        r.kind() == Kind.CLAIMED ? AutoDismiss.REPORT : AutoDismiss.SHORT));
    }
}
