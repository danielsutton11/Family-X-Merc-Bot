package com.familyx.mercbot.discord.handlers;

import com.familyx.mercbot.board.BoardService;
import com.familyx.mercbot.board.BoardService.Board;
import com.familyx.mercbot.discord.AutoDismiss;
import com.familyx.mercbot.discord.CustomId;
import com.familyx.mercbot.discord.InteractionHandler;
import com.familyx.mercbot.discord.ScreenTimeout;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * "See all" on a tier board: a merc's own pages of that board, 10 targets each (scope doc 5.3).
 * The shared board stays on the top 10, so paging never moves it for anyone else.
 */
@Component
public class BoardHandler implements InteractionHandler {

    private final BoardService boards;

    public BoardHandler(BoardService boards) {
        this.boards = boards;
    }

    @Override
    public String action() {
        return "board";
    }

    @Override
    public void button(ButtonInteractionEvent event, CustomId id) {
        int tierId = Integer.parseInt(id.arg(0));
        int page = Integer.parseInt(id.arg(1));
        // Built from the database only (BSP comes from its cache), so it answers well within Discord's 3 seconds.
        Board board = boards.boardFor(tierId);
        if (board == null) {
            event.reply("That board no longer exists.").setEphemeral(true)
                    .queue(h -> AutoDismiss.after(h, AutoDismiss.SHORT));
            return;
        }
        List<MessageTopLevelComponent> parts = BoardService.drawPage(board, page, Instant.now());
        if (event.getMessage().isEphemeral()) {
            long messageId = event.getMessage().getIdLong();
            event.editMessage(new MessageEditBuilder().setReplace(true).useComponentsV2().setComponents(parts).build())
                    .queue(h -> ScreenTimeout.restart(messageId, h));
        } else {
            event.replyComponents(parts).useComponentsV2().setEphemeral(true).queue(ScreenTimeout::restart);
        }
    }
}
