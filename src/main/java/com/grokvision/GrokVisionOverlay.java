package com.grokvision;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class GrokVisionOverlay extends Overlay
{
    private final GrokVisionConfig config;

    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public GrokVisionOverlay(GrokVisionConfig config)
    {
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        panelComponent.setPreferredSize(new Dimension(180, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlay())
        {
            return null;
        }

        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Grok-O-Vision")
            .right("active")
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Port")
            .right(String.valueOf(config.port()))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("")
            .right("See logs for data")
            .build());

        return panelComponent.render(graphics);
    }
}