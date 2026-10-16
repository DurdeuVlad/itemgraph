# M9 admin help visual evidence

Captured 2026-10-05 with MCPilot 0.15.0 from a connected NeoForge 1.21.1 client on an isolated local ItemGraph dev server (`127.0.0.1:25590`, NeoForge 21.1.248, ItemGraph 0.3.2). The test server had only Minecraft, NeoForge, and ItemGraph loaded. No GriefLogger jar/database or production data was used.

| Screenshot | Physical client viewport | MCPilot logical view | Surface |
| --- | ---: | ---: | --- |
| `help-overview-854x480.png` | 854x480 | 427x240, scale factor 2 | `/ig help` |
| `help-commands-854x480.png` | 854x480 | 427x240, scale factor 2 | `/ig help commands` |
| `help-journeys-854x480.png` | 854x480 | 427x240, scale factor 2 | `/ig help journeys` |
| `help-commands-1280x720.png` | 1280x720 | 640x360, scale factor 2 | `/ig help commands` |

MCPilot returned GUI-scaled PNG dimensions; the physical viewport is the reported logical width/height multiplied by `scaleFactor`. The `/ig help` and `/ig help commands` wording has since changed: the overview now leads with readiness, and the command catalog now uses compact groups with an explicit continuation. Therefore none of the tracked help screenshots verifies the current text or its viewport fit. Recapture `/ig help` and `/ig help commands` at 854x480 and 1280x720, and `/ig help journeys` at 854x480, after the next permitted dev-server run. This is visual evidence for help navigation only, not the full #153 incident walkthrough or the milestone-end cross-loader load/replay/export suite. `docs/ADMIN_QUICK_START.md` remains the complete offline feature map.

All ten `soundCategory_*` values in the MCPilot NeoForge client `options.txt` were `0.0` before and after this run. MCPilot reports this client as `mute: true`; its source defaults new clients to muted and writes every sound category to `0.0` at launch.
`quick-start-command-corpus.txt` is a parser-only fixture used by both loader test suites.
It is not an operator walkthrough or a sequence to paste into a server; in particular,
`/ig ingest history` queues an import when configured and `/ig inspect on` changes the
issuing player's inspection state. The actual beginner walkthrough is the short,
sequential example in `docs/ADMIN_QUICK_START.md`.
