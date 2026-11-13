package dev.kestrel.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The same kinds of elements as the Views fixture, built with Jetpack Compose. */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Ready") }
                var subscribed by remember { mutableStateOf(false) }
                var name by remember { mutableStateOf("") }
                Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Compose fixture", style = MaterialTheme.typography.headlineSmall)
                    Text(status)
                    Button(onClick = { status = "Clicked Compose button" }) { Text("Compose button") }
                    Row(
                        modifier = Modifier.toggleable(value = subscribed, role = Role.Checkbox, onValueChange = { subscribed = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = subscribed, onCheckedChange = null)
                        Text("Subscribe")
                    }
                    TextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                }
            }
        }
    }
}
