"""
OMP adapter — installs a native extension (not transcript scraping).

OMP exposes an extension API where a module does:

    export default function (pi) {
      pi.on('agent_start', ...)
      pi.on('tool_execution_start', ...)
    }

This is the same contract the existing `orca-agent-status.ts` on this machine
uses, which is why the integration is reliable: real lifecycle events instead
of guessing from session files.

The extension posts each event to the bridge with a fire-and-forget fetch so
OMP's TUI never waits on us.
"""

from __future__ import annotations

import shutil
from pathlib import Path

OMP_EXTENSIONS = Path.home() / ".omp" / "agent" / "extensions"
EXTENSION_NAME = "pixelpal-status.ts"

EXTENSION_SOURCE = """// PixelPal status bridge — installed by `pixelpal-bridge install`.
// Remove with `pixelpal-bridge uninstall` (deletes exactly this file).
//
// Why fire-and-forget: OMP awaits extension handlers on its event path, so a
// slow or dead bridge must never stall the TUI. We cap every post at 1s and
// keep only the latest pending payload (no unbounded queue).

const BRIDGE = process.env.PIXELPAL_BRIDGE || 'http://127.0.0.1:8787'
const POST_TIMEOUT_MS = 1000

let pending = null
let posting = false

function send(kind, fields = {}) {
  pending = { kind, fields }
  if (posting) return
  posting = true
  void flush()
}

async function flush() {
  try {
    while (pending) {
      const { kind, fields } = pending
      pending = null
      const controlled = new AbortController()
      const timer = setTimeout(() => controlled.abort(), POST_TIMEOUT_MS)
      try {
        await fetch(`${BRIDGE}/events`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          signal: controlled.signal,
          body: JSON.stringify({
            source: 'omp',
            kind,
            session_id: process.env.PIXELPAL_SESSION_ID || String(process.pid),
            project: process.cwd().split('/').pop() || '',
            at: Date.now() / 1000,
            id: `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            ...fields,
          }),
        })
      } catch {
        // bridge down: drop it, never retry in a loop
      } finally {
        clearTimeout(timer)
      }
    }
  } finally {
    posting = false
  }
}

const LABEL = {
  bash: 'running', shell: 'running',
  read: 'reading', edit: 'editing', write: 'writing',
  grep: 'searching', glob: 'finding files', task: 'delegating',
}

function describe(toolName, args) {
  const t = String(toolName || '').toLowerCase()
  const verb = LABEL[t] || 'using'
  const a = args || {}
  const detail = a.command || a.filePath || a.path || a.pattern || a.description || ''
  const base = detail ? String(detail).split('/').pop().slice(0, 60) : ''
  return base ? `${verb} ${base}` : verb
}

export default function (pi) {
  pi.on('agent_start', () => send('prompt'))
  pi.on('before_agent_start', (event) =>
    send('prompt', { detail: String(event?.prompt || '').slice(0, 60) })
  )
  pi.on('tool_execution_start', (event) =>
    send('tool_start', {
      tool: event?.toolName,
      detail: describe(event?.toolName, event?.args),
    })
  )
  pi.on('tool_execution_end', (event) =>
    send('tool_end', { tool: event?.toolName })
  )
  pi.on('message_end', (event) => {
    if (event?.message?.role !== 'assistant') return
    const text = Array.isArray(event.message.content)
      ? event.message.content.map((c) => c?.text || '').join(' ')
      : ''
    send('message', { detail: text.trim().slice(0, 80) })
  })
  pi.on('agent_end', () => send('turn_end'))
  pi.on('agent_settled', () => send('turn_end'))
}
"""


def install() -> list[str]:
    """Write the OMP extension. Returns the list of files touched."""
    if not OMP_EXTENSIONS.exists():
        return []
    target = OMP_EXTENSIONS / EXTENSION_NAME
    if target.exists() and "pixelpal" in target.read_text()[:200].lower():
        return []  # already installed
    target.write_text(EXTENSION_SOURCE)
    return [str(target)]


def uninstall() -> int:
    target = OMP_EXTENSIONS / EXTENSION_NAME
    try:
        target.unlink()
        return 1
    except OSError:
        return 0


def is_installed() -> bool:
    return (OMP_EXTENSIONS / EXTENSION_NAME).exists()
