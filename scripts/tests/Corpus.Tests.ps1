# Tests of scripts\lib\Corpus.ps1, which scripts\make-corpus.ps1, scripts\voice-eval.ps1 and scripts\a71-session.ps1 use.
# scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'Read-Corpus' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"

        # Writes a corpus file with these lines, and returns its path.
        function New-CorpusFile([string[]]$Lines) {
            $path = Join-Path $TestDrive 'commands.txt'
            [IO.File]::WriteAllText($path, ($Lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
            $path
        }
    }

    It 'reads the repository''s corpus' {
        $rows = @(Read-Corpus -Path "$PSScriptRoot\..\..\corpus\commands.txt")

        $rows.Count | Should -Be 315
        @($rows | Where-Object { $_.Owner }).Count | Should -Be 160
        @($rows | Select-Object -ExpandProperty Id -Unique).Count | Should -Be 315
        $rows[0].Text | Should -BeExactly 'Open Chrome.'
        @($rows | Where-Object { $_.Context -eq 'answer' }).Count | Should -Be 12
        @($rows | Where-Object { $_.Group -eq 'conversation' }).Count | Should -Be 20
        @($rows | Where-Object { $_.Group -eq 'dictation' }).Count | Should -Be 5
    }

    It 'reads the columns, and skips comments and empty lines' {
        $rows = @(Read-Corpus -Path (New-CorpusFile @('# a comment', '', 'x1 | own | answer | 1 | StopNamed | 0 | Never mind, Kestrel.')))

        $rows.Count | Should -Be 1
        $rows[0].Id | Should -BeExactly 'x1'
        $rows[0].Group | Should -BeExactly 'own'
        $rows[0].Context | Should -BeExactly 'answer'
        $rows[0].Spotted | Should -BeTrue
        $rows[0].Expected | Should -BeExactly 'StopNamed'
        $rows[0].Owner | Should -BeFalse
        $rows[0].Text | Should -BeExactly 'Never mind, Kestrel.'
    }

    It 'refuses a line that it cannot read: <_>' -ForEach @(
        'x1 | own | idle | 0 | Stop | 1',
        'x/1 | own | idle | 0 | Stop | 1 | Stop.',
        'x1 | own | ringing | 0 | Stop | 1 | Stop.',
        'x1 | own | idle | 2 | Stop | 1 | Stop.',
        'x1 | own | idle | 0 | Halt | 1 | Stop.',
        'x1 | own | idle | 0 | Stop | 1 | '
    ) {
        { Read-Corpus -Path (New-CorpusFile @($_)) } | Should -Throw '*commands.txt:1:*'
    }

    It 'refuses an id that is listed twice' {
        $line = 'x1 | own | idle | 0 | Stop | 1 | Stop.'

        { Read-Corpus -Path (New-CorpusFile @($line, $line)) } | Should -Throw '*listed twice*'
    }
}

Describe 'Read-CorpusScenes' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"

        # Writes a scenes file with these lines, and returns its path.
        function New-ScenesFile([string[]]$Lines) {
            $path = Join-Path $TestDrive 'scenes.txt'
            [IO.File]::WriteAllText($path, ($Lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
            $path
        }
    }

    It 'gives each line the labels of its scene, and skips comments and empty lines' {
        $lines = Read-CorpusScenes -Path (New-ScenesFile @('# a comment', '', 'scene dialog | Cancel; OK ;; Delete', 'line c129 | dialog', 'line x1 | dialog'))

        $lines.Count | Should -Be 2
        $lines['c129'] | Should -Be @('Cancel', 'OK', 'Delete')
        $lines['x1'] | Should -Be @('Cancel', 'OK', 'Delete')
    }

    It 'refuses a line that it cannot read: <_>' -ForEach @(
        'scene | Cancel',
        'scene dialog |  ; ',
        'line c129 | nowhere',
        'screen dialog | Cancel'
    ) {
        { Read-CorpusScenes -Path (New-ScenesFile @($_)) } | Should -Throw '*scenes.txt:1:*'
    }

    It 'refuses a scene or a line that is listed twice' {
        { Read-CorpusScenes -Path (New-ScenesFile @('scene s | a', 'scene s | b')) } | Should -Throw '*listed twice*'
        { Read-CorpusScenes -Path (New-ScenesFile @('scene s | a', 'line x1 | s', 'line x1 | s')) } | Should -Throw '*listed twice*'
    }

    It 'reads the contacts, which no line gets as labels' {
        $path = New-ScenesFile @('# contacts | Not; Read', 'scene dialog | Cancel', "contacts | Ana Petrovi$([char]0x0107) ; Mom;", 'line c129 | dialog')

        @(Read-CorpusContacts -Path $path) | Should -Be @("Ana Petrovi$([char]0x0107)", 'Mom')
        (Read-CorpusScenes -Path $path)['c129'] | Should -Be @('Cancel')
        @(Read-CorpusContacts -Path (New-ScenesFile @('scene dialog | Cancel'))) | Should -BeNullOrEmpty
    }

    It 'refuses contacts that are listed twice or have no names' {
        { Read-CorpusContacts -Path (New-ScenesFile @('contacts | Mom', 'contacts | Ana')) } | Should -Throw '*scenes.txt:2:*listed twice*'
        { Read-CorpusContacts -Path (New-ScenesFile @('contacts |  ; ')) } | Should -Throw '*scenes.txt:1:*need names*'
    }

    It 'reads the repository''s scenes, whose lines are lines of the corpus' {
        $ids = @(Read-Corpus -Path "$PSScriptRoot\..\..\corpus\commands.txt" | Select-Object -ExpandProperty Id)
        $lines = Read-CorpusScenes -Path "$PSScriptRoot\..\..\corpus\scenes.txt"

        $lines.Count | Should -Be 21
        @($lines.Keys | Where-Object { $_ -notin $ids }) | Should -BeNullOrEmpty
        $lines['c020'] | Should -Contain 'Send'
        @(Read-CorpusContacts -Path "$PSScriptRoot\..\..\corpus\scenes.txt") | Should -Be @("Ana Petrovi$([char]0x0107)", 'Mom')
    }
}

Describe 'Get-ClipSeconds' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"
    }

    It 'gives a short utterance 4 s, a long one more, and none more than 15 s' {
        Get-ClipSeconds -Text 'Stop.' | Should -Be 4
        Get-ClipSeconds -Text 'Kestrel, send a message to Ana on WhatsApp saying I''m running late.' | Should -Be 8
        Get-ClipSeconds -Text (@('word') * 40 -join ' ') | Should -Be 15
    }
}

Describe 'The evaluation''s answers and totals' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"
    }

    It 'reads the hook''s answer, and keeps the spaces of what Kestrel heard' {
        $answer = ConvertFrom-HookAnswer 'words=3 substitutions=0 deletions=1 insertions=0 request=ok read=Task millis=231 seconds=2.3 pss=953 twice=0 heard=open the  settings'

        $answer.words | Should -Be '3'
        $answer.deletions | Should -Be '1'
        $answer.read | Should -BeExactly 'Task'
        $answer.heard | Should -BeExactly 'open the  settings'
    }

    It 'refuses a text that is not such an answer, without repeating it' {
        { ConvertFrom-HookAnswer 'loading' } | Should -Throw 'Not an answer of the evaluation hook.'
    }

    It 'adds up the answers into the totals that it prints' {
        $totals = New-EvalTotals
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=3 substitutions=0 deletions=0 insertions=0 request=ok read=Name millis=200 seconds=2.0 pss=900 twice=0')
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=2 substitutions=1 deletions=0 insertions=1 request=wrong read=Other millis=400 seconds=1.0 pss=950 twice=1')
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=5 substitutions=0 deletions=0 insertions=0 request=skipped read=- millis=300 seconds=3.0 pss=940 twice=0')

        Format-EvalSummary $totals | Should -Be @(
            'Clips: 3; words: 10; word error rate: 20.0 % (1 substitutions, 0 deletions, 1 insertions)'
            'Requests read as intended: 1 of 2 (50.0 %)'
            'Transcription: median 300 ms, 90th percentile 300 ms, longest 400 ms; at most 400 ms per second of audio'
            'Decoded a second time without boost words: 1 of 3 clips'
            'Kestrel''s memory (PSS): at most 950 MB'
        )
    }

    It 'counts the commands that lead to the correct action, and leaves out the other lines' {
        $totals = New-EvalTotals
        foreach ($command in '1', '0', '1', '-') {
            Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer "words=2 substitutions=0 deletions=0 insertions=0 request=ok read=Other millis=200 seconds=1.0 pss=900 parts=1 twice=0 command=$command")
        }

        (Format-EvalSummary $totals)[2] | Should -BeExactly 'Commands that lead to the correct action: 2 of 3 (66.7 %)'
    }

    It 'prints the word error rate of a group of lines' {
        $totals = New-EvalTotals
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=8 substitutions=1 deletions=0 insertions=0 request=ok read=Other millis=200 seconds=2.0 pss=900 twice=0')

        Format-GroupErrors -Name 'Dictation lines' -Totals $totals | Should -BeExactly 'Dictation lines: 1 clips, 8 words, word error rate 12.5 %'
    }
}
