package com.familyx.mercbot.contract;

import com.familyx.mercbot.discord.Layout;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * #merc-results (scope doc 5.5): one line per contract start, pause and completion (hits join in
 * phase 4). Requesters are never named here.
 */
@Component
public class ResultsFeed {

    private final ObjectProvider<JDA> jda;
    private final Layout layout;

    public ResultsFeed(ObjectProvider<JDA> jda, Layout layout) {
        this.jda = jda;
        this.layout = layout;
    }

    public void post(String line) {
        JDA api = jda.getIfAvailable();
        if (api == null) {
            return;
        }
        layout.get(Layout.CH_RESULTS).map(id -> api.getTextChannelById(id)).ifPresent((TextChannel ch) ->
                ch.sendMessage(line).setAllowedMentions(Collections.emptySet()).queue(null, ignored -> { }));
    }
}
