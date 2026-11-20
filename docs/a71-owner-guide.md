# The A71 run: what you do

Since plan 4D, the three A71 scripts give Kestrel each command themselves and check the result on the phone. They need you for about 8 minutes with your hands, and you are near this computer for about 30. The whole run takes about 7 hours: `a71-commands.ps1` about 2 hours, `a71-session.ps1` about 4 hours and `a71-providers.ps1` about 45 minutes.

## Once, before the first run (about 2 minutes)

Write `corpus\owner\a71-config.json`, for example as below. Git ignores the folder, and later plans add more keys to the same file.

    {
      "callNumber": "<another phone of yours, in quotes>",
      "testContacts": true,
      "speakerTargetDb": -26,
      "pin": "<the A71's test PIN, in quotes>"
    }

- `callNumber`: a number that is fine to call, such as another phone of yours, with 5 to 15 digits. The two test contacts get it. On the A71, Kestrel never presses Call while you are away: the checks that count down to a call run while you are at the A71, and the A71 has no SIM card, so Android would refuse a call, and nothing rings.
- `testContacts`: `true` allows two test contacts, "Ana Test" and "Ana Other", with that number, in the local account `kestrel-test`. They never leave the phone, and the session deletes them again.
- `pin`: the scripts unlock the A71 with it, also right after a restart of the phone. Write it in quotes. They type it only when Android says clearly that the A71's PIN pad shows, and if it does not unlock the A71, they never type it again in that run, so a wrong PIN cannot lock the A71 out. They never write it into a result, a log or the window. Without it, a script stops at its start and says what is missing. A result "the unlock failed" means that the scripts typed the PIN in this file and it did not unlock the A71: check the PIN. A result "the phone did not show its PIN pad clearly, so the script typed nothing" means that the PIN was never typed: the PIN may be right, and the controller looks at how the A71 showed its lock screen.
- `speakerTargetDb`: how loud the phone should hear this computer's clips, in dBFS of the loudest 20 ms. The scripts set the clips' loudness to it themselves. Change it only if the results show many clips that the phone did not match.

Put this computer's speakers next to the A71, and turn their volume up. The scripts set the loudness themselves, but they cannot make the speakers louder than Windows lets them.

## At the run (about 30 minutes near this computer, about 8 with your hands)

The controller installs everything first, with each script's step `install`, and then, with you there, locks the A71 once and lets the scripts unlock it with your PIN, to see that it works. Then you do three things, one after the other. Each one beeps and says what you do and what the screens show.

1. **Your finger** (`a71-commands.ps1 -Serial <A71> -Step owner`, about 3 minutes, 1 with your hands). Press Enter. Kestrel calls the test contact "Ana Test" and counts down 4 seconds on its bar. When this computer beeps, touch the clock at the top of the A71's screen with one finger, never a button of the phone app. The bar should say "Cancelled". Then stay by the A71 for about 2 minutes without touching it: Kestrel counts down three more times, and the script stops each countdown itself.
2. **Your Galaxy Buds** (`a71-session.ps1 -Serial <A71> -Step earbuds`, about 8 minutes, 2 with your hands). Take the Buds out of their case, check that the A71 shows them as connected, lay them next to this computer's speaker and press Enter. The phone plays a quiet tone and the speaker says "Kestrel". When this computer beeps again, put the Buds back into their case.
3. **Your two Copilot approvals** (`a71-providers.ps1 -Serial <A71> -EmulatorSerial emulator-5554 -LockHeld` without its step `install`, about 5 minutes with your hands):
   - For kestrel-api37, the script puts the sign-in code on this computer's clipboard and opens github.com/login/device in its browser. Sign in to GitHub if it asks, paste the code with Ctrl+V, and approve. Afterwards the script puts back the text that was on the clipboard; a picture or a file there is lost.
   - On the A71, Chrome opens github.com/login/device inside Kestrel. Sign in to GitHub if it asks, touch and hold the code field, tap "Paste", and tap Continue and Authorize. Do not tap Chrome's X. If Chrome offers to save your password, answer it yourself; the script never taps Chrome's buttons. Kestrel's page should come back by itself; if it does not, the script brings it back after 3 minutes.
   - The script saves both profiles itself, and then says that you may leave. The live cases spend about 4 to 8 of your GitHub AI Credits.

After that, `a71-providers.ps1` goes on by itself, and then the controller runs the other steps of `a71-commands.ps1` and of `a71-session.ps1` without you, one after the other. In them Kestrel never presses Call: a countdown that a check did not plan is cancelled at once. Please do not touch the A71 or the emulator while they run; the A71's screen stays on.

## What stays afterwards

- The A71 keeps its Copilot profile, as you decided on 2026-11-20. The emulator's Copilot profile is deleted.
- The scripts put back every setting that they change, also after a failure or Ctrl+C, and they put back your clipboard around the checks that copy and around the sign-in on the A71. When Android does not let Kestrel read the clipboard first, the result says so, and the clipboard keeps the check's text. Each script's last lines name anything that it could not put back, and how to put it back by hand.
- The battery measures reset the A71's battery statistics. That cannot be undone.
- The results are in `corpus\owner`. They hold no words that Kestrel heard and no text of your screen.
