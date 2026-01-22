# Choicer

All credits goes to ChunkyAtlas for the ChanceMan plugin. This plugin is forked from his original work, purely due to personal interest in enhancing the experience. Please note this is a modified version of the original plugin. Even these added features were originally his ideas!!!

Please check out his plugin at https://github.com/ChunkyAtlas/ChanceMan if you are interested in giving him a shoutout.

I do not take credit for anything !

## Attribution

This plugin is based on the original **Chance Man** plugin by ChunkyAtlas,
licensed under the BSD 2-Clause License.
## Overview

**Chance Man** is a RuneLite plugin that locks tradeable items until they are unlocked by a random roll. Designed for players who enjoy adding extra randomness or progression to their gameplay, Chance Man provides a unique system for “earning” items through luck. Items become accessible only after you roll to unlock them, with progress saved per player across sessions.

## Features

- **Locking Mechanic**
    - Tradeable items (excluding coins) start out locked.
    - Items remain locked until you perform a successful roll to unlock them.
    - Prevents locked items from being picked up, equipped, or otherwise used until they are rolled.

- **Rolling System**
    - Roll animations determine which item gets unlocked.
    - The final rolled item is announced via chat messages and automatically becomes unlocked.
    - A dedicated panel button (“Roll”) lets you manually trigger a roll if you have locked items.

- **Ground Item & Inventory Detection**
    - Automatically rolls when you encounter locked items on the ground or receive them in your inventory (e.g., quest rewards).
    - Rolls each item only once, so repeated drops of the same item type won’t trigger multiple rolls.

- **Show Drops Menu**
    - Right-click an NPC and choose **Show Drops** to fetch its drop table from the wiki.
    - Use the search button in the Music tab to look up anything with a droptable, or NPC by name, level, or ID.
    - The Music tab displays icons and a progress bar for the NPC's drops, and hovering an icon shows its name.

- **Rolled & Unlocked Panels**
    - A **Rolled Items** section logs every item that has triggered a roll.
    - An **Unlocked Items** section shows which items have been successfully unlocked.
    - Both panels maintain descending order so the most recent items appear at the top.
    - A search bar lets you quickly find items.
    - The 🔄 button swaps between Rolled and Unlocked views.
    - Filter toggles show only unlocked‑not‑rolled or both unlocked‑and‑rolled items.
    - The Discord icon links to the community server for help and discussion and good vibes.

- **Persistence**
    - Each player’s rolled/unlocked data is stored locally in JSON files and mirrored to RuneLite’s cloud profile so progress syncs across machines.
    - Data is automatically saved and loaded for each character name.

- **Grand Exchange Search Filtering**
    - Locked items are hidden in GE search results and dimmed until you unlock them

- **Locked Item Dimming**
    - Locked item icons across interfaces are dimmed to clearly show they are unusable until rolled.
    - The dimming strength is configurable in the plugin settings.

## Configuration

Open RuneLite’s plugin settings and select **ChanceMan** to adjust these options:

- **Free To Play Mode** – Only roll items available to F2P players.
- **Include F2P trade-only items** – With F2P mode enabled, also roll items that normally require trading to obtain.
- **Roll Item Sets** – Include item set pieces in the pool of rollable items.
- **Roll Flatpacks** – Let flatpacks appear in rolls.
- **Weapon Poison Unlock Requirements** – Require the base weapon and poison to be unlocked before poisoned variants can roll.
- **Enable Roll Sounds** – Play sound effects during roll animations.
- **GE Purchases Require Rolls** – Items appear purchasable on the Grand Exchange only after they have been rolled and unlocked.
- **Unlocked Item Color** – Chat color for newly unlocked item names.
- **Rolled Item Color** – Chat color for the item that triggered the unlock.
- **Sort Drops by Rarity** – Order items in the Show Drops menu by rarity instead of item ID.
- **Show Rare Drop Table** – Toggle to display items from the rare drop table in the **Show Drops** menu.
- **Show Gem Drop Table** – Toggle to display items from the gem drop table in the **Show Drops** menu.
  Changing either option will clear cached drop data so updated tables are fetched.
- **Dim locked items** – Dim the icons of locked items throughout the game interface.
- **Dim opacity** – Control how transparent the dimming effect is (0 = no dim, 255 = fully transparent).

## Usage

1. **Start the Plugin**
    - Enable **ChanceMan** in the RuneLite plugin list.
    - The plugin automatically scans for tradeable items (excluding coins) and locks them until a roll occurs.

2. **Encountering Locked Items**
    - When you see a locked item on the ground or receive it in your inventory, ChanceMan will prompt a roll if it hasn’t already been rolled.
    - The item remains locked until the roll animation completes and it’s unlocked.

3. **Manual Rolls**
    - Use the **Roll** button in the ChanceMan panel to manually trigger a roll for a random locked item if you have any remaining locked items.
    - A spinning animation appears in an overlay, revealing the unlocked item at the end.

4. **Viewing Progress**
    - Open the **ChanceMan** side panel to see the **Rolled Items** and **Unlocked Items** columns.
    - The **Rolled Items** list tracks which items have triggered a roll.
    - The **Unlocked Items** list displays items that are fully unlocked for use.

5. **Restrictions**
    - While locked, items cannot be used, eaten, equipped, or otherwise interacted with (except “examine” and “drop”).
    - Once unlocked, they function as normal items.

## File Locations

- **Unlocked Items**  
  `~/.runelite/chanceman/<player_name>/chanceman_unlocked.json`
- **Rolled Items**  
  `~/.runelite/chanceman/<player_name>/chanceman_rolled.json`

These JSON files store your progress. Each player’s data is kept in a separate folder named after their in-game character name.

## Contribution

Contributions are welcome! If you encounter any issues, want new features, or have general feedback, please open an issue or submit a pull request.

## Contact

For questions, support, or feature requests, please open an issue on GitHub or contact me on Discord: attoz
