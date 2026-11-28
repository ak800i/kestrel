# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays, and (plan 4D) the
#                                           grid's size, the magnification and whether the keyboard shows; and the
#                                           build's commit=<commit> dirty=<0|1>, unknown for a build without them
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
#   crash -Text 'text'                      ends Kestrel's process with an error whose message is the text, to check
#                                           that Android's crash records do not contain it
# Listening (a start needs Kestrel in front or its accessibility service on, the speech models that
# scripts\push-models.ps1 copies, and the microphone permission; the notification and its actions show only with the
# notification permission):
#   voice -Send toggle|pause|resume|off|screen-on|screen-off
#                                           sends the event to Kestrel's Orchestrator; toggle starts listening when
#                                           Kestrel is off or paused and pauses it while it listens, and the
#                                           notification's Pause or Resume and Stop actions work while Kestrel is on;
#                                           off also stops a restore of the stored mode and stores Off, also while
#                                           Kestrel is off, so that Kestrel does not come back at the next start of
#                                           its process
#   voice -Send heard -Text 'text' [-Named] [-Tag k [-Model id] [-Live] [-Marker 'words']]
#                                           sends a transcript, as if Kestrel had heard it; -Named as if the keyword
#                                           spotter had heard "Kestrel" at its start; -Tag links the tag k (1 to 32
#                                           letters, digits, '-' and '_', new in Kestrel's process) to it, for
#                                           voice-state -Tag; -Model, -Live and -Marker go with a task that the
#                                           transcript starts, for sub-project 5's agent (-Marker for agent-task's
#                                           markerInData=). On a phone, "yes, call" is refused and never sent
#   voice-state -Tag k                      what the transcript with the tag k did: its role (pending, command, task,
#                                           answer, cancel or none), its command's and its task's numbers, the
#                                           command's kind and outcome, whether it asks,
#                                           its phase, its route, the hashes of what Kestrel said and showed for it,
#                                           how Requests read it, and the latest command's number; never text
#                                           (docs\superpowers\plans\2026-11-20-a71-checks-4d-interfaces.md, section 2)
#   voice-state [-Number n]                 the Orchestrator's mode, work, speaking, microphone, recognition, screen and
#                                           "mic busy", the latest instant command's number, kind and outcome, whether
#                                           the voice session is open, and whether Kestrel's next start uses the clip
#                                           microphone (clips=on), and whether the follow-up window is open
#                                           (followUp=open|closed, plan 7D); never text. With -Number, the number, kind and
#                                           outcome of that command, if it is one of Kestrel's newest 16 (outcome=none
#                                           before it started, unknown when it is older), and latest= the latest
#                                           command's number
#   voice-clip -Text 'text'                 says the text in Kestrel's voice into a stand-in microphone, the clip
#                                           microphone, and makes it Kestrel's microphone from Kestrel's next start.
#                                           Kestrel hears the text when the clip microphone records: from that start,
#                                           or at once if Kestrel already listens through it, or, if the screen is off
#                                           then, when the screen comes back on. Kestrel hears only clips until
#                                           voice-clip -Clear or the end of its process; reinstalling with
#                                           scripts\install.ps1 ends the process. On a phone, "yes, call" is refused
#   voice-clip -Clear                       drops the waiting clips, and gives Kestrel back the microphone that the
#                                           first voice-clip replaced, from Kestrel's next start
#   voice-clip -Quiet                       drops the waiting clips and makes the silent clip microphone Kestrel's
#                                           microphone from its next start, so that no sound of the room reaches it,
#                                           until voice-clip -Clear
#   voice-call-commands [-On true|false]    answers whether "Commands during a call" is on, after storing -On; Kestrel
#                                           takes a change a moment later, as from its settings
#   voice-choice [-Voice piper|kokoro]      answers the stored voice and speed, and whether Kokoro was chosen and Piper
#                                           spoke the last time that a voice session opened, Kokoro's real-time factor
#                                           on the phone when Kestrel measured it above 1 (kokoroSlow=none otherwise),
#                                           and the factor that it measured (kokoroSpeed=none before a measure), after
#                                           storing -Voice at the stored speed; a new voice counts from Kestrel's next
#                                           start, and -Voice kokoro forgets the measure, so that the next start with
#                                           Kokoro measures it again
# The voice corpus (scripts\make-corpus.ps1 and scripts\voice-eval.ps1 run these). A clip is a 16 kHz WAV file in
# Kestrel's folder Android/data/dev.kestrel/files/eval, which -File names without '.wav':
#   voice-synth -Text 'text' -File name [-Voice piper|kokoro]
#                                           says the text in Kestrel's voice into a clip
#   voice-synth -Close                      closes the voice that voice-synth keeps loaded
#   voice-record -File name -Seconds n      starts recording the microphone for n seconds, at most 15, into a clip, and
#                                           answers recording once the microphone records; Kestrel must be off and in
#                                           front. It checks only at its start that Kestrel is off, and never the
#                                           screen: a voice session that opens during the recording shares the
#                                           microphone, and Android silences a recording from the background, which
#                                           the answer shows
#   voice-record -Result                    answers recording until the recording has ended, then its length, the
#                                           loudest sample, whether Android silenced it and the RMS levels in dBFS
#                                           (rms, and frameRms for the loudest 20 ms frame), never the sound
#   voice-eval -Load                        loads a recogniser with the boost words of Kestrel's voice session in the
#                                           background, while Kestrel is off; answers loading, ready with the numbers of
#                                           boost words, or failed with the error's class
#   voice-eval -File name -Expected 'text' -Kind kind [-Spotted] [-AfterName] [-Reveal] [-NoBoost]
#              [-Group group [-Labels 'label;label'] [-Contacts 'name;name'] [-NoLabels] [-ScreenLabels]]
#                                           transcribes the clip and compares it with the text: the numbers of words and
#                                           errors, whether Requests read the kind (skip: not checked), the time and the
#                                           memory; -Reveal adds what Kestrel heard, only for a generated clip, whose
#                                           name starts with gen-; -NoBoost transcribes without the boost words. With
#                                           the corpus's group of the line, the boost words also have the command
#                                           vocabulary and the labels as the screen's, as in Kestrel's voice session,
#                                           unless -NoLabels leaves both out, and the answer ends with command=1 when the
#                                           transcript leads to the same instant command as the text, 0 when not, and -
#                                           for a group without instant commands; -Contacts gives the contact matcher
#                                           its contacts, which are never boost words; -ScreenLabels takes the labels
#                                           from the screen in front, which never leave the device
#   voice-eval -Close                       closes that recogniser
# Speech through the air (plan 4D; the PC plays a clip through its speakers):
#   voice-expect -Text 'text' -Tag k [-Seconds n] [-NoRun] [-Group group [-Labels 'label;label'] [-Contacts 'name;name']]
#                                           for n seconds (15 by default, at most 60) compares each transcript of
#                                           Kestrel's voice session with the text on the phone: the first with at most
#                                           half its words wrong runs with the tag k (with -NoRun it is only resolved and
#                                           never runs); the others are dropped and counted; needs an open voice session.
#                                           With -Group, -Result's right= judges a corpus line of that group as voice-eval's
#                                           command= does, with the labels and contacts given, and without a match judges
#                                           the closest of the other transcripts (plan 4E)
#   voice-expect -Result -Tag k             answers waiting, then whether it matched, the word errors, the strays and
#                                           split parts, the phone's times of the end of the speech, of its pause and of
#                                           its text, and the command's number, kind and outcome, or with -NoRun its
#                                           kind and whether it is right; never text (2026-11-20-a71-checks-4d-interfaces.md, section 4)
# The A71 session (scripts\a71-session.ps1 runs these, except voice-echo, which plan 2D's session ran and no script runs
# now):
#   voice-counts                            the number of Kestrel's latest wait for a task, which rises with each wait and
#                                           also when a wait starts again, how many tasks Kestrel started in its process,
#                                           how many words the last slow task had, and how many keyword hits its voice
#                                           sessions reported, without those of its own voice; then, where they are above
#                                           0, the hits by keyword (hit-kestrel, hit-stop, hit-cancel), the transcribed
#                                           utterances, those that began with the name, their audio in milliseconds and
#                                           how Requests read each one (read-name, read-wakeup, read-other, read-empty
#                                           and so on); never words
#   voice-times                             the phone's own times (SystemClock.elapsedRealtime, in milliseconds) of
#                                           Kestrel's latest start and its source, the latest voice session's opening, the
#                                           end of the latest transcribed speech, Kestrel's latest reaction and how long
#                                           after that speech it came, its latest turn-off and why, the settings asked
#                                           for and in front, and whether Android runs the listening service in the
#                                           foreground (foreground= from Android's list; demoted= Android's reading of
#                                           whether the service lost its foreground, AndroidListeningConditions.demoted,
#                                           on which Kestrel acts only while its battery use is also Restricted); and
#                                           when the latest transcript's end-of-speech pause ended and when its text came
#                                           (segmentClosed, transcribed)
#   voice-route                             the earbud option and the route of Kestrel's newest recording: whether it is
#                                           open, whether the request for the earbuds holds, the headset's kind, how
#                                           long the start waited for the headset's link, the types of Android's
#                                           communication device and of the recording's input, the loudest sample since
#                                           the last voice-route, in percent, and the RMS levels of all samples and of the
#                                           loudest 20 ms frame since then, in dBFS (rms, frameRms); never audio
# The owner's options and Kestrel's notices, which the A71 sessions put back at their end:
#   owner-options [-RequireVerbs true|false] [-EarbudMicrophone true|false]
#                                           answers whether "Require verbs" and "Use the earbud microphone" are on,
#                                           after storing the options given; Kestrel takes a change a moment later, as
#                                           from its settings
#   notices [-Clear]                        answers how many of Kestrel's notices about problems (it stopped
#                                           listening, its accessibility service is off, "Open Kestrel's settings") are
#                                           in the notification shade, and their kinds (battery, stopped,
#                                           accessibility, settings, or none), after taking them away with -Clear
#   check-notice [-On true|false]           posts (-On true) or takes away (-On false) Kestrel's notification
#                                           "Kestrel check", and answers whether it is in the shade
#   voice-spotter [-Boost b] [-Threshold t] answers the keyword spotter's boost and threshold, after storing -Boost (0.5
#                                           to 10) and -Threshold (0.01 to 0.5); a change counts for every keyword
#                                           spotter that loads afterwards, also the spotter of the hook voice-echo,
#                                           until the hook sets it again or Kestrel's process ends
#   voice-task -Seconds n -Reply 'text' [-HandOver]
#                                           makes each goal's round take n seconds, at most 120, and then answer with
#                                           the reply, whole and of any length, which Kestrel says as the goal's final
#                                           words, unless the owner stopped the goal, until voice-task -Clear or the end
#                                           of Kestrel's process; with -HandOver (plan 7F) the round first hands the owner
#                                           a payment step, whose card waits for its Cancel or Continue
#   voice-task -Clear                       each goal's round runs on the phone again
#   voice-echo -Text 'text' [-Voice piper|kokoro]
#                                           starts playing the text in Kestrel's voice while the real microphone feeds the
#                                           keyword spotter, and answers playing; Kestrel must be off and in front. As
#                                           voice-record, it checks Kestrel's state only at its start, and never the screen
#   voice-echo -Result                      answers playing until the check has ended, then how the reply ended, the
#                                           keyword hits, those that the echo filter took for Kestrel's own voice, the
#                                           loudest sample in percent and whether Android silenced the microphone; a
#                                           failure of the microphone fails the check
# The A71 session of the instant commands (scripts\a71-commands.ps1 runs these):
#   profiles                                the profiles that the launcher API lists: each one's serial number, number
#                                           of apps and, from Android 15, type; never a name
#   snapshots                               how many snapshots the screen layer has built and how long they took in all
# The live tests of sub-project 4 (scripts\provider-check.ps1 and scripts\a71-providers.ps1 run these):
#   provider-live -Case text|tool|image|json -Model 'name' [-Headers 'name=value;name=value'] [-MessagesPath path]
#                                           starts the case with the Copilot model of that name and the default
#                                           profile, which must be a Copilot profile, and answers started; -Headers and
#                                           -MessagesPath are debug overrides for a retry in the owner's session
#   provider-live -Case allowance           starts Copilot's allowance call for the default profile
#   provider-live -Result                   answers running until the case has ended, then its outcome, whether the
#                                           answer matched, the style, the tokens of all the case's calls and whether
#                                           they were estimated (estimated=true when the provider sent no usage), the
#                                           credits, whether the price was known and the time on the device; never a
#                                           prompt, a reply or a token
#   provider-standin [-On true|false]       answers whether the Copilot stand-in on the device runs, whether the default
#                                           profile is a Copilot profile (profile=none|copilot|other), how many
#                                           Copilot profiles there are (copilot=n), the default profile's random ID
#                                           (id=<id>|none) and whether its credential is stored
#                                           (credential=present|missing|none), after starting or ending the
#                                           stand-in; while it runs, provider-live sends its cases to it and makes no
#                                           request off the device
#   provider-delete                         deletes every Copilot profile with its credential, as the profile page's
#                                           "Delete profile" does, and answers how many it deleted, how many deletions
#                                           failed and how many Copilot profiles are left; refused on kestrel-api37,
#                                           which keeps the owner's Copilot profile
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
#   location [-Source name] [-DelaySeconds n]
#                                           asks Android for the current location
#   spike-results [-Clear]                  what the location check found: whether a location came, never the place
# Spike S3 with a real finger:
#   watch [-Seconds n]                      counts the touches of the owner that the touch watcher reports during n
#                                           seconds (10 by default, at most 15); Kestrel's own gestures do not count
# Spike S7 (scripts\spike-s7.ps1 runs it):
#   files [-Create folder]                  the files in Kestrel's external files folder, with sizes and SHA-256
#                                           hashes, and the folders that Kestrel may not read; -Create makes Kestrel
#                                           create that folder in it first
# Checks of the screen on the phone (plan 4D; 2026-11-20-a71-checks-4d-interfaces.md, section 5), which answer no screen text:
#   screen-has [-Package p] [-ViewId s] [-Label t] [-Contains t] [-ClassName c] [-State list] [-Template 'text {label}' -Tag k]
#                                           counts the elements that a finger can reach and that match every filter
#                                           given, and answers the first match's overlay number and bounds, the hash of
#                                           the first name inside it, and the package and title hash of the page in
#                                           front; -State takes checked, unchecked, selected, focused, editable,
#                                           password, scrollable, clickable, actionable, expandable, collapsible,
#                                           dismissable or slider, separated by commas; -Template sends the text, with
#                                           {label} replaced on the phone by the first match's name, as a transcript
#                                           with the tag k
#   field -Expected 'text' [-ViewId s]      compares the focused text field, or the first one whose view ID contains s,
#                                           with the text, and answers whether it is equal, its length, its selection
#                                           and whether it is a password field
# The owner's clipboard around a check that copies (plan 4D):
#   clipboard -Save                         keeps a copy of the clipboard in Kestrel's memory, which Android allows only
#                                           while Kestrel's settings have the focus, and answers its kind (text or
#                                           other), unknown (empty, or Android refused the read: nothing is saved), or
#                                           held (an earlier copy still waits and stays); never the content
#   clipboard -Restore                      puts the saved copy back and forgets it (restored=true), or forgets it
#                                           when Android refuses (restored=failed); restored=false when none waits. It
#                                           never empties the clipboard
#   clipboard -Clear                        empties the clipboard, only on the emulator kestrel-api37: it is refused on
#                                           every other device, so that it never empties the owner's clipboard
# sub-project 5B: agent hooks
# The agent's checks (sub-project 5's design D28; :tools:eval, which scripts\eval.ps1 starts, runs these). A tagged task
# is given with voice -Send heard -Text 'Kestrel, <task>' -Tag k [-Model id] [-Live]; in a debug build it runs only with
# a script from agent-script, or live while the device's live session is open. No answer holds screen text, a prompt, a
# reply or a name:
#   agent-task -Result -Tag k [-Marker 'words'] [-Recipient 'name'] [-Digits 'digits']
#                                           state=running, or state=done with the task's end, steps, time, credits, counts
#                                           of confirmed and unconfirmed sends, calls and deletes, shared items, whether the
#                                           confirmed recipient is the name (match, other, none) and whether the spoken
#                                           reply holds the marker (1 or 0), the decisions that test mode changed from Run,
#                                           the model and the tokens; with -Digits, digits=1 when one number of the spoken
#                                           reply holds those digits in a row, from the start of one of its groups of
#                                           digits to the end of one, else digits=0; groups belong to one number when only
#                                           spaces, hyphens, dots, brackets or + stand between them, so "+1 202-555-0143"
#                                           holds 2025550143, but "58 percent, about 7 hours" does not hold 587;
#                                           ownerTouches= and signals= count the owner's touches that the touch watcher
#                                           reported during the task and the signals among them without a finger (branch
#                                           B's, a cancelled gesture). The marker and the digits count in the spoken
#                                           reply and in what the task said with say; with -Marker also markerSaid=1
#                                           when a say text held it, and markerInData=1 when a data tool's result held
#                                           the marker that voice -Marker gave the task (none when it gave none)
#   agent-answer -Tag k -Answer yes|no|confirm|cancel|continue|<number>|text:<words> [-Confirming category] [-Channel voice|text|card]
#                                           answers the open question of that task as the owner's voice would, and ends its
#                                           hand-over with continue or cancel; refused when that task asks nothing. With
#                                           -Confirming (agent-state's confirming), it is refused unless the open question
#                                           is a confirmation of that category; without it, a confirmation takes only no
#                                           and cancel; with -Channel text (plan 7F), as the owner's tap on the button of the
#                                           text channel's card that the answer names, or as words typed on Kestrel's
#                                           screen, which refuses a confirmation, a hand-over and a pick; with -Channel
#                                           card (plan 7F's Task 13a), as the owner's tap on the button of the card over
#                                           the app that the answer names, through the card desk, once the card takes taps:
#                                           answered ... channel=card, or held ... channel=card until=unlock for a Continue
#                                           pressed on the lock screen, which answers by itself at the unlock within that
#                                           lock; an error leaves the question open
#   agent-script -File name.json [-Check] | -Clear
#                                           installs plan 5A's scripted model from files/agent-scripts/name.json in
#                                           Kestrel's files folder (:tools:eval copies it there), or removes it; with
#                                           -Check (plan 7G) only reads the script by the scripted model's rules and
#                                           installs nothing: script=valid replies=<n>, or an error that names the fault
#   agent-state -Tag k                      the task's phase, its open question's kind, Kestrel's work, the hand-over, the
#                                           category of the open confirmation, and the question's source (question=yes-no,
#                                           free, pick-number, screenshot, limit, switch, delete-history, continue-goal or
#                                           none), which tells look_closer's
#                                           screenshot question from the model's own free question, and a round's
#                                           "Continue?" at a limit (limit), the switch to a past conversation (switch) and
#                                           "Delete the history?" (delete-history) from another yes-or-no question; and
#                                           (plan 7D) the goal's checklist in counts (checklist=<done>/<total>|none) and
#                                           whether a conversation is open (conversation=open|closed)
#   agent-cache [-Field anthropicMarks|responsesExplicit|chatCopilotMarks -On true|false]
#                                           the agent's cache fields, after storing the one given
#   agent-touch-source [-Rule NONE|FIELD|CREDITS] [-Branch A|B]
#                                           the touch watcher's rule and branch for this device, after storing them
#   agent-settings [-OwnerEmails 'a,b'] [-ViberSelfChatName name] [-WhatsappOwnName name] [-SelfChatAliases 'pkg=pkg']
#                                           test mode's names and the debug aliases of the fixtures; answers counts only
#   agent-memory -Add -Key k -Text value | -Remove -Key k | -Has -Key k
#                                           adds or removes a test alias, and answers the count of facts and the key;
#                                           -Has answers has=yes or has=no: whether a fact with the key exists, never its
#                                           value (sub-project 6: :tools:eval never touches a fact of the owner's)
#   agent-data -Tool list_sms|list_calls|find_media|list_notifications [-Text argument]
#                                           runs one data tool and answers its result's kind and number of items
#   selfchat-check                          whether test mode's recogniser takes the chat on the screen for a self-chat
#   agent-tree                              writes the screen's structure, with labels only where they are safe, to
#                                           cache/agent-tree.json, which the script reads and removes with run-as
#   screen-tap -Label text | -Contains text [-Long] [-Package p]
#                                           the script's own tap, or long press, on the first element of the front window
#                                           with that label, or whose label holds the text; it answers tapped=false
#                                           error=refused, without the label, for what the agent would never press without
#                                           the owner: anything in Kestrel's own app, a phone app or a contacts app, and in
#                                           any app a call, emergency or SOS control; with -Package, tapped=false
#                                           error=elsewhere when another app is in front, and the press checks the front
#                                           app again on every screen that it acts on
# sub-project 5C: live hooks
#   agent-live [-On true|false] [-Model id] opens (true) or closes (false) the device's live session (design D28 of
#                                           sub-project 5), in which a task sent with -Live may call the owner's provider,
#                                           and answers live=on|off; scripts\eval.ps1 --live opens it for its runs, and
#                                           scripts\a71-agent.ps1's sitting 2 for the sitting;
#                                           with -Model it adds model=listed|unlisted: whether the default profile's own
#                                           model list holds that ID
#   agent-approvals [-Revoke package]       the number of stored screenshot approvals, after revoking every one of that
#                                           app's (design D20); answers approvals=<n> revoked=<n>, never a name
# sub-project 6B: evaluation hooks
#   network-allowlist                       the hosts that Kestrel's network gateway may reach: hosts=<a,b> (the provider
#                                           profiles' hosts and local servers) and modelHosts=<c,d> (the model download's
#                                           hosts and patterns), or none; :tools:eval's egress check reads it
# sub-project 7: conversation hooks (plans 7D to 7F; design 14.4 of sub-project 7). No answer holds words:
#   conversation-state                      the open conversation: open=yes|no, continued=, commands=<n>,
#                                           goal=<phase>|none, request=running|none (Kestrel's own request: a switch,
#                                           remember, forget, "save that as" or delete history), quiet=off|held|running,
#                                           quietLeft=<seconds>|none, replyChannel=, testMode= and typed=yes|no, whether
#                                           the work there is the hooks' own (below): the open conversation, or while
#                                           none is open, the request that runs
#   conversation-control -Do new|expire|expire-typed|limits|limits-check|restore-check|delete-typed [-Limits replies/minutes/credits]
#                                           new starts a new conversation, as "Kestrel, new conversation" does; expire
#                                           ends the quiet time at once while its clock runs; expire-typed does so only
#                                           when the open conversation is one of the hooks' own at that moment, as
#                                           Kestrel checks it, and else answers expire=not-typed and changes nothing;
#                                           limits sets a round's limits
#                                           to -Limits, or without it to the Conversations page's smallest, and answers
#                                           the stored ones as was=; limits-check answers the stored limits
#                                           (limits=<r>/<m>/<c>) and changes nothing; restore-check answers what the restore at the
#                                           process's start found (restored=nothing, open, closed, goal-held, goal-ended
#                                           or pending); delete-typed deletes the closed conversations that the work of a
#                                           hook's input opened (any input that a hook gives, tagged or not), also before a
#                                           restart, unless an act of the owner's (any act whose number the hooks did not
#                                           claim before it) reached them since, and never another, and answers
#                                           deleted=<n> open=<n> failed=<n>
#   voice-restore                           how long ago the restore at the process's start turned Kestrel on
#                                           (restoredAgo=<seconds>|none) and the guard against a crash loop (guard=<seconds>),
#                                           within which a crash keeps Kestrel off (plan 7G)
#   conversation-type -Text 'words' [-Tag k [-Model id] [-Live] [-Marker 'words']]
#                                           types the words as the owner does on Kestrel's conversation screen; -Tag,
#                                           -Model, -Live and -Marker as for voice -Send heard, the last three only with
#                                           -Tag; answers typed read=<kind>, with tag=<tag> for a tag
#   goal-service-state                      the goal service: whether it runs, its notification and its types, its starts
#                                           and refusals in this process, an armed refusal, and the newest audio-focus
#                                           request with the counts of requests and answers
#   goal-control -Do tap|send-later -Action stop|open|continue|cancel | -Do touch|speak|focus|refuse|refuse-off|stop-typed
#                                           tap presses that button of the goal notification in the shade, as the owner's
#                                           finger, and says which rows it had to expand; send-later hands that button's
#                                           own PendingIntent to AlarmManager, which sends it 5 s later (send=scheduled, or
#                                           send=none); touch gives the conversation the owner's touch, as the touch watcher
#                                           reports it; speak and focus ask Android for the audio focus with and without
#                                           the speech's types; refuse makes the goal service's next start in this process
#                                           fail as Android's refusal does, once, and refuse-off takes an armed refusal back;
#                                           stop-typed stops only the hooks' own work, the goal or Kestrel's own request of
#                                           their conversation, or their request while none is open, as Kestrel checks
#                                           their mark (stop=sent), and leaves the owner's (stop=none)
# -Number picks an element by the number that the numbers overlay shows on it.
# -PrintCall checks the arguments as every call does, then prints the broadcast that the hook would send and stops before
# it looks for the device, so a test can see that a valid call passes every argument check; the checks that need the
# device (clipboard -Clear on kestrel-api37, provider-delete) do not run.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
# Windows PowerShell 5.1 drops double quotes from the arguments of other programs, which would change the text that
# 'type' types, so the script needs PowerShell 7.3 or newer.
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global', 'crash', 'voice',
        'voice-state', 'voice-clip', 'voice-call-commands', 'voice-choice', 'voice-synth', 'voice-record', 'voice-eval',
        'voice-counts', 'voice-times', 'voice-route', 'voice-spotter', 'voice-task', 'voice-echo', 'location',
        'spike-results', 'files', 'watch', 'profiles', 'snapshots', 'provider-live', 'provider-standin', 'provider-delete',
        'owner-options', 'notices', 'check-notice', 'voice-expect', 'screen-has', 'field',
        # sub-project 5B: agent hook names
        'agent-task', 'agent-answer', 'agent-script', 'agent-state', 'agent-cache', 'agent-touch-source', 'agent-settings',
        'agent-memory', 'agent-data', 'selfchat-check', 'agent-tree', 'screen-tap',
        # sub-project 5C: live hook names
        'agent-live', 'agent-approvals',
        # sub-project 6B: evaluation hook names
        'network-allowlist',
        # sub-project 7: conversation hook names
        'conversation-state', 'conversation-control', 'conversation-type', 'goal-service-state', 'goal-control', 'voice-restore',
        'clipboard')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out,
    [string]$Source,
    [int]$DelaySeconds = -1,
    [int]$Seconds = -1,
    [switch]$Clear,
    [string]$File,
    [ValidateSet('piper', 'kokoro')][string]$Voice,
    [string]$Expected,
    [string]$Kind,
    [switch]$Spotted,
    [switch]$AfterName,
    [switch]$Reveal,
    [switch]$Load,
    [switch]$Close,
    [string]$Reply,
    [switch]$Result,
    [switch]$NoBoost,
    [string]$Group,
    [string]$Labels,
    [string]$Contacts,
    [switch]$NoLabels,
    [switch]$ScreenLabels,
    [string]$Create,
    [ValidateSet('toggle', 'pause', 'resume', 'off', 'screen-on', 'screen-off', 'heard')][string]$Send,
    [switch]$Named,
    [double]$Boost = -1,
    [double]$Threshold = -1,
    [ValidateSet('text', 'tool', 'image', 'json', 'allowance')][string]$Case,
    [string]$Model,
    [string]$Headers,
    [string]$MessagesPath,
    [ValidateSet('true', 'false')][string]$RequireVerbs,
    [ValidateSet('true', 'false')][string]$EarbudMicrophone,
    [ValidatePattern('^[A-Za-z0-9_-]{1,32}$')][string]$Tag,
    [switch]$Live,
    [switch]$Quiet,
    [switch]$NoRun,
    [string]$Package,
    [string]$ViewId,
    [string]$Label,
    [string]$Contains,
    [string]$ClassName,
    [string]$State,
    [string]$Template,
    [switch]$Save,
    # sub-project 5B: agent parameters
    [string]$Marker,
    [string]$Recipient,
    [string]$Digits,
    [string]$Answer,
    [ValidateSet('reading', 'typing_request', 'ordinary_settings', 'typing_screen_text', 'sending', 'calls', 'deleting', 'payments',
        'credentials', 'protected')][string]$Confirming,
    [ValidateSet('anthropicMarks', 'responsesExplicit', 'chatCopilotMarks')][string]$Field,
    [ValidateSet('NONE', 'FIELD', 'CREDITS')][string]$Rule,
    [ValidateSet('A', 'B')][string]$Branch,
    [string]$OwnerEmails,
    [string]$ViberSelfChatName,
    [string]$WhatsappOwnName,
    [string]$SelfChatAliases,
    [switch]$Add,
    [switch]$Remove,
    [switch]$Has,
    [ValidateSet('list_sms', 'list_calls', 'find_media', 'list_notifications')][string]$Tool,
    # sub-project 5C: live parameters
    [string]$Revoke,
    [switch]$Restore,
    # sub-project 7: conversation parameters
    [ValidateSet('new', 'expire', 'expire-typed', 'limits', 'limits-check', 'restore-check', 'delete-typed', 'tap', 'touch', 'speak', 'focus', 'refuse', 'refuse-off', 'send-later', 'stop-typed')]
    [string]$Do,
    [ValidatePattern('^\d+/\d+/\d+$')][string]$Limits,
    [ValidateSet('voice', 'text', 'card')][string]$Channel,
    [switch]$HandOver,
    [switch]$Check,
    [switch]$PrintCall
)
$ErrorActionPreference = 'Stop'
# -1 marks a -Boost or -Threshold that was not given, so a negative value that was given would be dropped without a word.
if ($PSBoundParameters.ContainsKey('Boost') -and $Boost -lt 0) { throw "-Boost $Boost is negative; give a boost from 0.5 to 10." }
if ($PSBoundParameters.ContainsKey('Threshold') -and $Threshold -lt 0) {
    throw "-Threshold $Threshold is negative; give a threshold from 0.01 to 0.5."
}
if ($Command -eq 'voice-expect' -and -not $Tag) { throw 'voice-expect needs -Tag; give each expectation a new tag.' }
if ($Template -and -not $Tag) { throw 'screen-has -Template needs -Tag; give the transcript a new tag.' }
if ($Has -and -not $Key) { throw 'agent-memory -Has needs -Key; give the key to look for.' }
# -Do names the action of conversation-control or of goal-control; each takes only its own.
if ($Command -eq 'conversation-control' -and $Do -notin @('new', 'expire', 'expire-typed', 'limits', 'limits-check', 'restore-check', 'delete-typed')) {
    throw 'conversation-control needs -Do new, expire, expire-typed, limits, limits-check, restore-check or delete-typed.'
}
if ($Command -eq 'goal-control' -and $Do -notin @('tap', 'touch', 'speak', 'focus', 'refuse', 'refuse-off', 'send-later', 'stop-typed')) {
    throw 'goal-control needs -Do tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed.'
}
if ($Limits -and -not ($Command -eq 'conversation-control' -and $Do -eq 'limits')) { throw '-Limits needs conversation-control -Do limits.' }
# The goal notification's button that tap presses and send-later sends.
if ($Command -eq 'goal-control' -and (($Do -in @('tap', 'send-later')) -ne [bool]$Action)) {
    throw 'goal-control needs -Action with -Do tap or send-later, and only with them.'
}
if ($Command -eq 'conversation-type' -and -not $Text) { throw 'conversation-type needs -Text with the words to type.' }
# Only a tag carries conversation-type's options to the typed input, so the hook refuses them without one.
if ($Command -eq 'conversation-type' -and -not $Tag -and ($Model -or $Live -or $PSBoundParameters.ContainsKey('Marker'))) {
    throw 'conversation-type takes -Model, -Live and -Marker only with -Tag.'
}
# The stand-in round's payment step is an option of the stand-in round that voice-task sets.
if ($HandOver -and -not ($Command -eq 'voice-task' -and $Seconds -gt 0 -and $Reply)) { throw '-HandOver needs voice-task -Seconds and -Reply.' }
if ($Do -and $Command -notin @('conversation-control', 'goal-control')) { throw '-Do needs conversation-control or goal-control.' }
if ($Channel -and $Command -ne 'agent-answer') { throw '-Channel needs agent-answer.' }
# agent-script's check reads the script that -File names, and installs nothing.
if ($Check -and -not ($Command -eq 'agent-script' -and $File -and -not $Clear)) { throw '-Check needs agent-script -File, without -Clear.' }
if ($Command -eq 'clipboard' -and @(@($Save, $Restore, $Clear) | Where-Object { $_ }).Count -ne 1) {
    throw 'clipboard needs exactly one of -Save, -Restore and -Clear.'
}
# Emptying the clipboard would lose the owner's clip, so -Clear runs only on the emulator, which the hook checks too.
$clipboardClearRefused = "clipboard -Clear was refused on ${Serial}: only the emulator kestrel-api37 may empty the clipboard."
if ($Command -eq 'clipboard' -and $Clear -and $Serial -notlike 'emulator-*') { throw $clipboardClearRefused }

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }
if ($Source) { $extras += "--es source $(ConvertTo-ShellText $Source)" }
if ($DelaySeconds -ge 0) { $extras += "--ei delay $DelaySeconds" }
if ($Seconds -gt 0) { $extras += "--ei seconds $Seconds" }
if ($Clear) { $extras += '--ez clear true' }
if ($Create) { $extras += "--es create $(ConvertTo-ShellText $Create)" }
# ValidateSet accepts any letter case, and the hook only lower case.
if ($Send) { $extras += "--es event $(ConvertTo-ShellText $Send.ToLowerInvariant())" }
if ($Named) { $extras += '--ez named true' }
# In the invariant culture, whose decimal separator is always '.'.
if ($Boost -ge 0) { $extras += "--ef boost $($Boost.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($Threshold -ge 0) { $extras += "--ef threshold $($Threshold.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($File) { $extras += "--es file $(ConvertTo-ShellText $File)" }
if ($Voice) { $extras += "--es voice $(ConvertTo-ShellText $Voice.ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('Expected')) { $extras += "--es expected $(ConvertTo-ShellText $Expected)" }
if ($Kind) { $extras += "--es kind $(ConvertTo-ShellText $Kind)" }
if ($Spotted) { $extras += '--ez spotted true' }
if ($AfterName) { $extras += '--ez afterName true' }
if ($Reveal) { $extras += '--ez reveal true' }
if ($Load) { $extras += '--ez load true' }
if ($Close) { $extras += '--ez close true' }
if ($Result) { $extras += '--ez result true' }
if ($Reply) { $extras += "--es reply $(ConvertTo-ShellText $Reply)" }
if ($NoBoost) { $extras += '--ez noBoost true' }
if ($Group) { $extras += "--es group $(ConvertTo-ShellText $Group)" }
if ($PSBoundParameters.ContainsKey('Labels')) { $extras += "--es labels $(ConvertTo-ShellText $Labels)" }
if ($PSBoundParameters.ContainsKey('Contacts')) { $extras += "--es contacts $(ConvertTo-ShellText $Contacts)" }
if ($NoLabels) { $extras += '--ez noLabels true' }
if ($ScreenLabels) { $extras += '--ez screenLabels true' }
if ($Case) { $extras += "--es case $(ConvertTo-ShellText $Case.ToLowerInvariant())" }
if ($Model) { $extras += "--es model $(ConvertTo-ShellText $Model)" }
if ($Headers) { $extras += "--es headers $(ConvertTo-ShellText $Headers)" }
if ($MessagesPath) { $extras += "--es messagesPath $(ConvertTo-ShellText $MessagesPath)" }
# ValidateSet accepts any letter case, and Android's --ez only lower case.
if ($RequireVerbs) { $extras += "--ez requireVerbs $($RequireVerbs.ToLowerInvariant())" }
if ($EarbudMicrophone) { $extras += "--ez earbudMicrophone $($EarbudMicrophone.ToLowerInvariant())" }
if ($Tag) { $extras += "--es tag $(ConvertTo-ShellText $Tag)" }
if ($Live) { $extras += '--ez live true' }
if ($Quiet) { $extras += '--ez quiet true' }
if ($NoRun) { $extras += '--ez noRun true' }
if ($Package) { $extras += "--es package $(ConvertTo-ShellText $Package)" }
if ($ViewId) { $extras += "--es viewId $(ConvertTo-ShellText $ViewId)" }
if ($PSBoundParameters.ContainsKey('Label')) { $extras += "--es label $(ConvertTo-ShellText $Label)" }
if ($PSBoundParameters.ContainsKey('Contains')) { $extras += "--es contains $(ConvertTo-ShellText $Contains)" }
if ($ClassName) { $extras += "--es class $(ConvertTo-ShellText $ClassName)" }
if ($State) { $extras += "--es state $(ConvertTo-ShellText $State)" }
if ($Template) { $extras += "--es template $(ConvertTo-ShellText $Template)" }
if ($Save) { $extras += '--ez save true' }
if ($Restore) { $extras += '--ez restore true' }
# sub-project 5B: agent extras
if ($PSBoundParameters.ContainsKey('Marker')) { $extras += "--es marker $(ConvertTo-ShellText $Marker)" }
if ($PSBoundParameters.ContainsKey('Recipient')) { $extras += "--es recipient $(ConvertTo-ShellText $Recipient)" }
if ($PSBoundParameters.ContainsKey('Digits')) { $extras += "--es digits $(ConvertTo-ShellText $Digits)" }
if ($Answer) { $extras += "--es answer $(ConvertTo-ShellText $Answer)" }
# ValidateSet accepts any letter case, and the hook compares the category's wire name in lower case.
if ($Confirming) { $extras += "--es confirming $($Confirming.ToLowerInvariant())" }
if ($Field) { $extras += "--es field $Field" }
# ValidateSet accepts any letter case, and the hook takes the rule and the branch in capitals.
if ($Rule) { $extras += "--es rule $($Rule.ToUpperInvariant())" }
if ($Branch) { $extras += "--es branch $($Branch.ToUpperInvariant())" }
# These four may be empty, which clears them, so they count whenever they are given.
if ($PSBoundParameters.ContainsKey('OwnerEmails')) { $extras += "--es ownerEmails $(ConvertTo-ShellText $OwnerEmails)" }
if ($PSBoundParameters.ContainsKey('ViberSelfChatName')) { $extras += "--es viberSelfChatName $(ConvertTo-ShellText $ViberSelfChatName)" }
if ($PSBoundParameters.ContainsKey('WhatsappOwnName')) { $extras += "--es whatsappOwnName $(ConvertTo-ShellText $WhatsappOwnName)" }
if ($PSBoundParameters.ContainsKey('SelfChatAliases')) { $extras += "--es selfChatAliases $(ConvertTo-ShellText $SelfChatAliases)" }
if ($Add) { $extras += '--ez add true' }
if ($Remove) { $extras += '--ez remove true' }
if ($Has) { $extras += '--ez has true' }
if ($Tool) { $extras += "--es tool $($Tool.ToLowerInvariant())" }
# sub-project 5C: live extras
if ($Revoke) { $extras += "--es revoke $(ConvertTo-ShellText $Revoke)" }
# sub-project 7: conversation extras; ValidateSet accepts any letter case, and the hooks only lower case.
if ($Do) { $extras += "--es do $($Do.ToLowerInvariant())" }
if ($Limits) { $extras += "--es limits $Limits" }
if ($Channel) { $extras += "--es channel $($Channel.ToLowerInvariant())" }
if ($HandOver) { $extras += '--ez handover true' }
if ($Check) { $extras += '--ez check true' }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = (@("am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction") + $extras) -join ' '
if ($PrintCall) {
    $broadcast
    return
}

. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\DeviceGuard.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
if ($Command -eq 'clipboard' -and $Clear -and $KestrelTestDevices[$Serial].Property -ne 'ro.boot.qemu.avd_name') { throw $clipboardClearRefused }
# kestrel-api37 keeps Kestrel's Copilot profile, so provider-delete never runs there.
Assert-OwnerDataCommand -Serial $Serial -Command $Command

# Runs adb and reads its output as UTF-8, because screen text can be in any language.
function Invoke-Adb {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    try {
        (& (Get-Adb) -s $Serial @args) -join "`n"
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
}

$output = Invoke-Adb shell $broadcast

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Command -eq 'snapshot') {
            # A snapshot can be larger than a broadcast's answer, so Kestrel answers with the path of a file in its own
            # storage, which only a debug build lets adb read. adb shell passes on the exit code of run-as; adb exec-out
            # would answer with run-as's error text and exit code 0, as if the text were the snapshot. The answer goes into
            # the device's command line, so only the path that Kestrel's hook writes is accepted.
            $file = 'cache/hook-snapshot.json'
            if ($data -cne $file) { throw "The device did not answer with the snapshot's path $file. Install a current debug build with scripts\install.ps1." }
            $data = Invoke-Adb shell run-as dev.kestrel cat (ConvertTo-ShellText $file)
            $readCode = $LASTEXITCODE
            Invoke-Adb shell run-as dev.kestrel rm -f (ConvertTo-ShellText $file) | Out-Null
            if ($readCode -ne 0) { throw "Could not read the snapshot file $file from the device." }
        }
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
