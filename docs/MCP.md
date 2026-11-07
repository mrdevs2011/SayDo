# SayDo MCP: tools and token-saving design

Every MCP call goes AI -> relay -> phone and costs 0.5-3 s plus the tokens of the result. The design goal is
few calls and short results. Tool names and arguments live in `relay/lib/tools.ts` (schemas) and
`app/.../McpCommands.java` (phone side); keep them in sync.

## Tools

| Tool | Purpose | Arguments |
|---|---|---|
| `device_status` | Phone state, call first | - |
| `find_macro` | Ranks the owner's macros against a goal (any language), at most 3 candidates | `query`, `limit` |
| `list_macros` | One line per macro, paged | `limit` (20, max 50), `offset` |
| `describe_macro` | Description, apps, parameters, sensitive flag, steps | `macro` |
| `set_macro_info` | AI annotates a macro (metadata only) | `macro`, `description`, `aliases` (added), `sensitive` (true only) |
| `run_macro` | Runs a macro; sensitive ones need `confirm` | `macro`, `params`, `confirm` |
| `scroll_to` | Swipes until a text is visible | `text`, `direction`, `max_swipes` |
| `read_screen` | Compact screen listing | `query`, `bounds`, `max_elements` (default 80) |
| `act` | Up to 15 steps in one call | `steps`, `return` (none/screen/diff), `stop_on_error` |
| `click_element`, `tap`, `long_press`, `swipe`, `type_text`, `press`, `open_app` | Single steps | see schema |
| `read_notifications` | "What came to me" | `limit` (15, max 50), `app`, `since_minutes` |
| `screenshot` | Expensive image, last resort | `max_width` (540), `quality` (50) |
| `list_apps`, `wake_screen` | Helpers | - |

## Token saving

- `read_screen` prints `index label flags` per element (flags: click, edit, scroll, checked/unchecked), no bounds
  unless `bounds: true`, labels cut to 50 characters, at most 80 lines, hidden and label-less non-clickable
  elements skipped. `query` keeps only matching elements; their index is the same as in the full list, so
  `click_element`/`type_text`/`act` accept it. The phone caches all useful elements, not only the printed ones.
- Single action tools answer `ok` or a one-line error.
- `act` runs steps on the phone with no round trip between them. It pauses 100-600 ms after a step, retries a
  click on text or a type into the focused field for up to 1.5 s, stops at the first failure (default), and has a
  60 s cap. Result: `1 ok 2 ok 3 FAIL no element 'Yuborish'`, then optionally the final screen (max 40 lines)
  or only what appeared or changed since the start (`diff`, max 30). Click numbers refer to the last
  `read_screen`; after the screen changes use text.
- Tool descriptions and the server instructions are short on purpose: they are paid for in every conversation.
- Rough size, synthetic 40-element screen: old `read_screen` format 2485 characters, new default 661 (27 percent).

## Typical flows

- What came to me: `read_notifications` (optionally `app`, `since_minutes`). No app has to be opened.
- Send a message (only when the user asked): `act` with `open_app`, `wait {text}`, `click` the chat (text),
  `click` the field, `type`, `click` the send button, `return: "diff"` to confirm. Read the chat with
  `read_screen` + `query` first if the labels are unknown.
- Find a control: `read_screen` with `query`.
- Screenshot only when `read_screen` shows `(empty: use screenshot)`, e.g. games. It fails in secure windows
  (banking, some password screens).

## Phone-side safeguards

- While a command runs the floating START bubble is made untouchable (it sat on the right edge and swallowed
  taps on send buttons) and restored afterwards; a safety timer and the service stop also restore it.
- `read_notifications` only returns data when the owner switched on "AI bildirishnomalarni o'qiy olsin" in
  SayDo > MCP (default off) and granted Android Notification access. Otherwise it answers with what to do.
  The last 100 notifications live in memory only (cleared when the listener disconnects or the setting is
  switched off); own, ongoing, foreground-service and group-summary notifications are ignored; content is never
  logged. Command arguments are no longer logged either (tool name only).
- Screenshots need `android:canTakeScreenshot="true"` in the accessibility config (added); after an update
  Accessibility may need to be switched off and on again.

## Macro first (2.15 work, built, NOT deployed, NOT run on a phone)

The server instructions tell the AI: call `find_macro` with the goal first; a score of 0.6 or more is a good match
and is run with `run_macro`; otherwise drive the phone with `act` and `read_screen` (with `query`).

### Metadata

`Macro` has `description` (free text, JSON `desc`) next to `aliases`, `sensitive`, `category`. `sensitiveReviewed`
(JSON `sens_rev`) is set when the owner saves the Info dialog, so automatic money detection stays out of the owner's
decision. Old macros load unchanged (fields default to empty/false; tested).

- At the end of a recording `McpMacros.withAutoInfo` writes a description built by `MacroDescriber`: app labels
  (PackageManager, package name if unknown) and the visible texts of clicked anchors, in order, for example
  `YouTube: Search > Library > Favourites (6 steps)`. Never typed text, nothing from edit fields, texts with 4 or more
  digits (amounts, phone numbers) are skipped, each part is cut to 24 characters, the body to 140.
- The owner edits description, other names (aliases, any language) and the sensitive flag in the app: MacroStudio
  (long press on a row) > "Info (AI)". Saving marks the flag as reviewed.
- The AI may use `set_macro_info` after it understood a macro. It adds aliases, sets the description, may set
  `sensitive=true`, and can never clear it or touch steps.

### Ranking (`MacroSearch`, runs on the phone)

Fields and weights: trigger and aliases 1.0, app labels 0.85, description 0.75, clicked button texts 0.5, category 0.3.
Words are folded (`McpText`: case, accents, apostrophes, Cyrillic to Latin), a small table maps the same idea in
Uzbek, Russian and English to one concept (musiqa / музыка / music), common Uzbek and Russian endings are tolerated,
and partial words, sound-alike spellings (youtube / yutub / ютуб share the skeleton `ytb`) and small typos count.
Word order does not matter. "open", "play", "check" and similar verbs weigh 0.3. Score = share of the goal's words found
x (0.7 + 0.3 x share of the macro's own name covered), plus up to 0.03 for use count. The existing `TriggerMatcher`
is used for whole-phrase matches and edit-distance similarity. Limits: the concept table is small and hand made;
goals about things the macro text does not mention at all (for example the name of a song) cannot match.

### Safety

- Money apps: `McpGuard.MONEY_FRAGMENTS` (payme, click.uz, paynet, uzum, anorbank, humo, uzcard, bank, wallet, ...).
  A macro with a step recorded in such an app is sensitive automatically (`isSensitive`), unless the owner reviewed
  the flag. The fragments are substrings, so some harmless packages match (for example anything containing "bank").
- A sensitive macro asked for by the AI runs only with `confirm=true` (the AI sets it only after the owner confirmed
  the exact action in the chat) AND, unless switched off in SayDo > MCP, an Allow on the phone: heads-up
  notification with Allow and Deny, 60 s, no answer = Deny (`McpApproval`). The notification shows only the macro
  trigger. Without these the tool answers with a refusal that tells the AI what to do. Decision table:
  `McpGuard.decideRun`, unit tested.
- While a money app is in front, `tap`, `long_press`, `swipe`, `click_element`, `type_text`, `read_screen`, `screenshot`, `scroll_to` and each click/tap/type
  step of `act` are refused, `open_app` (also inside `act`) refuses to open a money app, unless the owner switched
  on "AI pul ilovalarida ishlay olsin" (default off). `read_screen`, `device_status`, `press` and `wait` still work,
  so the AI can see where it is and leave. A macro the owner approved is not affected.
- Nothing typed and no amounts are logged; the command log prints the tool name only.
- Not covered: the AI can still act in a bank app that does not match the fragment list, and `scroll_to`
  (swipes only) is not blocked in money apps.

### Parameters (`run_macro params`)

`params` replaces the text of recorded steps that have a variable slot (`Gesture.variableSlot` / `textToInject`) by
rewriting the macro JSON before `Runner.run`, so `GestureService` is not touched. Open: the recorder never creates
such steps (only touches), so today no real macro has slots; `run_macro` with params on a macro without a matching
slot answers with an error. The text step itself works in the existing replay (`SmartGestureDispatcher` sets the text
on the anchored field) but was not tried on a phone.

### Flow 1: a macro matches

Owner: "YouTube'ni ochib, yoqtirgan musiqamni qo'y".
1. `find_macro {query: "youtube ni ochib yoqtirgan musiqamni qoy"}` (synthetic catalog, real output of the ranking code):
   `0.83 a1b2c3d4 "youtube favorite music" apps=YouTube` + description `YouTube: Library > Favourites (6 steps)`.
2. Score above 0.6, not sensitive: `run_macro {macro: "a1b2c3d4"}` -> `completed "youtube favorite music" (6 steps); app in front: com.google.android.youtube`.
3. The AI answers in one line.

A sensitive example: "Payme orqali kommunalka to'la". `find_macro` returns `1.00 ... "payme" SENSITIVE`. The AI asks the owner
"Run the Payme macro (utilities)? " and only after "yes" calls `run_macro {macro, confirm: true}`; the phone shows
Allow/Deny. Without confirm the answer is `refused: this macro is sensitive ...`.

### Flow 2: no macro, fallback

Owner: "Telegramda Ali bilan chatni och".
1. `find_macro` -> `none: no macro fits...` (or a weak score under 0.6; the AI tries one phrasing in another language).
2. `act {steps: [{open_app: "org.telegram.messenger"}, {wait: {text: "Ali"}}, {click: "Ali"}], return: "diff"}`.
3. Only if it worked and the owner wants it: offer to save it as a macro (see next section).

### `save_actions_as_macro` (stretch, NOT built, design only)

Idea: the MCP service remembers the last 30 successful steps of `act`/click/type in memory (cleared on stop), and the
tool turns them into a normal macro. Why it was not built now: a replayable step needs a usable `NodeAnchor` (id, class,
text, bounds, ancestry hash) and the normalised x/y. `act` clicks by text or number, so the service would have to build
the anchor from the clicked `AccessibilityNodeInfo` at click time (all fields are available there), set `screenPkg`,
use `Gesture.createTapWithAnchor` for clicks and `Gesture.createTextInput` (with the field's anchor) for text, and add
the launcher icon tap that replay needs because `Runner` always presses Home first (STATUS, open item 7). That touches
the recorder's data and `GestureService.play`, which the record-and-replay work owns. Typed text must never be stored
silently: store it as a variable slot with an empty default. Do this after replay in real apps passes.

### Text matching and `scroll_to`

`read_screen query`, `click_element text`, `wait text/gone` and `scroll_to` now ignore case and accents and treat Latin and
Cyrillic Uzbek/Russian as equal ("Юбориш" finds "Yuborish"). `scroll_to` swipes (default 8, max 20) until the text is on
screen and returns its `read_screen` line; it stops early when the screen no longer changes.
