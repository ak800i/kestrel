# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
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
#   voice -Send heard -Text 'text' [-Named] [-Tag k [-Model id] [-Live]]
#                                           sends a transcript, as if Kestrel had heard it; -Named as if the keyword
#                                           spotter had heard "Kestrel" at its start; -Tag links the tag k (1 to 32
#                                           letters, digits, '-' and '_', new in Kestrel's process) to it, for
#                                           voice-state -Tag; -Model and -Live go with a task that the transcript
#                                           starts, for sub-project 5's agent. On a phone, "yes, call" is refused and
#                                           never sent
#   voice-state -Tag k                      what the transcript with the tag k did: its role (pending, command, task,
#                                           answer, cancel or none), its command's and its task's numbers, the
#                                           command's kind and outcome, whether it asks,
#                                           its phase, its route, the hashes of what Kestrel said and showed for it,
#                                           how Requests read it, and the latest command's number; never text
#                                           (docs\superpowers\plans\2026-11-20-a71-checks-4d-interfaces.md, section 2)
#   voice-state [-Number n]                 the Orchestrator's mode, work, speaking, microphone, recognition, screen and
#                                           "mic busy", the latest instant command's number, kind and outcome, whether
#                                           the voice session is open, and whether Kestrel's next start uses the clip
#                                           microphone (clips=on); never text. With -Number, the number, kind and
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
#                                           loudest sample and whether Android silenced it, never the sound
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
#                                           on which Kestrel acts only while its battery use is also Restricted)
#   voice-route                             the earbud option and the route of Kestrel's newest recording: whether it is
#                                           open, whether the request for the earbuds holds, the headset's kind, how
#                                           long the start waited for the headset's link, the types of Android's
#                                           communication device and of the recording's input, and the loudest sample
#                                           since the last voice-route, in percent; never audio
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
#   voice-task -Seconds n -Reply 'text'     makes each task take n seconds, at most 120, and then say the reply, unless
#                                           Kestrel cancelled it, until voice-task -Clear or the end of Kestrel's process
#   voice-task -Clear                       each task ends at once again, as in sub-project 2
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
#                                           profile is a Copilot profile (profile=none|copilot|other), and how many
#                                           Copilot profiles there are (copilot=n), after starting or ending the
#                                           stand-in; while it runs, provider-live sends its cases to it and makes no
#                                           request off the device
#   provider-delete                         deletes every Copilot profile with its credential, as the profile page's
#                                           "Delete profile" does, and answers how many it deleted, how many deletions
#                                           failed and how many Copilot profiles are left
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
# -Number picks an element by the number that the numbers overlay shows on it.
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
        'owner-options', 'notices', 'check-notice')][string]$Command,
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
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'
# -1 marks a -Boost or -Threshold that was not given, so a negative value that was given would be dropped without a word.
if ($PSBoundParameters.ContainsKey('Boost') -and $Boost -lt 0) { throw "-Boost $Boost is negative; give a boost from 0.5 to 10." }
if ($PSBoundParameters.ContainsKey('Threshold') -and $Threshold -lt 0) {
    throw "-Threshold $Threshold is negative; give a threshold from 0.01 to 0.5."
}
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

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

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
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
