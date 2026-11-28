# Tests of scripts\lib\HookCall.ps1, the hooks' transport that scripts\hook.ps1 uses. scripts\test-scripts.ps1 runs these
# tests in Windows PowerShell 5.1 and in PowerShell 7. The outputs are real ones of 'content call' on kestrel-api37 (plan
# 7G's amendment "The hooks' transport"), captured once with this build's TestHookProvider.

Describe 'The hooks'' transport' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\HookCall.ps1"
    }

    It 'writes a hook as content call with its typed extras, quoted for the device''s shell' {
        $extras = @(
            (Format-HookExtra -Name do -Type s -Value tap),
            (Format-HookExtra -Name seconds -Type i -Value 5),
            (Format-HookExtra -Name live -Type b -Value true),
            (Format-HookExtra -Name boost -Type f -Value 1.5)
        )

        Format-HookCall -Command goal-control -Extras $extras |
            Should -BeExactly "content call --uri content://dev.kestrel.hooks --method GOAL_CONTROL --extra 'do:s:tap' --extra 'seconds:i:5' --extra 'live:b:true' --extra 'boost:f:1.5'"
        Format-HookCall -Command network-allowlist | Should -BeExactly 'content call --uri content://dev.kestrel.hooks --method NETWORK_ALLOWLIST'
    }

    It 'escapes \ and : in a value as content reads them, and quotes '' for the shell' {
        # On kestrel-api37, voice-state with this extra answered 'error: no transcript has the tag it''s a:b\c'.
        Format-HookExtra -Name tag -Type s -Value "it's a:b\c" | Should -BeExactly "--extra 'tag:s:it'\''s a\:b\\c'"
        Format-HookExtra -Name text -Type s -Value '' | Should -BeExactly "--extra 'text:s:'"
    }

    It 'reads an answer and an error''s answer' {
        $state = ConvertFrom-HookCallOutput "Result: Bundle[{code=0, text=standin=off profile=copilot copilot=1 id=profile-b568f281-684a-42db-8f75-405ccca77931 credential=present}]`n"
        $allowlist = ConvertFrom-HookCallOutput 'Result: Bundle[{code=0, text=hosts=api.business.githubcopilot.com,api.github.com modelHosts=*.hf.co,*.huggingface.co,*.modelscope.cn,github.com,huggingface.co,objects.githubusercontent.com,raw.githubusercontent.com,release-assets.githubusercontent.com}]'
        $refused = ConvertFrom-HookCallOutput 'Result: Bundle[{code=1, text=error: add --es do with one of tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed}]'

        $state.Code | Should -Be 0
        $state.Text | Should -BeExactly 'standin=off profile=copilot copilot=1 id=profile-b568f281-684a-42db-8f75-405ccca77931 credential=present'
        $allowlist.Text | Should -BeLike 'hosts=api.business.githubcopilot.com,api.github.com modelHosts=*,release-assets.githubusercontent.com'
        $refused.Code | Should -Be 1
        $refused.Text | Should -BeExactly 'error: add --es do with one of tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed'
    }

    It 'keeps a text that holds the format''s own marks and line breaks' {
        $marks = ConvertFrom-HookCallOutput "Result: Bundle[{code=1, text=error: unknown command 'X}], code=0, text=Y'}]`n"
        $lines = ConvertFrom-HookCallOutput "Result: Bundle[{code=1, text=error: unknown command 'LINE1`nLINE2'}]`n"

        $marks.Code | Should -Be 1
        $marks.Text | Should -BeExactly "error: unknown command 'X}], code=0, text=Y'"
        $lines.Text | Should -BeExactly "error: unknown command 'LINE1`nLINE2'"
    }

    It 'says that Kestrel has no hooks when content finds no provider, and names any other output' {
        $missing = "Error while accessing provider:dev.kestrel.hooks`njava.lang.IllegalStateException: Could not find provider: dev.kestrel.hooks`n" +
            "`tat com.android.commands.content.Content`$Command.execute(Content.java:519)`n`tat com.android.commands.content.Content.main(Content.java:735)"

        { ConvertFrom-HookCallOutput $missing } | Should -Throw 'Kestrel did not answer: it has no test hooks. Install a debug build with scripts\install.ps1.' -ErrorId KestrelHookSilent
        { ConvertFrom-HookCallOutput 'Result: null' } | Should -Throw "The hook's call was lost:*Result: null" -ErrorId KestrelHookLost
    }

    # Plan 7G's review of the hooks' transport, I2: only code 1 is Kestrel's refusal; a call that failed on its way, as when
    # Kestrel's process ended during it ('kill -9' during WATCH), is lost, and a refused caller ('run-as dev.kestrel content
    # call') is a failure of its own.
    It 'tells a call that Kestrel''s process did not live to answer and a refused caller from Kestrel''s own refusal' {
        $dead = "Error while accessing provider:dev.kestrel.hooks`nandroid.os.DeadObjectException`n" +
            "`tat android.os.BinderProxy.transactNative(Native Method)`n`tat android.os.BinderProxy.transact(BinderProxy.java:626)`n" +
            "`tat android.content.ContentProviderProxy.call(ContentProviderNative.java:764)`n" +
            "`tat com.android.commands.content.Content`$CallCommand.onExecute(Content.java:599)`n" +
            "`tat com.android.commands.content.Content`$Command.execute(Content.java:522)`n`tat com.android.commands.content.Content.main(Content.java:735)"
        $refused = "Error while accessing provider:dev.kestrel.hooks`n" +
            "java.lang.SecurityException: Permission Denial: Do not have permission in call getContentProviderExternal() from pid=4343, uid=10353 requires android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY`n" +
            "`tat android.os.Parcel.createExceptionOrNull(Parcel.java:3392)`n" +
            "`tat com.android.commands.content.Content`$Command.execute(Content.java:516)`n" +
            "Caused by: android.os.RemoteException: Remote stack trace:`n" +
            "`tat com.android.server.am.ActivityManagerService.enforceCallingPermission(ActivityManagerService.java:6997)"

        { ConvertFrom-HookCallOutput $dead } | Should -Throw "The hook's call was lost:*Kestrel may have acted*android.os.DeadObjectException*" -ErrorId KestrelHookLost
        { ConvertFrom-HookCallOutput $refused } |
            Should -Throw "The hook's caller was refused: java.lang.SecurityException: Permission Denial:*ACCESS_CONTENT_PROVIDERS_EXTERNALLY. Only adb's shell (uid 2000) and root (uid 0) may use Kestrel's test hooks." -ErrorId KestrelHookCallerRefused
        { ConvertFrom-HookCallOutput 'Result: Bundle[{code=2, text=x}]' } | Should -Throw "The hook's call was lost: its answer had the unknown code 2.*" -ErrorId KestrelHookLost
    }
}
