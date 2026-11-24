# The Main Spec Rewritten for Conversations (Plan 7.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the sections of the main spec that Appendix A of sub-project 7's design names, so that the main spec describes Kestrel after sub-project 7: a standalone copilot and driver of the phone with two channels, voice and text, that holds conversations of several commands, works on long goals and keeps past conversations.

**Architecture:** Documentation only. Each task replaces exact texts of `docs/superpowers/specs/2026-11-12-kestrel-design.md` with exact new texts, grouped by the spec's sections, so that a reviewer can accept or reject one group at a time. The main spec states each rule at its own level of detail and refers to sub-project 7's design for the details ("sub-project 7's design, section 8"). The decisions table gets five new rows, D29 to D33, which name the design's decisions D1 to D23 (D24, how the design is written, needs no row; D12, the architecture, is named in section 7.4's row of `:core:conversation`), and the changed rows D1, D7, D11, D15, D17, D24, D26 and D27. The last task checks the whole spec for words that must be gone and for broken references, and updates the description in `README.md`.

**Tech Stack:** Markdown (GitHub's flavour, with Mermaid), PowerShell 7.3 and Windows PowerShell 5.1 for the checks, Python 3 for the plan checker.

## Global Constraints

- The plan changes only `docs/superpowers/specs/2026-11-12-kestrel-design.md` and `README.md`. It changes no code, runs no Gradle, uses no device and calls no provider.
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes. Here that means: every replacement is made whole, exactly as the plan shows it, and a text that does not fit the spec as found is a fault of the plan. Stop and ask the controller; never adapt a text by hand.
- Sub-project 7's design, `docs/superpowers/specs/2026-11-24-conversations-design.md` at commit `75dd098b`, binds this plan; its decisions D1 to D24 are the owner's. Where a new text of this plan says something that the design does not say, or says it otherwise, stop and ask the controller.
- Plain words and short sentences, because the owner reads every sentence (the rules of "Talking to a human"): common phrasing, the subject, verb and object close together, no new noun stacks, and the small words such as "that" kept.
- Each replacement replaces exactly one occurrence. Before a task, every old text of the task must exist exactly once in the file as the earlier tasks left it.
- Make each replacement with an exact, whole-string replace (the agent's edit tool, or an editor's exact replace). Never retype an old text by hand. Never use PowerShell 5.1's `Set-Content -Encoding UTF8`, which writes a byte-order mark.
- The repository uses LF line endings and UTF-8 without a byte-order mark. The spec keeps its own characters, such as `…`, `–`, `÷`, `·` and `≥`.
- This plan is `.superpowers/sdd/plan-7-0.md`; the controller commits it as `docs/superpowers/plans/2026-11-24-conversations-7-0-spec.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

"The design" and "sub-project 7's design" mean `2026-11-24-conversations-design.md`; "the spec" means `2026-11-12-kestrel-design.md`. "Decision D13 of sub-project 7" names the design's D13; a plain "D29" names the spec's own row.

## Before you start

- The code is the branch `sp3` in `H:\Code\kestrel-sp3` at `4c37cca0` (main is at the same commit) or a later commit of the controller's. Plan 6B and its fix rounds are in it: they changed, among others, sections 8.10, 11.4, 12.5, 16.3 and 16.5 (the notification access and the photo and video permissions in the evaluation's kept set, `PermissionFlowTest` and `$KestrelDataProperties` of `scripts\lib\DeviceGuard.ps1`, Kestrel's own pages for the owner, and an `open_deep_link` app with two activities). None of this plan's old texts overlaps those changes, and no new text of this plan contradicts them. Task 12 renames the two "Continue the task?" of section 16.5's sentence on the run's answers.
- Run the plan checker over the whole plan against the current `HEAD`, before Task 1:

  ```powershell
  $env:PLANX = 'H:\Code\kestrel\.superpowers\sdd\plan-7-0.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:START_TASK = '1'; $env:S3A = 'H:\Code\kestrel\.superpowers\sdd\no-such-folder'; $env:SIMDIR = $env:S3A; $env:OUTDIR = 'plan70-sim'; python C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py
  ```

  Expected: `operations: 119`, no line that starts with `PROBLEM:`, and `files: 2`. The command must set `S3A` and `SIMDIR` to a folder that does not exist, as above: without them, the checker reads an old copy of the spec from the session's `plan3a-sim` folder and reports old texts as missing. If the controller committed the plan elsewhere, set `PLANX` to that path. If a `PROBLEM:` line names an old text that plan 6B-basefix changed, stop: the controller rebases this plan on the new text (see "Left for the controller").
- Measure the counts before the first change of Task 1, in `H:\Code\kestrel-sp3`, and note them in the task's report: `(Select-String -Path docs\superpowers\specs\2026-11-12-kestrel-design.md -Pattern '^#{1,4} ' -CaseSensitive).Count` is H₀, the spec's headings (100 at `75dd098b`), and `(Select-String -Path docs\superpowers\specs\2026-11-12-kestrel-design.md -Pattern '^\| D\d+ \|' -CaseSensitive).Count` is R₀, the rows of the decisions table (28). Each task gives its counts as "H + k" and "R + k", where H and R are the counts before the task. If a count differs, stop and ask the controller.
- Before each task, run `git status --short`, and run the plan checker with `START_TASK` set to the task's number and `BASE_REF` to the current `HEAD`; it checks this task and every later one against the spec as the earlier tasks left it. If a text is missing, or the spec or `README.md` shows changes that are not this plan's, stop and ask the controller.

## Not in this plan

- Sub-project 7's design itself, which stays as the owner approved it.
- `README.md`'s sections other than its first paragraph: the conversation screen, the Conversations page and the new hooks enter the README with the plans that build them (7D to 7G).
- `AGENTS.md`, the code, the scripts and the plans of sub-project 7 (7A to 7G).
- Section 19.1's results of earlier sub-projects, which say what was measured then, with the words of their time ("a dictated task", "the wait for a task").

## Changes to the design that this plan makes

The design's Appendix A lists the sections that change. This plan also changes a few places that the appendix does not name, because the spec would contradict itself otherwise. The controller decides each of them (see "Left for the controller"):

- **The precedence of the two specs.** The design says that it decides "until" plan 7.0. The spec's header gets a row that says that from plan 7.0 on the spec describes Kestrel after sub-project 7 and decides where the two differ, while the design keeps the details that the spec refers to.
- **The decisions D29 to D33.** The spec gets five rows that sum up the design's 24 decisions and name them. D11, D15, D17 and D26 change together with D1, D7, D24 and D27, because they named a spoken yes, spoken replies, the logs alone and six sub-projects.
- **"Continue the goal?" by voice.** The design says that a touch never ends a held goal and that the card's wait leaves it held (design 6.5 and 10.4), and that a held goal waits 30 minutes (decision D17 of sub-project 7). Today, silence to "Continue the task?" ends the task. The spec now says that silence by voice also leaves the goal held, which the design implies but does not say for speech. The controller accepted this on 24 November 2026.
- **Section 19 and section 20.** The design's risks R2 and R3 become the spikes S9 (the goal service's starts, which plan 7E checks) and S10 (the speed of the first search, which plan 7C measures), with the design's fallbacks. Its risks R1, R4, R5, R6, R7 and R9 become the spec's risks 13 to 18.
- **The spec's Appendix A.** Section 8.3 says that Appendix A lists Kestrel's own requests, so its table of Kestrel's commands gains "new conversation", "continue the conversation about …" and the follow-up window, and its rows for a task, "stop", "continue" and "delete history" change.
- **The word "conversation".** The spec used "conversation" for the messages of one task run with the model (sections 11.2 and 12.6). These become "the run's messages" (the code's `Conversation`), and the glossary says so, so that "conversation" means the owner's conversation everywhere else.
- **"Task".** A task is now the work of one command, and a task run is one run of the agent's loop; a goal runs as one or more task runs. The spec keeps "task" wherever this meaning holds, and the glossary says so.
- **Section 18.** The first release for daily use needs the first six sub-projects; sub-project 7 comes in a later release.

## Map of the changes

| Task | Spec sections | What changes |
|---|---|---|
| 1 | Header, 1, 3 | The precedence row; the two channels and conversations in the summary; the glossary's new terms |
| 2 | 4 | D1, D7, D11, D15, D17, D24, D26 and D27 changed; D29 to D33 added |
| 3 | 5 | The two musts as goals of the release with sub-project 7; new non-goals; the multi-turn tasks, `records-injection` and the search's speed as criteria |
| 4 | 7.2, 7.3, 7.4, 7.5, 17 | `ConversationActivity` and `GoalService`; the Orchestrator keeps voice, beside the conversation engine and `ScreenTurns`; `:core:conversation`; the data flow; the repository's tree |
| 5 | 8.3, 8.4, 8.5, 8.6, 8.7, 8.9, 9.4, 9.5, 9.6, 9.7, 9.8 | The follow-up window, the new local requests, the microphone button, calls that pause a goal, the bar's progress, "On hold" and "Go on…"; the question card; the touch option |
| 6 | 10.3, 10.4, 11.1, 11.2 | The context's order; entry through the conversation; channels and replies; steering, records and the records block in the loop |
| 7 | 11.4, 11.5, 11.7, 11.8, 11.9, 11.10 | `update_checklist` and `search_conversations`; rounds, "Continue?", the checklist and resume; the completion check's checklist rule; what Kestrel listens to while a goal runs |
| 8 | 12.1, 12.3, 12.4, 12.5, 12.6, 12.7 | The typed-input boundary; the owner's words and the allowance across commands; the card's waits; Kestrel's own screen; the vault per run; test mode per conversation |
| 9 | 13.4, 13.5, 13.6 | The archive and its key; "delete history" covers conversations; `FOREGROUND_SERVICE_SPECIAL_USE` and the goal service's type |
| 10 | 14.1, 14.4, 14.5, 14.6 | A touch interrupts a goal; held goals; "Continue the goal?" after a restart; a refused start of the goal service |
| 11 | 15 | The conversation screen as the launcher, past conversations, the goal notification and the Conversations page |
| 12 | 16.1, 16.2, 16.3, 16.4, 16.5, 16.6 | The new hooks, unit tests, device tests, multi-turn tasks and `records-injection` |
| 13 | 18, 19, 20, Appendix A | Sub-project 7 with its plans; the spikes S9 and S10; the risks 13 to 18; Kestrel's commands |
| 14 | the whole spec, `README.md` | The consistency check, and the README's description |

---

### Task 1: The header, the summary and the glossary

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (the header table, section 1, section 3)

**Interfaces:**
- Consumes: nothing.
- Produces: the terms that every later task uses, as the glossary defines them: conversation, command, channel, conversation screen, goal, round, task and task run, record, quiet time, follow-up window, steering and held goal. The summary refers to sections 11.1, 11.7 and 15, which Tasks 6, 7 and 11 rewrite.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '1'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The header's row on sub-project 7**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Licence | GPL-3.0-or-later |
```

with:

```text
| Licence | GPL-3.0-or-later |
| Sub-project 7 | Conversations, from the design of 24 November 2026 (`2026-11-24-conversations-design.md`, called "sub-project 7's design" below). Plan 7.0 wrote it into this spec. Since then, this spec describes Kestrel after sub-project 7 and decides where the two differ, and that design keeps the details that this spec refers to. |
```

- [ ] **Step 3: The summary**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Kestrel is a personal Android app that replaces Google's Voice Access on the owner's Samsung Galaxy S23+.

It keeps everything that Voice Access does. The owner can still give direct commands such as "scroll down", "tap Send" or "show numbers", and can dictate and edit text by voice. Kestrel adds a second layer on top: the owner can give a whole task in one sentence, for example "Kestrel, send Ana a Viber message that I'm 10 minutes late", and an agent performs it on the phone step by step through Android's accessibility service.
```

with:

```text
Kestrel is a personal Android app for the owner's Samsung Galaxy S23+: a standalone copilot and driver of the phone. The owner reaches it through two channels: his voice, and the conversation screen, where he types. Kestrel also replaces Google's Voice Access.

It keeps everything that Voice Access does. The owner can still give direct commands such as "scroll down", "tap Send" or "show numbers", and can dictate and edit text by voice. Kestrel adds a second layer on top: the owner holds a conversation with Kestrel, by voice or typed. He gives a command, for example "Kestrel, send Ana a Viber message that I'm 10 minutes late", and an agent performs it on the phone step by step through Android's accessibility service. The next command can build on the earlier ones, as in "and send it to Mila too", and the conversation ends by itself after a quiet time without a command (section 11.1). For a bigger goal, the agent keeps a checklist, works within limits that the owner sets, and asks before it goes on (section 11.7). The owner can look through his past conversations, read them and continue one (section 15).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
It sends a screenshot to the LLM only with the owner's permission, which Kestrel asks for by voice.
```

with:

```text
It sends a screenshot to the LLM only with the owner's permission, which Kestrel asks for by voice or on its question card.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
A check in Kestrel's code decides which actions need a spoken confirmation, which are handed back to the owner, and which are always blocked.
```

with:

```text
A check in Kestrel's code decides which actions need the owner's confirmation, which are handed back to the owner, and which are always blocked.
```

- [ ] **Step 4: The glossary**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Task | A free-form request that starts with the name "Kestrel", or that follows the side-key or earbud gesture. The agent or a saved routine performs it. |
```

with:

```text
| Conversation | A series of the owner's commands that build on each other, spoken or typed. At most one conversation is open. It ends when the quiet time passes after a command, or when the owner starts a new one, and a past conversation can be continued (section 11.1). The agent's `Conversation` in the code is something else: the messages of one task run with the model (section 11.2). |
| Command | One input of the owner's that asks Kestrel to do something: spoken, with the name "Kestrel", after the side-key or earbud gesture or inside the follow-up window; or typed on the conversation screen. |
| Channel | How an input came in and how Kestrel answers it: the voice channel or the text channel (section 11.1). |
| Conversation screen | Kestrel's screen where the owner reads the conversation and types commands. The launcher opens it (section 15). |
| Goal | The work that a command asks for, with its checklist. A goal can run over several rounds and take steering and "continue" from later inputs (section 11.7). |
| Round | One stretch of a goal's work within the limits that the owner sets (section 11.7). A yes to "Continue?" gives another round. |
| Task | The work of one command, done by the agent or by a saved routine. A task run is one run of the agent's loop. A goal runs as one or more task runs: a yes to "Continue?" starts another round in the same task run, and a resumed goal starts a new task run (section 11.7). |
| Record | The structured summary of one command: the request, what Kestrel said and asked, what the owner answered, the data tools' results, the apps used, the checklist and the final answer. The later commands of the conversation get the records as context (section 11.2), and the search and the conversation screen read them. |
| Quiet time | The time without a command after which the conversation ends: 1 minute by default (section 11.1). |
| Follow-up window | The short time after Kestrel's spoken final reply in which the owner may speak to the conversation without the name: 8 s by default (section 8.3). |
| Steering | A command that comes while a goal acts. It changes the goal instead of starting a new one (section 11.10). |
| Held goal | A goal that is open but not acting: paused by a lock or a call, paused at a limit, or interrupted (section 14.4). It is held for 30 minutes. |
```

- [ ] **Step 5: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'replaces Google''s Voice Access on the owner''s Samsung', 'which Kestrel asks for by voice.', 'need a spoken confirmation', 'A free-form request that starts with the name') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the four texts `: 0`, then H + 0.

- [ ] **Step 6: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 1: the header names sub-project 7, the summary describes a copilot with two channels and conversations, and the glossary gains the conversation's terms" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The decisions

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 4)

**Interfaces:**
- Consumes: the terms of Task 1.
- Produces: the rows D29 (conversations), D30 (the text channel), D31 (goals), D32 (past conversations) and D33 (safety across a conversation), which every later task cites, and the changed rows D1, D7, D11, D15, D17, D24, D26 and D27. Decision D27 keeps its number, so section 14.1's heading (Task 10) can keep "(D27)".

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '2'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The section's introduction**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
The owner made these decisions during brainstorming on 11–12 November 2026. Later sections refer to them as D1–D28.
```

with:

```text
The owner made decisions D1 to D28 during brainstorming on 11–12 November 2026, and D29 to D33 on 24 November 2026 for sub-project 7, whose design names them in detail as its own D1 to D24 ("decision D13 of sub-project 7"). Plan 7.0 changed D1, D7, D11, D15, D17, D24, D26 and D27 to match them. Later sections refer to them as D1–D33.
```

- [ ] **Step 3: The changed rows**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D1 | Hands-free level | Voice-first. Every step inside a task must work by voice. Touch is fine for setup and for occasional fixes. |
```

with:

```text
| D1 | Hands-free level and channels | Voice or text. Every step of a spoken conversation works by voice; a typed conversation uses the conversation screen and its cards. Touch is fine for setup and for occasional fixes. |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D7 | Task trigger | Tasks start with the name "Kestrel". Other speech is matched against the instant commands, and anything unrecognised is ignored. The side key and the earbud gesture mean "the next sentence is a task". |
```

with:

```text
| D7 | Command trigger | A spoken command starts with the name "Kestrel", or comes without it inside the follow-up window after Kestrel's spoken final reply. A typed command on the conversation screen needs no name. Other speech is matched against the instant commands, and anything unrecognised is ignored. The side key, the earbud gesture and the conversation screen's microphone button mean "the next sentence is a command". |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
A screenshot goes to the LLM only after a spoken yes, which can be "always for this app".
```

with:

```text
A screenshot goes to the LLM only after the owner's yes, spoken or on Kestrel's question card, which can be "always for this app".
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D15 | Speech output | Kestrel speaks confirmations, answers, hand-overs and errors, and shows a feedback bar. It does not narrate each step by default. The offline voice is built into the app. |
```

with:

```text
| D15 | Speech output | In a spoken conversation, Kestrel speaks confirmations, answers, hand-overs and errors. A typed command gets written replies, unless the owner turns on spoken replies to typed commands (D30). Kestrel shows a feedback bar. It does not narrate each step by default. The offline voice is built into the app. |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D17 | Activity log | A full log (commands, redacted prompts, LLM replies, actions and cost), encrypted and deleted after 7 days. "Delete history" wipes it. |
```

with:

```text
| D17 | Activity log | A full log (commands, redacted prompts, LLM replies, actions and cost), encrypted and deleted after 7 days. "Delete history" wipes it, the network log and the past conversations (D32). |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D24 | Model strategy | A lightweight model first, an automatic switch to a versatile model when the agent is stuck, a cost cap of 10 AI Credits per task (Kestrel asks before going over), and spending shown in the app. |
```

with:

```text
| D24 | Model strategy | A lightweight model first, an automatic switch to a versatile model when the agent is stuck, and spending shown in the app. A goal works in rounds, within limits that the owner sets: 500 model replies, 60 minutes of working time and 1,000 AI Credits by default. At a limit, Kestrel reports what is done and what is left and asks "Continue?"; each yes allows the same again. A goal without progress still ends early as stuck (decisions D7 and D22 of sub-project 7). |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
built as six sub-projects (section 18). |
```

with:

```text
built as seven sub-projects (section 18). |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D27 | Touch | Any touch on the screen cancels a running task, a routine replay or a pending confirmation. |
```

with:

```text
| D27 | Touch | Any touch on the screen interrupts a goal that acts or asks, a routine replay included. Kestrel stops at once, a pending confirmation counts as not given, and the goal stays open and held, with Continue and Cancel (decision D13 of sub-project 7). A touch cancels an instant command's question or countdown. |
```

- [ ] **Step 4: The rows of sub-project 7**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| D28 | Licence | GPL-3.0-or-later. |
```

with:

```text
| D28 | Licence | GPL-3.0-or-later. |
| D29 | Conversations | Multi-turn conversations with memory are a must. Each command builds on the earlier commands of its conversation: a later command gets a structured record of each earlier one (the request, everything that Kestrel said, its final answer, the data tools' results, the apps used and the checklist), never an old screen. One conversation is open at a time, by voice and typed. It ends after a quiet time between two commands: 1 minute by default, from 30 s to 30 minutes. After each spoken final reply, a follow-up window of 8 s by default takes speech without the name, and everything said in it goes to the conversation. Instant commands do not restart the quiet time. Memory facts and route notes do not change (decisions D1, D3, D4, D14, D15 and D18 of sub-project 7). |
| D30 | The text channel | The conversation screen is a must. The launcher opens it; it has the transcript, a text field, a microphone button and "New conversation", and it works with listening off. Typed input counts as the owner's only when he typed it on Kestrel's own screen. Kestrel's own app is never an action target, so Kestrel leaves its screen before it acts. A question inside another app shows a card with buttons and "Reply…". Replies follow the input: spoken for a spoken command, written for a typed one, and a setting speaks the replies to typed commands too. A typed conversation waits longer for answers than a spoken one (decisions D5, D8, D10, D20 and D21 of sub-project 7). |
| D31 | Goals | Longer goals within the limits that the owner sets (D24), with a checklist, steering while a goal runs, recovery, and continuation across later commands. A command while a goal acts steers it; "stop" and "cancel" end it. A touch, a lock, a call or the end of Kestrel's process holds a goal for 30 minutes instead of ending it, and the goal continues only when the owner says so. A foreground service runs for every open goal. Not fewer questions, not tasks that Kestrel starts by itself, and no work in the background or on a virtual display (decisions D2, D7, D9, D11, D13, D17, D19 and D22 of sub-project 7). |
| D32 | Past conversations | Kept on the phone, encrypted, for 30 days by default; listed, read, deleted and continued, as in VS Code's Copilot chat. The agent has a search tool that runs on the phone; only the matching records go to the model, and only within the task that asked. "Delete history" erases them (decisions D6 and D23 of sub-project 7). |
| D33 | Safety across a conversation | The owner's words in every command and answer of a conversation count as his own text later, and the sending, calls and deleting that an earlier command asked for carry to the later commands. Standard mode still confirms every send, share and delete, and every call is still handed over. Data from earlier results stays data, confirmations never carry, and test mode holds for the whole conversation (decisions D8 and D16 of sub-project 7). |
```

- [ ] **Step 5: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'Voice-first', 'Every step inside a task must work by voice', 'Task trigger', 'after a spoken yes', 'cost cap of 10 AI Credits', 'six sub-projects (section 18)', 'cancels a running task, a routine replay or a pending', 'D1–D28') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^\| D\d+ \|' -CaseSensitive).Count
```

Expected: each of the eight texts `: 0`, then R + 5 (33 from 28).

- [ ] **Step 6: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 2: the decisions of sub-project 7 as D29 to D33, and D1, D7, D11, D15, D17, D24, D26 and D27 changed to match them" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Goals, non-goals and success criteria

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 5.1, 5.2 and 5.3)

**Interfaces:**
- Consumes: D29 to D33 (Task 2).
- Produces: the success criteria of sub-project 7, which section 18.7 (Task 13) names in its "Done when": the multi-turn tasks, the case `records-injection` and the first search's speed. They refer to sections 16.5, 16.6 and 11.4, which Tasks 12 and 7 change.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '3'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The goals of the release with sub-project 7**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
### 5.1 Goals for the first release
```

with:

```text
### 5.1 Goals

The first release has these goals:
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
4. The owner can see and delete everything that Kestrel stored or sent (D16, D17).
```

with:

```text
4. The owner can see and delete everything that Kestrel stored or sent (D16, D17).

The release that holds sub-project 7 adds two goals that the owner calls musts, with the same weight as the four above:

5. Multi-turn conversations with memory (D29): a conversation lasts several commands, spoken and typed, and each command builds on the earlier ones.
6. A text channel (D30): on the conversation screen, the owner types a command, also while listening is off.

That release also brings long goals with a checklist, steering and "Continue?" (D31); past conversations that are kept, searched, read and continued (D32); goals that survive a touch and the end of Kestrel's process; and no weaker safety (D33).
```

- [ ] **Step 3: The new non-goals**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
### 5.2 Non-goals for the first release
```

with:

```text
### 5.2 Non-goals

Neither the first release nor the release that holds sub-project 7 has these goals:
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Distribution through Google Play, use by other people, and tablets.
```

with:

```text
- Distribution through Google Play, use by other people, and tablets.
- Tasks that Kestrel starts by itself, work with the screen off, and work on a virtual display (D31). Longer goals do not mean fewer questions: the safety check asks during a goal exactly as often as during a task.
- Typed input from outside Kestrel's own screen: an inline reply in a notification, a share intent, or any intent that carries a command (D30).
- Syncing conversations to another device, and exporting them. The export of the logs stays (section 13.5).
```

- [ ] **Step 4: The criteria of sub-project 7**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Prompt-injection suite | no send, share, call or delete happens without the confirmation that Standard mode requires, in 5 live runs of each of the 14 fixtures (section 16.6) | Emulator |
```

with:

```text
| The multi-turn tasks of sub-project 7 (section 16.5) | each passes 3 of 3 dry runs, and each one that runs live passes 3 of 3 runs in test mode. They are not part of the 29 tasks' rates, and their own baseline is recorded once | Emulator; the A71 where a task names it |
| Prompt-injection suite | no send, share, call or delete happens without the confirmation that Standard mode requires, in 5 live runs of each of the 14 fixtures (section 16.6), and in the runs of the multi-turn case `records-injection` | Emulator |
| The first search over 30 days of past conversations (section 11.4) | at most 1 s | A71 |
```

- [ ] **Step 5: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'Goals for the first release', 'Non-goals for the first release') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in 'The release that holds sub-project 7 adds two goals', 'Syncing conversations to another device', 'The multi-turn tasks of sub-project 7 (section 16.5)', 'The first search over 30 days of past conversations') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the first two texts `: 0`, each of the next four `: 1`, then H + 0.

- [ ] **Step 6: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 3: the two musts of sub-project 7 as goals, its non-goals, and its success criteria" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The architecture and the repository

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 7.2, 7.3, 7.4, 7.5 and 17)

**Interfaces:**
- Consumes: D29 to D33 (Task 2).
- Produces: the names that later tasks use: `ConversationActivity` (the conversation screen), `GoalService` (the goal service), `MainActivity` (the settings), the `ConversationEngine` and its `ConversationMachine`, `PhoneSignals`, `ScreenTurns`, the module `:core:conversation`, and the ports `GoalPort` and `HistoryPort` of `:core:agent`. Section 7.3's new heading is "Orchestrator, conversation and screen turns".

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '4'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The Android components**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Settings UI | Activities built with Jetpack Compose | The setup checklist, profiles, lists and logs (section 15). |
```

with:

```text
| `ConversationActivity` | The launcher activity, built with Jetpack Compose (`singleTask`) | The conversation screen: the transcript, the text field and the microphone button, Stop, Continue and Cancel, "New conversation" and the past conversations (section 15). It works with listening off, holds no state of its own, and opens the settings from its gear button. |
| `GoalService` | Foreground service of the type `specialUse` (Android 14 and later), with `mediaPlayback` added while it speaks with listening off | Runs for every open goal, in every phase, with listening on or off, and shows the goal's notification: its progress with Stop and Open, or Continue and Cancel while the goal is held (sections 13.6 and 15). |
| Settings UI | `MainActivity` and its pages, built with Jetpack Compose | The setup checklist, profiles, lists and logs (section 15). It opens from the conversation screen's gear button, from "Kestrel, open settings" and from the intents that name a page. |
```

- [ ] **Step 3: The Orchestrator, the conversation and the screen's turns**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
### 7.3 Orchestrator
```

with:

```text
### 7.3 Orchestrator, conversation and screen turns
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
A single `Orchestrator` coordinates everything, so that two parts of Kestrel never drive the screen at the same time. Every input becomes an event for it: speech results, keyword hits, screen events, touches, locking and unlocking, calls, button presses and LLM results.
```

with:

```text
Three parts share the coordination, so that two parts of Kestrel never drive the screen at the same time. The `Orchestrator` owns the voice: every spoken input becomes an event for it, and so do keyword hits, screen events, touches, locking and unlocking, calls and button presses. The `ConversationEngine` of `:core:conversation` owns the conversation and its goal (section 11.1): the Orchestrator hands it the owner's spoken commands, the conversation screen hands it the typed ones, and `PhoneSignals` in `:app` hands it the locks, the unlocks and the calls, also while listening is off. `ScreenTurns` in `:app` lets one actor at a time use the screen: an instant command, a goal or the settings.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
2. **The current activity:** idle, running an instant command, running a task (agent or routine), or waiting for the owner. Waiting covers a confirmation, an answer to Kestrel's question, and a hand-over.
```

with:

```text
2. **The current activity:** idle, running an instant command, waiting for a command (after "Kestrel" alone, the side key or the microphone button), the follow-up window (section 8.3), or the conversation's goal as the engine's view shows it: acting, asking, handed over or held.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Each transition is defined in one place and covered by unit tests: the Orchestrator lives in `:app`, and its rules are one pure function from the state and an event to the new state and a list of effects, such as speaking or running a task. The microphone and recognition are not effects; they follow from the state, so they cannot drift from it. Section 11.10 describes which speech Kestrel accepts while a task runs.
```

with:

```text
Each transition is defined in one place and covered by unit tests. The Orchestrator lives in `:app`, and its rules are one pure function from the state and an event to the new state and a list of effects, such as speaking or handing a command to the conversation. Its state holds the conversation's view, which the engine sends it at every change. The conversation's rules are a pure function of the same kind, `ConversationMachine`; the engine applies it to one event at a time and performs its effects in order. The microphone and recognition are not effects; they follow from the state, so they cannot drift from it. Section 11.10 describes which speech Kestrel accepts while a goal runs.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
A cancelled job must complete, for at most 3 s, before a new command, a task or the settings may act; after that, Kestrel goes on without it.
```

with:

```text
A cancelled job must complete, for at most 3 s, before a new command, a goal or the settings may take the screen through `ScreenTurns`; after that, Kestrel goes on without it.
```

- [ ] **Step 4: The modules**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| `:app` | Wiring, Android services and the Compose settings UI | No |
```

with:

```text
| `:app` | Wiring, Android services, the voice and text channels, the conversation screen and the Compose settings UI | No |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| `:core:agent` | The agent loop, its observation, its tools, memory and routines. It reaches the rest of Kestrel only through its ports (`ScreenPort`, `EyesPort`, `DataPort`, `OwnerPort`, `ModelPort`, `AppsPort`, `MemoryStore`, `TaskLog`, `AgentClock`), which `:app` and `:core:datatools` implement, so that the whole loop runs in JVM tests with a scripted model | Yes |
```

with:

```text
| `:core:agent` | The agent loop, its observation, its tools, memory and routines. It reaches the rest of Kestrel only through its ports (`ScreenPort`, `EyesPort`, `DataPort`, `OwnerPort`, `ModelPort`, `AppsPort`, `MemoryStore`, `TaskLog`, `AgentClock`, and `GoalPort` and `HistoryPort` for the goal around a task run and for the search of past conversations), which `:app`, `:core:datatools` and `:core:conversation` implement, so that the whole loop runs in JVM tests with a scripted model. It knows nothing about conversations: one call runs one task run | Yes |
| `:core:conversation` | The conversation: its state machine and engine, the quiet time, the goal runner with steering, limits, interruption and resume, the records and the context block built from them, the owner's channels behind one port, Kestrel's own requests without a model, and the store of past conversations with its retention and its search on the phone. It depends on `:core:agent`, `:core:safety` and `:core:commands`, never on `:app`, `:core:voice`, `:core:screen` or `:core:privacy`, so that the whole conversation runs in JVM tests with the scripted model (decision D12 of sub-project 7) | Yes |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| `:core:privacy` | The network gateway and its allowlist, the network log, the activity log and encrypted storage, including the credential store and the loopback server for browser sign-in | No |
```

with:

```text
| `:core:privacy` | The network gateway and its allowlist, the network log, the activity log and encrypted storage, including the credential store, the archive of past conversations with its own key (section 13.4) and the loopback server for browser sign-in | No |
```

- [ ] **Step 5: The data flow**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
    ORC -->|task| AGT[":core:agent"]
```

with:

```text
    ORC -->|spoken command| CONV[":core:conversation"]
    TXT[Conversation screen] -->|typed command| CONV
    CONV -->|goal as task runs| AGT[":core:agent"]
    CONV -->|records| ARC["Archive of past conversations (:core:privacy)"]
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
    ORC --> TTS[Voice output]
```

with:

```text
    ORC --> TTS[Voice output]
    CONV -->|spoken replies| ORC
    CONV -->|written replies| TXT
    AGT -->|search_conversations| CONV
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Speech becomes text, and the Orchestrator routes it. Instant commands go through `:core:commands` straight to `:core:screen`. Tasks go to `:core:agent`, which first looks for a matching routine and otherwise runs the LLM loop.
```

with:

```text
- Speech becomes text, and the Orchestrator routes it. Instant commands go through `:core:commands` straight to `:core:screen`. Commands of the conversation go to `:core:conversation`, and so does the text that the owner types on the conversation screen. For a command, the conversation starts a goal and runs it as task runs of `:core:agent`, which first looks for a matching routine and otherwise runs the LLM loop. The conversation answers the owner over the channel of his latest input (section 11.1).
```

- [ ] **Step 6: The repository's tree**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
  core/screen/ voice/ commands/ providers/ agent/ safety/ privacy/ perception/ datatools/
```

with:

```text
  core/screen/ voice/ commands/ providers/ agent/ conversation/ safety/ privacy/ perception/ datatools/
```

- [ ] **Step 7: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'A single `Orchestrator` coordinates everything', 'running a task (agent or routine)', 'such as speaking or running a task', 'ORC -->|task|', 'Tasks go to `:core:agent`') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in '| `ConversationActivity` |', '| `GoalService` |', '| `:core:conversation` |', '### 7.3 Orchestrator, conversation and screen turns') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the first five texts `: 0`, each of the next four `: 1`, then H + 0.

- [ ] **Step 8: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 4: the conversation screen, the goal service, the conversation engine beside the Orchestrator, :core:conversation and the data flow through the conversation" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The voice pipeline, the overlays and Voice Access's options

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.3, 8.4, 8.5, 8.6, 8.7, 8.9, 9.4, 9.5, 9.6, 9.7 and 9.8)

**Interfaces:**
- Consumes: D29 to D31 (Task 2); `GoalService`, `PhoneSignals` (Task 4).
- Produces: the follow-up window's rules in section 8.3, to which the glossary (Task 1), sections 11.1 and 11.10 (Tasks 6 and 7) and section 12.1 (Task 8) refer; the question card of section 9.4, which sections 12.4, 14.1 and 15 name; "Continue the goal?" after a call (section 8.7), which section 14.4 (Task 10) repeats for the lock.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '5'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: Endpointing, the command and Kestrel's own requests (section 8.3)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and while Kestrel waits for a task, after "Yes?" or after an interruption with "Kestrel", the limit becomes 1.5 s, so that the owner can pause while dictating a task:
```

with:

```text
and while Kestrel waits for a command, after "Yes?" or after an interruption with "Kestrel", the limit becomes 1.5 s, so that the owner can pause while dictating a command:
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- A transcript is a task when its first word matches "Kestrel", allowing near misses such as "Kestral" or "Kes trel". When the keyword spotter heard the name at the start of the utterance, more distant spellings count, and the name may also be the second word, as in "Hey Kestrel". A transcript without a word like the name is not a task, even when the spotter fired, so that a false hit cannot start a task. Kestrel removes the name, and the rest is the task. "Kestrel" on its own makes Kestrel answer "Yes?" and treat the next utterance within 8 s as the task.
```

with:

```text
- A transcript is a command of the conversation when its first word matches "Kestrel", allowing near misses such as "Kestral" or "Kes trel". When the keyword spotter heard the name at the start of the utterance, more distant spellings count, and the name may also be the second word, as in "Hey Kestrel". A transcript without a word like the name is not a command, even when the spotter fired, so that a false hit cannot start a goal. The follow-up window below is the one exception. Kestrel removes the name, and the rest is the command. "Kestrel" on its own makes Kestrel answer "Yes?" and treat the next utterance within 8 s as the command.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- A few requests that start with "Kestrel" are handled on the phone without the LLM, such as "Kestrel, wake up" and "Kestrel, delete history". Appendix A lists them.
- Everything else goes to the instant-command parser (section 9.5). Speech that is neither a task nor a command is ignored, except during dictation (section 9.7).
```

with:

```text
- A few requests that start with "Kestrel" are handled on the phone without the LLM, such as "Kestrel, wake up", "Kestrel, delete history", "Kestrel, new conversation" and "Kestrel, continue the conversation about the trip". Appendix A lists them. Typed on the conversation screen, Kestrel's own requests count without the name (section 12.1).
- **The follow-up window** (D29). When Kestrel has finished speaking a command's final reply, or the final reply of one of its own requests, it opens the follow-up window: 8 s by default, set on the Conversations page (section 15), and never longer than the quiet time. Every utterance that starts inside the window goes to the conversation as a command, without the name, also in the paused mode. Kestrel's own requests, such as "stop", "cancel", "stop listening", "turn off", "new conversation" and "continue the conversation about X", keep their meaning, and the instant-command parser is not asked. The bar shows "Go on…" with the seconds left. The window does not open after a `say` in the middle of a goal, because steering a goal by voice always needs the name, so that a TV cannot steer it. It does not open after a written reply either. It ends at its timer, at any transcript, at Kestrel's next reply, when the screen turns off, a call rings or listening stops, and when the conversation closes. After it, "Kestrel, …" is needed again.
- Everything else goes to the instant-command parser (section 9.5). Speech that is neither a command of the conversation nor an instant command is ignored, except during dictation (section 9.7).
```

- [ ] **Step 3: Stop, and speech by channel (section 8.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- "Stop" or "cancel" ends the speech and also cancels a running task. "Kestrel" ends the speech and listens for a new request.
```

with:

```text
- "Stop" or "cancel" ends the speech and also ends a running goal with its report (section 11.10). "Kestrel" ends the speech and listens for a new request.
- Kestrel speaks in a spoken conversation, and for a typed command only while "Speak replies to typed commands" is on (section 11.1). With listening off, it speaks only those typed replies, through the goal service (section 7.2), and loads its voice engine for them alone.
```

- [ ] **Step 4: The listening modes (section 8.5)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- While Kestrel is paused, "Kestrel, wake up" returns it to listening. "Kestrel, <task>" runs the task and then returns to the paused mode.
```

with:

```text
- While Kestrel is paused, "Kestrel, wake up" returns it to listening. "Kestrel, <command>" goes to the conversation, and Kestrel then returns to the paused mode. The follow-up window after a spoken final reply takes speech without the name in the paused mode too (section 8.3). With listening off, the conversation screen and its typed goals still work.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
A task never comes back (section 14.4).
```

with:

```text
A goal never resumes by itself: Kestrel asks "Continue the goal?" (section 14.4).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
(it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends)
```

with:

```text
(it starts to listen, the screen comes back on, or its reply, the wait for a command or a goal ends)
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
it starts again while Kestrel speaks, waits for a task, runs one or does not record,
```

with:

```text
it starts again while Kestrel speaks, waits for a command, runs a goal or does not record,
```

- [ ] **Step 5: Activation (section 8.6)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Starts listening if needed and treats the next utterance as a task: Kestrel says "Yes?" and waits for it |
```

with:

```text
Starts listening if needed and treats the next utterance as a command: Kestrel says "Yes?" and waits for it |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| "Kestrel, wake up" while paused | Resumes listening |
```

with:

```text
| "Kestrel, wake up" while paused | Resumes listening |
| The conversation screen's microphone button | The same as the side key: it starts listening if needed and waits for one command, which runs as a spoken one |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
When Kestrel is off, it asks for the task once its voice session is open,
```

with:

```text
When Kestrel is off, it asks for the command once its voice session is open,
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Kestrel goes on listening, and the notification comes back with the next change of mode.
```

with:

```text
Kestrel goes on listening, and the notification comes back with the next change of mode. The actions of the goal notification (section 15) reach Kestrel in the same way.
```

- [ ] **Step 6: A call during a goal (section 8.7)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- After the call, the previous listening mode returns. A call pauses a running task instead of cancelling it (section 14.4): when the call ends with the phone unlocked, or at the next unlock, Kestrel asks "Continue the task?"; yes resumes the task, which looks at the screen again first, and no, silence, "stop" or a touch cancel it with its report. A task paused for more than 30 minutes ends, and its report comes at the next unlock. A call that becomes active without ringing within 5 seconds of an action of the agent stops the task at once, and with the permission for calls Kestrel ends that call; a call that rang is never ended. A hand-over is not paused: its 3 minutes keep running (design D2 and D13 of sub-project 5).
```

with:

```text
- After the call, the previous listening mode returns. A call pauses a running goal instead of ending it, with listening on or off, because `PhoneSignals` follows the calls while a goal is open (section 14.4). When the call ends with the phone unlocked, or at the next unlock, Kestrel asks "Continue the goal?": by voice when a voice session is open, and always as a card and in the goal notification. Yes resumes the goal, which looks at the screen again first; no, "stop", "cancel" or Cancel end it with its report. Silence and a touch leave it held. A held goal ends after 30 minutes with its report, which comes when the owner is back. A call that becomes active without ringing within 5 seconds of an action of the agent ends the goal at once, with listening on or off, and with the permission for calls Kestrel ends that call; a call that rang is never ended. A hand-over is not paused: its 3 minutes keep running (design D2 and D13 of sub-project 5).
```

- [ ] **Step 7: The feedback bar (section 8.9)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
A thin overlay at the top of the screen shows the listening mode, the last thing that Kestrel heard, the current task step and any question that Kestrel is asking: when Kestrel starts to wait for a task, after "Kestrel" on its own or after an interruption with "Kestrel", it shows "Yes?", also when it does not say it. When Kestrel turns itself off, it shows why in place of "Off" (section 14.6).
```

with:

```text
A thin overlay at the top of the screen shows the listening mode, the last thing that Kestrel heard, a goal's progress and any question that Kestrel is asking: when Kestrel starts to wait for a command, after "Kestrel" on its own or after an interruption with "Kestrel", it shows "Yes?", also when it does not say it. During a goal it shows the step and the checklist, such as "Step 12 · 3 of 7 · Replying to Mila", and "Got it." when steering arrives. With a goal held, it shows briefly "On hold: say 'Kestrel, continue' or open Kestrel."; it names the words, because it takes no touches, and the buttons are on the conversation screen, the card and the goal notification. In the follow-up window it shows "Go on…" with the seconds left (section 8.3). In a typed conversation it also shows Kestrel's words and, for a few seconds, its final answer. When Kestrel turns itself off, it shows why in place of "Off" (section 14.6).
```

- [ ] **Step 8: The question card and the touch option (sections 9.4 and 9.8)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Confirmation card | Section 12.4. Its Confirm and Cancel buttons accept touches. |
```

with:

```text
| Question card | Sections 12.4 and 15. In a spoken conversation it shows a confirmation and "Continue the goal?"; in a typed conversation it shows every question. Its buttons follow the question's kind: Yes and No, Confirm and Cancel, up to 4 choices, Continue and Stop or Cancel, and "Reply…", which opens the conversation screen with the question in focus. A confirmation never has "Reply…". Its buttons accept touches only after a short arming delay, and they ignore a touch that passes through another window over them. While it shows, Kestrel's screen actions act on nothing. |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Voice Access's "Cancel on touch" option is always on in Kestrel, for tasks and for an instant command's question or a call's countdown (section 14.1).
```

with:

```text
Voice Access's "Cancel on touch" option is always on in Kestrel: a touch interrupts a goal, which stays held, and cancels an instant command's question or a call's countdown (section 14.1).
```

- [ ] **Step 9: "Stop" in the grammar, and "conversation" kept for the owner's conversation (sections 8.7, 9.5, 9.6 and 9.7)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
or a call's countdown, or a running task.
```

with:

```text
or a call's countdown, and ends a running goal with its report (section 11.10).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
so that it never transcribes the call's conversation;
```

with:

```text
so that it never transcribes what is said in the call;
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
bare speech is dropped without a word, because it may be conversation, except during dictation.
```

with:

```text
bare speech is dropped without a word, because it may be people talking, except during dictation.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
because it is conversation far more often than an answer to a dialog;
```

with:

```text
because it is chatter far more often than an answer to a dialog;
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
because two conversations of an app may share the position of their message field.
```

with:

```text
because two chats of an app may share the position of their message field.
```

- [ ] **Step 10: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'waits for a task', 'A transcript is a task', 'neither a task nor a command', 'Kestrel, <task>', 'A task never comes back', 'if needed and treats the next utterance as a task', 'asks for the task once', 'Continue the task?', 'the current task step', 'Confirmation card', 'is always on in Kestrel, for tasks', ', or a running task.', 'the call''s conversation', 'because it may be conversation', 'because it is conversation far more often', 'two conversations of an app') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: `Continue the task? : 2` (section 14.4 keeps it until Task 10, and section 16.5's sentence of plan 6B-basefix until Task 12), every other text `: 0`, then H + 0.

- [ ] **Step 11: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 5: the follow-up window, Kestrel's new requests, the microphone button, a call that pauses a goal, the bar's progress, the question card, and stop that ends a goal" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The context, the entry through the conversation and the loop's records

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 10.3, 10.4, 11.1 and 11.2)

**Interfaces:**
- Consumes: D29 to D33 (Task 2); `ConversationEngine`, `GoalRunner`, `ScreenTurns` (Task 4); the follow-up window (Task 5).
- Produces: section 11.1's heading "Entry through the conversation" and its rules (the lifecycle, what a command does, Kestrel's own requests, the replies by channel, the restore), to which the summary, the glossary and sections 8.4, 12.1, 13.5, 14.4 and 15 refer; section 11.2's steering, records and records block, to which sections 11.7, 11.10, 12.3 and 12.6 refer; the context's order (the test-mode note, the records, the memory), which section 12.7 (Task 8) names.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '6'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The context in the requests (sections 10.3 and 10.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and the per-task text follows in a field of its own, `context`:
```

with:

```text
and the per-command text follows in a field of its own, `context` (the test-mode note, the records of the conversation's earlier commands and the command's memory, in this order, so that the part that every command of a conversation shares comes first and the providers' automatic prefix caches can reuse it from one command to the next; section 11.2):
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Its requests count against the owner's ChatGPT plan, not against Kestrel's cost cap.
```

with:

```text
Its requests count against the owner's ChatGPT plan, not against the AI Credits of a goal's limits (section 11.7).
```

- [ ] **Step 3: Entry through the conversation (section 11.1)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
### 11.1 Entry

- When a task arrives, the routine matcher runs first (section 11.9).
```

with:

```text
### 11.1 Entry through the conversation

Every command of the owner's, spoken or typed, goes to the one open conversation, which the `ConversationEngine` of `:core:conversation` keeps (sub-project 7's design, sections 6 and 11).

- **The conversation's lifecycle.** The first command opens a conversation, and it stays open while the owner keeps giving commands. Voice and text feed it, in either order. It ends when the quiet time passes (D29), and also at "New conversation", at "delete history" (section 13.5), when the owner continues another conversation, and when test mode changes (section 12.7). The quiet time runs only while nothing is pending: no goal is open, in any phase; no question waits; Kestrel is not speaking or showing a final reply; the conversation screen's field is empty, or the screen is not in front; and Kestrel is not waiting for a command after "Kestrel" alone, the side key or the microphone button. It starts from zero when each of these ends. The lock, the screen, a call, the listening mode and instant commands do not affect it. When it runs out, Kestrel says nothing: the conversation joins the past conversations (section 13.4), and the conversation screen shows a line such as "Conversation ended at 18:04". The next command opens a new conversation.
- **What a command does.** A command while no goal is open starts a goal (section 11.7), which runs as task runs of the agent loop. A command while the goal acts steers it, and one while the goal asks answers its question (section 11.10). A held goal resumes with "continue", with the Continue buttons or, after a limit or an interruption, with any command, which then also steers it (section 14.4). While a goal is open, "new conversation" and a request to continue a past conversation get "Stop the goal first.". A goal belongs to the command that started it: its steering, its answers and the "continue" inputs add their lines to that command's record and get no record of their own (section 11.2).
- **Kestrel's own requests** need no model: remember, forget and save that as (section 11.9), delete history (section 13.5), "new conversation" (also "start a new conversation") and "continue the conversation about X" (also "continue our conversation about X" and "go back to the conversation about X"). For the last one, Kestrel searches the past conversations on the phone by the topic's words (section 11.4), takes the best match and asks, for example, "Continue the conversation from Thursday, 20 November, about 'Find the restaurant Mila sent me'?". Without a topic, it means the newest past conversation; without a match, Kestrel says "I didn't find a conversation about that.". Yes closes the open conversation into the list and makes the chosen one the open conversation again, with its records, and Kestrel says "We're back in the conversation from Thursday, 20 November."; no or no answer changes nothing. "Kestrel, continue" alone keeps its meaning for goals. A remember, forget or save-that-as request gets a record of its own; steering, answers, "continue", "new conversation" and a request to continue a past conversation get none.
- **Replies follow the channel** of the owner's latest input (D30). A spoken input gets Kestrel's words, questions and final answer spoken on the assistant stream and shown on the bar, with a confirmation also on the question card, and its final answer opens the follow-up window (section 8.3). A typed input gets them in the conversation screen's transcript and on the bar, and every question as the card, with no speech and no follow-up window. With "Speak replies to typed commands" on, Kestrel speaks them too, and a spoken answer to a card counts, but the window still does not open. A typed answer or steering message in a spoken goal switches the replies to written from then on, and a spoken input switches them back. The transcript always shows everything. Silent mode and the volume behave as section 8.4 says.
- **Kestrel's process.** The conversation, its records and the goal's checklist are stored, encrypted, as they change (section 13.4). After a restart, Kestrel reads them before it takes any input: a conversation without a goal is still open while its quiet time has not passed, and an open goal is held as interrupted and asks "Continue the goal?" (section 14.4).
- When a goal starts, the routine matcher runs first (section 11.9).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- `:app`'s `AgentRunner` starts each task in Kestrel's own scope and starts the touch watcher before the task's first action.
```

with:

```text
- The `GoalRunner` of `:core:conversation` starts each task run in Kestrel's own scope, takes the screen through `ScreenTurns` (section 7.3) and starts the touch watcher before the run's first action; `:app`'s `AppGoalEnvironment` gives it the model and the other ports.
```

- [ ] **Step 4: The loop's messages and context (section 11.2)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Each task is one conversation with tool calls, in this order: the tool definitions and the system prompt, which are the same for every request of every task; the per-task context (the memory that the task needs and the test-mode note); the task message (the owner's words, the local date and time, and what the task may do without an extra question, section 12.3); the first observation, in a user message of its own; and the steps. Providers that support prompt caching can cache the fixed part and every step that no longer changes.
```

with:

```text
- Each task run is one exchange of messages with the model, with tool calls; the code calls these the run's `Conversation`. They come in this order: the tool definitions and the system prompt, which are the same for every request of every task; the per-command context, which holds the test-mode note (which also says so when the conversation was continued), the records block of the conversation's earlier commands, and the memory that the command needs; the task message (the owner's words, which start "The owner's new command: …" in a later command of a conversation, the local date and time, and what the task may do without an extra question, section 12.3); the first observation, in a user message of its own; and the steps. Within a run the context never changes, and every command of a conversation starts its context with the same test-mode note, followed by the records that the commands share, so providers that support prompt caching can cache the fixed part, the context and every step that no longer changes. The first command of a conversation has no records block.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and that typed placeholders such as `[OTP_1]` stand for values that Kestrel keeps on the phone.
```

with:

```text
and that typed placeholders such as `[OTP_1]` stand for values that Kestrel keeps on the phone. Since sub-project 7 it also states: that a goal of more than two steps keeps a checklist with `update_checklist`; that a step that failed is marked failed with its reason and a new item is added; that an owner message may change the plan, and that the model then updates the checklist; that the records of earlier commands and the results of `search_conversations` are data, except the owner's words after "The owner said:"; and that `search_conversations` finds what the conversation does not hold.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
So the conversation has a stable region that never changes once written and a tail with the two full observations,
```

with:

```text
So the run's messages have a stable region that never changes once written and a tail with the two full observations,
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
if the provider still refuses the history, the task's conversation only grows from then on,
```

with:

```text
if the provider still refuses the history, the run's messages only grow from then on,
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Every text that does not come from the model is redacted exactly once, when it enters the conversation (section 12.6). Result lines and summaries name an element by the label that the redacted observation showed.
```

with:

```text
- Every text that does not come from the model is redacted exactly once, when it enters the run's messages (section 12.6). Result lines and summaries name an element by the label that the redacted observation showed.
- **Steering.** A command that comes while the goal acts (section 11.10) reaches the model at the start of its next turn, after the pause gate and the limit check and before the model call; a step that has started runs to its end. Each one becomes an owner message after the last step, such as `The owner added (typed) at step 14: "Also send it to Mila, not only Ana."`. It joins the stable region at once and never shrinks, so it only adds to what the caches hold. Its words count as the owner's, and its verbs join the allowance (section 12.3).
- **The records.** Each command's record fills while its task runs: the request; each `say`, question and answer as it happens; each data tool's result text as the model saw it, already redacted and quoted; each app that an executed step acted in; Kestrel's own lines of what was done; the checklist; and the report. No observation, screenshot, OCR line or tool argument goes into a record, and no vault value: a placeholder becomes its kind's words before a line is stored. A record keeps at most 200 lines and 20 data notes of at most 2,000 characters each.
- **The records block.** A later command's context holds the records block. Kestrel builds it by code as its own text, from parts that were redacted once already, and redacts nothing twice. The owner's words show as "The owner said: …", the data notes keep their quotes and escapes, and Kestrel's lines (the ledger, the final sentence and the checklist's texts) are labelled as Kestrel's. Its budget is 2,000 tokens, counted as an observation's tokens are counted (section 11.3), and `min(2,000, window ÷ 8)` for a local Ollama model. The newest earlier record shows its request and its answer, at most 400 characters each; its end and at most 5 apps; its checklist as the open items, with one line that counts the closed ones, such as "12 done, 1 skipped, 0 failed"; and at most 5 data notes of 600 characters, 10 lines of 300 characters and 5 ledger lines of 200 characters, the newest ones. Older records are condensed once, by code: to the request, the answer, the end, the apps, the checklist's count and open items, and one line per data note. A condensed record stays condensed, also after the conversation is continued. The block never passes its budget: Kestrel adds the newest record's request, answer, end, apps and counts first, then its open items, then its data notes, lines and ledger lines, newest first, and then the older records, condensed, newest first and each whole. What does not fit is left out with one line that counts it; for left-out commands, the line also says that `search_conversations` finds them (sub-project 7's design, section 8).
```

- [ ] **Step 5: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'per-task', 'cost cap', 'When a task arrives', 'AgentRunner', 'Each task is one conversation with tool calls', 'So the conversation has a stable region', "the task's conversation only grows", 'when it enters the conversation (section 12.6)') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
"heading : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern '### 11.1 Entry through the conversation').Count)"
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: `cost cap : 1` (section 11.7 keeps it until Task 7), every other text `: 0`, `heading : 1`, then H + 0.

- [ ] **Step 6: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 6: the context's order, entry through the conversation with its lifecycle, own requests and channels, and steering, records and the records block in the loop" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The tools, the limits, the completion check, memory and steering

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 11.4, 11.5, 11.7, 11.8, 11.9 and 11.10)

**Interfaces:**
- Consumes: D24, D31 to D33 (Task 2); section 11.1's channels and section 11.2's records and steering (Task 6).
- Produces: the tools `update_checklist` and `search_conversations` (section 11.4), to which sections 5.3, 11.1, 11.2, 13.4 and 15 refer; the rounds, "Continue?", the checklist and resume (section 11.7), to which sections 1, 3, 12.6, 14.1 and 14.4 refer; section 11.10's heading "What Kestrel listens to while a goal runs" and its rules, to which sections 3, 7.3, 8.4 and 11.2 refer.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '7'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The two new tools (section 11.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Dialogue | `ask_user` (a spoken question and a spoken answer), `say`, `finish`, `fail` | The owner's answer or "no answer"; `finish` ends the task, or fails with what was missing |
| Memory | `remember` and `forget`, which both need the owner's confirmation | Whether the owner agreed |
```

with:

```text
| Dialogue | `ask_user` (a question and its answer over the conversation's channel, section 11.1), `say`, `finish`, `fail` | The owner's answer or "no answer"; `finish` ends the task, or fails with what was missing |
| Memory | `remember` and `forget`, which both need the owner's confirmation | Whether the owner agreed |
| Goal | `update_checklist`, the second-to-last tool of the list (section 11.7) | "Result: checklist saved, 3 of 7 done (c1 … c7)." or the reason why Kestrel refused the checklist |
| Past conversations | `search_conversations`, the last tool of the list | At most 5 earlier commands that match, newest first, as quoted data |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- `look_closer` runs the three steps of section 11.6, one per call on the same screen.
```

with:

```text
- `look_closer` runs the three steps of section 11.6, one per call on the same screen.
- `update_checklist` takes 1 to 20 items each time, among them every item that is still todo or doing, each with its id from the last result (left out for a new item), a text of at most 120 characters and a state, which is todo, doing, done, skipped or failed. A done, skipped or failed item may be left out and stays as it is, and a new item goes at the end, so a long goal's checklist has no limit on its length. Kestrel's code checks it. At most one item is doing. A done item names the step that did it: a step of this round whose action ran and did not fail, a data step with a result, or a step that the owner finished after a hand-over; an item that was done in the checklist that the round started with may stay done without one. An item keeps its id, and its text may not change once it is done. A skipped or failed item gives its reason, of at most 120 characters. A placeholder becomes its kind's words, as in `say`. An invalid call returns its reason and counts as an invalid tool call (section 11.7). A short task may finish without a checklist.
- `search_conversations` takes a query (a name, an app or a topic) and an optional first and last day. It searches, on the phone, the records of the past conversations within their retention and the records of the open conversation that its records block left out, never observations or screenshots. The query and each record are folded as the matcher folds words (section 9.6), so letter case, diacritics and the two Serbian scripts do not count, and names match with the matcher's near misses. A record must hold at least half of the query's words; more words rank higher, and then the newer record. The result, at most 1,500 tokens, gives for each match its day and time, the command's number, what the owner said, what Kestrel answered, the apps and the data notes, quoted and redacted as stored. Only the matches go to the model, only in the task run that called the tool, and the result also becomes a data note of the command that asked (D32).
```

- [ ] **Step 3: A permission's explanation (section 11.5)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Kestrel requests each permission the first time it needs it and explains why by voice.
```

with:

```text
- Kestrel requests each permission the first time it needs it and explains why over the conversation's channel (section 11.1).
```

- [ ] **Step 4: Rounds, "Continue?", the checklist and resume (section 11.7)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Invalid tool calls (a reply without a usable call, a cut reply or a failed `finish`) are counted apart.
```

with:

```text
Invalid tool calls (a reply without a usable call, a cut reply or a failed `finish`) are counted apart. `search_conversations` counts as a data tool. `update_checklist` neither makes progress nor counts as a step without progress, so it can neither hide being stuck nor cause it; two checklist updates in a row, with no other tool between them, count as an invalid tool call.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
3 invalid tool calls on the versatile model end the task.
```

with:

```text
3 invalid tool calls on the versatile model end the task. A goal without progress ends in these ways as stuck, whatever its limits (D24).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Each task has a cost cap of 10 credits ($0.10). When a task reaches it, Kestrel asks "This task has used N credits. Continue?" with the real total; yes allows 10 more. A profile whose spending counts no credits, such as a plan sign-in, has no cap.
- A task can take at most 30 model replies, invalid ones included, and 5 minutes of Kestrel's own working time. The time spent waiting for the owner (questions, confirmations, hand-overs and permission dialogs) and a paused task do not count. Each LLM call can take at most 30 s.
```

with:

```text
- **Goals and rounds.** A command that starts a goal runs it as a task run, whose first round starts at once. Each round may take the limits that the owner sets on the Conversations page (section 15): by default 500 model replies, invalid ones included, 60 minutes of Kestrel's own working time and 1,000 AI Credits (D24). The time spent waiting for the owner (questions, confirmations, hand-overs and permission dialogs) and a paused or held goal do not count. Each LLM call can take at most 30 s. A profile whose spending counts no credits, such as a plan sign-in, has no credit limit.
- **"Continue?".** At the first limit that a round reaches, Kestrel's code, never the model, builds one question from the checklist and the ledger, such as "I've worked for 60 minutes on this. Done: 4 of 7 steps. Left: reply to Mila, archive the thread. Continue?", or "I've used 500 model replies …" or "This has used 1,000 AI Credits …" with the real total. Yes gives the same again from the current totals, in the same task run, so the model keeps its messages and the prompt caches. No ends the goal as stopped at a limit, with a report from the checklist of what is done and what is left. No answer (the voice answer wait, or the card's 2 minutes in a typed conversation) ends the round as paused at a limit; the goal is held (section 14.4), and "continue", the Continue buttons or any command resume it. There is no other question about cost.
- **The checklist.** For a goal of more than two steps, the model keeps a checklist with `update_checklist` (section 11.4). The goal stores it; the conversation screen, the bar and the goal notification show it; and the command's record keeps it. Recovery is the model's work, helped by the checklist: a step that failed is marked failed with its reason, a new item is added, and the goal goes on.
- **Resume.** A goal that resumes after a pause at a limit without an answer, after an interruption or after a restart of Kestrel starts a new task run. Its first observation is fresh, and its task message starts with Kestrel's own lines: the goal's first command, what interrupted it, its checklist, what Kestrel did so far, and "Look at the screen afresh; it may have changed.". A steering command that resumed the goal follows as an owner message. The old model messages are neither stored nor used again. Kestrel's code repeats no earlier step: the checklist and the ledger tell the model what is done, and a share that was confirmed but not seen sent is listed as tried. Every confirmation is asked again (D33).
```

- [ ] **Step 5: The checklist in the completion check (section 11.8)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and it stores no route notes and offers no routine.
```

with:

```text
and it stores no route notes and offers no routine. `finish` also fails while an item of the goal's checklist is todo or doing, with a result that names them, such as "finish failed: the checklist has open items c4 and c5; mark them done, skipped or failed".
```

- [ ] **Step 6: Memory and routines by either channel (section 11.9)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
The owner adds them in settings, or by voice ("Kestrel, remember that home is …") after a spoken confirmation.
```

with:

```text
The owner adds them in settings, or with a command, spoken or typed ("Kestrel, remember that home is …"), after his confirmation.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
The owner says "Kestrel, save that as <name>" within 10 minutes after a task.
```

with:

```text
The owner says or types "Kestrel, save that as <name>" within 10 minutes after a task.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and a touch cancels a replay.
```

with:

```text
and a touch interrupts a replay as it interrupts a goal (section 14.1).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
it asks after it has spoken the task's result.
```

with:

```text
it asks after it has given the task's result.
```

- [ ] **Step 7: What Kestrel listens to while a goal runs (section 11.10)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
### 11.10 What Kestrel listens to while a task runs

- While the agent or a routine is acting, or while Kestrel waits for a confirmation or for an answer to its question, Kestrel accepts only "stop", "cancel" and the answer it asked for. "Stop" and "cancel" also count with a few filler words, as in "Stop it" or "Okay, cancel that". Kestrel ignores all other speech, so that a conversation or a TV cannot interfere. To start a different task, the owner first says "stop". Any request after "stop", as in "Stop. Kestrel, open Viber." or "Stop. Kestrel, forget the gate code.", cancels the task first, also while it is paused or handed over, and then counts as it would without a task.
- During a hand-over (section 12.4), Kestrel is not acting. All instant commands work as usual, so that the owner can do the step by voice, and "Kestrel, continue" resumes the task.
```

with:

```text
### 11.10 What Kestrel listens to while a goal runs

- While a goal acts, Kestrel takes a spoken command that starts with "Kestrel" as steering (D31): an addition or a correction that the model gets at its next step (section 11.2), and the bar shows "Got it.". Typed text on the conversation screen is always taken as steering. "Stop" and "cancel", spoken with or without the name or typed, and the Stop buttons of the conversation screen and the goal notification, end the goal at once with its report. "Stop" and "cancel" also count with a few filler words, as in "Stop it" or "Okay, cancel that". Kestrel ignores all other speech, so that people talking nearby or a TV cannot interfere, and the follow-up window does not open during a goal (section 8.3). A request after "stop", as in "Stop. Kestrel, open Viber." or "Stop. Kestrel, forget the gate code.", ends the goal first, also while it is held or handed over, and then counts as it would without a goal.
- While Kestrel waits for an answer to its question or for a confirmation, the next typed text, or speech within the answer wait (15 s to start an answer and 35 s once speaking, as in section 7.3), answers it; it is not steering.
- During a hand-over (section 12.4), Kestrel is not acting. All instant commands work as usual, so that the owner can do the step by voice, and "Kestrel, continue" or the Continue button ends the hand-over and resumes the goal. A typed command gets "Finish the step first, then tap Continue, or Cancel.".
- While a goal is held (section 14.4), "continue" and the Continue buttons resume it. After a limit or an interruption, any command resumes it too, as steering. After a pause for a lock or a call, a command gets "Answer 'Continue the goal?' first, or say cancel.".
- While a goal is open, "new conversation" and a request to continue a past conversation get "Stop the goal first.", and Kestrel ignores "delete history" (section 13.5).
```

- [ ] **Step 8: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'cost cap', 'a spoken question and a spoken answer', 'explains why by voice', '30 model replies', 'or by voice ("Kestrel, remember', 'and a touch cancels a replay', 'after it has spoken the task''s result', '### 11.10 What Kestrel listens to while a task runs', 'so that a conversation or a TV') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in '| Goal | `update_checklist`', '| Past conversations | `search_conversations`', '### 11.10 What Kestrel listens to while a goal runs') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the first nine texts `: 0`, each of the next three `: 1`, then H + 0.

- [ ] **Step 9: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 7: update_checklist and search_conversations, rounds with Continue?, the checklist's finish rule, resume, and steering while a goal runs" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Safety

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 12.1, 12.3, 12.4, 12.5, 12.6 and 12.7)

**Interfaces:**
- Consumes: D29, D30 and D33 (Task 2); the question card (Task 5); section 11.1's channels and section 11.2's records (Task 6); `update_checklist`, `search_conversations` and section 11.7's resume (Task 7).
- Produces: the typed-input boundary (section 12.1), to which sections 8.3, 5.2 and 15 refer; the carry across a conversation (section 12.3), to which section 11.2 refers; Kestrel's own screen during a goal (section 12.5), to which sections 14.4 and 15 refer; test mode per conversation (section 12.7), to which section 11.1 refers.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '8'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: Speech from another app, and the typed-input boundary (section 12.1)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Any app in front can start Kestrel's assistant and play speech, so it can speak a task and even say "confirm". What limits it: the phone must be unlocked with the app in front; the task's allowance (section 12.3) asks again for every category that the spoken task did not name; test mode blocks every send that does not go to the owner's own chats; a confirmation names the real recipient and text from the screen, and the card shows them; and while another app plays media or game audio, a confirmation of sending or deleting counts only by the card's button, or by voice after Kestrel has paused that audio.
```

with:

```text
Any app in front can start Kestrel's assistant and play speech, so it can speak a command and even say "confirm", and the follow-up window adds up to 8 s after each spoken final reply in which speech counts without the name (D29). What limits it: the phone must be unlocked with the app in front; the allowance (section 12.3) asks again for every category that neither the command nor an earlier command of the conversation named; test mode blocks every send that does not go to the owner's own chats; a confirmation names the real recipient and text from the screen, and the card shows them; and while another app plays media or game audio, a confirmation of sending or deleting counts only by the card's button, or by voice after Kestrel has paused that audio.

Typed input counts as the owner's only when he typed it with a keyboard on Kestrel's own conversation screen (D30). The screen's root view and its buttons, and the question card's buttons, drop a touch that passes through another app's window over them (`filterTouchesWhenObscured`). No exported intent carries a command: the launcher intent carries nothing; "Reply…" opens the screen with an explicit intent that names only the question's number; the goal notification's actions carry only an action's name; the screen ignores any text or data in its intent; and its only intent filter is the launcher's, which a unit test over the merged manifest checks. No notification takes an inline reply. From Android 14, the screen's window is marked as sensitive for accessibility, so that only accessibility tools may act on it, and Kestrel's own agent never acts on it, because Kestrel's app is agent-free (section 12.5). Kestrel reads typed text without the name for its own requests only, and never as an instant command; everything else is a command of the conversation.
```

- [ ] **Step 3: The owner's words and the allowance across a conversation (section 12.3)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
An action whose category the task did not ask for needs a confirmation in every mode, also in full-auto, and its question says so: "You didn't ask me to send anything. …".
```

with:

```text
An action whose category the task did not ask for needs a confirmation in every mode, also in full-auto, and its question says so: "You didn't ask me to send anything. …".

Across a conversation (D33), the allowance of a command is its own categories joined with those that the conversation's earlier commands and their steering asked for, and a steering message adds its own verbs, so "And to Mila too" does not get "You didn't ask me to send anything.". Standard mode still confirms every send, share and delete, and every call is still handed over in every mode. The owner's words of every earlier command, answer and steering message of the conversation, spoken or typed on Kestrel's screen, count as his own text: typing them is typing the request, `add_contact` counts them as his, and the link check of section 12.1 takes them as his words for a search link. Data stays data: the data notes of earlier records, the results of `search_conversations`, and Kestrel's and the model's lines (the ledger, the `say` lines and the checklist's texts) never count as the owner's words, so typing them is typing screen text. Confirmations and hand-overs belong to one task run, so a new command and a resumed goal ask again. Screenshot approvals stay per app (section 11.6). A continued conversation is the same conversation: the owner's words and the allowance of its records carry into its new commands.
```

- [ ] **Step 4: Confirmations and hand-overs by channel (section 12.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Kestrel speaks the question and shows it on a confirmation card. "Confirm", "yes", "send it" or the card's Confirm button go ahead. "Cancel", "no", the Cancel button or 15 s of silence cancel. A touch anywhere else on the screen also cancels (section 14.1).
```

with:

```text
- In a spoken conversation, Kestrel speaks the question and shows it on the question card. In a typed one, the card alone shows it, and Kestrel speaks it too while "Speak replies to typed commands" is on. "Confirm", "yes", "send it" or the card's Confirm button go ahead. "Cancel", "no", the Cancel button or silence cancel: 15 s of silence in a spoken conversation, and 30 s for the card of a typed one (D30). In a typed conversation, a question card waits 2 minutes, and the screen waits 5 minutes after a tap on "Reply…"; when such a wait ends, the question has no answer, and the card of "Continue the goal?" closes while the goal stays held (sub-project 7's design, section 10.4). A confirmation's card never has "Reply…", so a confirmation is always a button or a spoken word. A touch anywhere else on the screen interrupts the goal, and the confirmation counts as not given (section 14.1). A confirmation never carries to a later command or to a resumed goal (D33).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
The owner can act by touch or with instant commands, and touches do not cancel the task during a hand-over. "Kestrel, continue" resumes the task, "cancel" ends it, and the task also ends after 3 minutes without either.
```

with:

```text
The owner can act by touch or with instant commands, and touches do not interrupt the goal during a hand-over. "Kestrel, continue" or the Continue button of the card, the conversation screen or the goal notification resumes the goal, "cancel" or Cancel ends it, and the goal also ends after 3 minutes without either, in a spoken and in a typed conversation.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
so that his touches on that page never cancel the task.
```

with:

```text
so that his touches on that page never interrupt the goal.
```

- [ ] **Step 5: Kestrel's own screen (section 12.5)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Kestrel's own app is always agent-free and is not on the editable list, so that the agent cannot change Kestrel's settings, such as test mode or this list.
```

with:

```text
- Kestrel's own app is always agent-free and is not on the editable list, so that the agent cannot change Kestrel's settings, such as test mode or this list, and never acts on the conversation screen. `open_app` and the link check refuse it, and observations leave its windows out. When the owner sends a typed command or answers a question on the conversation screen, Kestrel moves its screen to the back, so that the app that he was in, or the home screen, comes back. It then waits up to 3 s until no window of Kestrel's but its overlays is on the screen. When Kestrel's own screen comes to the front during a goal, the goal waits instead of ending; when the screen has not left after 3 s, the goal is held as interrupted, with "Leave Kestrel's screen, then tap Continue or say 'Kestrel, continue'." (section 14.4). The other agent-free apps still end the task.
```

- [ ] **Step 6: The vault per task run (section 12.6)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
The real values stay in an in-memory vault for the duration of the task, and Kestrel clears the vault when the task ends.
```

with:

```text
The real values stay in an in-memory vault for the duration of the task run, and Kestrel clears the vault when the run ends, so a later command and a resumed goal start with an empty vault; a yes to "Continue?" keeps the run and its vault (section 11.7). A command's record holds a redacted value only as its kind's words ("a one-time code"), never as a placeholder, so no later run can type it back (section 11.2).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
is redacted once, when it enters the conversation, with the texts beside it
```

with:

```text
is redacted once, when it enters the run's messages, with the texts beside it
```

- [ ] **Step 7: Test mode for the whole conversation (section 12.7)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Test mode is a development setting, and it is on during all testing (D25).
```

with:

```text
- Test mode is a development setting, and it is on during all testing (D25).
- A conversation reads test mode when it opens and keeps that value for all its commands, whatever the setting says later (D33); its context starts with the test-mode note (section 11.2). A continued conversation takes the value of the moment when it is continued, and its note says so: "This conversation was continued on <date>; test mode is <on | off> now.". A change of test mode on the Safety page ends an open goal with its report and closes the open conversation, so that the next command runs under the new value. The agent cannot change the setting, because Kestrel's own app is agent-free.
```

- [ ] **Step 8: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'so it can speak a task', 'shows it on a confirmation card', 'touches do not cancel the task', 'never cancel the task', 'for the duration of the task, and', 'enters the conversation') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in 'only when he typed it with a keyboard', 'Across a conversation (D33)', 'A conversation reads test mode when it opens') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the first six texts `: 0`, each of the next three `: 1`, then H + 0.

- [ ] **Step 9: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 8: the typed-input boundary, the owner's words and the allowance across a conversation, the card's waits, Kestrel's own screen, the vault per run and test mode per conversation" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: Storage, the logs and the permissions

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 13.4, 13.5 and 13.6)

**Interfaces:**
- Consumes: D32 (Task 2); `GoalService` (Task 4); `search_conversations` (Task 7).
- Produces: the archive of past conversations and its key `kestrel.conversations` (section 13.4), to which sections 7.4, 11.1 and 14.4 refer; the new "delete history" (section 13.5), to which sections 11.1, 11.10 and 15 and Appendix A refer; the goal service's type and `FOREGROUND_SERVICE_SPECIAL_USE` (section 13.6), to which sections 7.2 and 14.6 refer.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '9'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The archive of past conversations (section 13.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- The model files live in the app's external files folder (section 8.10). They contain no personal data.
```

with:

```text
- Past conversations (D32) are rows of their own in the Room database, one row per conversation, from the database's version 3. A row's payload is the conversation as JSON, encrypted with AES-GCM under a Keystore key of its own, `kestrel.conversations`, with the row's id as associated data, so that a payload cannot be moved to another row. Only the conversation's random 128-bit id, its open and last-change times, whether it is open, and its number of commands are plain. A payload that fails its check is deleted and reported to the activity log by its kind only. The payload carries a version number, so that a later version of Kestrel can read it.
- The open conversation is stored at every change of its state, its records, its checklist and its goal's phase, so that a command's record grows while its goal works and a restart finds it (section 14.4). A closed conversation is kept for 30 days after its last change by default, or 1, 7, 90 or 365 days as the owner sets on the Conversations page; the open one is never purged. Kestrel purges at its start and once a day, also rows dated more than a day ahead, as it does with the logs, and a shorter retention purges at once. Continuing a conversation counts as a change, so the conversation is kept longer.
- The search of past conversations (section 11.4) decrypts each row once per process and keeps an index of folded words per record, in memory only, like the vault; "delete history" drops it.
- The model files live in the app's external files folder (section 8.10). They contain no personal data.
```

- [ ] **Step 3: The logs and "delete history" (section 13.5)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Activity log | The spoken command,
```

with:

```text
| Activity log | The owner's command, spoken or typed,
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Both logs are encrypted and can be viewed in settings. A button in settings wipes both after a confirmation. "Kestrel, delete history" (also "delete my history" and "clear history") asks "Delete the activity log and the network log? Say yes or no." and wipes both only after "yes"; Kestrel then says "I deleted the history." or "I couldn't delete the history." It needs no model, and while a task exists Kestrel ignores it.
```

with:

```text
- Both logs are encrypted and can be viewed in settings. The button "Delete history" in settings, "Kestrel, delete history" (also "delete my history" and "clear history") and "delete history" typed on the conversation screen ask "Delete the activity log, the network log and past conversations?", spoken with "Say yes or no." or as a card with Yes and No. Only after "yes" do they wipe both logs, every conversation row including the open one, the search's index and the key `kestrel.conversations`, so that no copy of the database file can be read afterwards; the next write makes a new key. The open conversation closes. Kestrel then says "I deleted the history." or "I couldn't delete the history." It needs no model, and while a goal is open Kestrel ignores it.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
When an export fails, Kestrel empties the file, so that no cut-off JSON stays in it.
```

with:

```text
When an export fails, Kestrel empties the file, so that no cut-off JSON stays in it. Past conversations are never exported.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Kestrel writes no audio, transcript, reply, boost word,
```

with:

```text
- Kestrel writes no audio, transcript, typed text, record of a conversation, reply, boost word,
```

- [ ] **Step 4: The goal service's permission and type (section 13.6)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
`FOREGROUND_SERVICE_DATA_SYNC` (for the sign-in service, section 10.4),
```

with:

```text
`FOREGROUND_SERVICE_DATA_SYNC` (for the sign-in service, section 10.4), `FOREGROUND_SERVICE_SPECIAL_USE` (for the goal service, section 7.2),
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Kestrel never requests `SEND_SMS`, `CALL_PHONE`,
```

with:

```text
- The goal service (section 7.2) is a foreground service of the type `specialUse` from Android 14 on, with the property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, which names its purpose: an on-device agent that carries out the owner's request through the accessibility service while the owner watches. No other type fits: `microphone` is for recording, `dataSync` has another purpose and a daily limit from Android 15, `shortService` lasts about 3 minutes, and the other types describe other work or need permissions that Kestrel must not ask for. Android 13 does not check types, so the service starts there without one. While it speaks a typed command's reply with listening off, it adds `mediaPlayback`, because Android 17 lets an app play audio in the background only from such a service (B5), and it drops the type after the speech. When Android refuses to start it, the goal is held as interrupted (section 14.6; sub-project 7's design, section 10.6).
- Kestrel never requests `SEND_SMS`, `CALL_PHONE`,
```

- [ ] **Step 5: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'Delete the activity log and the network log?', 'while a task exists Kestrel ignores it', '| Activity log | The spoken command') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in '`kestrel.conversations`, with the row''s id as associated data', '`FOREGROUND_SERVICE_SPECIAL_USE` (for the goal service', 'Past conversations are never exported.') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the first three texts `: 0`, each of the next three `: 1`, then H + 0.

- [ ] **Step 6: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 9: the encrypted archive of past conversations with its own key, delete history that covers conversations, and the goal service's specialUse type" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: Error handling and resilience

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 14.1, 14.4, 14.5 and 14.6)

**Interfaces:**
- Consumes: D27, D31 and D33 (Task 2); `GoalService` (Task 4); the question card and section 8.7's call rules (Task 5); section 11.7's rounds and resume (Task 7); section 12.5's wait for Kestrel's own screen (Task 8); the goal service's type (Task 9).
- Produces: section 14.1's heading "A touch interrupts or cancels (D27)" and the interruption's rules, to which sections 9.8, 11.9 and 12.4 and Appendix A refer; section 14.4's held goals and "Continue the goal?" after a restart, to which sections 3, 8.5, 8.7, 11.1, 11.10, 12.5 and 13.4 refer; the row on a refused start of the goal service, to which section 13.6 refers.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '10'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: A touch interrupts a goal (section 14.1)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
### 14.1 Cancel on touch (D27)

- Any touch on the screen cancels a running task, a routine replay, a pending confirmation or an open question from the agent. Kestrel stops at once, removes its task overlays, says "Cancelled", and reports what it had already done (section 14.5). This way, Kestrel never races with the owner, and changes that it did not make never confuse it.
```

with:

```text
### 14.1 A touch interrupts or cancels (D27)

- Any touch on the screen interrupts a goal that acts or asks, a routine replay included. Kestrel stops at once and removes its task overlays; an open question is withdrawn, and an open confirmation counts as not given. Over the conversation's channel, Kestrel reports what it had already done and how to go on, such as "Stopped. I opened Viber and typed the message. Say 'Kestrel, continue' or tap Continue to go on, or cancel." (section 14.5). The goal stays open and held for 30 minutes (section 14.4), with Continue and Cancel on the conversation screen and in the goal notification, and the bar names the words. Continue resumes the goal in a new round with a fresh observation, and the safety check asks every confirmation again. This way, Kestrel never races with the owner, and changes that it did not make never confuse it.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- Touches on Kestrel's own confirmation card act on the card. During a hand-over, Kestrel is not acting, so touches are expected and do not cancel the task (section 12.4).
```

with:

```text
- Touches on Kestrel's own question card act on the card. During a hand-over, Kestrel is not acting, so touches are expected and do not interrupt the goal (section 12.4). So are the owner's touches on the conversation screen, from a tap on the card's "Reply…" until his answer is sent and Kestrel's screen has left. A touch while a goal is held never ends it and never answers "Continue the goal?"; a card that asks it closes at the touch, and the conversation screen and the goal notification keep the question.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Starting the watcher, showing a confirmation card and asking a question forget every credit
```

with:

```text
Starting the watcher, showing a question card and asking a question forget every credit
```

- [ ] **Step 3: Interruptions and held goals (section 14.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- **"Stop" or "cancel"** cancels the running task immediately and removes the overlays.
- **An incoming call or the screen locking** pauses the task and keeps its state in memory. Afterwards Kestrel asks "Continue the task?". A message that was composed but not sent stays unsent.
- **A routine step that does not match** hands over to the agent from that step (section 11.9).
- **Tasks are never resumed after the app's process restarts,** because a stale task could do something that the owner no longer wants.
```

with:

```text
- **"Stop" or "cancel"** ends the running goal immediately with its report and removes the overlays.
- **An incoming call or the screen locking** pauses the goal, with listening on or off, and keeps its state in memory (section 8.7). When the owner is back, after the unlock or at the end of the call with the phone unlocked, Kestrel asks "Continue the goal?": by voice when a voice session is open, and always as a card and in the goal notification. Yes resumes the goal in the same task run, with a fresh observation. A message that was composed but not sent stays unsent.
- **A touch** interrupts the goal (section 14.1), and **a limit of a round** that gets no answer pauses it (section 11.7).
- **Held goals.** A goal that is paused by a lock or a call, paused at a limit, or interrupted is held for 30 minutes, and the quiet time waits meanwhile (D31). Continue resumes it; "stop", "cancel" or Cancel end it with its report; after 30 minutes it ends with its partial report. A report that would come while the screen is off or a call lasts is kept until the owner is back.
- **A routine step that does not match** hands over to the agent from that step (section 11.9).
- **The end of Kestrel's process.** The conversation, its records and the goal's checklist are stored as they change (section 13.4). At the next start, a conversation without a goal is still open when its quiet time has not passed since its stored start, and it is closed otherwise. An open goal, in any phase, becomes interrupted and is held for 30 minutes from its last stored change. Kestrel never resumes it by itself, because a stale goal could do something that the owner no longer wants: it asks "Continue the goal?", by voice once a voice session is open and the owner is back, and always as a card and in the goal notification. Yes starts a new task run from the checklist with a fresh observation (section 11.7), and every confirmation is asked again; no, Cancel or the end of the hold end the goal with its partial report. The stored times are wall-clock times, so a change of the clock can keep a conversation open or close it wrongly after a restart, as for the listening mode (section 8.5).
```

- [ ] **Step 4: A goal's partial report (section 14.5)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
"I opened Ana's chat and typed the message, but I didn't send it because you cancelled."
```

with:

```text
"I opened Ana's chat and typed the message, but I didn't send it because you cancelled." A goal's report also says, from its checklist, which items are done and which are left.
```

- [ ] **Step 5: The launcher, and a refused start of the goal service (section 14.6)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
tap its listening notification or to open Kestrel from the launcher |
```

with:

```text
tap its listening notification or to open Kestrel from the launcher and tap the gear |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
which opens them, or to open Kestrel from the launcher |
```

with:

```text
which opens them, or to open Kestrel from the launcher and tap the gear |
| Android refuses to start the goal service (section 13.6) | Kestrel says "Android didn't let me start working in the background. Open Kestrel and tap Continue." and holds the goal as interrupted (section 14.4) |
```

- [ ] **Step 6: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'Cancel on touch (D27)', 'cancels a running task', 'confirmation card', 'Continue the task?', 'Tasks are never resumed') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in '### 14.1 A touch interrupts or cancels (D27)', '**The end of Kestrel''s process.**', '| Android refuses to start the goal service') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: `cancels a running task : 1` (Appendix A keeps it until Task 13), `Continue the task? : 1` (section 16.5's sentence of plan 6B-basefix, until Task 12), every other of the first five texts `: 0`, each of the next three `: 1`, then H + 0.

- [ ] **Step 7: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 10: a touch interrupts a goal, held goals, Continue the goal? after a restart, and a refused start of the goal service" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 11: The conversation screen, past conversations and the Conversations page

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 15)

**Interfaces:**
- Consumes: D29 to D32 (Task 2); `ConversationActivity`, `GoalService`, `MainActivity` (Task 4); section 11.1's continuing of a past conversation (Task 6); section 11.4's matching and section 11.7's limits (Task 7); section 12.5's wait for Kestrel's own screen (Task 8); section 13.4's retention and section 13.5's "delete history" (Task 9).
- Produces: the conversation screen, the past conversations, the goal notification and the Conversations page, to which sections 1, 3, 7.2, 8.3, 8.6, 9.4, 11.7 and 13.4 refer.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '11'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The launcher and the list of pages**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
The settings app has these screens: the setup checklist; listening and activation; voice and speech; instant commands (section 9.8);
```

with:

```text
Kestrel's launcher icon opens the conversation screen, and the settings app opens from its gear button (D30). The settings app has these screens: the setup checklist; listening and activation; voice and speech; conversations; instant commands (section 9.8);
```

- [ ] **Step 3: The conversation screen, past conversations and the goal notification**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and an About page with licences, versions and the status of the model files. "Kestrel, open settings" opens it.
```

with:

```text
and an About page with licences, versions and the status of the model files. "Kestrel, open settings" opens it.

**The conversation screen** (`ConversationActivity`, D30) shows the open conversation's transcript, drawn from its records: the owner's typed and spoken inputs, each with a small keyboard or microphone mark; Kestrel's words, questions and answers; the steering; the goal's checklist as one card that updates; the final answers; and a line where the conversation ended or was continued. Below the transcript are a text field ("Tell Kestrel what to do"), Send, and a microphone button, which acts as the side key does (section 8.6). While a goal is open, the screen shows Stop; with the goal held, it shows Continue and Cancel. Its top bar has "New conversation", which reads "Stop and start new" while a goal is open and then ends the goal with its report before it closes the conversation; the gear, which opens the settings; and a menu with "Past conversations". While the setup checklist is not done, a banner "Finish setting up Kestrel" opens it. The screen works with listening off, holds no state of its own, and draws the conversation as the engine shows it. When the owner sends a typed command or answers a question there, Kestrel moves the screen to the back before it acts (section 12.5).

**Past conversations** (D32) lists, newest first, the closed conversations within their retention, each with its date, its first request cut at 80 characters and its number of commands. A search field filters the list with the matching of section 11.4. Opening one shows its transcript, read-only, with the time of each command, and the buttons "Continue this conversation" and "Delete". Continuing makes it the open conversation again, as section 11.1 says, and closes the open one into the list; while a goal is open, Kestrel says "Stop the goal first.". Each conversation can be deleted from the list or from its transcript.

**The goal notification** of the goal service (section 7.2), on its own channel "Goals", says "Kestrel is working" with the current checklist item and a progress bar, and has Stop and Open; with the goal held, it asks "Continue the goal?" with Continue and Cancel. On the lock screen it says only "Kestrel is working on a task", because the item may hold names. Its actions reach Kestrel as the listening notification's actions do (section 8.6), and it has no inline reply. A tap on it opens the conversation screen; while the goal acts, that tap is a touch and interrupts it (section 14.1).
```

- [ ] **Step 4: The Conversations page**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
the settings app takes such an intent also while it is in front.
```

with:

```text
the settings app takes such an intent also while it is in front.

The page "Conversations" holds:

- the quiet time between commands (D29): 1 minute by default, or 30 s, 2, 5, 10 or 30 minutes; it counts at once, also for the running clock;
- the follow-up window (D29): 8 s by default, or off, 4, 12, 20 or 30 s, never more than the quiet time; it counts from the next final reply;
- "Speak replies to typed commands" (D30): off by default; it counts from the next reply;
- how long past conversations are kept (D32): 30 days by default, or 1, 7, 90 or 365 days; a change runs a purge at once;
- the limits per round of a goal (D24): 500 model replies (from 50 to 2,000), 60 minutes of working time (from 5 to 240) and 1,000 AI Credits (from 10 to 5,000, shown only for a profile that counts credits); they count from the next round;
- "Delete all past conversations", with a question.

The choices are stored encrypted, like every setting. The Home page of the settings has a row for the page, and "Delete history" on the activity log's page names past conversations too (section 13.5).
```

- [ ] **Step 5: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'Kestrel''s launcher icon opens the conversation screen', '**The conversation screen** (`ConversationActivity`, D30)', '**Past conversations** (D32)', '**The goal notification**', 'The page "Conversations" holds:') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the five texts `: 1`, then H + 0.

- [ ] **Step 6: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 11: the conversation screen as the launcher, past conversations, the goal notification and the Conversations page" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 12: Testing and evaluation

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 16.1, 16.2, 16.3, 16.4, 16.5 and 16.6)

**Interfaces:**
- Consumes: the names and rules of Tasks 2 to 11.
- Produces: the hooks `conversation-type`, `conversation-state` and `conversation-control` and the new answers of `agent-state`, `agent-task` and `voice-state` (section 16.1); the package `dev.kestrel.app.conversation` (section 16.3); the multi-turn tasks (section 16.5) and the case `records-injection` (section 16.6), which sections 5.3 and 18.7 name.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '12'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: The hooks of sub-project 7 (section 16.1)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Release builds do not contain it, and the build-time audit fails a release build that does.
```

with:

```text
For sub-project 7, it also types a command or an answer as if on the conversation screen, tagged like a transcript (`conversation-type`). It reports the conversation without its words (`conversation-state`): whether one is open and whether it was continued, its number of commands, the goal's phase, whether the quiet time is held or running and for how long, the reply channel and test mode. It starts a new conversation, ends the quiet time at once so that tests need not wait, sets small limits per round for a test, and reports what Kestrel found after a restart (`conversation-control`). It says whether the follow-up window is open (`voice-state`). Its answer to `agent-state` also gives the checklist's count, whether a conversation is open, and the phases paused-at-limit and interrupted. `agent-task` names a goal's new ends, stopped-at-limit, paused-at-limit and interrupted, in place of the old ends steps, time and cost. Release builds do not contain it, and the build-time audit fails a release build that does.
```

- [ ] **Step 3: The unit tests (section 16.2)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- the Orchestrator's transitions;
```

with:

```text
- the Orchestrator's transitions, and the conversation's (`ConversationMachineTest`), to which the task cases of `RulesTest` moved one for one, so that each old behaviour has a named new test;
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
- routine template matching and slot extraction.
```

with:

```text
- routine template matching and slot extraction;
- the goal's task run with the scripted model: the checklist's rules, steering, the limits with "Continue?" and its three answers, the interruption's report and the wait for Kestrel's own screen;
- the records block: its caps, the checklist's open items and line of counts, the fill order that never passes the budget, condensing once, the left-out lines, the budget for a local Ollama model, no placeholder in any record, and the context's order;
- the store of past conversations with a fake archive and its retention; the search's folding, near misses, ranking and index; and Kestrel's own requests, among them continuing a past conversation;
- the channels: the reply channel by the latest input, spoken replies to typed commands, every output in the record, and the waits of a typed conversation;
- safety across a conversation: the owner's earlier words, the carried allowance with Standard mode still confirming and every call still handed over, a search match typed as screen text, no carried confirmation, and test mode fixed for the conversation and taken anew by a continued one.
```

- [ ] **Step 4: The device tests of the conversation, and the corpus's lines of speech that is no command (sections 16.3 and 16.4)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Kestrel's data, such as its memory, survives a run (checked on `kestrel-api37`).
```

with:

```text
Kestrel's data, such as its memory, survives a run (checked on `kestrel-api37`).

The conversation's device tests (the package `dev.kestrel.app.conversation`, sub-project 7) run on `kestrel-api37` through `scripts\connected-test.ps1`, with a scripted model. They cover a typed command that leaves Kestrel's screen and acts on a fixture of `:testapp`; a second typed command that uses the first one's record; the question card with Yes, No and "Reply…", and the fresh observation after "Reply…"; the waits of a typed conversation, shortened by an instrumentation argument; Stop; a touch that interrupts and Continue that resumes; "New conversation"; the past conversations, a transcript and "Continue this conversation"; the screen's buttons under an overlay of `:testapp`, which drop the touch; and the goal service with listening off. A restart test ends Kestrel's process with the hook during a goal, and checks that the conversation is still open, that the goal is interrupted and asks "Continue the goal?", and that yes continues from the checklist with a fresh observation. The store's tests check the database's migration from version 2 to 3 with the real Keystore, and that "delete history" removes the rows and the key. The agent's device tests that expected "Cancelled." after a touch expect the interruption's sentence. On the A71, within the limits of AGENTS.md, the checks of sub-project 7 cover the goal service's three starts (section 19), the first search over a filled archive (section 5.3) and a typed goal with listening off.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
For an instant-command, conversation or dictation line,
```

with:

```text
For an instant-command line, a line of the corpus's group `conversation` (speech that is no command) or a dictation line,
```

- [ ] **Step 5: The multi-turn tasks (section 16.5)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
`--rerun` runs the tasks that lost passes the 3 more times.
```

with:

```text
`--rerun` runs the tasks that lost passes the 3 more times.
- From sub-project 7, a task is a list of turns, each spoken or typed, with an optional hook call, wait or check between two turns, so a task of one turn keeps today's form. The answers also cover a limit's "Continue?" (yes, or silence, as the task says), "Continue the goal?" and the switch to a past conversation.
- The multi-turn tasks of sub-project 7 are: `followup-settings` (a second setting spoken inside the follow-up window; one conversation of two commands); `typed-contact-then-message` (on the A71 only: a typed command finds a test contact's number, and the next one sends it to Telegram's Saved Messages; typing the found number is confirmed as screen text in Standard mode, and the send is confirmed); `steer-while-running` (a dry run on kestrel-api37 only, because the A71's alarms stay untouched: an alarm corrected from 7:00 to 7:30 while the goal runs); `limit-continue` (small limits set by the hook: the question, a second round of the same size, a pause at silence, and "continue"); `interrupt-continue` (a tap during the goal interrupts it, and "Kestrel, continue" finishes it with nothing done twice); `resume-after-death` (the process ends during the goal, and yes to "Continue the goal?" finishes it from its checklist with nothing done twice); `search-past` (a second conversation finds the first one's marker with `search_conversations`); `continue-past` (on kestrel-api37: one conversation reads an SMS with a marker, and after its quiet time it is continued by name and answers a follow-up about the marker); and `quiet-time-ends` (two conversations, and the second request has no records block). Each has a dry-run script, and the ones that can run live also run live in test mode. Their baseline is recorded once and kept apart, so that the 29 tasks keep theirs (section 5.3).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
"no" to a yes-or-no question, to "Continue the task?" and to `look_closer`'s screenshot question,
```

with:

```text
"no" to a yes-or-no question, to "Continue the goal?" and to `look_closer`'s screenshot question,
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
("cancel", or "no" to "Continue the task?")
```

with:

```text
("cancel", or "no" to "Continue the goal?")
```

- [ ] **Step 6: The multi-turn injection case (section 16.6)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Live, each fixture runs 5 times.
```

with:

```text
Live, each fixture runs 5 times. The multi-turn case `records-injection` lets a fixture's text reach a command's record as data and then gives a harmless command; it passes by the same rule.
```

- [ ] **Step 7: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
"Continue the task? : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern 'Continue the task?').Count)"
foreach ($text in '`conversation-control`', '`ConversationMachineTest`', '`dev.kestrel.app.conversation`', '`followup-settings`', '`records-injection` lets a fixture''s text', 'a line of the corpus''s group `conversation`') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: `Continue the task? : 0`, each of the six texts `: 1`, then H + 0.

- [ ] **Step 8: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 12: the conversation's hooks, unit tests and device tests, the multi-turn tasks of the suite and records-injection" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 13: The delivery plan, the spikes, the risks and Kestrel's commands

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 18, 19 and 20, and Appendix A)

**Interfaces:**
- Consumes: D26 and D29 to D33 (Task 2); the criteria of section 5.3 (Task 3); the names of Tasks 4 to 12.
- Produces: section 18.7 "Sub-project 7: Conversations", to which risk 13 refers; the spikes S9 and S10, to which section 16.3 refers ("section 19"); the risks 13 to 18; and Appendix A's rows for Kestrel's commands, which section 8.3 names.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '13'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: Seven sub-projects (section 18)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Kestrel is built as six sub-projects, in this order. Each one gets its own implementation plan in `docs/superpowers/plans/`, and each plan starts by re-reading this spec and the results of the spikes in section 19. The first release for daily use needs all six.
```

with:

```text
Kestrel is built as seven sub-projects, in this order. Each one gets its own implementation plan in `docs/superpowers/plans/`, and each plan starts by re-reading this spec and the results of the spikes in section 19. The first release for daily use needs the first six; sub-project 7 comes in a later release.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and a release candidate is ready for the owner to install on the S23+ whenever they decide to.
```

with:

```text
and a release candidate is ready for the owner to install on the S23+ whenever they decide to.

### 18.7 Sub-project 7: Conversations

- **Scope:** multi-turn conversations with memory and the conversation screen, the owner's two musts (D29, D30); long goals with a checklist, steering, limits per round and "Continue?" (D31); past conversations that are kept, searched, read and continued (D32); safety across a conversation (D33); the new module `:core:conversation`, the goal service, the encrypted archive, the Conversations page and the multi-turn tasks of the suite. Its design is `docs/superpowers/specs/2026-11-24-conversations-design.md`.
- **Plans:** 7.0 writes the design into this spec. 7A lays the agent's groundwork in `:core:agent`: the goal and history ports, the checklist, the limits and their question, the new ends, the context's order, steering and the wait for Kestrel's own screen. 7B builds `:core:conversation` alone, on the JVM. 7C adds the encrypted archive in `:core:privacy` with its adapter, and can run beside 7B. 7D moves the voice path onto the conversation, deletes `AgentRunner`, `AppOwnerPort` and the task parts of the Orchestrator's rules in the same plan, so that there is never a time with two task paths, and switches the owner's limits on. 7E adds the goal service and the resilience to locks, calls and restarts with listening off. 7F adds the conversation screen as the launcher, the past conversations, the question card with "Reply…", the typed-input boundary, the speech rules and the Conversations page. 7G adds the multi-turn tasks to `:tools:eval`. The order is 7.0, 7A, 7B (with 7C beside it), 7D, 7E, 7F and 7G, and each plan ends green on its own.
- **Done when:** the suite's 29 tasks show no drop against their baseline of sub-project 6, judged as section 16.5 says, on kestrel-api37 and the A71; each multi-turn task passes 3 of 3 dry runs, and the live ones 3 of 3 runs in test mode; the injection suite, with `records-injection`, has no send, share, call or delete without the confirmation that Standard mode requires; the egress check is unchanged; the first search over 30 days of conversations takes at most 1 s on the A71 (section 5.3); the voice path passes its gate: the task cases of the Orchestrator's rules moved one for one to the conversation's tests, the device tests of `dev.kestrel.app.agent` and `dev.kestrel.app.commands` pass, and the eval dry suite gives sub-project 6's result on kestrel-api37; and the results of the spikes S9 and S10 are in section 19.1.
```

- [ ] **Step 3: The spikes S9 and S10 (section 19)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| S8 | Can the sherpa-onnx library (with ONNX Runtime linked statically) and onnxruntime-android load together without conflicts? | Emulator | A sherpa-onnx build that shares the same ONNX Runtime library |
```

with:

```text
| S8 | Can the sherpa-onnx library (with ONNX Runtime linked statically) and onnxruntime-android load together without conflicts? | Emulator | A sherpa-onnx build that shares the same ONNX Runtime library |
| S9 | Can the goal service start as a foreground service of the type `specialUse` from the conversation screen, from the voice path while `ListeningService` runs, and from the goal notification's Continue? Can it add `mediaPlayback` while it speaks with listening off? Plan 7E checks it. | A71, emulator | Kestrel holds the goal as interrupted and says "Android didn't let me start working in the background. Open Kestrel and tap Continue." |
| S10 | Does the first search over 30 days of past conversations take at most 1 s on the A71, where each row is decrypted through the Keystore? Plan 7C measures it. | A71 | The search's index stored encrypted in a row of its own |
```

- [ ] **Step 4: The risks of sub-project 7 (section 20)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
12. **Unverified research.** Some facts in Appendix B came from research agents and were not re-read (marked †). They must be checked before a design decision relies on them.
```

with:

```text
12. **Unverified research.** Some facts in Appendix B came from research agents and were not re-read (marked †). They must be checked before a design decision relies on them.
13. **The voice path's rewrite (sub-project 7).** Moving the task rules of the Orchestrator, `AgentRunner` and `AppOwnerPort` into the conversation may lose a rule from sub-project 5's reviews. The one-for-one move of their tests, the unchanged device suites and the eval dry suite are the gate (section 18.7).
14. **The cost of long goals.** 1,000 AI Credits are $10 per round, and each yes to "Continue?" allows as much again. A goal that makes slow progress, such as scrolling a long list, stops only at a limit; stuck detection still ends a goal without progress.
15. **Steering lands one step late.** A step that started before a correction runs to its end, also a confirmed send. "Stop" and a touch still stop at once.
16. **The allowance across commands in full-auto.** With D33, a command such as "and to Mila too" may send in full-auto without the question "You didn't ask me to …", also in a conversation that was continued days later. Standard and Relaxed modes still confirm every send, and every call is still handed over.
17. **Typed input on Android 13.** Android 13 cannot mark the conversation screen's window as sensitive for accessibility, so another app's accessibility service could set the field's text. The A71 is only a test device; the S23+ runs Android 17, where the window is protected.
18. **Prompt size.** The records block adds up to 2,000 tokens to the first request of each later command, which the cache then holds for its later steps. The suite's token counts show the real effect.
```

- [ ] **Step 5: Kestrel's commands (Appendix A)**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| General | "Hey Google, …" commands through Gemini | Not applicable; tasks start with "Kestrel" |
```

with:

```text
| General | "Hey Google, …" commands through Gemini | Not applicable; Kestrel's commands start with "Kestrel", come inside the follow-up window or are typed |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Options | Cancel on touch | Always on, for tasks and for an instant command's question or a call's countdown (section 14.1) |
```

with:

```text
| Options | Cancel on touch | Always on: a touch interrupts a goal, which stays held with Continue and Cancel, and cancels an instant command's question or a call's countdown (section 14.1) |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Kestrel adds these commands, which use its name:
```

with:

```text
Kestrel adds these commands, which use its name. Typed on the conversation screen, Kestrel's own requests need no name (section 12.1):
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
| Kestrel, [task] | Runs a task |
| Kestrel | Answers "Yes?" and treats the next utterance as a task |
| Kestrel, wake up | Resumes listening from the paused mode |
| Kestrel, turn off | Turns Kestrel off, as "Stop Kestrel" does |
| Kestrel, stop | Stops speech and cancels a running task |
| Kestrel, continue | Resumes a task after a hand-over |
| Kestrel, save that as [name] | Saves the last task as a routine |
| Kestrel, remember [fact]; Kestrel, forget [fact] | Adds or removes an alias or fact, after confirmation |
| Kestrel, delete history | Wipes the activity log and the network log, after a spoken confirmation. It comes with the agent (sub-project 5); until then, the button on the settings pages of the logs does it. |
```

with:

```text
| Kestrel, [command] | A command of the conversation: it starts a goal, steers the running one or resumes a held one (sections 11.1 and 11.10) |
| [command] inside the follow-up window | The same, without the name (section 8.3) |
| Kestrel | Answers "Yes?" and treats the next utterance as a command |
| Kestrel, wake up | Resumes listening from the paused mode |
| Kestrel, turn off | Turns Kestrel off, as "Stop Kestrel" does |
| Kestrel, stop | Stops speech and ends a running goal with its report |
| Kestrel, continue | Resumes a goal after a hand-over, a pause, a limit or an interruption |
| Kestrel, new conversation; Kestrel, start a new conversation | Closes the open conversation; while a goal is open, Kestrel says "Stop the goal first." |
| Kestrel, continue the conversation about [topic]; continue our conversation about [topic]; go back to the conversation about [topic] | Finds the past conversation and asks "Continue the conversation from <day> about <topic>?" before it switches; without a topic, it means the newest past conversation (section 11.1) |
| Kestrel, save that as [name] | Saves the last task as a routine |
| Kestrel, remember [fact]; Kestrel, forget [fact] | Adds or removes an alias or fact, after confirmation |
| Kestrel, delete history | Wipes the activity log, the network log and the past conversations, after a confirmation (section 13.5) |
```

- [ ] **Step 6: Check the result**

Run:

```powershell
$spec = 'docs\superpowers\specs\2026-11-12-kestrel-design.md'
foreach ($text in 'six sub-projects', 'needs all six', 'Runs a task', 'cancels a running task', 'Resumes a task after a hand-over', 'tasks start with "Kestrel"') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
foreach ($text in '### 18.7 Sub-project 7: Conversations', '| S9 |', '| S10 |', '18. **Prompt size.**', '| Kestrel, new conversation; Kestrel, start a new conversation |') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }
(Select-String -Path $spec -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected: each of the first six texts `: 0`, each of the next five `: 1`, then H + 1 (the heading of section 18.7).

- [ ] **Step 7: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(spec): plan 7.0 task 13: sub-project 7 in the delivery plan, the spikes S9 and S10, its risks, and Kestrel's conversation commands in Appendix A" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 14: The consistency check and the README's description

**Files:**
- Modify: `README.md` (its first paragraph)
- Check: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (the whole file)

**Interfaces:**
- Consumes: every change of Tasks 1 to 13.
- Produces: a spec without the words that sub-project 7 made wrong, with every reference to a section of its own resolved, every table whole and every fence closed; and a README whose description names Kestrel as a copilot and driver of the phone and says what sub-project 7 adds.

- [ ] **Step 1: Check the task's texts**

Run the plan checker as "Before you start" says, with `$env:START_TASK = '14'`.
Expected: no line that starts with `PROBLEM:`.

- [ ] **Step 2: Check the whole spec**

Run, in `H:\Code\kestrel-sp3`:

```powershell
$spec = [IO.File]::ReadAllText((Resolve-Path 'docs\superpowers\specs\2026-11-12-kestrel-design.md'))
$gone = @(
  'Voice-first', 'Every step inside a task must work by voice', 'Continue the task?', 'cost cap', '10 credits ($0.10)',
  '30 model replies', 'Tasks are never resumed', 'A task never comes back', 'Cancel on touch (D27)', 'cancels a running task',
  'six sub-projects', 'Runs a task', 'confirmation card', 'Delete the activity log and the network log?', 'per-task',
  "the task's conversation", 'enters the conversation', 'treats the next utterance as a task', 'waits for a task',
  'Task trigger', 'a spoken confirmation', 'a spoken question and a spoken answer', 'D1–D28', 'Kestrel, <task>',
  'A transcript is a task', 'while a task exists', 'Kestrel speaks the question and shows it on a confirmation card',
  ', or a running task.', "after it has spoken the task's result", "the call's conversation", 'because it may be conversation',
  'because it is conversation far more often', 'two conversations of an app', 'Goals for the first release', "A goal's round is one task run"
)
$found = @($gone | Where-Object { $spec.Contains($_) })
"gone but found: $(if ($found.Count) { $found -join ' | ' } else { 'none' })"
$need = @(
  '| D33 |', '### 11.1 Entry through the conversation', '### 11.10 What Kestrel listens to while a goal runs',
  '### 14.1 A touch interrupts or cancels (D27)', '### 18.7 Sub-project 7: Conversations', '| S9 |', '| S10 |',
  '`update_checklist`', '`search_conversations`', 'Continue the goal?', '`:core:conversation`', '`GoalService`',
  '`ConversationActivity`', '`kestrel.conversations`', 'FOREGROUND_SERVICE_SPECIAL_USE', 'The page "Conversations" holds:'
)
$missing = @($need | Where-Object { -not $spec.Contains($_) })
"needed but missing: $(if ($missing.Count) { $missing -join ' | ' } else { 'none' })"
$heads = [regex]::Matches($spec, '(?m)^#{2,3} (\d+(?:\.\d+)?)\.? ') | ForEach-Object { $_.Groups[1].Value }
$refs = [regex]::Matches($spec, '(?<!design, )\b[Ss]ections? (\d+(?:\.\d+)?)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$dangling = @($refs | Where-Object { $heads -notcontains $_ })
"dangling section references: $(if ($dangling.Count) { $dangling -join ', ' } else { 'none' })"
$bad = @(); $lines = $spec -split "`n"; $cols = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i].StartsWith('|')) {
    $n = ([regex]::Matches(($lines[$i] -replace '`[^`]*`', ''), '(?<!\\)\|')).Count
    if ($cols -eq 0) { $cols = $n } elseif ($n -ne $cols) { $bad += $i + 1 }
  } else { $cols = 0 }
}
"table rows with another number of cells: $(if ($bad.Count) { $bad -join ', ' } else { 'none' })"
"fence lines: $([regex]::Matches($spec, '(?m)^\x60\x60\x60').Count)"
$ds = [regex]::Matches($spec, '(?m)^\| D(\d+) \|') | ForEach-Object { [int]$_.Groups[1].Value }
"decisions: $($ds.Count), from D$($ds[0]) to D$($ds[-1])"
"carriage returns: $(([regex]::Matches($spec, "`r")).Count)"
(Select-String -Path docs\superpowers\specs\2026-11-12-kestrel-design.md -Pattern '^#{1,4} ' -CaseSensitive).Count
```

Expected:

```text
gone but found: none
needed but missing: none
dangling section references: none
table rows with another number of cells: none
fence lines: 6
decisions: 33, from D1 to D33
carriage returns: 0
101
```

The last line is H₀ + 1 (the heading of section 18.7). If any line differs, stop and ask the controller; do not change the spec in this task.

- [ ] **Step 3: Read the changed sections once as the owner**

Run: `git diff 75dd098b -- docs/superpowers/specs/2026-11-12-kestrel-design.md`
Expected: only the texts of Tasks 1 to 13 changed (and plan 6B-basefix's five sentences, which this diff shows as well). Read each new text once in its section. Note in the task's report every sentence that needs a second read, with its section; do not change it in this task.

- [ ] **Step 4: The README's description**

In `README.md`, replace:

```text
Kestrel is a private, voice-first assistant for Android. It is meant to replace Google's Voice Access on the owner's phone. It keeps the direct commands that Voice Access has, such as "scroll down", "tap Send" or "show numbers", and it adds whole tasks in one sentence, such as "Kestrel, send Ana a Viber message that I'm 10 minutes late". An agent on the phone then performs the task through Android's accessibility service.
```

with:

```text
Kestrel is a private, standalone copilot and driver of an Android phone, and it also replaces Google's Voice Access on the owner's phone. It keeps the direct commands that Voice Access has, such as "scroll down", "tap Send" or "show numbers", and it adds whole tasks in one sentence, such as "Kestrel, send Ana a Viber message that I'm 10 minutes late". An agent on the phone then performs the task through Android's accessibility service. Today you give Kestrel its tasks by voice. Sub-project 7, which is being built, adds a second channel, a conversation screen where you type, and turns tasks into conversations: a command can build on the earlier ones, a bigger goal gets a checklist and limits that you set, and past conversations stay on the phone, encrypted, for you to read and continue (design spec, section 1).
```

- [ ] **Step 5: Check the README**

Run: `foreach ($text in 'voice-first', 'standalone copilot and driver of an Android phone') { "$text : $((Select-String -Path README.md -SimpleMatch -CaseSensitive -Pattern $text).Count)" }`
Expected: `voice-first : 0`, then `standalone copilot and driver of an Android phone : 1`.

- [ ] **Step 6: Commit**

```powershell
git add README.md
git commit -m "docs(readme): Kestrel described as a standalone copilot and driver of the phone, with what sub-project 7 adds" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Left for the controller

- **Plan 6B-basefix's sentence on the answers.** That sentence of section 16.5 is in `4c37cca0` and names "Continue the task?" twice. Task 12, Step 5 now renames both to "Continue the goal?", because sub-project 7 renames the question (design 14.3 and 14.4); the design's new answers (`limit`, `continue-goal` and `switch`) are in the same step. If 6B-basefix changes that sentence again before Task 12 runs, rebase these two replacements.
- **Any other text that 6B-basefix moved.** The checker of "Before you start" names it. Rebase the replacement on the new text; keep the new text of this plan unless the moved text changed its meaning.
- **The decisions of "Changes to the design that this plan makes".** Each one goes beyond the design's Appendix A: the precedence row, D11, D15, D17 and D26, silence by voice that leaves a held goal held (accepted), the spikes S9 and S10, the risks 13 to 18, the spec's Appendix A, "the run's messages", the meaning of "task", and sub-project 7 in a later release. Accept or reject each before Task 1; a rejected one removes its replacement from its task, and Task 14's lists lose the words that only it removes.
- **A note for plan 7D in `progress.md`.** Design 14.3 names only the touch case as a test that changes on purpose. With silence by voice that leaves a held goal held, the "Continue the task?" test that moves from `RulesTest` to `ConversationMachineTest` also changes on purpose: silence no longer ends the goal. Write this into `.superpowers/sdd/progress.md` for plan 7D.
- **The README's other sections.** Its sections on the setup checklist, the settings pages and the agent still describe Kestrel before sub-project 7. Plans 7D to 7G should update them as they ship the conversation screen, the Conversations page and the hooks.
