# Tests of the helpers in scripts\lib\Kestrel.ps1 that scripts\command-test.ps1 uses to read the device's state and to
# choose the tests. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.

Describe 'Get-StreamVolume' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'reads the index from the answer of cmd media_session volume --get' {
        Get-StreamVolume -Lines @('[V] will get volume', '[V] stream=3', '[V] volume is 7 in range [0..25]') | Should -Be 7
    }

    It 'throws when the answer has no volume' {
        { Get-StreamVolume -Lines @('Unknown command') } | Should -Throw -ExpectedMessage 'Not an answer of cmd media_session volume: *'
    }
}

Describe 'Get-UserIdsNamed' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the IDs of the users with the name' {
        $lines = @('Users:', '	UserInfo{0:Owner:c13} running', '	UserInfo{10:KestrelTest:1030}', '	UserInfo{11:KestrelTest:1030}')

        Get-UserIdsNamed -Lines $lines -Name 'KestrelTest' | Should -Be @(10, 11)
    }

    It 'returns nothing when no user has the name' {
        @(Get-UserIdsNamed -Lines @('Users:', '	UserInfo{0:Owner:c13} running') -Name 'KestrelTest').Count | Should -Be 0
    }
}

Describe 'Get-GrantedPermissions' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        $script:dump = @(
            '    install permissions:',
            '      android.permission.INTERNET: granted=true',
            '    User 0: ceDataInode=1 installed=true',
            '      runtime permissions:',
            '        android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET ]',
            '        android.permission.READ_CONTACTS: granted=false, flags=[ ]',
            '        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]'
        )
    }

    It 'returns the permissions of the list that are granted' {
        $asked = @('android.permission.RECORD_AUDIO', 'android.permission.READ_CONTACTS', 'android.permission.POST_NOTIFICATIONS', 'android.permission.READ_PHONE_STATE')

        Get-GrantedPermissions -Dump $script:dump -Permissions $asked | Should -Be @('android.permission.RECORD_AUDIO', 'android.permission.POST_NOTIFICATIONS')
    }

    It 'returns nothing for a package that is not installed' {
        @(Get-GrantedPermissions -Dump @('Unable to find package: dev.kestrel') -Permissions @('android.permission.RECORD_AUDIO')).Count | Should -Be 0
    }
}

Describe 'Get-CommandTestSelection' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'selects the whole package without classes' {
        Get-CommandTestSelection -Class @() | Should -BeExactly 'package=dev.kestrel.app.commands'
    }

    It 'selects classes and methods of the package by their simple names' {
        Get-CommandTestSelection -Class @('TouchCommandsTest', 'GestureCommandsTest#swipeUpOnANumber') |
            Should -BeExactly 'class=dev.kestrel.app.commands.TouchCommandsTest,dev.kestrel.app.commands.GestureCommandsTest#swipeUpOnANumber'
    }

    It 'refuses a name that is no class name: <_>' -ForEach @('dev.kestrel.app.voice.VoiceHookTest', 'Touch;rm', '', '#method') {
        { Get-CommandTestSelection -Class @($_) } | Should -Throw -ExpectedMessage 'Not a test class of dev.kestrel.app.commands: *'
    }
}
