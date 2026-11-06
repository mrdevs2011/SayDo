# SayDo MCP: tools and token-saving design

Every MCP call goes AI -> relay -> phone and costs 0.5-3 s plus the tokens of the result. The design goal is
few calls and short results. Tool names and arguments live in `relay/lib/tools.ts` (schemas) and
`app/.../McpCommands.java` (phone side); keep them in sync.

## Tools

| Tool | Purpose | Arguments |
|---|---|---|
| `device_status` | Phone state, call first | - |
| `list_macros`, `run_macro` | Recorded macros | `macro` (id or trigger) |
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
