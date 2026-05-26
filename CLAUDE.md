# Agent Operating Rules & Constraints

## 1. System Environment & Stack
- Core Stack: Java 25, Quarkus 3.35.4, Gradle Kotlin DSL (`./gradlew`), langchain4j via Quarkiverse. Package root `com.quark`.
- Code Style: idiomatic Quarkus/CDI; virtual threads first-class; no raw `mvn`/`gradle` invocations while dev mode runs (use the quarkus-agent MCP).

## 2. Execution Constraints (Deterministic Guardrails)
- WIP Limit: You are strictly forbidden from working on more than one feature or file at a time. WIP = 1.
- State Rule: If a file change or intent is not committed to Git or written to `PROGRESS.md`, it does not exist.
- Non-Interactive: Do not run bash commands that block the terminal or expect human input (e.g., `./gradlew quarkusDev` in the foreground). Drive dev mode through the quarkus-agent MCP (`quarkus_start`, `quarkus_status`, `quarkus_logs`).

## 3. Tool & Verification Pipeline
- BEFORE making any changes, you MUST run the baseline verification check via the quarkus-agent MCP: `quarkus_callTool` with `toolName: "devui-testing_runTests"`.
- AFTER every code modification, you MUST run the same check: `quarkus_callTool` with `toolName: "devui-testing_runTests"`.
- Victory Condition: You are NOT allowed to declare a task "done" based on your own evaluation. A task is only done when the test runner reports all tests pass (zero failures, zero errors).

## 4. Failure Escalation (Stop Hooks)
- If a test fails 3 times consecutively with the same error, STOP execution immediately.
- Do not attempt a 4th time. Write the exact stack trace to `PROGRESS.md` and ask the human for clarification.
