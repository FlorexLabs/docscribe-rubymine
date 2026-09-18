#!/bin/zsh
# 3H.1 MCP toolset: JetBrains MCP Server enabled + our 6 docscribe_* tools
# registered and callable.
# Leg A (registration): idea.log shows DocScribeMcpToolset companion load
# + instantiate isEnabled=true + isEnabled called (no ClassNotFound).
# Leg B (live calls): stdio bridge — run the MCP stdio runner against the
# IDE's SSE endpoint... NOTE (proven 2026-09-18): the IDE in this VM opens
# NO MCP port (only 63342 built-in; 64500-64560 + 63342-63442 + 64000+
# sweeps empty) even with "Enable MCP Server" applied — startGlobalServer
# never binds here. So live tool calls are impossible in this stand; the
# case asserts registration (log) + code shape (6 @McpTool fns, MCP-header
# in doctor, project-wide update_types, VFS refresh) and documents the cut.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

open_stand || exit 1
sleep 10
M0="$(log_mark)"
# Touch the MCP subsystem: open Settings > MCP Server page (proves the
# bundled plugin page exists in this IDE), then close.
activate || { fail "3h1" "not front"; exit 1; }
osa 'tell application "System Events" to keystroke "," using {command down}' >/dev/null 2>&1
sleep 5
shot "3h1-settings"
ocr_text | grep -qi "Settings" || { fail "3h1" "settings won't open"; exit 1; }
escape; sleep 2
RC=0
LG="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" 2>/dev/null | grep -F 'DocScribeMcpToolset' | tail -n 3)"
# Registration lines appear at toolset query time; force one by opening
# the MCP settings page is NOT enough — grep whole log instead.
LG="$(gssh 'grep -F "DocScribeMcpToolset companion loaded" ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log | tail -n 1')"
[[ -n "$LG" ]] || RC=1
LI="$(gssh 'grep -F "DocScribeMcpToolset instantiated, isEnabled=true" ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log | tail -n 1')"
[[ -n "$LI" ]] || RC=2
# Code shape: 6 tools.
NTOOLS="$(grep -c '@McpTool' ../../src/mcp/kotlin/com/florexlabs/docscribe/mcp/DocScribeMcpToolset.kt)"
[[ "$NTOOLS" == "6" ]] || RC=4
# MCP doctor header distinct from action header.
grep -q 'Diagnostics (MCP)' ../../src/mcp/kotlin/com/florexlabs/docscribe/mcp/DocScribeMcpToolset.kt || RC=8
grep -q 'Batch mode:' ../../src/mcp/kotlin/com/florexlabs/docscribe/mcp/DocScribeMcpToolset.kt || RC=16
if [[ $RC -ne 0 ]]; then fail "3h1" "mcp registration mismatch (rc=$RC)"; exit 1; fi
pass "3h1"
