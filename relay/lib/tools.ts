import { z } from 'zod';

/**
 * Tools the AI sees. The relay only validates arguments and forwards them; the phone
 * executes them (see app: McpCommands.java). Keep names and arguments in sync.
 *
 * Coordinates are screen pixels, as reported by read_screen / device_status.
 */
export type ToolDef = {
  name: string;
  title: string;
  description: string;
  input: z.ZodRawShape;
  timeoutSec: number;
  readOnly?: boolean;
};

const px = z.number().int().min(0).max(20000);

const ACTIONS = ['click', 'tap', 'long_press', 'swipe', 'type', 'press', 'open_app', 'wait'] as const;
const KEYS = ['home', 'back', 'recents', 'notifications', 'quick_settings', 'lock_screen', 'power_dialog'] as const;

/** One step of `act`: exactly one action key (type may also carry index/append). */
export const actStep = z
  .object({
    click: z.union([z.number().int().min(0), z.string().min(1).max(200)]).optional(),
    tap: z.array(px).length(2).optional(),
    long_press: z.array(px).min(2).max(3).optional().describe('[x,y,ms]'),
    swipe: z.array(px).min(4).max(5).optional().describe('[x1,y1,x2,y2,ms]'),
    type: z.string().max(5000).optional(),
    index: z.number().int().min(0).optional(),
    append: z.boolean().optional(),
    press: z.enum(KEYS).optional(),
    open_app: z.string().min(3).max(200).regex(/^[A-Za-z0-9_.]+$/).optional(),
    wait: z
      .object({
        text: z.string().min(1).max(200).optional(),
        gone: z.string().min(1).max(200).optional(),
        app: z.string().min(3).max(200).regex(/^[A-Za-z0-9_.]+$/).optional(),
        ms: z.number().int().min(0).max(3000).optional(),
        timeout: z.number().int().min(100).max(15000).optional(),
      })
      .strict()
      .refine((w) => w.text !== undefined || w.gone !== undefined || w.app !== undefined || w.ms !== undefined, {
        message: 'wait needs text, gone, app or ms',
      })
      .optional(),
  })
  .strict()
  .refine((o) => ACTIONS.filter((k) => o[k] !== undefined).length === 1, {
    message: 'each step needs exactly one action: ' + ACTIONS.join(', '),
  })
  .refine((o) => (o.index === undefined && o.append === undefined) || o.type !== undefined, {
    message: 'index/append only go with type',
  });

/** Phone-side cap of act is 60 s; the relay waits a little longer. */
export const ACT_TIMEOUT_SEC = 75;

export const TOOLS: ToolDef[] = [
  {
    name: 'device_status',
    title: 'Device status',
    description:
      'Phone state: screen, app in front, battery, accessibility on/off. Call first.',
    input: {},
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'list_macros',
    title: 'List macros',
    description: 'Recorded SayDo macros: id, trigger, steps.',
    input: {},
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'run_macro',
    title: 'Run macro',
    description:
      'Runs a recorded macro by id or trigger and waits until it ends.',
    input: { macro: z.string().min(1).max(200).describe('Macro id or trigger text, e.g. "open youtube"') },
    timeoutSec: 240,
  },
  {
    name: 'read_screen',
    title: 'Read screen',
    description:
      'Screen elements, one line each: "index label flags" (click/edit/scroll/checked). Index works with ' +
      'click_element/type_text. Use query to filter.',
    input: {
      query: z.string().min(1).max(200).optional().describe('Case-insensitive filter on text/description/id'),
      bounds: z.boolean().optional().describe('Add [l,t][r,b] pixels'),
      max_elements: z.number().int().min(1).max(600).optional().describe('Default 80'),
    },
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'screenshot',
    title: 'Screenshot',
    description: 'Expensive image of the screen; prefer read_screen. Use only when the tree is empty (games). Fails on secure windows.',
    input: {
      max_width: z.number().int().min(240).max(1440).optional().describe('Default 540'),
      quality: z.number().int().min(20).max(95).optional().describe('Default 50'),
    },
    timeoutSec: 25,
    readOnly: true,
  },
  {
    name: 'act',
    title: 'Act',
    description:
      'Up to 15 steps in one call: click (number|text), tap, long_press, swipe, type, press, open_app, wait ' +
      '({text,gone,app,ms,timeout}). Returns "1 ok 2 FAIL why" and optionally the screen.',
    input: {
      steps: z.array(actStep).min(1).max(15),
      return: z.enum(['none', 'screen', 'diff']).optional().describe('none (default), screen, or diff (what changed)'),
      stop_on_error: z.boolean().optional().describe('Default true'),
    },
    timeoutSec: ACT_TIMEOUT_SEC,
  },
  {
    name: 'read_notifications',
    title: 'Read notifications',
    description:
      'Recent notifications, newest first, "HH:mm App: title - text". Needs the owner to allow it in SayDo and in Android.',
    input: {
      limit: z.number().int().min(1).max(50).optional().describe('Default 15'),
      app: z.string().min(1).max(100).optional().describe('Package or app name part'),
      since_minutes: z.number().int().min(1).max(1440).optional(),
    },
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'click_element',
    title: 'Click element',
    description:
      'Clicks a read_screen number, or the element whose text/description/id contains `text`.',
    input: {
      index: z.number().int().min(0).optional(),
      text: z.string().min(1).max(200).optional(),
    },
    timeoutSec: 20,
  },
  {
    name: 'tap',
    title: 'Tap',
    description: 'Taps pixel coordinates.',
    input: { x: px, y: px },
    timeoutSec: 20,
  },
  {
    name: 'long_press',
    title: 'Long press',
    description: 'Long press at pixel coordinates.',
    input: { x: px, y: px, duration_ms: z.number().int().min(300).max(5000).optional() },
    timeoutSec: 20,
  },
  {
    name: 'swipe',
    title: 'Swipe',
    description: 'Swipes in pixels; swipe up scrolls down.',
    input: { x1: px, y1: px, x2: px, y2: px, duration_ms: z.number().int().min(50).max(5000).optional() },
    timeoutSec: 20,
  },
  {
    name: 'type_text',
    title: 'Type text',
    description:
      'Sets text in a field (read_screen number, or the focused one); append keeps the old text.',
    input: {
      text: z.string().max(5000),
      index: z.number().int().min(0).optional(),
      append: z.boolean().optional(),
    },
    timeoutSec: 20,
  },
  {
    name: 'press',
    title: 'System button',
    description: 'System key or panel.',
    input: {
      key: z.enum(['home', 'back', 'recents', 'notifications', 'quick_settings', 'lock_screen', 'power_dialog']),
    },
    timeoutSec: 20,
  },
  {
    name: 'open_app',
    title: 'Open app',
    description: 'Opens an app by package name.',
    input: { package: z.string().min(3).max(200).regex(/^[A-Za-z0-9_.]+$/) },
    timeoutSec: 20,
  },
  {
    name: 'list_apps',
    title: 'List apps',
    description: 'Launchable apps: label and package.',
    input: {},
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'wake_screen',
    title: 'Wake screen',
    description: 'Turns the screen on (does not unlock).',
    input: {},
    timeoutSec: 20,
  },
];
