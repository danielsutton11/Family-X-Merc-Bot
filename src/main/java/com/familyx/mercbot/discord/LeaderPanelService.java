package com.familyx.mercbot.discord;

import com.familyx.mercbot.discord.Panels.Panel;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Keeps the pinned leader panel's numbers current (debounced through {@link MessageUpdater}). */
@Service
public class LeaderPanelService {

    private final Layout layout;
    private final Panels panels;
    private final MessageUpdater updater;

    public LeaderPanelService(Layout layout, Panels panels, MessageUpdater updater) {
        this.layout = layout;
        this.panels = panels;
        this.updater = updater;
    }

    public void refresh() {
        updater.request("leader-panel", () -> {
            Optional<Long> channel = layout.get(Layout.CH_LEADERS);
            Optional<Long> message = layout.get(Layout.MSG_LEADERS);
            if (channel.isEmpty() || message.isEmpty()) {
                return Optional.empty();
            }
            Panel p = panels.leaderPanel();
            return Optional.of(new MessageUpdater.Edit(channel.get(), message.get(),
                    new MessageEditBuilder().setEmbeds(p.embed()).setComponents(p.rows()).build()));
        });
    }
}
