# Choicer

Choicer is a RuneLite plugin that adds structured randomness and progression to item acquisition in Old School RuneScape.

This project is a **fork and substantial modification** of the original **ChanceMan** plugin by **ChunkyAtlas**, developed under the BSD 2-Clause License.  
The original concept, groundwork, and much of the underlying logic originate from that project and are credited accordingly.
https://github.com/ChunkyAtlas/chance-man

---

## Project Background

The original **ChanceMan** plugin introduced a system where the **first drop of an item would unlock a single random item**.  
Choicer takes that foundation and deliberately changes the gameplay philosophy while preserving the proven technical core.

### Key differences from the original project:
- ❌ Removed single random unlocks  
- ✅ Introduced **multiple-choice unlock rolls**
- ✅ Retained core drop, loot, and persistence logic
- ✅ Expanded the item pool to include **untradeable items**
- ✅ Reworked UI and interaction flow

This fork exists to explore a **more player-driven and transparent progression model**, while staying within RuneLite and Jagex rules.

---

## Overview

**Choicer** locks items until they are explicitly unlocked through a roll-based choice system.

When a locked item is encountered for the first time, the plugin presents **multiple unlock options** and lets the player choose one.  
Progress is tracked per character and persists across sessions and machines.

---

## Features

### 🔒 Item Locking
- Tradeable items (excluding coins) start locked by default
- Untradeable items can also be included
- Locked items cannot be:
  - Picked up
  - Equipped
  - Used
- Examine and drop remain available

---

### 🎲 Choice-Based Rolling System
- Rolls present **multiple unlock options**
- Player selects which item to unlock
- Roll results are announced in chat
- Manual rolls available via the plugin panel

---

### 📦 Drop & Inventory Detection
- Automatically triggers rolls for:
  - Ground drops
  - Inventory additions (e.g. quest rewards)
- Each item type rolls only once
- Duplicate drops do not retrigger rolls

---

### 📊 Drop Table Integration
- Right-click NPC → **Show Drops**
- Fetches drop tables from the OSRS Wiki
- Music tab search supports:
  - NPC name
  - NPC ID
  - Combat level
- Visual progress bars and hoverable item icons

---

### 📁 Progress Panels
- **Rolled Items**
  - Tracks items that triggered a roll
- **Unlocked Items**
  - Shows usable items
- Features:
  - Newest items shown first
  - Search support
  - View toggling
  - Filter controls
  - Discord link for support and discussion

---

### 💾 Persistence & Sync
- Data stored locally as JSON
- Mirrored to RuneLite cloud profiles
- Automatically syncs across machines
- Character-specific storage

---

### 🛒 Grand Exchange Integration
- Locked items hidden or dimmed in GE search
- GE purchases can require prior unlock

---

### 🌑 Visual Feedback
- Locked items are dimmed across interfaces
- Dimming strength is configurable

---

## Configuration Options

Accessible via RuneLite plugin settings:

- Free-to-Play mode
- Include F2P trade-only items
- Roll item sets
- Roll flatpacks
- Weapon poison unlock rules
- Roll sound effects
- GE purchase restrictions
- Chat colors for roll events
- Number of roll choices
- Drop sorting by rarity
- Rare drop table toggle
- Gem drop table toggle
- Locked item dimming & opacity

---

## Usage

1. Enable **Choicer** in RuneLite
2. Encounter a locked item
3. Choose one item from the roll to unlock
4. Use unlocked items normally
5. Track progress in the side panel

Manual rolls are available if locked items remain.

---

## Data Storage

Progress is stored per character:

~/.runelite/choicer/<player_name>/
├── choicer_unlocked.json
└── choicer_rolled.json

---

## Attribution & Licensing

This project is based on **ChanceMan** by **ChunkyAtlas**  
Original repository:  
https://github.com/ChunkyAtlas/ChanceMan

Licensed under the **BSD 2-Clause License**.  
All original copyright notices are preserved.

Choicer introduces independent modifications, UI changes, and feature expansions while respecting the original license terms.

---

## Contributions

Bug reports, suggestions, and pull requests are welcome.  
Please keep requests within RuneLite and Jagex rules.

---

## Contact

- GitHub Issues for bugs and feature requests
- Discord server linked in the plugin UI
- Email for general inquiries: attosservices@gmail.com