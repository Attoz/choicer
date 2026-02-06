package com.choicer;

import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPosition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.awt.*;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.choicer.ChoicerConfig;
import com.choicer.ChoicerOverlay;

@RunWith(MockitoJUnitRunner.class)
public class ChoicerOverlayHitboxTest {

@Mock
private Client client;

@Mock
private ItemManager itemManager;

@Mock
    private ChoicerConfig config;

    private ChoicerOverlay choicerOverlay;

@Before
public void setUp() {
// Initialize the overlay with all required dependencies
        choicerOverlay = new ChoicerOverlay(client, itemManager);
        choicerOverlay.setConfig(config);
        choicerOverlay = spy(choicerOverlay);

// Set overlay position and mock bounds
        choicerOverlay.setPosition(OverlayPosition.DYNAMIC);
        doReturn(new Rectangle(100, 100, 400, 200)).when(choicerOverlay).getBounds();
}

@Test
    public void testHitboxDetectionWithSingleOption() {
// Setup test data
        List<Integer> options = Collections.singletonList(123); // Test item ID
        choicerOverlay.setChoicerOptions(options);
        choicerOverlay.setSelectionPending(true);

// Simulate render call which will set up hitboxes
        choicerOverlay.render(mock(Graphics2D.class));

        List<Rectangle> hitboxes = choicerOverlay.getCurrentHitboxes();
        assertEquals("Should create a single hitbox", 1, hitboxes.size());
Point hitPoint = getCenterOfScreenHitbox(hitboxes.get(0));

// Test hit detection at different positions
        Integer result = choicerOverlay.getOptionAt(hitPoint.x, hitPoint.y);
assertNotNull("Should detect click on first button", result);
        assertEquals("Should return the correct item ID", (Integer) 123, result);

// Click outside any button
        Rectangle bounds = choicerOverlay.getBounds();
        result = choicerOverlay.getOptionAt(bounds.x - 20, bounds.y - 20);
assertNull("Should not detect click outside buttons", result);
}

@Test
public void testHitboxDetectionWithMultipleOptions() {
// Setup test data with 3 options
List<Integer> options = List.of(123, 456, 789);
        choicerOverlay.setChoicerOptions(options);
        choicerOverlay.setSelectionPending(true);

// Simulate render call which will set up hitboxes
        choicerOverlay.render(mock(Graphics2D.class));

        List<Rectangle> hitboxes = choicerOverlay.getCurrentHitboxes();
assertEquals("Hitboxes should match option count", options.size(), hitboxes.size());

// Test hit detection for each button
for (int i = 0; i < hitboxes.size(); i++) {
Point center = getCenterOfScreenHitbox(hitboxes.get(i));
            Integer result = choicerOverlay.getOptionAt(center.x, center.y);
assertNotNull("Should detect click on button " + (i + 1), result);
assertEquals("Should return the correct item ID", options.get(i), result);
}

// Click between first and second buttons
        Rectangle bounds = choicerOverlay.getBounds();
Rectangle first = hitboxes.get(0);
Rectangle second = hitboxes.get(1);
int betweenX = bounds.x + first.x + first.width + gapBetween(first, second) / 2;
int betweenY = bounds.y + first.y + first.height / 2;
        Integer result = choicerOverlay.getOptionAt(betweenX, betweenY);
assertNull("Should not detect click between buttons", result);
}

@Test
public void testHitboxEdgeCases() {
// Setup test data with 2 options
List<Integer> options = List.of(123, 456);
        choicerOverlay.setChoicerOptions(options);
        choicerOverlay.setSelectionPending(true);

// Simulate render call which will set up hitboxes
        choicerOverlay.render(mock(Graphics2D.class));

        List<Rectangle> hitboxes = choicerOverlay.getCurrentHitboxes();
assertEquals("Should create hitboxes for each option", options.size(), hitboxes.size());
        Rectangle bounds = choicerOverlay.getBounds();

// Test edge of first button (top-left corner)
Rectangle first = hitboxes.get(0);
        Integer result = choicerOverlay.getOptionAt(bounds.x + first.x, bounds.y + first.y);
assertNotNull("Should detect click on edge of first button", result);
assertEquals("Should return the first item ID", (Integer) 123, result);

// Test between buttons (should not register)
Rectangle second = hitboxes.get(1);
int betweenX = bounds.x + first.x + first.width + gapBetween(first, second) / 2;
int betweenY = bounds.y + first.y + first.height / 2;
        result = choicerOverlay.getOptionAt(betweenX, betweenY);
assertNull("Should not detect click between buttons", result);
}

@Test
public void testNoHitboxWhenNotPending() {
// Setup test data but don't set selection pending
List<Integer> options = Collections.singletonList(123);
        choicerOverlay.setChoicerOptions(options);
// selectionPending is false by default

// Simulate render call which would normally set up hitboxes
        choicerOverlay.render(mock(Graphics2D.class));

// Should not detect any hits even when clicking where a button would be
        Integer result = choicerOverlay.getOptionAt(150, 150);
assertNull("Should not detect any hits when selection is not pending", result);
}

private Point getCenterOfScreenHitbox(Rectangle hitbox) {
        Rectangle bounds = choicerOverlay.getBounds();
int x = bounds.x + hitbox.x + hitbox.width / 2;
int y = bounds.y + hitbox.y + hitbox.height / 2;
return new Point(x, y);
}

private int gapBetween(Rectangle left, Rectangle right) {
return Math.max(1, right.x - (left.x + left.width));
}
}