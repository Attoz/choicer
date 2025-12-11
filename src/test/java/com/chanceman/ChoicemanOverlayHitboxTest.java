package com.chanceman;

import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.awt.*;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class ChoicemanOverlayHitboxTest {

    @Mock
    private Client client;

    @Mock
    private ItemManager itemManager;

    @Mock
    private ChanceManConfig config;

    private ChoicemanOverlay choicemanOverlay;

    @Before
    public void setUp() {
        // Initialize the overlay with all required dependencies
        choicemanOverlay = new ChoicemanOverlay(client, itemManager);
        choicemanOverlay.setConfig(config);
        choicemanOverlay = spy(choicemanOverlay);
        
        // Set overlay position and mock bounds
        choicemanOverlay.setPosition(OverlayPosition.DYNAMIC);
        doReturn(new Rectangle(100, 100, 400, 200)).when(choicemanOverlay).getBounds();
    }

    @Test
    public void testHitboxDetectionWithSingleOption() {
        // Setup test data
        List<Integer> options = Collections.singletonList(123); // Test item ID
        choicemanOverlay.setChoicemanOptions(options);
        choicemanOverlay.setSelectionPending(true);

        // Simulate render call which will set up hitboxes
        choicemanOverlay.render(mock(Graphics2D.class));

        List<Rectangle> hitboxes = choicemanOverlay.getCurrentHitboxes();
        assertEquals("Should create a single hitbox", 1, hitboxes.size());
        Point hitPoint = getCenterOfScreenHitbox(hitboxes.get(0));

        // Test hit detection at different positions
        Integer result = choicemanOverlay.getOptionAt(hitPoint.x, hitPoint.y);
        assertNotNull("Should detect click on first button", result);
        assertEquals("Should return the correct item ID", (Integer) 123, result);

        // Click outside any button
        Rectangle bounds = choicemanOverlay.getBounds();
        result = choicemanOverlay.getOptionAt(bounds.x - 20, bounds.y - 20);
        assertNull("Should not detect click outside buttons", result);
    }

    @Test
    public void testHitboxDetectionWithMultipleOptions() {
        // Setup test data with 3 options
        List<Integer> options = List.of(123, 456, 789);
        choicemanOverlay.setChoicemanOptions(options);
        choicemanOverlay.setSelectionPending(true);

        // Simulate render call which will set up hitboxes
        choicemanOverlay.render(mock(Graphics2D.class));

        List<Rectangle> hitboxes = choicemanOverlay.getCurrentHitboxes();
        assertEquals("Hitboxes should match option count", options.size(), hitboxes.size());

        // Test hit detection for each button
        for (int i = 0; i < hitboxes.size(); i++) {
            Point center = getCenterOfScreenHitbox(hitboxes.get(i));
            Integer result = choicemanOverlay.getOptionAt(center.x, center.y);
            assertNotNull("Should detect click on button " + (i + 1), result);
            assertEquals("Should return the correct item ID", options.get(i), result);
        }

        // Click between first and second buttons
        Rectangle bounds = choicemanOverlay.getBounds();
        Rectangle first = hitboxes.get(0);
        Rectangle second = hitboxes.get(1);
        int betweenX = bounds.x + first.x + first.width + gapBetween(first, second) / 2;
        int betweenY = bounds.y + first.y + first.height / 2;
        Integer result = choicemanOverlay.getOptionAt(betweenX, betweenY);
        assertNull("Should not detect click between buttons", result);
    }

    @Test
    public void testHitboxEdgeCases() {
        // Setup test data with 2 options
        List<Integer> options = List.of(123, 456);
        choicemanOverlay.setChoicemanOptions(options);
        choicemanOverlay.setSelectionPending(true);

        // Simulate render call which will set up hitboxes
        choicemanOverlay.render(mock(Graphics2D.class));

        List<Rectangle> hitboxes = choicemanOverlay.getCurrentHitboxes();
        assertEquals("Should create hitboxes for each option", options.size(), hitboxes.size());
        Rectangle bounds = choicemanOverlay.getBounds();

        // Test edge of first button (top-left corner)
        Rectangle first = hitboxes.get(0);
        Integer result = choicemanOverlay.getOptionAt(bounds.x + first.x, bounds.y + first.y);
        assertNotNull("Should detect click on edge of first button", result);
        assertEquals("Should return the first item ID", (Integer) 123, result);

        // Test between buttons (should not register)
        Rectangle second = hitboxes.get(1);
        int betweenX = bounds.x + first.x + first.width + gapBetween(first, second) / 2;
        int betweenY = bounds.y + first.y + first.height / 2;
        result = choicemanOverlay.getOptionAt(betweenX, betweenY);
        assertNull("Should not detect click between buttons", result);
    }

    @Test
    public void testNoHitboxWhenNotPending() {
        // Setup test data but don't set selection pending
        List<Integer> options = Collections.singletonList(123);
        choicemanOverlay.setChoicemanOptions(options);
        // selectionPending is false by default

        // Simulate render call which would normally set up hitboxes
        choicemanOverlay.render(mock(Graphics2D.class));

        // Should not detect any hits even when clicking where a button would be
        Integer result = choicemanOverlay.getOptionAt(150, 150);
        assertNull("Should not detect any hits when selection is not pending", result);
    }

    private Point getCenterOfScreenHitbox(Rectangle hitbox) {
        Rectangle bounds = choicemanOverlay.getBounds();
        int x = bounds.x + hitbox.x + hitbox.width / 2;
        int y = bounds.y + hitbox.y + hitbox.height / 2;
        return new Point(x, y);
    }

    private int gapBetween(Rectangle left, Rectangle right) {
        return Math.max(1, right.x - (left.x + left.width));
    }
}
