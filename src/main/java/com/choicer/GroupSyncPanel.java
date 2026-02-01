package com.choicer;

import com.choicer.sync.GroupMember;
import com.choicer.sync.GroupSyncConfigKeys;
import com.choicer.sync.GroupSyncService;
import com.choicer.sync.GroupSyncStatus;
import net.runelite.client.config.ConfigManager;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.UUID;

import static com.choicer.ChoicerPanel.*;

public class GroupSyncPanel extends JPanel
{
    private final GroupSyncService groupSyncService;
    private final ConfigManager configManager;

    private final JLabel syncStatusLabel = new JLabel("Sync: disabled");
    private GroupSyncStatus lastGroupStatus;
    private final DefaultListModel<GroupMember> membersModel = new DefaultListModel<>();
    private final JList<GroupMember> membersList = new JList<>(membersModel);
    private final JButton refreshMembersButton = new JButton("Refresh members");
    private final JButton kickMemberButton = new JButton("Kick member");

    private final JTextField joinCodeField = new JTextField();
    private final JTextField createNameField = new JTextField();
    private final JSpinner createMaxMembersSpinner = new JSpinner(new SpinnerNumberModel(5, 2, 100, 1));
    private final JButton joinButton = new JButton("Join");
    private final JButton createButton = new JButton("Create");
    private final JButton leaveButton = new JButton("Leave");
    private final JButton copyJoinCodeButton = new JButton("Copy code");
    private JPanel ownerHeaderRow;
    private JLabel ownerHeaderLabel;
    private JPanel joinHeaderRow;
    private JPanel joinSection;
    private JPanel createSection;
    private JPanel actionsRow;
    private JPanel membersHeaderRow;
    private JScrollPane membersScroll;
    private JPanel createNameRow;
    private JPanel createMaxRow;
    private volatile boolean isOwner = false;

    public GroupSyncPanel(GroupSyncService groupSyncService, ConfigManager configManager)
    {
        this.groupSyncService = groupSyncService;
        this.configManager = configManager;
        build();
    }

    public void updateGroupSyncStatus(GroupSyncStatus status)
    {
        if (status == null) return;
        lastGroupStatus = status;
        String state = status.message != null ? status.message : status.state;
        String version = status.version > 0 ? ("v" + status.version) : "v-";
        String last = status.lastSync != null ? status.lastSync.toString() : "never";
        syncStatusLabel.setText("Sync: " + state + " (" + version + ", " + last + ")");
        updateGroupUiState();
    }

    public void updateMembers(java.util.List<GroupMember> list)
    {
        membersModel.clear();
        if (list == null || list.isEmpty())
        {
            setOwnerUiVisible(false);
            updateGroupUiState();
            return;
        }
        for (GroupMember member : list)
        {
            membersModel.addElement(member);
        }
        setOwnerUiVisible(isCurrentUserOwner(list));
        updateGroupUiState();
    }

    public boolean isGroupSyncEnabledSetting()
    {
        String value = configManager.getConfiguration("choicer", "groupSyncEnabled");
        return value != null && value.equalsIgnoreCase("true");
    }

    private void build()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentY(Component.TOP_ALIGNMENT);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("Group Sync");
        title.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        title.setForeground(TEXT_MUTED);
        header.add(title);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        joinCodeField.setBackground(FIELD_BG);
        joinCodeField.setForeground(TEXT);
        joinCodeField.setCaretColor(TEXT);
        joinCodeField.setFont(SMALL_FONT);
        joinCodeField.setColumns(16);
        joinCodeField.setText(readString(GroupSyncConfigKeys.JOIN_CODE));

        createNameField.setBackground(FIELD_BG);
        createNameField.setForeground(TEXT);
        createNameField.setCaretColor(TEXT);
        createNameField.setFont(SMALL_FONT);
        createNameField.setColumns(16);
        createNameField.setText(readString(GroupSyncConfigKeys.CREATE_NAME));

        createMaxMembersSpinner.setValue(readInt(GroupSyncConfigKeys.CREATE_MAX_MEMBERS, 5));
        JComponent spinnerEditor = createMaxMembersSpinner.getEditor();
        if (spinnerEditor instanceof JSpinner.DefaultEditor)
        {
            JFormattedTextField tf = ((JSpinner.DefaultEditor) spinnerEditor).getTextField();
            tf.setColumns(3);
            tf.setHorizontalAlignment(SwingConstants.RIGHT);
        }

        joinHeaderRow = sectionHeader("Join a group");
        ownerHeaderRow = sectionHeader("Create a group");
        createNameRow = labelWithField("Group Name", createNameField);
        createMaxRow = labelWithSpinner("Max Members", createMaxMembersSpinner);

        installAutoSave();

        JPanel joinActionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        joinActionsRow.setOpaque(false);
        joinButton.setFocusPainted(false);
        joinButton.setBackground(BUTTON_BG);
        joinButton.setForeground(Color.WHITE);
        joinButton.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        joinButton.setPreferredSize(new Dimension(120, 26));
        joinButton.addActionListener(e -> {
            saveGroupSettings();
            String code = joinCodeField.getText();
            if (code != null)
            {
                groupSyncService.joinGroup(code.trim());
            }
        });

        joinActionsRow.add(joinButton);

        JPanel createActionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        createActionsRow.setOpaque(false);
        createButton.setFocusPainted(false);
        createButton.setBackground(BUTTON_BG);
        createButton.setForeground(Color.WHITE);
        createButton.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        createButton.setPreferredSize(new Dimension(120, 26));
        createButton.addActionListener(e -> {
            saveGroupSettings();
            String name = createNameField.getText() != null ? createNameField.getText().trim() : "";
            int maxMembers = ((Number) createMaxMembersSpinner.getValue()).intValue();
            groupSyncService.createGroup(name, maxMembers, resultDto -> {
                if (resultDto == null || resultDto.join_code == null) return;
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Group created. Join code: " + resultDto.join_code, "Group Created", JOptionPane.INFORMATION_MESSAGE)
                );
            });
        });

        createActionsRow.add(createButton);

        actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionsRow.setOpaque(false);
        leaveButton.setFocusPainted(false);
        leaveButton.setBackground(BUTTON_BG);
        leaveButton.setForeground(Color.WHITE);
        leaveButton.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        leaveButton.addActionListener(e -> groupSyncService.leaveGroup());

        copyJoinCodeButton.setFocusPainted(false);
        copyJoinCodeButton.setBackground(BUTTON_BG);
        copyJoinCodeButton.setForeground(Color.WHITE);
        copyJoinCodeButton.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        copyJoinCodeButton.addActionListener(e -> copyJoinCode());

        actionsRow.add(leaveButton);
        actionsRow.add(copyJoinCodeButton);
        actionsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        actionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        syncStatusLabel.setFont(SMALL_FONT);
        syncStatusLabel.setForeground(TEXT_MUTED);

        membersList.setBackground(PANEL_BG_ALT);
        membersList.setForeground(TEXT);
        membersList.setFont(SMALL_FONT);
        membersList.setVisibleRowCount(4);
        membersList.setCellRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (isSelected)
                {
                    label.setBackground(SELECTION);
                }
                else
                {
                    label.setBackground(index % 2 == 0 ? LIST_ROW_A : LIST_ROW_B);
                }
                label.setForeground(TEXT);
                if (value instanceof GroupMember)
                {
                    GroupMember member = (GroupMember) value;
                    String name = resolveMemberName(member);
                    String role = member.role != null ? member.role : "member";
                    label.setText(name + " (" + role + ")");
                }
                return label;
            }
        });

        membersScroll = new JScrollPane(membersList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        membersScroll.getViewport().setBackground(PANEL_BG_ALT);
        membersScroll.setBorder(new LineBorder(PANEL_BORDER));
        membersScroll.setPreferredSize(new Dimension(0, 84));
        membersScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));

        refreshMembersButton.setText("Refresh");
        refreshMembersButton.setFocusPainted(false);
        refreshMembersButton.setBackground(BUTTON_BG);
        refreshMembersButton.setForeground(Color.WHITE);
        refreshMembersButton.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        refreshMembersButton.setPreferredSize(new Dimension(92, 26));
        refreshMembersButton.addActionListener(e -> groupSyncService.refreshMembers());

        kickMemberButton.setFocusPainted(false);
        kickMemberButton.setBackground(BUTTON_BG);
        kickMemberButton.setForeground(Color.WHITE);
        kickMemberButton.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        kickMemberButton.setPreferredSize(new Dimension(120, 26));
        kickMemberButton.addActionListener(e -> kickSelectedMember());

        membersHeaderRow = new JPanel();
        membersHeaderRow.setOpaque(false);
        membersHeaderRow.setLayout(new BoxLayout(membersHeaderRow, BoxLayout.Y_AXIS));
        JLabel membersTitle = new JLabel("Group members");
        membersTitle.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        membersTitle.setForeground(TEXT_MUTED);

        JPanel membersActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        membersActions.setOpaque(false);
        membersActions.add(refreshMembersButton);
        membersActions.add(kickMemberButton);
        membersActions.setAlignmentX(Component.LEFT_ALIGNMENT);

        membersHeaderRow.add(membersTitle);
        membersHeaderRow.add(Box.createVerticalStrut(4));
        membersHeaderRow.add(membersActions);
        membersHeaderRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        joinSection = new JPanel(new GridBagLayout());
        joinSection.setOpaque(false);
        joinSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints j = new GridBagConstraints();
        j.gridx = 0;
        j.gridy = 0;
        j.weightx = 1.0;
        j.fill = GridBagConstraints.HORIZONTAL;
        j.insets = new Insets(0, 0, 2, 0);
        joinSection.add(joinHeaderRow, j);
        j.gridy++;
        joinSection.add(labelWithField("Join Code", joinCodeField), j);
        j.gridy++;
        j.insets = new Insets(2, 0, 0, 0);
        joinSection.add(joinActionsRow, j);
        joinSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, joinSection.getPreferredSize().height));

        createSection = new JPanel(new GridBagLayout());
        createSection.setOpaque(false);
        createSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 2, 0);
        createSection.add(ownerHeaderRow, c);
        c.gridy++;
        createSection.add(createNameRow, c);
        c.gridy++;
        createSection.add(createMaxRow, c);
        c.gridy++;
        c.insets = new Insets(2, 0, 0, 0);
        createSection.add(createActionsRow, c);
        createSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, createSection.getPreferredSize().height));

        add(header);
        add(Box.createVerticalStrut(6));
        add(joinSection);
        add(Box.createVerticalStrut(6));
        add(createSection);
        add(Box.createVerticalStrut(6));
        actionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(actionsRow);
        add(Box.createVerticalStrut(4));
        syncStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(syncStatusLabel);
        add(Box.createVerticalStrut(4));
        membersHeaderRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(membersHeaderRow);
        add(Box.createVerticalStrut(2));
        membersScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(membersScroll);
        add(Box.createVerticalGlue());
        add(Box.createVerticalStrut(6));
        setOwnerUiVisible(false);
        updateGroupUiState();
    }

    private JPanel labelWithField(String label, JComponent field)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);

        JLabel lbl = new JLabel(label);
        lbl.setFont(SMALL_FONT);
        lbl.setForeground(TEXT_MUTED);

        if (field instanceof JTextField)
        {
            JTextField tf = (JTextField) field;
            tf.setPreferredSize(new Dimension(120, 24));
            tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        }

        row.add(lbl);
        row.add(Box.createVerticalStrut(2));
        row.add(field);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        return row;
    }

    private String shortUserId(UUID userId)
    {
        if (userId == null) return "Unknown";
        String raw = userId.toString();
        return raw.length() > 8 ? raw.substring(0, 8) : raw;
    }

    private String resolveMemberName(GroupMember member)
    {
        if (member == null)
        {
            return "Unknown";
        }
        if (member.displayName != null)
        {
            String trimmed = member.displayName.trim();
            if (!trimmed.isEmpty())
            {
                return trimmed;
            }
        }
        return shortUserId(member.userId);
    }

    private JPanel labelWithSpinner(String label, JSpinner spinner)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);

        JLabel lbl = new JLabel(label);
        lbl.setFont(SMALL_FONT);
        lbl.setForeground(TEXT_MUTED);

        spinner.setPreferredSize(new Dimension(120, 24));
        spinner.setMaximumSize(new Dimension(140, 24));

        row.add(lbl);
        row.add(Box.createVerticalStrut(2));
        row.add(spinner);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        return row;
    }

    private JPanel sectionHeader(String label)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JLabel lbl = new JLabel(label);
        lbl.setFont(SMALL_FONT.deriveFont(Font.BOLD));
        lbl.setForeground(TEXT_MUTED);
        row.add(lbl, BorderLayout.WEST);
        if ("Create a group".equals(label))
        {
            ownerHeaderLabel = lbl;
        }
        return row;
    }

    private void saveGroupSettings()
    {
        configManager.setConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.JOIN_CODE, joinCodeField.getText().trim());
        configManager.setConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.CREATE_NAME, createNameField.getText().trim());
        configManager.setConfiguration(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.CREATE_MAX_MEMBERS, String.valueOf(((Number) createMaxMembersSpinner.getValue()).intValue()));
    }

    private void copyJoinCode()
    {
        String joinCode = groupSyncService.getJoinCode();
        if (joinCode == null || joinCode.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "No join code available. Create or rotate a code first.", "Join Code", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try
        {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(joinCode), null);
            JOptionPane.showMessageDialog(this, "Join code copied to clipboard.", "Join Code", JOptionPane.INFORMATION_MESSAGE);
        }
        catch (Exception e)
        {
            JOptionPane.showMessageDialog(this, "Failed to copy join code.", "Join Code", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void installAutoSave()
    {
        FocusListener onFocusLost = new FocusAdapter()
        {
            @Override
            public void focusLost(FocusEvent e)
            {
                saveGroupSettings();
            }
        };

        joinCodeField.addFocusListener(onFocusLost);
        createNameField.addFocusListener(onFocusLost);
        createMaxMembersSpinner.addChangeListener(e -> saveGroupSettings());
    }

    private void kickSelectedMember()
    {
        GroupMember member = membersList.getSelectedValue();
        if (member == null || member.userId == null)
        {
            JOptionPane.showMessageDialog(this, "Select a member to kick.", "Kick Member", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String label = resolveMemberName(member);
        int result = JOptionPane.showConfirmDialog(this, "Kick " + label + "?", "Kick Member", JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION)
        {
            return;
        }
        groupSyncService.kickMember(member.userId);
    }

    private boolean isCurrentUserOwner(java.util.List<GroupMember> list)
    {
        String userId = configManager.getConfiguration(GroupSyncConfigKeys.GROUP, "supabase.user_id");
        if (userId == null || userId.isEmpty() || list == null) return false;
        for (GroupMember member : list)
        {
            if (member == null || member.userId == null) continue;
            if (!userId.equals(member.userId.toString())) continue;
            return "owner".equalsIgnoreCase(member.role);
        }
        return false;
    }

    private void setOwnerUiVisible(boolean isOwner)
    {
        this.isOwner = isOwner;
        ownerHeaderRow.setVisible(isOwner);
        createNameRow.setVisible(isOwner);
        createMaxRow.setVisible(isOwner);
        createButton.setVisible(isOwner);
        copyJoinCodeButton.setVisible(isOwner);
        kickMemberButton.setVisible(isOwner);
    }

    private void updateGroupUiState()
    {
        boolean joined = isGroupJoined();
        if (joinSection != null)
        {
            joinSection.setVisible(!joined);
        }
        if (createSection != null)
        {
            createSection.setVisible(!joined);
        }
        leaveButton.setVisible(joined);
        membersHeaderRow.setVisible(joined);
        membersScroll.setVisible(joined);
        refreshMembersButton.setEnabled(joined);
        copyJoinCodeButton.setVisible(joined && isOwner);
        actionsRow.setVisible(joined);

        if (!joined)
        {
            createNameRow.setVisible(true);
            createMaxRow.setVisible(true);
            createButton.setVisible(true);
            ownerHeaderRow.setVisible(true);
            if (ownerHeaderLabel != null)
            {
                ownerHeaderLabel.setText("Create a group");
            }
            kickMemberButton.setVisible(false);
        }
        else
        {
            ownerHeaderRow.setVisible(false);
            if (ownerHeaderLabel != null)
            {
                ownerHeaderLabel.setText("Owner tools");
            }
            createNameRow.setVisible(false);
            createMaxRow.setVisible(false);
            createButton.setVisible(false);
            kickMemberButton.setVisible(isOwner);
        }
        revalidate();
        repaint();
    }

    private boolean isGroupJoined()
    {
        String groupId = configManager.getConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.group_id");
        if (groupId == null || groupId.isEmpty())
        {
            return false;
        }
        if (lastGroupStatus == null)
        {
            return true;
        }
        String state = lastGroupStatus.state != null ? lastGroupStatus.state : "";
        String message = lastGroupStatus.message != null ? lastGroupStatus.message : "";
        if ("auth_changed".equalsIgnoreCase(state))
        {
            return false;
        }
        if ("idle".equalsIgnoreCase(state)
                && ("No group joined".equalsIgnoreCase(message) || "Left group".equalsIgnoreCase(message)))
        {
            return false;
        }
        if ("error".equalsIgnoreCase(state) && message.toLowerCase().contains("group state not found"))
        {
            return false;
        }
        return true;
    }

    private String readString(String key)
    {
        String value = configManager.getConfiguration(GroupSyncConfigKeys.GROUP, key);
        return value == null ? "" : value;
    }

    private int readInt(String key, int fallback)
    {
        String value = configManager.getConfiguration(GroupSyncConfigKeys.GROUP, key);
        if (value == null || value.isEmpty()) return fallback;
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (Exception e)
        {
            return fallback;
        }
    }
}
