# Phase A CLI

The installed `fruit-and-faults` executable exposes exactly six course commands:

```text
fruit-and-faults start <workspace> [--yes]
fruit-and-faults status
fruit-and-faults check
fruit-and-faults hint
fruit-and-faults next [--answer <option-id>] [--yes]
fruit-and-faults list
```

`--help`, `--version`, `--verbose`, and `--no-color` are common options. Common
flags may appear before or after a command. `--help` accepts command context
(including `start --help` without a destination); `--version` accepts no command
or positional arguments. `--answer` applies only to `next`; `--yes` applies only
to `start` and `next`. Duplicate flags, missing/duplicate values, unknown options,
extra positional arguments, and unsupported commands return usage code 2.
Quote workspace paths containing spaces using the current shell's ordinary
quoting rules. Unicode paths are supported; control/bidirectional format
characters in a start destination are rejected before filesystem access.

CLI instructions, headings, and prompts are in Russian. Installed course titles,
goals, instructions, hints, reflection choices, and targeted feedback retain the
bundle's published text. Help/version and the original Task 1 missing/unknown
command diagnostics retain their established English copy.

Human views, previews, and prompts go to stdout; failure diagnostics go to
stderr in expected/observed/next-action form. Default output contains no stack
trace or arbitrary exception message. `--verbose` adds a failure category or at
most four exception class identities when a boundary retains a cause. Messages,
stack frames, configuration bytes, process logs, credentials, and absolute
machine paths are not exposed through this diagnostic mode. Typed diagnostics
are limited to 32 observations and 4096 code points per field; terminal and
bidirectional controls are removed. Previews remain complete, including all
affected paths, rather than being truncated before confirmation. The selected
absolute destination is displayed only in start's requested preview; ordinary
workspace diagnostics use learner-relative paths.

Exit codes remain the Task 1 contract:

| Code | Meaning |
| ---: | --- |
| 0 | Successful command, including a read-only resume or completed route |
| 1 | Incomplete learner work, missing artifacts, wrong reflection, Git gate, or declined/EOF prompt |
| 2 | Invalid arguments, missing required noninteractive flags, or no workspace |
| 3 | Workspace conflict, malformed/incompatible progress, or unsafe workspace |
| 4 | Compilation or visible test failure |
| 5 | Validation/Git timeout or interruption |
| 10 | Internal installation, course, or adapter failure |

`System.exit` is confined to `main`; injected runs return a code and leave
caller-owned streams open. `ApplicationFactory` is the sole CLI composition
root. It wires real safe filesystem/progress/journal adapters, the bundled
course, read-only Git status, offline-cache preflight, bounded processes, public
validators, and application use cases. Commands discover the workspace from
the current directory or any nested directory; discovery and safety policies
remain in the [workspace contract](workspace-start-format.md).

Without an actual terminal (`Console.isTerminal` on Java 26), the CLI never
reads stdin or prompts, even when redirected input contains bytes. Fresh start
prints a validated preview and requires `--yes`; a compatible read-only start
resume needs no confirmation. Noninteractive `next` requires both a stable
option ID in `--answer` and `--yes` before validation/composition starts. It still
prints the exact transition preview. Interactive `next` displays numbered
choices, maps the selected number to the stable ID, gives targeted wrong-answer
feedback, and offers a retry. No full-screen TUI is used. Prompts accept
`да`/`д`/`yes`/`y` as confirmation; other values or EOF decline. Input lines are
bounded to 256 characters, and reflection interaction stops after 32 steps.
Color is emitted only for an actual/injected interactive terminal and disabled
by `--no-color`.

Start prints the active lesson and first-commit/public-GitHub instructions.
GitHub remains optional and never causes network access or CLI authentication.
`status` reports cheap artifact presence, hint level, local Git facts, remote
advice, continuation availability, and exactly one recommended next command.
`list` exposes titles/states only. See the [check](lesson-check-contract.md),
[status/hint/Git](status-hint-git-contract.md), and
[advancement](lesson-advance-contract.md) contracts for persistence and gates.
Preview and confirmed attempts revalidate application state; this can run the
lesson check again. Observed changes stop the operation; the existing snapshot
and filesystem-provider limitations remain unchanged.
