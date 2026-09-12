package com.ztraqto.openxross.api.components;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedContainerTest {

    @Test
    void placesEmbedTextAndControlsInsideOneContainer() {
        var embed = new EmbedBuilder()
                .setTitle("Settings")
                .setDescription("Choose an action.")
                .addField("Scope", "Server", false)
                .setFooter("Xecute")
                .setColor(new Color(88, 101, 242))
                .build();

        var container = EmbedContainer.of(embed, ActionRow.of(Button.primary("open", "Open")));

        assertEquals(new Color(88, 101, 242), container.getAccentColor());
        assertTrue(container.getComponents().stream()
                .filter(component -> component.getType() == Component.Type.TEXT_DISPLAY)
                .map(component -> component.asTextDisplay().getContent())
                .anyMatch(text -> text.contains("Settings") && text.contains("Choose an action.")));
        assertTrue(container.getComponents().stream()
                .anyMatch(component -> component.getType() == Component.Type.ACTION_ROW
                        && component.asActionRow().getButtons().stream()
                        .anyMatch(button -> "open".equals(button.getCustomId()))));
    }
}
