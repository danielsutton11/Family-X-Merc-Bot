# Family X Merc Bot

Java 21 · Spring Boot 3.3 · JDA 5 · PostgreSQL + Flyway · Railway.

This is **phase 1 (Foundation)** of the build plan in the scope doc. It gives you:

- the full database schema (26 tables) plus default tiers and Capacity X;
- `/merc-setup`, which builds or repairs every channel, role and pinned panel and is safe to re-run;
- the leader panel, with working **Keys** and **Settings → Factions**;
- an encrypted, rotating Torn API key pool with health checks and BSP subscription detection;
- the interaction router, debounced message updater and audit log that later phases build on.

Buttons for later phases reply with "arrives in build phase N".

**Done when:** setup builds every channel, role and panel, and each family faction has 2 healthy leader keys.

## 1. Discord application

1. In the [Discord developer portal](https://discord.com/developers/applications), create an application and a bot, and copy the token.
2. Under **Bot**, turn on **Server Members Intent** (needed from phase 2 to give out merc roles).
3. Invite the bot with the `bot` and `applications.commands` scopes and these permissions:
   Manage Roles, Manage Channels, View Channels, Send Messages, Embed Links, Read Message History, Manage Messages,
   Create Public Threads, Create Private Threads (needed so the bot can stop members starting threads in read-only channels).
   Invite link with exactly these permissions:
   `https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot%20applications.commands&permissions=103347743760`
4. In Server Settings → Roles, drag the bot's role **above** the roles it will manage (Merc, 10/10 Merits, Stricken, Bronze, Silver, Gold).

## 2. Railway

1. Create a project from this repo. Railway builds it with the included `Dockerfile`.
2. Add the **PostgreSQL** plugin.
3. On the bot service, set the variables from `.env.example`:

| Variable | Value |
| --- | --- |
| `DISCORD_TOKEN` | Bot token |
| `DISCORD_GUILD_ID` | Family X server ID |
| `FAMILY_X_MERC_MANAGER_ROLE_ID` | Existing Family X merc manager role |
| `VERIFIED_ROLE_ID` | Existing Verified role |
| `FAMILY_X_ROLE_ID` | Existing Family X role |
| `MERC_REQUESTS_CHANNEL_ID` | Existing public #merc-requests channel |
| `MERC_KEY_MASTER` | Output of `openssl rand -base64 32`. **Keep a copy**; losing it means re-adding every key. |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | Reference the Postgres service, e.g. `${{Postgres.PGHOST}}` |

Flyway creates the schema on first start. Set a Railway usage alert (for example $15) to keep an eye on cost.

## 3. First run

1. `/merc-setup` is hidden from members without Manage Server. To let the Merc Managers role use it, go to Server Settings → Integrations → the bot → `/merc-setup` and add the role. The bot also checks for the role itself.
2. Run `/merc-setup`. It creates:
   - **Merc Service:** #merc-signup, #bronze, #silver, #gold, #merc-results, #merc-leaderboard;
   - **Merc Leaders:** #merc-leaders, #merc-inbox, #merc-payouts, #merc-audit;
   - roles Merc, 10/10 Merits, Stricken, Bronze, Silver, Gold;
   - the guide and request panel in your existing #merc-requests.
3. In #merc-leaders, open **Settings → Factions**. Set Capacity X's leader role, then add Med X, Phoenix X, Helvete X and Circle X (ID, plus name if no keys exist yet) and their leader roles.
4. Each faction's leaders open **Keys → Add my key** with a Limited key that has faction API access. The bot checks the key's linked Discord account matches theirs, that it's from a family faction, and that it can read faction news, then checks for a BSP subscription.
5. The leader panel shows each faction's healthy key count, with ⚠️ until it reaches 2.

## Development

```bash
mvn test          # unit tests
mvn package       # builds target/merc-bot.jar
```

Code layout (`src/main/java/com/familyx/mercbot`):

| Package | Contents |
| --- | --- |
| `config` | Environment settings |
| `keys` | Key encryption, storage, validation, daily health check |
| `torn` | Torn v2 client and the rotating key pool |
| `bsp` | Battle Stats Predictor client |
| `faction`, `tier`, `settings`, `audit` | Repositories and the audit log |
| `discord` | JDA setup, router (`merc:<action>:<sub>` ids), message updater, panels |
| `discord.setup` | `/merc-setup` |
| `discord.handlers` | Keys, Factions, Settings, Help, and placeholders for later phases |
