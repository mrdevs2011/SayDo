import { z } from 'zod';
import type { ToolDef } from './tools.js';

/**
 * "Macro first" tools. Names and arguments must match app: McpCommands.java / McpMacros.java.
 * Descriptions are short on purpose: they are paid for in every conversation.
 */
const macroRef = z.string().min(1).max(200).describe('Macro id (from find_macro/list_macros) or trigger text');

export const MACRO_TOOLS: ToolDef[] = [
  {
    name: 'find_macro',
    title: 'Find macro',
    description:
      "Ranks the owner's recorded macros against a goal, in any language (Uzbek, Russian, English). Returns up to 3: " +
      'score 0..1, id, trigger, apps, SENSITIVE, description. Call first for every task.',
    input: {
      query: z.string().min(1).max(300).describe("The goal in the owner's words"),
      limit: z.number().int().min(1).max(3).optional().describe('Default 3'),
    },
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'list_macros',
    title: 'List macros',
    description: 'Recorded macros, one line each: id, trigger, apps, description. Paged.',
    input: {
      limit: z.number().int().min(1).max(50).optional().describe('Default 20'),
      offset: z.number().int().min(0).max(10000).optional(),
    },
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'describe_macro',
    title: 'Describe macro',
    description: 'What a macro does: description, apps, parameters, sensitive flag, steps (kind and target text).',
    input: { macro: macroRef },
    timeoutSec: 20,
    readOnly: true,
  },
  {
    name: 'set_macro_info',
    title: 'Set macro info',
    description:
      'Adds a description or alias phrasings to a macro so find_macro finds it later. Metadata only, never the steps. ' +
      'sensitive=true can be set; only the owner can clear it.',
    input: {
      macro: macroRef,
      description: z.string().max(300).optional().describe('What it does, one line'),
      aliases: z.array(z.string().min(1).max(60)).max(10).optional().describe('Other ways to ask for it; added to existing'),
      sensitive: z.boolean().optional(),
    },
    timeoutSec: 20,
  },
  {
    name: 'run_macro',
    title: 'Run macro',
    description:
      'Runs a macro and waits until it ends: "completed" or "stopped at step N", plus the app in front. ' +
      'SENSITIVE macros (money, banking, deleting, sending) are refused unless confirm=true, which you set only after the ' +
      'owner confirmed that exact action in the chat; the phone then also asks the owner to allow it.',
    input: {
      macro: macroRef,
      params: z
        .record(z.string().max(200), z.string().max(1000))
        .optional()
        .describe('Slot name to value, for macros with parameters (see describe_macro)'),
      confirm: z.boolean().optional().describe('Only after the owner explicitly confirmed this exact action'),
    },
    timeoutSec: 240,
  },
  {
    name: 'scroll_to',
    title: 'Scroll to text',
    description:
      'Swipes until an element containing `text` is visible (ignores case, accents, Latin/Cyrillic). Returns its read_screen line.',
    input: {
      text: z.string().min(1).max(200),
      direction: z.enum(['down', 'up', 'left', 'right']).optional().describe('Default down'),
      max_swipes: z.number().int().min(1).max(20).optional().describe('Default 8'),
    },
    timeoutSec: 45,
  },
];
