package dev.kestrel.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * The text fields of the editing tests in Jetpack Compose, as prototype P4 had them: a `BasicTextField` with a `String`
 * (one line, the search action and a placeholder), one with a `TextFieldValue` (more lines), and one with a
 * `TextFieldState` (one line and the search action). Their nodes all say "one line"; only the input that the app starts
 * tells the notes field apart. The test tags are the view IDs, and the line under each field reports its text and, where
 * Compose tells it, its selection.
 */
class ComposeEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Ready") }
                var search by remember { mutableStateOf("") }
                var notes by remember { mutableStateOf(TextFieldValue("")) }
                val state = rememberTextFieldState()
                Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).semantics { testTagsAsResourceId = true },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Compose edit fixture", style = MaterialTheme.typography.headlineSmall)
                    Text(status)
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().testTag("compose_search").semantics { contentDescription = "Compose search" },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { status = "Searched: \"$search\"" }),
                        decorationBox = { field ->
                            Box {
                                if (search.isEmpty()) Text("Search")
                                field()
                            }
                        },
                    )
                    Text("Compose search: \"$search\"")
                    BasicTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth().testTag("compose_notes").semantics { contentDescription = "Compose notes" },
                        minLines = 3,
                    )
                    Text("Compose notes: \"${notes.text.replace("\n", "\\n")}\" ${notes.selection.start}-${notes.selection.end}")
                    BasicTextField(
                        state = state,
                        modifier = Modifier.fillMaxWidth().testTag("compose_state").semantics { contentDescription = "Compose state" },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { status = "Searched state: \"${state.text}\"" },
                    )
                    Text("Compose state: \"${state.text}\" ${state.selection.start}-${state.selection.end}")
                }
            }
        }
    }
}
