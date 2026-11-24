# What keeps Kestrel's data and the owner's data on the test devices. On 2026-11-24 a run of connected tests let the
# Android Gradle plugin uninstall Kestrel on kestrel-api37, which deleted its Copilot profile, permissions, settings and
# the agent's memory. So scripts\gradle.ps1 reaches no device, scripts\connected-test.ps1 accepts only arguments that keep
# Kestrel installed with its data and compares the device before and after the tests, and no script deletes the profile
# that kestrel-api37 keeps. Dot-source it after scripts\lib\Kestrel.ps1; the functions about the owner's data also need
# scripts\lib\Session.ps1.

# Gradle's command line ---------------------------------------------------------------------------------------------

# The first words of the names of the Android Gradle plugin's tasks that act on a device: installs, uninstalls,
# connected (on-device) tests and device checks.
$DeviceTaskWords = @('install', 'uninstall', 'connected', 'device')

# Tells what the task that a name on Gradle's command line selects can do to a device:
#   ConnectedTest  a task that runs connected tests, by its full name: connected<...>AndroidTest or connectedCheck
#   Device         every other task that can install, uninstall or run tests on a device
#   Build          a task that cannot
# Gradle also runs the task that a name abbreviates: it compares names without regard to letter case, and camel case by
# camel case, each part of the name as the start of a word of the task's name (cDAT for connectedDebugAndroidTest, uA
# for uninstallAll), always from the start of the task's name. So a name can reach a device task only when its first
# part is the start of one of $DeviceTaskWords or the name starts with one of them, and every such name is a Device
# task, also when it could name another task too. installDist of Gradle's application plugin, which scripts\eval.ps1
# runs to build :tools:eval's copy on this computer, is a Build task.
function Get-GradleTaskKind([string]$Name) {
    # The last part of a task's path is the task's name.
    $task = ($Name -split ':')[-1]
    if ($task -cmatch '^connected(\w*AndroidTest|Check)$') { return 'ConnectedTest' }
    if ($task -ceq 'installDist' -or -not $task) { return 'Build' }
    # The first part ends before the next capital letter, digit or punctuation, as Gradle splits a name.
    $first = ([regex]::Match($task, '^.[a-z]*')).Value.ToLowerInvariant()
    $lower = $task.ToLowerInvariant()
    foreach ($word in $DeviceTaskWords) {
        if ($word.StartsWith($first) -or $lower.StartsWith($word)) { return 'Device' }
    }
    'Build'
}

# The options of Gradle 9.9.1 (gradle\wrapper\gradle-wrapper.properties) that take a value, by their long and short
# names. They are every StringBuildOption, EnumBuildOption, ListBuildOption and IntegerBuildOption that the distribution
# gives a command-line name (StartParameterBuildOptions, LoggingConfigurationBuildOptions, DaemonBuildOptions,
# ParallelismBuildOptions and BuildLayoutParametersBuildOptions), and -D and -P (SystemPropertiesCommandLineConverter
# and ProjectPropertiesCommandLineConverter), as read from gradle-9.9.1-bin; Gradle's command-line reference lists them
# too: https://docs.gradle.org/9.9.1/userguide/command_line_interface.html. Gradle 9 has no -b or -c.
$GradleValueOptions = @(
    'gradle-user-home', 'g', 'project-dir', 'p', 'project-cache-dir', 'init-script', 'I', 'include-build', 'exclude-task', 'x',
    'system-prop', 'D', 'project-prop', 'P', 'console', 'console-unicode', 'warning-mode', 'max-workers', 'priority',
    'configuration-cache-problems', 'update-locks', 'dependency-verification', 'F', 'write-verification-metadata', 'M',
    'develocity-plugin-version', 'develocity-url'
)

# The task options of Gradle 9.9.1 that take a value, each with the names of the tasks that have it, as their @Option
# annotations in gradle-9.9.1-bin declare them: --tests of the test tasks (AbstractTestTask: test, and the Android Gradle
# plugin's test<Variant>UnitTest), --task of help (Help), --group and --groups of tasks (TaskReportTask), and the options of
# wrapper (Wrapper) that are not switches, as in `wrapper --gradle-version 9.9.1 --distribution-type bin`. Gradle gives a
# task option to the task before it, so its next argument is a value only after such a task; after another task, it
# counts as a task name, which can only refuse more.
$GradleTaskValueOptions = [ordered]@{
    'tests' = '^test(\w*UnitTest)?$'
    'task' = '^help$'
    'group' = '^tasks$'
    'groups' = '^tasks$'
    'gradle-version' = '^wrapper$'
    'distribution-type' = '^wrapper$'
    'gradle-distribution-url' = '^wrapper$'
    'gradle-distribution-sha256-sum' = '^wrapper$'
    'network-timeout' = '^wrapper$'
    'retries' = '^wrapper$'
    'retry-back-off-ms' = '^wrapper$'
}

# Whether the task option -Name takes a value after the task -Task (a name on the command line, or '').
function Test-GradleTaskValueOption([string]$Name, [string]$Task) {
    $GradleTaskValueOptions.Contains($Name) -and ($Task -split ':')[-1] -cmatch $GradleTaskValueOptions[$Name]
}

# Reads Gradle's command line as Gradle 9.9.1's parser (org.gradle.cli.CommandLineParser) reads it, and returns each
# argument with its kind, for scripts\gradle.ps1:
#   Serial         --serial, the Android Gradle plugin's option that would choose the device and so pass by ANDROID_SERIAL
#   Option         every other option, with its value when the value is attached to it (--console=plain, -Pname=value)
#   Value          the value of the option before it (--tests dev.kestrel.app.X, -P name=value)
#   ExcludedTask   the task that the -x or --exclude-task before it keeps from running
#   ConnectedTest, Device or Build   a task, as Get-GradleTaskKind tells
# An option that takes a value takes the next argument unless that one starts with '-'; a short option also takes the
# rest of its argument (-xcheck, -x=check). The parser also accepts a long option after one '-' (-console plain).
# After '--', only tasks and their options follow. Where Gradle could read an argument as a task, it counts as one.
function Get-GradleArgumentKinds([string[]]$Arguments) {
    $afterOptions = $false
    $valueOf = $null
    $task = ''
    foreach ($argument in $Arguments) {
        $option = $valueOf
        $valueOf = $null
        $kind = 'Option'
        if ($option -and $argument -notmatch '^-.') {
            $kind = if ($option -cin 'exclude-task', 'x') { 'ExcludedTask' } else { 'Value' }
        } elseif ($argument -like '--serial*') {
            $kind = 'Serial'
        } elseif ($argument -notmatch '^-.') {
            $kind = Get-GradleTaskKind $argument
            $task = $argument
        } elseif ($argument -eq '--') {
            $afterOptions = $true
        } elseif ($afterOptions) {
            # A task's own option, which the task's parser reads as Gradle's parser reads Gradle's options.
            if ($argument -cmatch '^--?([^=]+)$' -and (Test-GradleTaskValueOption $Matches[1] $task)) { $valueOf = $Matches[1] }
        } elseif ($argument -cmatch '^--([^=]+)$') {
            $name = $Matches[1]
            if ($GradleValueOptions -ccontains $name -or (Test-GradleTaskValueOption $name $task)) { $valueOf = $name }
        } elseif ($argument -cmatch '^-([^-=][^=]*)$') {
            # A long option after one '-', or a short option that takes the next argument; a short option whose value
            # follows it in the same argument takes nothing more.
            if ($GradleValueOptions -ccontains $Matches[1]) { $valueOf = $Matches[1] }
        }
        [pscustomobject]@{ Argument = $argument; Kind = $kind }
    }
}

# Refuses a call of scripts\gradle.ps1 that could install, uninstall or run tests on a device, also through an
# abbreviated task name, or that chooses a device with --serial: such tasks run only through scripts\connected-test.ps1,
# which keeps Kestrel and its data, and scripts\install.ps1. A task that -x excludes is no such call.
function Assert-GradleBuildArguments([string[]]$Arguments) {
    foreach ($parsed in Get-GradleArgumentKinds -Arguments $Arguments) {
        $argument = $parsed.Argument
        switch ($parsed.Kind) {
            'Serial' { throw "Refusing the Gradle argument '$argument'. gradle.ps1 reaches no device." }
            { $_ -in 'ConnectedTest', 'Device' } {
                throw ("Refusing the Gradle task '$argument', which can install, uninstall or run tests on a device. Run device tests " +
                    'with scripts\connected-test.ps1 and install with scripts\install.ps1.')
            }
        }
    }
}

# The arguments of scripts\connected-test.ps1 ----------------------------------------------------------------------

# The Gradle properties with which scripts\connected-test.ps1 runs every connected test, so that the Android Gradle
# plugin 9.5 neither uninstalls Kestrel nor clears its data. A value on Gradle's command line overrides the same property
# in any gradle.properties file and in ORG_GRADLE_PROJECT_ variables. The plugin:
$KestrelDataProperties = [ordered]@{
    # uninstalls every APK that it installed for the tests, Kestrel among them, at the end of the run, unless this is true;
    'android.injected.androidTest.leaveApksInstalledAfterRun' = 'true'
    # with this true, uninstalls an installed APK whose signature differs from the one that it built, before the tests;
    'android.experimental.testOptions.uninstallIncompatibleApks' = 'false'
    # passes the instrumentation arguments of the command line after those of the build files, so this value overrides
    # theirs. With clearPackageData true, the test orchestrator, which Kestrel's build does not use, clears the data of
    # the app under test after each test.
    'android.testInstrumentationRunnerArguments.clearPackageData' = 'false'
}

# The flags that a caller may give scripts\connected-test.ps1. They change only what Gradle prints, whether it reuses its
# caches and whether it goes on after a failure.
$ConnectedTestFlags = @('--info', '--quiet', '-q', '--stacktrace', '--console=plain', '--offline', '--continue', '--rerun-tasks',
    '--no-configuration-cache')

# Returns the Gradle arguments of scripts\connected-test.ps1: the caller's arguments, then the properties of
# $KestrelDataProperties. It accepts only connected test tasks by their full names, instrumentation arguments
# (-Pandroid.testInstrumentationRunnerArguments.<name>=<value>), '-x <task>' or '--exclude-task <task>', the flags of
# $ConnectedTestFlags and the script's own properties as the script passes them, which it then passes once. It refuses
# every other argument: one that sets a property of $KestrelDataProperties otherwise, and also Gradle's other options,
# such as an init script, another project folder or another Gradle user home, which could change what the plugin does.
function Get-ConnectedTestArguments([string[]]$Arguments) {
    $own = @(foreach ($name in $KestrelDataProperties.Keys) { "-P$name=$($KestrelDataProperties[$name])" })
    $passed = [System.Collections.Generic.List[string]]::new()
    for ($index = 0; $index -lt $Arguments.Count; $index++) {
        $argument = $Arguments[$index]
        if ($own -ccontains $argument) { continue }
        foreach ($name in $KestrelDataProperties.Keys) {
            if ($argument -like "*$name*") {
                throw ("Refusing the Gradle argument '$argument'. connected-test.ps1 itself sets $name=$($KestrelDataProperties[$name]), " +
                    'so that the run keeps Kestrel and its data.')
            }
        }
        if ($argument -like '--serial*') { throw "Refusing the Gradle argument '$argument'. Choose the device with -Serial." }
        if ($argument -cin '-x', '--exclude-task') {
            $excluded = if ($index + 1 -lt $Arguments.Count) { $Arguments[$index + 1] } else { '' }
            if (-not $excluded -or $excluded -like '-*') { throw "Refusing the Gradle argument '$argument' without the task that it excludes." }
            $passed.Add($argument)
            $passed.Add($excluded)
            $index++
            continue
        }
        $accepted = $ConnectedTestFlags -ccontains $argument -or $argument -cmatch '^-Pandroid\.testInstrumentationRunnerArguments\.\w+=' -or
            ($argument -notlike '-*' -and (Get-GradleTaskKind $argument) -eq 'ConnectedTest')
        if (-not $accepted) {
            throw ("Refusing the Gradle argument '$argument'. connected-test.ps1 accepts only connected test tasks, such as " +
                ':app:connectedDebugAndroidTest, instrumentation arguments (-Pandroid.testInstrumentationRunnerArguments.<name>=<value>), ' +
                "-x <task>, --exclude-task <task> and the flags $($ConnectedTestFlags -join ', ').")
        }
        $passed.Add($argument)
    }
    @($passed) + $own
}

# The packages on the device ----------------------------------------------------------------------------------------

# The packages that Gradle installs for connected tests besides Kestrel (dev.kestrel), which matches none of them: the
# fixture app of :testapp, and the test APKs, which Gradle names after the app or the library module that they test,
# such as dev.kestrel.test and dev.kestrel.privacy.test. Each is a pattern of PowerShell's -like, in which '*' stands for
# any text, as it does in a 'case' of the device's shell.
$KestrelTestPackages = @('dev.kestrel.testapp', 'dev.kestrel.test', 'dev.kestrel.*.test')

function Test-KestrelTestPackage([string]$Name) {
    @($KestrelTestPackages | Where-Object { $Name -like $_ }).Count -gt 0
}

# Returns the names of the installed packages whose names contain 'dev.kestrel', as 'pm list packages' lists them.
function Get-KestrelPackageNames([string]$Serial) {
    @(Invoke-SettingsCommand -Serial $Serial -Command 'pm list packages dev.kestrel' | ForEach-Object { "$_".Trim() -replace '^package:', '' } | Where-Object { $_ })
}

# Returns the first-install times in a dump of 'dumpsys package', such as 'user 0: 2026-11-20 10:11:12', of the users
# that have the package installed. Since Android 14 the dump shows a time for every user, also one without the package,
# whose time an update can change; an older dump shows one time for the package, before its users.
function Get-FirstInstallTime([string[]]$Dump) {
    $user = $null
    $installed = $true
    @(foreach ($line in $Dump) {
            if ($line -match '^\s*User (\d+):') {
                $user = $Matches[1]
                $installed = $line -notmatch '\binstalled=false\b'
            } elseif ($line -match '^\s*firstInstallTime=(.+)$' -and $installed) {
                $time = $Matches[1].Trim()
                if ($null -eq $user) { $time } else { "user ${user}: $time" }
            }
        })
}

# Reads what scripts\connected-test.ps1 compares before and after the tests: Kestrel's first-install times
# (Get-FirstInstallTime), or $null when Kestrel is not installed, and the installed packages that match
# $KestrelTestPackages. It throws when it cannot read them.
function Get-KestrelPackageState([string]$Serial) {
    $names = Get-KestrelPackageNames -Serial $Serial
    $installTimes = $null
    if ($names -contains 'dev.kestrel') {
        $installTimes = @(Get-FirstInstallTime -Dump (Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys package dev.kestrel'))
        if ($installTimes.Count -eq 0) { throw "dumpsys package dev.kestrel on $Serial shows no firstInstallTime." }
    }
    [pscustomobject]@{
        KestrelInstallTimes = $installTimes
        TestPackages = @($names | Where-Object { Test-KestrelTestPackage $_ })
    }
}

# Returns a message when Kestrel was installed before the tests (-Before) and is now missing or was installed again
# (-After), both from Get-KestrelPackageState, else $null. Android deletes an app's data when it uninstalls the app, and an
# app that is installed again gets a new first-install time, while 'adb install -r' keeps it. The first-install time
# shows nothing else: it stays the same after 'pm clear', and after a test deletes Kestrel's own stores in its process.
function Get-KestrelInstallChange($Before, $After, [string]$Serial) {
    if ($null -eq $Before.KestrelInstallTimes) { return $null }
    $lost = 'which deleted its data there: its AI profiles, permissions, settings and the agent''s memory.'
    if ($null -eq $After.KestrelInstallTimes) { return "Kestrel (dev.kestrel) was uninstalled from $Serial during the tests, $lost" }
    $then = $Before.KestrelInstallTimes -join ', '
    $now = $After.KestrelInstallTimes -join ', '
    if ($then -cne $now) {
        return "Kestrel (dev.kestrel) was installed again on $Serial during the tests (first installed $then before them, $now now), $lost"
    }
    $null
}

# Uninstalls each package of -Packages, which must match $KestrelTestPackages, and returns whether none of them is still
# installed. For each one that stays, it warns with the reason and the command that uninstalls it by hand.
function Remove-TestPackages([string]$Serial, [string[]]$Packages) {
    $gone = $true
    foreach ($package in $Packages) {
        if (-not (Test-KestrelTestPackage $package)) { throw "$package is not a test package of Kestrel." }
        $reason = ''
        $removed = try {
            Invoke-SettingsCommand -Serial $Serial -Command "pm uninstall $package" | Out-Null
            (Get-KestrelPackageNames -Serial $Serial) -notcontains $package
        } catch {
            $reason = " ($($_.Exception.Message))"
            $false
        }
        if (-not $removed) {
            Write-Warning "Could not uninstall $package from $Serial$reason. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial uninstall $package"
            $gone = $false
        }
    }
    $gone
}

# Returns a command for the device's shell that uninstalls every package of $KestrelTestPackages but those of -Keep, the
# ones that were installed before the tests. scripts\connected-test.ps1 gives it in its warnings when it could not do
# that itself, because it then does not know which packages the tests installed.
function Format-TestPackageRemoval([string[]]$Keep) {
    $kept = if ($Keep) { "$($Keep -join '|')) ;; " } else { '' }
    'for p in $(pm list packages dev.kestrel); do case ${p#package:} in ' + $kept + "$($KestrelTestPackages -join '|')) pm uninstall `${p#package:};; esac; done"
}

# Ends a run of connected tests on the device's packages, after Get-KestrelPackageState gave -Before: writes an error
# when Kestrel was uninstalled or installed again (Get-KestrelInstallChange), and uninstalls each test package that the
# run added. Returns whether Kestrel is still the installed app of before and every added test package is gone. When it
# cannot read the packages, it warns with the command that removes the added test packages by hand.
function Complete-TestPackages([string]$Serial, $Before) {
    $after = try {
        Get-KestrelPackageState -Serial $Serial
    } catch {
        Write-Warning ("Could not read the packages of $Serial ($($_.Exception.Message)), so the script could not check that Kestrel " +
            "was not uninstalled or installed again, nor uninstall the test packages. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell " +
            "'$((Format-TestPackageRemoval -Keep $Before.TestPackages) -replace "'", "''")'")
        return $false
    }
    $right = $true
    $change = Get-KestrelInstallChange -Before $Before -After $after -Serial $Serial
    if ($change) {
        Write-Error -Message $change -ErrorAction Continue
        $right = $false
    }
    $added = @($after.TestPackages | Where-Object { $Before.TestPackages -notcontains $_ })
    if (-not (Remove-TestPackages -Serial $Serial -Packages $added)) { $right = $false }
    $right
}

# The owner's data on kestrel-api37 ---------------------------------------------------------------------------------

# Whether the serial is kestrel-api37, which holds the owner's own Google account and Kestrel's Copilot profile, which no
# script may sign in, remove or clear there (AGENTS.md). Assert-TestDevice must have accepted the serial.
function Test-OwnerDataDevice([string]$Serial) {
    $device = if ($KestrelTestDevices) { $KestrelTestDevices[$Serial] }
    if (-not $device) { throw "Run Assert-TestDevice -Serial $Serial before asking whether it holds the owner's data." }
    $device.Pattern -eq 'kestrel-api37'
}

# Refuses a debug hook that deletes data that kestrel-api37 keeps: provider-delete, which deletes every Copilot profile
# with its credential. scripts\hook.ps1 asks before it sends a command.
function Assert-OwnerDataCommand([string]$Serial, [string]$Command) {
    if ($Command -eq 'provider-delete' -and (Test-OwnerDataDevice -Serial $Serial)) {
        throw "provider-delete was refused on ${Serial}: kestrel-api37 keeps Kestrel's Copilot profile and its credential (AGENTS.md)."
    }
}

# The number of accounts of the type in 'dumpsys account', such as com.google or Telegram's org.telegram.messenger, which
# Telegram adds while it is signed in; from the type only, never a name.
function Get-AccountCount([string[]]$Dump, [string]$Type) {
    @($Dump | Where-Object { $_ -match "^\s*Account \{.*type=$([regex]::Escape($Type))\}" }).Count
}

# The number of Google accounts in 'dumpsys account', from the type only, never a name.
function Get-GoogleAccountCount([string[]]$Dump) { Get-AccountCount $Dump 'com.google' }

# The answer of provider-standin, such as 'standin=off profile=copilot copilot=1 id=profile-1 credential=present', as an
# ordered table: whether the stand-in runs, the kind of the default profile, the number of Copilot profiles, the default
# profile's ID, which Kestrel makes at random and which holds nothing secret (none without a default profile), and
# whether its credential is stored (present, missing, or none for a profile without one).
function ConvertFrom-StandInAnswer([string]$Text) {
    $pairs = ConvertFrom-HookPairs $Text
    if (-not $pairs.Contains('standin') -or -not $pairs.Contains('profile') -or "$($pairs['copilot'])" -notmatch '^\d+$' -or
        -not $pairs['id'] -or "$($pairs['credential'])" -notin 'present', 'missing', 'none') {
        throw "Not an answer of provider-standin: '$Text'. Install the current debug build with scripts\install.ps1."
    }
    $pairs
}

# Reads Kestrel's profiles on the device through the debug hook provider-standin, which changes nothing: the kind of the
# default profile (none, copilot or other), the number of Copilot profiles, the default profile's ID and whether its
# credential is stored (ConvertFrom-StandInAnswer). Android gives no hook broadcast to a package that it stopped, as
# 'am force-stop' leaves Kestrel, so the package is unstopped first ('pm unstop', which starts nothing; the broadcast then
# starts Kestrel's process). scripts\hook.ps1 needs PowerShell 7.3, so it runs in pwsh. Throws when Kestrel does not
# answer.
function Get-KestrelProfiles([string]$Serial) {
    try { Invoke-SettingsCommand -Serial $Serial -Command 'pm unstop dev.kestrel' | Out-Null } catch { Write-Verbose "pm unstop failed: $($_.Exception.Message)" }
    $pwsh = Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $pwsh) { throw 'PowerShell 7 (pwsh), which scripts\hook.ps1 needs, is not installed' }
    $hook = Join-Path $PSScriptRoot '..\hook.ps1'
    $run = Invoke-TimedProcess -FilePath $pwsh.Source -TimeoutSeconds 60 -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $hook,
        '-Serial', $Serial, '-Command', 'provider-standin')
    if (-not $run.Completed) { throw 'scripts\hook.ps1 provider-standin did not end within 60 s' }
    if ($run.ExitCode -ne 0) { throw "scripts\hook.ps1 provider-standin ended with exit code $($run.ExitCode)" }
    $answer = ConvertFrom-StandInAnswer $run.Output
    [pscustomobject]@{ Profile = "$($answer['profile'])"; Copilot = [int]$answer['copilot']; Id = "$($answer['id'])"; Credential = "$($answer['credential'])" }
}

# Reads, and changes nothing, what AGENTS.md asks to compare before and after a run on kestrel-api37: the number of
# Google accounts in 'dumpsys account', and Kestrel's profiles (Get-KestrelProfiles), or, in ProfilesUnread, why they
# could not be read, such as a Kestrel that is not installed. Throws when it cannot read the accounts.
function Get-OwnerDataState([string]$Serial) {
    $accounts = Get-GoogleAccountCount @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys account')
    $profiles = $null
    $unread = $null
    try { $profiles = Get-KestrelProfiles -Serial $Serial } catch { $unread = $_.Exception.Message }
    [pscustomobject]@{ GoogleAccounts = $accounts; Profiles = $profiles; ProfilesUnread = $unread }
}

# Returns a message for each change of the owner's data between two answers of Get-OwnerDataState, during -During, such
# as 'the tests'. Profiles that could not be read before are not compared; profiles that were read before and cannot be
# read after count as a change, because they may be gone. :tools:eval's AccountGuard compares the same fields in the same
# way (tools\eval\src\main\kotlin\dev\kestrel\eval\run\AccountGuard.kt).
function Get-OwnerDataChange($Before, $After, [string]$Serial, [string]$During) {
    if ($After.GoogleAccounts -ne $Before.GoogleAccounts) {
        "The number of Google accounts on $Serial changed during ${During}: $($Before.GoogleAccounts) before, $($After.GoogleAccounts) now."
    }
    if (-not $Before.Profiles) { return }
    if (-not $After.Profiles) {
        "Kestrel's profiles on $Serial could not be read after ${During} ($($After.ProfilesUnread)), so its Copilot profile may be gone."
        return
    }
    if ($After.Profiles.Profile -ne $Before.Profiles.Profile) {
        "Kestrel's default profile on $Serial changed during ${During}: $($Before.Profiles.Profile) before, $($After.Profiles.Profile) now."
    }
    if ($After.Profiles.Copilot -ne $Before.Profiles.Copilot) {
        "The number of Kestrel's Copilot profiles on $Serial changed during ${During}: $($Before.Profiles.Copilot) before, $($After.Profiles.Copilot) now."
    }
    if ($After.Profiles.Id -cne $Before.Profiles.Id) {
        "Kestrel's default profile on $Serial was replaced during ${During}: $($Before.Profiles.Id) before, $($After.Profiles.Id) now."
    }
    if ($After.Profiles.Credential -ne $Before.Profiles.Credential) {
        "The credential of Kestrel's default profile on $Serial changed during ${During}: $($Before.Profiles.Credential) before, $($After.Profiles.Credential) now."
    }
}

# Describes an answer of Get-OwnerDataState in one line, with counts, kinds and states only.
function Format-OwnerDataState($State, [string]$Serial) {
    $profiles = if ($State.Profiles) {
        "Kestrel's default profile is $($State.Profiles.Profile) with its credential $($State.Profiles.Credential), and it has $($State.Profiles.Copilot) Copilot profiles"
    } else {
        "Kestrel's profiles could not be read ($($State.ProfilesUnread))"
    }
    "$Serial has $($State.GoogleAccounts) Google accounts; $profiles"
}

# Reads the owner's data on kestrel-api37 again and compares it with -Before, from Get-OwnerDataState. Returns a message
# for each change (Get-OwnerDataChange), or one that says that the accounts could not be read; nothing when all is as
# before. It writes nothing, so that each script reports the messages in its own way.
function Compare-OwnerData([string]$Serial, $Before, [string]$During) {
    $after = try {
        Get-OwnerDataState -Serial $Serial
    } catch {
        "The Google accounts of $Serial could not be read after ${During} ($($_.Exception.Message)), so the owner's data there was not checked."
        return
    }
    Get-OwnerDataChange -Before $Before -After $after -Serial $Serial -During $During
}
