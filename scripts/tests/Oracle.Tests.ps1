# Tests of scripts\lib\Oracle.ps1, which reads Android's dumps for the A71 sessions' automatic checks. The dumps are cut
# down from Android 13's formats. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Oracle.ps1"
}

Describe 'The readers of Android''s dumps' {
    It 'finds the package in front, the home app and whether the keyguard shows' {
        $activities = @(
            'Display #0 (activities from top to bottom):'
            '  * Task{6c1 #12 type=standard A=1000:com.android.settings}'
            '  topResumedActivity=ActivityRecord{9f3e2b1 u0 com.android.settings/.Settings t12}'
            'KeyguardController:'
            '  mKeyguardShowing=false'
        )

        Get-ResumedPackage $activities | Should -BeExactly 'com.android.settings'
        Get-ResumedPackage @('  mResumedActivity: ActivityRecord{1 u0 com.sec.android.app.launcher/.activities.LauncherActivity t1}') | Should -BeExactly 'com.sec.android.app.launcher'
        Get-ResumedPackage @('  topResumedActivity=null') | Should -BeNullOrEmpty
        Test-KeyguardShowing $activities | Should -BeFalse
        Test-KeyguardShowing @('  mKeyguardShowing=true') | Should -BeTrue
        Test-KeyguardShowing @() | Should -BeNullOrEmpty
        Get-HomePackage @('priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true', 'com.sec.android.app.launcher/com.android.launcher3.uioverrides.QuickstepLauncher') | Should -BeExactly 'com.sec.android.app.launcher'
        Get-HomePackage @('No activity found') | Should -BeNullOrEmpty
    }

    It 'reads the power state, the keyboard and the focused window' {
        Get-Wakefulness @('POWER MANAGER (dumpsys power)', '  mWakefulness=Asleep', '  mWakefulnessChanging=false') | Should -BeExactly 'Asleep'
        Test-InputShown @('  mShowRequested=true mShowExplicitlyRequested=false', '  mInputShown=true') | Should -BeTrue
        Test-InputShown @('  mInputShown=false') | Should -BeFalse
        Test-InputShown @() | Should -BeNullOrEmpty
        Get-FocusedWindow @('  mCurrentFocus=Window{4a8 u0 com.android.settings/com.android.settings.Settings}') | Should -BeExactly 'com.android.settings'
        Get-FocusedWindow @('  mCurrentFocus=Window{77 u0 NotificationShade}') | Should -BeExactly 'NotificationShade'
        Get-FocusedWindow @('  mCurrentFocus=null') | Should -BeNullOrEmpty
    }

    It 'reads the playback state of one package''s media session, and the muted streams' {
        $sessions = @(
            '  Sessions Stack - have 2 sessions:'
            '    music 41 (userId=0)'
            '      ownerPid=1234, ownerUid=10200, userId=0'
            '      package=com.example.music'
            '      state=PlaybackState {state=3, position=1000, buffered position=0, speed=1.0}'
            '    fixture 42 (userId=0)'
            '      ownerPid=2345, ownerUid=10334, userId=0'
            '      package=dev.kestrel'
            '      state=PlaybackState {state=2, position=0, buffered position=0, speed=0.0}'
        )
        $audio = @(
            'Stream volumes (device: index)'
            '- STREAM_RING:'
            '   Muted: true'
            '- STREAM_MUSIC:'
            '   Muted: false'
            '   Muted Internally: false'
        )

        Get-PlaybackState -Dump $sessions -Package 'dev.kestrel' | Should -Be 2
        Get-PlaybackState -Dump $sessions -Package 'com.example.music' | Should -Be 3
        Get-PlaybackState -Dump $sessions -Package 'com.example.other' | Should -BeNullOrEmpty
        Get-PlaybackState -Dump @('    package=dev.kestrel', '      state=PlaybackState {state=PAUSED(2), position=0}') -Package 'dev.kestrel' | Should -Be 2
        Test-StreamMuted -Dump $audio | Should -BeFalse
        Test-StreamMuted -Dump $audio -Stream STREAM_RING | Should -BeTrue
        Test-StreamMuted -Dump $audio -Stream STREAM_ALARM | Should -BeNullOrEmpty
    }

    It 'hashes a text as the hooks do, and finds a fixed sentence among a command''s hashes' {
        Get-TextHash 'abc' | Should -BeExactly 'ba7816bf8f01cfea'
        $said = "1111111111111111,$(Get-TextHash 'Cancelled')"

        Test-HashedText -Hashes $said -Text 'Cancelled' | Should -BeTrue
        Test-HashedText -Hashes $said -Text 'cancelled' | Should -BeFalse
        Test-HashedText -Hashes 'none' -Text 'Cancelled' | Should -BeFalse
    }
}
