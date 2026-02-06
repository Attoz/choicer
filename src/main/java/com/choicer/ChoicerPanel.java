package com.choicer;

import com.choicer.managers.RollAnimationManager;
import com.choicer.managers.RolledItemsManager;
import com.choicer.managers.UnlockedItemsManager;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.border.*;
import java.awt.Component;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
* Panel for displaying rolled and unlocked items.
* It provides UI for manual roll actions, search/filter functionality,
* and displays each item with its icon and full item name.
* Each item panel shows a tooltip on both the icon and the panel with the item name.
*/
public class ChoicerPanel extends PluginPanel
{
    private static final Color BG = new Color(34, 30, 23);
    private static final Color PANEL_BG = new Color(44, 38, 28);
    private static final Color PANEL_BG_ALT = new Color(50, 43, 32);
    private static final Color PANEL_BORDER = new Color(92, 76, 52);
    private static final Color PANEL_HIGHLIGHT = new Color(120, 102, 70);
    private static final Color PANEL_SHADOW = new Color(24, 20, 14);
    private static final Color ACCENT = new Color(201, 168, 92);
    private static final Color TEXT = new Color(232, 217, 175);
    private static final Color TEXT_MUTED = new Color(200, 186, 150);
    private static final Color LIST_ROW_A_TOP = new Color(54, 46, 34);
    private static final Color LIST_ROW_A_BOTTOM = new Color(42, 36, 27);
    private static final Color LIST_ROW_B_TOP = new Color(49, 42, 32);
    private static final Color LIST_ROW_B_BOTTOM = new Color(38, 33, 25);
    private static final Color LIST_ROW_SEPARATOR = new Color(61, 52, 37);
    private static final Color SELECTION_TOP = new Color(109, 90, 55);
    private static final Color SELECTION_BOTTOM = new Color(78, 64, 38);
    private static final Color SELECTION_BORDER = new Color(201, 168, 92);
    private static final Color FIELD_BG = new Color(42, 36, 27);
    private static final Color BUTTON_HOVER = new Color(68, 56, 36);

    private static final Font TITLE_FONT = new Font("Georgia", Font.BOLD, 18);
    private static final Font UI_FONT = new Font("Georgia", Font.PLAIN, 12);
    private static final Font SMALL_FONT = new Font("Georgia", Font.PLAIN, 11);

private final UnlockedItemsManager unlockedItemsManager;
private final RolledItemsManager rolledItemsManager;
private final ItemManager itemManager;
private final HashSet<Integer> allTradeableItems;
private final ClientThread clientThread;
private final RollAnimationManager rollAnimationManager;

// Caches for item icons and names
private final Map<Integer, ImageIcon> itemIconCache = new HashMap<>();
private final Map<Integer, String> itemNameCache = new HashMap<>();

// CardLayout panel to show either Rolled or Unlocked view
private final JPanel centerCardPanel = new JPanel(new CardLayout());
private final DefaultListModel<Integer> rolledModel = new DefaultListModel<>();
private final JList<Integer> rolledList = new JList<>(rolledModel);
private final DefaultListModel<Integer> unlockedModel = new DefaultListModel<>();
private final JList<Integer> unlockedList = new JList<>(unlockedModel);

// View selection row: 3 buttons (swap, filter unlocked-not-rolled, filter unlocked-and-rolled)
    private final JButton swapViewButton = new JButton("🔄");
    private final JToggleButton filterUnlockedNotRolledButton = new JToggleButton("🔓");
    private final JToggleButton filterUnlockedAndRolledButton = new JToggleButton("🔀");

// Flag for current view: true = showing Unlocked, false = showing Rolled
private boolean showingUnlocked = true;

// Search text
private String searchText = "";

// Single count label at the bottom
private final JLabel countLabel = new JLabel("Unlocked: 0/0");

// Roll button for manual roll actions
private final JButton rollButton = new JButton("Roll");

// Active filter: "NONE", "UNLOCKED_NOT_ROLLED", or "UNLOCKED_AND_ROLLED"
private String activeFilter = "NONE";

// Default color for item text
    private final Color defaultItemTextColor = TEXT;

/**
     * Constructs a ChoicerPanel.
    *
    * @param unlockedItemsManager Manager for unlocked items.
    * @param rolledItemsManager   Manager for rolled items.
    * @param itemManager          The item manager.
    * @param allTradeableItems    List of all tradeable item IDs.
    * @param clientThread         The client thread for scheduling UI updates.
    * @param rollAnimationManager The roll animation manager to trigger animations.
    */
    public ChoicerPanel(
UnlockedItemsManager unlockedItemsManager,
RolledItemsManager rolledItemsManager,
ItemManager itemManager,
HashSet<Integer> allTradeableItems,
ClientThread clientThread,
RollAnimationManager rollAnimationManager
)
{
this.unlockedItemsManager = unlockedItemsManager;
this.rolledItemsManager = rolledItemsManager;
this.itemManager = itemManager;
this.allTradeableItems = allTradeableItems;
this.clientThread = clientThread;
this.rollAnimationManager = rollAnimationManager;
init();
}

/**
    * Initializes the panel UI components.
    */
private void init()
{
setLayout(new BorderLayout());
setBorder(new EmptyBorder(15, 15, 15, 15));
        setBackground(BG);

// ========== TOP PANEL (Header, Search, Buttons Row) ==========
JPanel topPanel = new JPanel();
topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
topPanel.setOpaque(false);

// Header
topPanel.add(buildHeaderPanel());
topPanel.add(Box.createVerticalStrut(10));

// Search Bar
topPanel.add(buildSearchBar());
topPanel.add(Box.createVerticalStrut(10));

// Button row: 3 columns, each for one button
JPanel buttonRowPanel = new JPanel(new GridLayout(1, 3, 10, 0));
buttonRowPanel.setOpaque(false);

// Style the 3 buttons identically
styleButton(swapViewButton);
styleToggleButton(filterUnlockedNotRolledButton);
styleToggleButton(filterUnlockedAndRolledButton);

// Tooltips & actions
swapViewButton.setToolTipText("Swap between Unlocked and Rolled views");
swapViewButton.addActionListener(e -> toggleView());

filterUnlockedNotRolledButton.setToolTipText("Filter: Show items that are unlocked but not rolled");
filterUnlockedNotRolledButton.addActionListener(e ->
{
if (filterUnlockedNotRolledButton.isSelected())
{
activeFilter = "UNLOCKED_NOT_ROLLED";
filterUnlockedAndRolledButton.setSelected(false);
}
else
{
activeFilter = "NONE";
}
updatePanel();
});

filterUnlockedAndRolledButton.setToolTipText("Filter: Show items that are both unlocked and rolled");
filterUnlockedAndRolledButton.addActionListener(e ->
{
if (filterUnlockedAndRolledButton.isSelected())
{
activeFilter = "UNLOCKED_AND_ROLLED";
filterUnlockedNotRolledButton.setSelected(false);
}
else
{
activeFilter = "NONE";
}
updatePanel();
});

// Add them in left->right order
buttonRowPanel.add(swapViewButton);
buttonRowPanel.add(filterUnlockedNotRolledButton);
buttonRowPanel.add(filterUnlockedAndRolledButton);

// Add the row to the top panel
topPanel.add(buttonRowPanel);

// EXTRA SPACE between the buttons row and the icon panel
topPanel.add(Box.createVerticalStrut(10));

add(topPanel, BorderLayout.NORTH);

// ========== CENTER PANEL (CardLayout) ==========
rolledList.setCellRenderer(new ItemCellRenderer());
rolledList.setVisibleRowCount(10);
rolledList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
JScrollPane rolledScroll = new JScrollPane(
rolledList,
JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
);
rolledScroll.setPreferredSize(new Dimension(250, 300));
        applyListTheme(rolledList, rolledScroll);
JPanel rolledContainer = createTitledPanel("Rolled Items", rolledScroll);

// Use a custom cell renderer that can handle long text
unlockedList.setCellRenderer(new ItemCellRenderer());
unlockedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
unlockedList.setLayoutOrientation(JList.VERTICAL);
unlockedList.setVisibleRowCount(-1);

// Create a scroll pane with horizontal scrolling enabled
JScrollPane unlockedScroll = new JScrollPane(
unlockedList,
JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
);
unlockedScroll.setPreferredSize(new Dimension(250, 300)); // Set a reasonable default size
unlockedScroll.setWheelScrollingEnabled(true);
        applyListTheme(unlockedList, unlockedScroll);
JPanel unlockedContainer = createTitledPanel("Unlocked Items", unlockedScroll);

centerCardPanel.add(rolledContainer, "ROLLED");
centerCardPanel.add(unlockedContainer, "UNLOCKED");
add(centerCardPanel, BorderLayout.CENTER);

// ========== BOTTOM PANEL (Count + Roll) ==========
JPanel bottomPanel = new JPanel();
bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
bottomPanel.setOpaque(false);

// Single count label
JPanel countPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
countPanel.setOpaque(false);
        countLabel.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        countLabel.setForeground(TEXT_MUTED);
countPanel.add(countLabel);
bottomPanel.add(countPanel);
bottomPanel.add(Box.createVerticalStrut(10));

// Roll button
JPanel rollButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
rollButtonPanel.setOpaque(false);
rollButton.setPreferredSize(new Dimension(100, 30));
rollButton.setFocusPainted(false);
        rollButton.setBackground(PANEL_BG_ALT);
        rollButton.setForeground(TEXT);
        rollButton.setFont(UI_FONT.deriveFont(Font.BOLD));
        rollButton.setBorder(new LineBorder(ACCENT));
        installButtonHover(rollButton, PANEL_BG_ALT);
rollButton.addActionListener(this::performManualRoll);
rollButtonPanel.add(rollButton);
bottomPanel.add(rollButtonPanel);

add(bottomPanel, BorderLayout.SOUTH);

// Default to Unlocked view
showingUnlocked = true;
((CardLayout) centerCardPanel.getLayout()).show(centerCardPanel, "UNLOCKED");
updatePanel();
}

/**
    * Renders each item ID as an icon + name in the JList.
    */
private class ItemCellRenderer extends JPanel implements ListCellRenderer<Integer>
{
private final JLabel iconLabel = new JLabel();
private final JLabel nameLabel = new JLabel();
        private final int PREFERRED_HEIGHT = 38; // Height for each row
        private boolean selected = false;
        private boolean evenRow = false;

public ItemCellRenderer()
{
            setLayout(new BorderLayout(8, 0));
            setOpaque(false);
add(iconLabel, BorderLayout.WEST);
add(nameLabel, BorderLayout.CENTER);
            nameLabel.setFont(SMALL_FONT);
            iconLabel.setBorder(new EmptyBorder(0, 2, 0, 4));
            iconLabel.setOpaque(false);
            nameLabel.setOpaque(false);
}

@Override
public Dimension getPreferredSize()
{
Dimension preferred = super.getPreferredSize();
preferred.height = PREFERRED_HEIGHT;
return preferred;
}

@Override
public Component getListCellRendererComponent(JList<? extends Integer> list,
Integer itemId,
int index,
boolean isSelected,
boolean cellHasFocus)
{
// icon
iconLabel.setIcon(getItemIcon(itemId));

// name (async load if missing)
String name = itemNameCache.get(itemId);
if (name == null)
{
nameLabel.setText("Loading…");
getItemNameAsync(itemId, n ->
{
itemNameCache.put(itemId, n);
list.repaint(list.getCellBounds(index, index));
});
}
else
{
nameLabel.setText(name);
}

// selection styling
            selected = isSelected;
            evenRow = (index % 2 == 0);
            nameLabel.setForeground(isSelected ? TEXT : defaultItemTextColor);

            Border line = isSelected
                    ? new LineBorder(SELECTION_BORDER)
                    : new MatteBorder(0, 0, 1, 0, LIST_ROW_SEPARATOR);
            setBorder(new CompoundBorder(line, new EmptyBorder(4, 8, 4, 8)));

// Let Swing compute width from the label's preferred size
nameLabel.setPreferredSize(null);
setPreferredSize(null);

return this;
}

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                Color top;
                Color bottom;
                if (selected)
                {
                    top = SELECTION_TOP;
                    bottom = SELECTION_BOTTOM;
                }
                else if (evenRow)
                {
                    top = LIST_ROW_A_TOP;
                    bottom = LIST_ROW_A_BOTTOM;
                }
                else
                {
                    top = LIST_ROW_B_TOP;
                    bottom = LIST_ROW_B_BOTTOM;
                }
                g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            finally
            {
                g2.dispose();
            }
            super.paintComponent(g);
        }
}

/**
    * Toggles between Unlocked view and Rolled view.
    */
private void toggleView()
{
showingUnlocked = !showingUnlocked;
CardLayout cl = (CardLayout) centerCardPanel.getLayout();
if (showingUnlocked)
{
cl.show(centerCardPanel, "UNLOCKED");
}
else
{
cl.show(centerCardPanel, "ROLLED");
}
updatePanel();
}

/**
    * Creates a titled container panel that wraps the given content panel.
    *
    * @param title        The title to display on the border.
    * @return The container panel.
    */
private JPanel createTitledPanel(String title, Component content)
{
JPanel container = new JPanel(new BorderLayout());
container.setOpaque(false);

        Border line = new LineBorder(PANEL_BORDER);
        Border inner = new MatteBorder(1, 1, 1, 1, PANEL_HIGHLIGHT);
        Border empty = new EmptyBorder(6, 6, 6, 6);
TitledBorder titled = BorderFactory.createTitledBorder(line, title);
        titled.setTitleColor(TEXT_MUTED);
        titled.setTitleFont(UI_FONT.deriveFont(Font.BOLD));
        container.setBorder(new CompoundBorder(new CompoundBorder(titled, inner), empty));

// Wrap content in a panel with FlowLayout to prevent horizontal stretching
JPanel contentWrapper = new JPanel(new BorderLayout());
contentWrapper.setOpaque(false);
contentWrapper.add(content, BorderLayout.CENTER);

// Add the wrapper to the container
container.add(contentWrapper, BorderLayout.CENTER);
return container;
}

/**
    * Styles a general JButton to match the design.
    *
    * @param button The button to style.
    */
private void styleButton(JButton button)
{
button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(PANEL_BG_ALT);
        button.setForeground(TEXT);
        button.setFont(UI_FONT.deriveFont(Font.BOLD));
        button.setBorder(new LineBorder(ACCENT));
button.setPreferredSize(new Dimension(50, 30));
        installButtonHover(button, PANEL_BG_ALT);
}

/**
    * Styles a JToggleButton to match the design.
    *
    * @param button The toggle button to style.
    */
    private void styleToggleButton(JToggleButton button)
{
button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setForeground(TEXT);
        button.setFont(UI_FONT.deriveFont(Font.BOLD));
        button.setBorder(new LineBorder(ACCENT));
        button.addChangeListener(e -> applyToggleState(button));
        applyToggleState(button);
button.setPreferredSize(new Dimension(50, 30));
        installButtonHover(button, PANEL_BG_ALT);
    }

    private void applyToggleState(JToggleButton button)
    {
        if (button.isSelected())
        {
            button.setBackground(SELECTION_BOTTOM);
            button.setForeground(TEXT);
        }
        else
        {
            button.setBackground(PANEL_BG_ALT);
            button.setForeground(TEXT_MUTED);
        }
    }

    private void installButtonHover(AbstractButton button, Color normal)
    {
        button.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e)
            {
                if (!button.isEnabled()) return;
                button.setBackground(BUTTON_HOVER);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e)
            {
                if (!button.isEnabled()) return;
                if (button instanceof JToggleButton && ((JToggleButton) button).isSelected())
                {
                    button.setBackground(SELECTION_BOTTOM);
                }
                else
                {
                    button.setBackground(normal);
                }
            }
        });
    }

    private void applyListTheme(JList<Integer> list, JScrollPane scroll)
    {
        list.setBackground(PANEL_BG);
        list.setForeground(TEXT);
        list.setSelectionBackground(SELECTION_BOTTOM);
        list.setSelectionForeground(TEXT);
        list.setFixedCellHeight(-1);

        scroll.getViewport().setBackground(PANEL_BG);
        scroll.setBorder(new CompoundBorder(
                new CompoundBorder(new LineBorder(PANEL_BORDER), new MatteBorder(1, 1, 1, 1, PANEL_SHADOW)),
                new EmptyBorder(4, 4, 4, 4)
        ));
        scroll.getVerticalScrollBar().setBackground(PANEL_BG);
        scroll.getHorizontalScrollBar().setBackground(PANEL_BG);
}

/**
    * Builds the header panel with an icon and title.
    *
    * @return The header panel.
    */
private JPanel buildHeaderPanel()
{
JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
headerPanel.setOpaque(false);

// Header icon
        ImageIcon headerIcon = new ImageIcon(getClass().getResource("/com/choicer/icon.png"));
JLabel iconLabel = new JLabel(headerIcon);

// Title label
        JLabel titleLabel = new JLabel("Choicer");
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(TEXT);

// Create the Discord button
JButton discordButton = new JButton();
        discordButton.setToolTipText("Join The Choicer Discord");
// Scale the Discord icon to 16x16
        ImageIcon discordIcon = new ImageIcon(getClass().getResource("/com/choicer/discord.png"));
Image scaledImage = discordIcon.getImage().getScaledInstance(20, 20, Image.SCALE_SMOOTH);
discordButton.setIcon(new ImageIcon(scaledImage));
// Make the button look flat (no border or background)
discordButton.setOpaque(false);
discordButton.setContentAreaFilled(false);
discordButton.setBorderPainted(false);
// Add an action to open the Discord link
        discordButton.addActionListener(e -> LinkBrowser.browse("https://discord.gg/jhJYKpS7G8"));

// Assemble the header panel
headerPanel.add(iconLabel);
headerPanel.add(Box.createHorizontalStrut(10));
headerPanel.add(titleLabel);
headerPanel.add(discordButton);

return headerPanel;
}

/**
    * Builds the search bar panel.
    *
    * @return The search bar panel.
    */
private JPanel buildSearchBar()
{
JPanel searchBarPanel = new JPanel(new BorderLayout());
searchBarPanel.setOpaque(false);
searchBarPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

JPanel searchContainer = new JPanel(new BorderLayout());
        searchContainer.setBackground(FIELD_BG);
        searchContainer.setBorder(new CompoundBorder(
                new CompoundBorder(new LineBorder(PANEL_BORDER), new MatteBorder(1, 1, 1, 1, PANEL_HIGHLIGHT)),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));

// Search icon
        JLabel searchIcon = new JLabel("\uD83D\uDD0D");
        searchIcon.setForeground(TEXT_MUTED);
searchIcon.setBorder(new EmptyBorder(0, 5, 0, 5));
searchContainer.add(searchIcon, BorderLayout.WEST);

// Search field
JTextField searchField = new JTextField();
        searchField.setBackground(FIELD_BG);
        searchField.setForeground(TEXT);
searchField.setBorder(null);
        searchField.setCaretColor(TEXT);
searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
searchField.addKeyListener(new KeyAdapter()
{
@Override
public void keyReleased(KeyEvent e)
{
SwingUtilities.invokeLater(() ->
{
searchText = searchField.getText().toLowerCase();
updatePanel();
});
}
});
searchContainer.add(searchField, BorderLayout.CENTER);

// Clear label to reset search
        JLabel clearLabel = new JLabel("❌");
        clearLabel.setFont(SMALL_FONT.deriveFont(9f));
        clearLabel.setForeground(TEXT_MUTED);
clearLabel.setBorder(new EmptyBorder(0, 6, 0, 6));
clearLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
clearLabel.addMouseListener(new java.awt.event.MouseAdapter()
{
@Override
public void mouseClicked(java.awt.event.MouseEvent e)
{
searchField.setText("");
searchText = "";
updatePanel();
}
});
searchContainer.add(clearLabel, BorderLayout.EAST);

searchBarPanel.add(searchContainer, BorderLayout.CENTER);
return searchBarPanel;
}

/**
    * Triggers a manual roll animation when the Roll button is clicked.
    *
    * @param e The action event.
    */
private void performManualRoll(java.awt.event.ActionEvent e)
{
if (rollAnimationManager.isRolling())
{
return;
}
List<Integer> locked = new ArrayList<>();
for (int id : allTradeableItems)
{
if (!unlockedItemsManager.isUnlocked(id))
{
locked.add(id);
}
}
if (locked.isEmpty())
{
JOptionPane.showMessageDialog(
this,
"All items are unlocked!",
                    "Choicer",
JOptionPane.INFORMATION_MESSAGE
);
return;
}
int randomItemId = locked.get(new Random().nextInt(locked.size()));
rollAnimationManager.setManualRoll(true);
rollAnimationManager.enqueueRoll(randomItemId);
}

/**
    * Main update routine: filters the active set (Unlocked or Rolled), applies search text and filter toggles,
    * updates the single count label, and then builds the item list without trailing gaps.
    */
public void updatePanel()
{
clientThread.invokeLater(() ->
{
// Build filtered lists
List<Integer> filteredRolled = new ArrayList<>();
for (Integer id : rolledItemsManager.getRolledItems())
{
ItemComposition comp = itemManager.getItemComposition(id);
String sanitizedName = sanitizeItemName(comp != null ? comp.getName() : null);
if (sanitizedName == null)
{
continue;
}
String lowerName = sanitizedName.toLowerCase();
if (searchText.isEmpty() || lowerName.contains(searchText))
{
filteredRolled.add(id);
}
}

Collections.reverse(filteredRolled);

List<Integer> filteredUnlocked = new ArrayList<>();
for (Integer id : unlockedItemsManager.getUnlockedItems())
{
ItemComposition comp = itemManager.getItemComposition(id);
String sanitizedName = sanitizeItemName(comp != null ? comp.getName() : null);
if (sanitizedName == null)
{
continue;
}
String lowerName = sanitizedName.toLowerCase();
if (searchText.isEmpty() || lowerName.contains(searchText))
{
filteredUnlocked.add(id);
}
}

Collections.reverse(filteredUnlocked);

// Apply active filter toggles
if (activeFilter.equals("UNLOCKED_NOT_ROLLED"))
{
filteredUnlocked.removeIf(id -> rolledItemsManager.getRolledItems().contains(id));
filteredRolled.clear();
}
else if (activeFilter.equals("UNLOCKED_AND_ROLLED"))
{
filteredUnlocked.removeIf(id -> !rolledItemsManager.getRolledItems().contains(id));
filteredRolled.removeIf(id -> !unlockedItemsManager.getUnlockedItems().contains(id));
}

SwingUtilities.invokeLater(() ->
{
rolledModel.clear();
for (int id : filteredRolled)
{
rolledModel.addElement(id);
}

unlockedModel.clear();
for (int id : filteredUnlocked)
{
unlockedModel.addElement(id);
}

int total = allTradeableItems.size();
countLabel.setText(showingUnlocked
? "Unlocked: " + unlockedModel.size() + "/" + total
: "Rolled:  " + rolledModel.size()   + "/" + total);
});
});
}

/**
    * Retrieves (and caches) the item icon for a given item ID.
    *
    * @param itemId The item ID.
    * @return The ImageIcon for the item, or null if not available.
    */
private ImageIcon getItemIcon(int itemId)
{
if (itemIconCache.containsKey(itemId))
{
return itemIconCache.get(itemId);
}
BufferedImage image = itemManager.getImage(itemId, 1, false);
if (image == null)
{
return null;
}
ImageIcon icon = new ImageIcon(image);
itemIconCache.put(itemId, icon);
return icon;
}

/**
    * Asynchronously retrieves the item name for a given item ID and passes it to the callback.
    *
    * @param itemId   The item ID.
    * @param callback Consumer to receive the item name.
    */
private void getItemNameAsync(int itemId, Consumer<String> callback)
{
clientThread.invokeLater(() -> {
ItemComposition comp = itemManager.getItemComposition(itemId);
String name = sanitizeItemName(comp != null ? comp.getName() : null);
SwingUtilities.invokeLater(() -> callback.accept(name != null ? name : "Unknown"));
});
}

private String sanitizeItemName(String rawName)
{
if (rawName == null)
{
return null;
}
String trimmed = rawName.trim();
if (trimmed.isEmpty()
|| trimmed.equalsIgnoreCase("null")
|| trimmed.equalsIgnoreCase("Members")
|| trimmed.equalsIgnoreCase("(Members)")
|| trimmed.matches("(?i)null\\s*\\(Members\\)"))
{
return null;
}
return trimmed;
}
}
