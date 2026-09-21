// Shell commands without a limit can hang the coding session forever
// (a waiting console, a stuck daemon, an interactive prompt). Pi has no
// default bash timeout, so the host promises the model a 300 s default
// (see the MagicPaper system prompt); this extension enforces that promise.
// An explicit timeout in the tool call always wins.

export const DEFAULT_TIMEOUT_SECONDS = 300;

const SHELL_TOOLS = new Set(['bash', 'powershell']);

/** Patches `input.timeout` in place: positive numbers are kept, everything else becomes the default. */
export function withDefaultTimeout(input) {
  const given = Number(input.timeout);
  input.timeout = Number.isFinite(given) && given > 0 ? given : DEFAULT_TIMEOUT_SECONDS;
  return input;
}

export default function shellTimeout(pi) {
  pi.on('tool_call', event => {
    if (!SHELL_TOOLS.has(event.toolName)) return;
    if (!event.input || typeof event.input !== 'object') return;
    withDefaultTimeout(event.input);
  });
}
