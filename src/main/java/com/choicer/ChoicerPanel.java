package com.choicer;

import com.choicer.managers.ObtainedItemsManager;
import com.choicer.managers.RollAnimationManager;
import com.choicer.managers.RolledItemsManager;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.BorderFactory;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Panel for displaying rolled/obtained items with list-based filtering.
 */
public class ChoicerPanel extends PluginPanel
{
    private static final Color BG = new Color(34, 30, 23);
    private static final Color PANEL_BG = new Color(44, 38, 28);
    private static final Color PANEL_BG_ALT = new Color(50, 43, 32);
    private static final Color PANEL_BORDER = new Color(92, 76, 52);
    private static final Color ACCENT = new Color(201, 168, 92);
    private static final Color TEXT = new Color(232, 217, 175);
    private static final Color TEXT_MUTED = new Color(200, 186, 150);
    private static final Color LIST_ROW_A = new Color(48, 42, 32);
    private static final Color LIST_ROW_B = new Color(42, 36, 27);
    private static final Color SELECTION = new Color(90, 75, 50);
    private static final Color FIELD_BG = new Color(42, 36, 27);
    private static final Color BUTTON_BG = new Color(60, 63, 65);

    private static final Font TITLE_FONT = new Font("Georgia", Font.BOLD, 18);
    private static final Font UI_FONT = new Font("Georgia", Font.PLAIN, 12);
    private static final Font SMALL_FONT = new Font("Georgia", Font.PLAIN, 11);

    private enum ListMode
    {
        ROLLED("Rolled"),
        OBTAINED("Obtained"),
        ROLLED_NOT_OBTAINED("Rolled, not Obtained"),
        USABLE("Usable");

        private final String label;

        ListMode(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    private static final Map<ListMode, String> MODE_TOOLTIPS = new EnumMap<>(ListMode.class);
    static
    {
        MODE_TOOLTIPS.put(ListMode.ROLLED, "Show items that have been rolled (unlocked)");
        MODE_TOOLTIPS.put(ListMode.OBTAINED, "Show items that have been obtained in-game");
        MODE_TOOLTIPS.put(ListMode.ROLLED_NOT_OBTAINED, "Show rolled items you haven't obtained yet");
        MODE_TOOLTIPS.put(ListMode.USABLE, "Show items that are both rolled and obtained");
    }

    private final ObtainedItemsManager obtainedItemsManager;
    private final RolledItemsManager rolledItemsManager;
    private final ItemManager itemManager;
    private final HashSet<Integer> allTradeableItems;
    private final ClientThread clientThread;
    private final RollAnimationManager rollAnimationManager;

    private final Map<Integer, ImageIcon> itemIconCache = new HashMap<>();
    private final Map<Integer, String> itemNameCache = new HashMap<>();
    private final Set<Integer> iconFetchInFlight = ConcurrentHashMap.newKeySet();
    private final Set<Integer> nameFetchInFlight = ConcurrentHashMap.newKeySet();

    private final DefaultListModel<Integer> listModel = new DefaultListModel<>();
    private final JList<Integer> itemList = new JList<>(listModel);
    private final JTextField searchField = new JTextField();
    private final JLabel countLabel = new JLabel("Rolled: 0/0");
    private final JComboBox<ListMode> modeDropdown = new JComboBox<>(ListMode.values());

    private ListMode listMode = ListMode.ROLLED;
    private String searchText = "";

    public ChoicerPanel(
            ObtainedItemsManager obtainedItemsManager,
            RolledItemsManager rolledItemsManager,
            ItemManager itemManager,
            HashSet<Integer> allTradeableItems,
            ClientThread clientThread,
            RollAnimationManager rollAnimationManager
    )
    {
        this.obtainedItemsManager = obtainedItemsManager;
        this.rolledItemsManager = rolledItemsManager;
        this.itemManager = itemManager;
        this.allTradeableItems = allTradeableItems;
        this.clientThread = clientThread;
        this.rollAnimationManager = rollAnimationManager;
        init();
    }

    private void init()
    {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setBackground(BG);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.add(buildHeaderPanel());
        top.add(Box.createVerticalStrut(8));
        top.add(buildSearchBar());
        top.add(Box.createVerticalStrut(8));
        top.add(buildFilterRow());
        top.add(Box.createVerticalStrut(8));

        add(top, BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
    }

    private JPanel buildHeaderPanel()
    {
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        headerPanel.setOpaque(false);

        BufferedImage headerImage = ImageUtil.loadImageResource(getClass(), "/com/choicer/icon.png");
        ImageIcon headerIcon = headerImage != null ? new ImageIcon(headerImage) : null;
        JLabel iconLabel = new JLabel(headerIcon);

        JLabel titleLabel = new JLabel("Choicer");
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(TEXT);

        JButton discordButton = new JButton();
        discordButton.setToolTipText("Join the Choicer Discord");
        BufferedImage discordImage = ImageUtil.loadImageResource(getClass(), "/com/choicer/discord.png");
        if (discordImage != null)
        {
            Image scaledImage = discordImage.getScaledInstance(20, 20, Image.SCALE_SMOOTH);
            discordButton.setIcon(new ImageIcon(scaledImage));
        }
        discordButton.setOpaque(false);
        discordButton.setContentAreaFilled(false);
        discordButton.setBorderPainted(false);
        discordButton.addActionListener(e -> LinkBrowser.browse("https://discord.gg/TMkAYXxncU"));

        headerPanel.add(iconLabel);
        headerPanel.add(titleLabel);
        headerPanel.add(Box.createHorizontalStrut(8));
        headerPanel.add(discordButton);
        return headerPanel;
    }

    private JPanel buildSearchBar()
    {
        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);

        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setBackground(FIELD_BG);
        searchBox.setBorder(new EmptyBorder(6, 8, 6, 8));

        JLabel icon = new JLabel("\uD83D\uDD0D");
        icon.setForeground(TEXT_MUTED);
        icon.setBorder(new EmptyBorder(0, 0, 0, 6));
        searchBox.add(icon, BorderLayout.WEST);

        searchField.setBackground(FIELD_BG);
        searchField.setForeground(TEXT);
        searchField.setBorder(null);
        searchField.setCaretColor(TEXT);
        searchField.setFont(UI_FONT);
        searchField.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyReleased(KeyEvent e)
            {
                searchText = searchField.getText().toLowerCase();
                updatePanel();
            }
        });

        searchBox.add(searchField, BorderLayout.CENTER);
        container.add(searchBox, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildFilterRow()
    {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);

        JLabel label = new JLabel("Filter");
        label.setFont(UI_FONT.deriveFont(Font.BOLD));
        label.setForeground(TEXT_MUTED);

        modeDropdown.setSelectedItem(listMode);
        modeDropdown.setFont(UI_FONT);
        modeDropdown.setBackground(PANEL_BG);
        modeDropdown.setForeground(TEXT);
        modeDropdown.setToolTipText(MODE_TOOLTIPS.get(listMode));
        modeDropdown.addActionListener(e ->
        {
            Object sel = modeDropdown.getSelectedItem();
            if (sel instanceof ListMode)
            {
                listMode = (ListMode) sel;
                modeDropdown.setToolTipText(MODE_TOOLTIPS.get(listMode));
                updatePanel();
            }
        });

        row.add(label, BorderLayout.WEST);
        row.add(modeDropdown, BorderLayout.CENTER);
        return row;
    }

    private JPanel buildCenter()
    {
        itemList.setCellRenderer(new ItemCellRenderer());
        itemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        itemList.setVisibleRowCount(12);
        itemList.setBackground(PANEL_BG);
        itemList.setFixedCellHeight(36);

        JScrollPane scroll = new JScrollPane(
                itemList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        scroll.getViewport().setBackground(PANEL_BG);
        scroll.setBorder(null);

        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);

        Border line = new LineBorder(PANEL_BORDER);
        Border empty = new EmptyBorder(6, 6, 6, 6);
        TitledBorder titled = BorderFactory.createTitledBorder(line, "Items");
        titled.setTitleColor(TEXT_MUTED);
        titled.setTitleFont(UI_FONT.deriveFont(Font.BOLD));
        container.setBorder(new CompoundBorder(titled, empty));
        container.add(scroll, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildBottom()
    {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setOpaque(false);

        JPanel countPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        countPanel.setOpaque(false);
        countLabel.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        countLabel.setForeground(TEXT_MUTED);
        countPanel.add(countLabel);

        JButton rollButton = new JButton("Roll");
        rollButton.setFocusPainted(false);
        rollButton.setBackground(BUTTON_BG);
        rollButton.setForeground(Color.WHITE);
        rollButton.setFont(UI_FONT.deriveFont(Font.BOLD));
        rollButton.setPreferredSize(new Dimension(120, 30));
        rollButton.addActionListener(this::performManualRoll);

        JPanel rollPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        rollPanel.setOpaque(false);
        rollPanel.add(rollButton);

        bottom.add(Box.createVerticalStrut(6));
        bottom.add(countPanel);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(rollPanel);
        return bottom;
    }

    private void performManualRoll(java.awt.event.ActionEvent e)
    {
        if (rollAnimationManager.isRolling()) return;
        if (!rollAnimationManager.hasTradeablesReady()) return;

        final List<Integer> tradeableSnapshot;
        synchronized (allTradeableItems)
        {
            tradeableSnapshot = new ArrayList<>(allTradeableItems);
        }

        List<Integer> locked = new ArrayList<>();
        for (int id : tradeableSnapshot)
        {
            if (!rolledItemsManager.isRolled(id))
            {
                locked.add(id);
            }
        }

        if (locked.isEmpty()) return;

        rollAnimationManager.setManualRoll(true);
        rollAnimationManager.enqueueRoll(0);
    }

    public void updatePanel()
    {
        final ListMode modeSnap = listMode;
        final String searchSnap = searchText;

        clientThread.invokeLater(() ->
        {
            Set<Integer> obtained = obtainedItemsManager.getObtainedItems();
            Set<Integer> rolled = rolledItemsManager.getRolledItems();
            List<Integer> base = new ArrayList<>();

            switch (modeSnap)
            {
                case ROLLED:
                    base.addAll(rolled);
                    break;
                case OBTAINED:
                    base.addAll(obtained);
                    break;
                case ROLLED_NOT_OBTAINED:
                    base.addAll(rolled);
                    base.removeIf(obtained::contains);
                    break;
                case USABLE:
                    base.addAll(rolled);
                    base.removeIf(id -> !obtained.contains(id));
                    break;
            }

            if (searchSnap != null && !searchSnap.isEmpty())
            {
                base.removeIf(id ->
                {
                    ItemComposition comp = itemManager.getItemComposition(id);
                    String name = comp != null ? comp.getName() : null;
                    return name == null || !name.toLowerCase().contains(searchSnap);
                });
            }

            Collections.reverse(base);

            final int total;
            synchronized (allTradeableItems)
            {
                total = allTradeableItems.size();
            }

            SwingUtilities.invokeLater(() ->
            {
                listModel.clear();
                for (Integer id : base)
                {
                    listModel.addElement(id);
                }
                countLabel.setText(formatCountLabel(modeSnap, base.size(), total));
                itemList.revalidate();
                itemList.repaint();
            });
        });
    }

    private String formatCountLabel(ListMode mode, int count, int total)
    {
        String label;
        switch (mode)
        {
            case OBTAINED:
                label = "Obtained";
                break;
            case ROLLED_NOT_OBTAINED:
                label = "Rolled (Not Obtained)";
                break;
            case USABLE:
                label = "Usable";
                break;
            case ROLLED:
            default:
                label = "Rolled";
                break;
        }
        return String.format("%s: %d/%d", label, count, total);
    }

    private class ItemCellRenderer extends JPanel implements ListCellRenderer<Integer>
    {
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();

        ItemCellRenderer()
        {
            setLayout(new BorderLayout(6, 0));
            setOpaque(true);
            iconLabel.setPreferredSize(new Dimension(32, 32));
            nameLabel.setFont(SMALL_FONT);
            nameLabel.setForeground(TEXT);
            add(iconLabel, BorderLayout.WEST);
            add(nameLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends Integer> list, Integer value, int index,
                boolean isSelected, boolean cellHasFocus)
        {
            if (value == null)
            {
                nameLabel.setText("");
                iconLabel.setIcon(null);
                return this;
            }

            ImageIcon ico = itemIconCache.get(value);
            if (ico != null)
            {
                iconLabel.setIcon(ico);
            }
            else
            {
                iconLabel.setIcon(null);
                requestItemIcon(value, list, index);
            }

            String name = itemNameCache.get(value);
            if (name != null)
            {
                nameLabel.setText(name);
                setToolTipText(name);
            }
            else
            {
                nameLabel.setText("...");
                setToolTipText(null);
                requestItemName(value, list, index);
            }

            if (isSelected)
            {
                setBackground(SELECTION);
            }
            else
            {
                setBackground(index % 2 == 0 ? LIST_ROW_A : LIST_ROW_B);
            }
            return this;
        }
    }

    private void requestItemIcon(int itemId, JList<?> list, int index)
    {
        if (!iconFetchInFlight.add(itemId)) return;

        clientThread.invokeLater(() ->
        {
            BufferedImage image = itemManager.getImage(itemId);
            ImageIcon icon = null;
            if (image != null)
            {
                BufferedImage resized = ImageUtil.resizeImage(image, 32, 32);
                icon = new ImageIcon(resized);
            }
            final ImageIcon finalIcon = icon;
            SwingUtilities.invokeLater(() ->
            {
                if (finalIcon != null)
                {
                    itemIconCache.put(itemId, finalIcon);
                }
                iconFetchInFlight.remove(itemId);
                Rectangle r = list.getCellBounds(index, index);
                if (r != null) list.repaint(r);
                else list.repaint();
            });
        });
    }

    private void requestItemName(int itemId, JList<?> list, int index)
    {
        if (!nameFetchInFlight.add(itemId)) return;

        clientThread.invokeLater(() ->
        {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            String name = (comp != null) ? comp.getName() : "Unknown";
            SwingUtilities.invokeLater(() ->
            {
                itemNameCache.put(itemId, name);
                nameFetchInFlight.remove(itemId);
                Rectangle r = list.getCellBounds(index, index);
                if (r != null) list.repaint(r);
                else list.repaint();
            });
        });
    }
}
