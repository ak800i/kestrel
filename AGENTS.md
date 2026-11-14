# Notes for agents

- The design is in [docs/superpowers/specs/2026-11-12-kestrel-design.md](docs/superpowers/specs/2026-11-12-kestrel-design.md), and the plans are in [docs/superpowers/plans/](docs/superpowers/plans/). The progress of the plan that is being carried out is in `.superpowers/sdd/progress.md`, a local file that is not in git.
- Test only on the `kestrel-api37` emulator (`emulator-5554`). Never use the AVD `zoran` or the Galaxy S23+. Use the Galaxy A71 only in a plan step that names it, and only together with the owner.
- Run Gradle through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` when you are done.
- `Get-Adb` and the other helpers exist only after `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain`, so run that in the same command that uses them.
- After you change a script, run `scripts\test-scripts.ps1`.
