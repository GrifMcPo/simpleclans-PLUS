![### Simpleclans-PLUS](https://cdn.modrinth.com/data/cached_images/fd2b00525752c369fea7a2db1a0ad86ce92b60d8.png)

**Simpleclans-PLUS** is the most complete, lightweight clan plugin built for modern Minecraft servers.  
Zero configuration needed to get started — install and go.  
Packed with **alliances, wars, raids, duels, land claiming, bounties, a clan bank, leaderboards**, and more.  
Built by a server owner, for server owners.  
**30 language support** included out of the box.

---

![### Why Simpleclans-PLUS?](https://cdn.modrinth.com/data/cached_images/4d0a42ba9db618b704c910b50bf6489108e5fb5d_0.webp)

Most clan plugins are either too bloated, abandoned, or require a ton of setup.  
**Simpleclans-PLUS** is different:

- ✅ **Drop-in install** — SQLite database auto-creates, no SQL setup needed
- ✅ **Modular** — disable any feature you don't want via `config.yml`
- ✅ **PlaceholderAPI + LuckPerms + Vault** integration out of the box
- ✅ **Active development** — new features added regularly
- ✅ **30 languages** — your players speak their language, not yours
- ✅ **Open source** — MIT licensed, fork and extend freely

---

## ⚔️ Features

### Clan Management
- Create, invite, join, leave, and disband clans
- Role hierarchy: `Leader` › `Co-Leader` › `Member` › `Recruit`
- Promote, demote, and kick with full permission control
- Interactive GUI menu via `/clan menu`

### 🤝 Clan Alliances
- Send, accept, and remove alliance requests between clans
- Configurable friendly-fire protection for allies
- Set a max alliance limit per clan

### ⚔️ Clan Wars
- Declare war on rival clans with `/clan war challenge`
- Configurable minimum online members and kill limits
- Surrender option included
- Full war status and info commands

### 💥 Clan Raids
- Launch timed raids against enemy clans
- Configurable cooldown and raid duration
- Raid history tracking

### 🥊 Clan Duels
- Quick 1v1 challenge system between clans
- Configurable challenge timeout

### 🏠 Clan Home
- Set and teleport to a clan home
- Configurable warmup delay (anti-teleport-abuse)

### 🗺️ Clan Land Claim
- Claim chunks as clan territory
- Economy-based upkeep with grace periods before losing land
- Chunk upgrade system for expanding your territory
- Enemy building/destruction protection in claimed land
- Admin bypass permission

### 💰 Clan Bank
- Shared clan economy vault (requires Vault)
- Deposit, withdraw, and check balance
- Full transaction log

### 🎯 Clan Bounties
- Place bounties on players using in-game currency
- Configurable minimum bounty amount
- List and remove bounties

### 🏆 Clan Leaderboards
- Rankings based on kills, level, and members
- Configurable display count

### 💬 Clan Chat System
- Toggle private clan chat with `/clan chat`
- One-time clan message with `/clan chatmsg <message>`

### 📊 PlaceholderAPI Placeholders
```
%simpleclans_clan_name%
%simpleclans_clan_role%
%simpleclans_clan_level%
%simpleclans_clan_kills%
%simpleclans_member_count%
%simpleclans_online_members%
%simpleclans_clan_leader%
```

### 🔔 Auto-Update Notifier
- Get notified in-game when a new version is available
- Update directly with `/clan admin update`

---

![### Commands](https://cdn.modrinth.com/data/cached_images/999bcd27588da5226c8e88914d9f7d262d58e5f5_0.webp)

### General Commands
| Command | Description | Permission |
|---|---|---|
| /clan create \<name\> | Create a new clan | simpleclans.create |
| /clan invite \<player\> | Invite a player | simpleclans.invite |
| /clan join \<name\> | Accept an invitation | simpleclans.join |
| /clan leave | Leave your clan | simpleclans.leave |
| /clan info [name] | View clan info | simpleclans.info |
| /clan list | List all clans | simpleclans.list |
| /clan chat | Toggle clan chat | simpleclans.chat |
| /clan chatmsg \<msg\> | Send one clan message | simpleclans.chatmsg |
| /clan menu | Open GUI menu | simpleclans.menu |
| /clan leaderboard | View top clans | simpleclans.leaderboard |

### Co-Leader / Leader Commands
| Command | Description | Permission |
|---|---|---|
| /clan promote \<player\> | Promote a member | simpleclans.promote |
| /clan demote \<player\> | Demote a member | simpleclans.demote |
| /clan kick \<player\> | Kick a member | simpleclans.kick |
| /clan disband | Disband your clan | simpleclans.disband |

### Alliance Commands
| Command | Description | Permission |
|---|---|---|
| /clan ally add \<clan\> | Send alliance request | simpleclans.ally.add |
| /clan ally accept \<clan\> | Accept alliance request | simpleclans.ally.accept |
| /clan ally remove \<clan\> | Remove an alliance | simpleclans.ally.remove |
| /clan ally list | List alliances | simpleclans.ally.list |

### War Commands
| Command | Description | Permission |
|---|---|---|
| /clan war challenge \<clan\> | Declare war | simpleclans.war.challenge |
| /clan war accept \<clan\> | Accept war | simpleclans.war.accept |
| /clan war surrender | Surrender | simpleclans.war.surrender |
| /clan war status | War status | simpleclans.war.status |
| /clan war info | War info | simpleclans.war.info |

### Raid Commands
| Command | Description | Permission |
|---|---|---|
| /clan raid start \<clan\> | Start a raid | simpleclans.raid.start |
| /clan raid end | End a raid | simpleclans.raid.end |
| /clan raid status | Raid status | simpleclans.raid.status |
| /clan raid history | Raid history | simpleclans.raid.history |

### Duel Commands
| Command | Description | Permission |
|---|---|---|
| /clan duel challenge \<clan\> | Challenge to duel | simpleclans.duel.challenge |
| /clan duel accept \<clan\> | Accept duel | simpleclans.duel.accept |

### Home Commands
| Command | Description | Permission |
|---|---|---|
| /clan home | Teleport to clan home | simpleclans.home.teleport |
| /clan home set | Set clan home | simpleclans.home.set |
| /clan home delete | Delete clan home | simpleclans.home.delete |

### Land Claim Commands
| Command | Description | Permission |
|---|---|---|
| /clan claim | Claim current chunk | simpleclans.claim.claim |
| /clan unclaim | Unclaim current chunk | simpleclans.claim.unclaim |
| /clan claim buy | Buy more claim slots | simpleclans.claim.buy |
| /clan claim list | List claimed chunks | simpleclans.claim.list |
| /clan claim info | Info about a chunk | simpleclans.claim.info |

### Bank Commands
| Command | Description | Permission |
|---|---|---|
| /clan bank deposit \<amount\> | Deposit to clan bank | simpleclans.bank.deposit |
| /clan bank withdraw \<amount\> | Withdraw from clan bank | simpleclans.bank.withdraw |
| /clan bank balance | Check bank balance | simpleclans.bank.balance |
| /clan bank log | Transaction history | simpleclans.bank.log |

### Bounty Commands
| Command | Description | Permission |
|---|---|---|
| /clan bounty set \<player\> \<amount\> | Place a bounty | simpleclans.bounty.set |
| /clan bounty list | View bounties | simpleclans.bounty.list |
| /clan bounty remove \<player\> | Remove a bounty | simpleclans.bounty.remove |

### Admin Commands
| Command | Description | Permission |
|---|---|---|
| /clan admin promote \<player\> \<clan\> | Promote in any clan | simpleclans.admin.promote |
| /clan admin demote \<player\> \<clan\> | Demote in any clan | simpleclans.admin.demote |
| /clan admin kick \<player\> \<clan\> | Kick from any clan | simpleclans.admin.kick |
| /clan admin disband \<clan\> | Disband any clan | simpleclans.admin.disband |
| /clan admin reload | Reload config | simpleclans.admin.reload |
| /clan admin update | Update the plugin | simpleclans.admin |
| /clan admin help | Admin help list | simpleclans.admin |

---

## ⚙️ Configuration

Every feature can be toggled on/off in `config.yml`. All values ship with sensible defaults so the plugin works perfectly out of the box without touching a single line.

**Database:** SQLite (auto-created on first start, no setup required)  
**Economy:** Vault (optional — required for bank, bounty, and land claim costs)

---

## 🔌 Compatibility

| Software | Status |
|---|---|
| Spigot / Paper / Purpur / Bukkit | ✅ Full support |
| PlaceholderAPI | ✅ Auto-detected |
| LuckPerms | ✅ Auto-detected |
| Vault | ✅ Auto-detected |
| Minecraft 1.21+ | ✅ Tested |

---

## 📌 Plugin Info

**Name:** Simpleclans-PLUS  
**Author:** Robin  
**License:** MIT  
**Source:** GitHub
**Database:** SQLite
