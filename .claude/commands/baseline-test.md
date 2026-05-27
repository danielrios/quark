---
description: Run the quarkus-agent baseline test gate per CLAUDE.md §3
allowed-tools: [mcp__quarkus-agent__quarkus_callTool, mcp__quarkus-agent__quarkus_status, mcp__quarkus-agent__quarkus_logs]
---

Run the CLAUDE.md §3 test verification by calling `mcp__quarkus-agent__quarkus_callTool` with:

```
toolName: devui-testing_runTests
```

After the call returns:
1. Report the pass/fail counts in one sentence.
2. If anything failed, paste the failing test names and the first stack frame of each failure.
3. If the MCP server is not reachable, say so explicitly — do NOT fall back to `./gradlew test`, since CLAUDE.md mandates the MCP path. Ask the human to start the MCP or check `docs/adr/0004-claude-code-harness.md`.

Do not edit files in response to this command — it is read-only verification.
