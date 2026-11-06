package com.familyx.mercbot.discord;

import com.familyx.mercbot.config.BotProperties;
import com.familyx.mercbot.discord.Panels.Panel;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/** Re-draws the pinned panels that show tiers after a tier or price changes. */
@Service
public class PanelRefresher {

    private final Layout layout;
    private final Panels panels;
    private final MessageUpdater updater;
    private final LeaderPanelService leaderPanel;
    private final BotProperties props;

    public PanelRefresher(Layout layout, Panels panels, MessageUpdater updater, LeaderPanelService leaderPanel,
                          BotProperties props) {
        this.layout = layout;
        this.panels = panels;
        this.updater = updater;
        this.leaderPanel = leaderPanel;
        this.props = props;
    }

    public void tiersChanged() {
        edit("request-panel", Optional.of(props.requestsChannelId()), Layout.MSG_REQUEST_PANEL, panels::requestPanel);
        edit("signup-panel", layout.get(Layout.CH_SIGNUP), Layout.MSG_SIGNUP, panels::signupPanel);
        leaderPanel.refresh();
    }

    private void edit(String key, Optional<Long> channel, String messageKey, Supplier<Panel> panel) {
        updater.request(key, () -> {
            Optional<Long> message = layout.get(messageKey);
            if (channel.isEmpty() || channel.get() == 0 || message.isEmpty()) {
                return Optional.empty();
            }
            Panel p = panel.get();
            return Optional.of(new MessageUpdater.Edit(channel.get(), message.get(),
                    new MessageEditBuilder().setEmbeds(p.embed()).setComponents(p.rows()).build()));
        });
    }
}
