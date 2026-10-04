package computer.handy.android.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import computer.handy.android.R

/**
 * Playground for the overlay. Handy's own package is normally always excluded; the service
 * makes an exception while this screen is visible (see [isVisible]).
 */
class TestActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HandyTheme {
                Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.test_title)) }) }) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.test_intro), style = MaterialTheme.typography.bodyMedium)

                        var single by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = single,
                            onValueChange = { single = it },
                            label = { Text(stringResource(R.string.test_field_single)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        var multi by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = multi,
                            onValueChange = { multi = it },
                            label = { Text(stringResource(R.string.test_field_multi)) },
                            placeholder = { Text(stringResource(R.string.test_field_multi_hint)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )

                        Text(stringResource(R.string.test_field_view), style = MaterialTheme.typography.labelLarge)
                        // A classic EditText: most apps still use Views, and they handle
                        // ACTION_SET_TEXT differently from Compose.
                        val hint = stringResource(R.string.test_field_view_hint)
                        AndroidView(
                            factory = { ctx ->
                                EditText(ctx).apply {
                                    this.hint = hint
                                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        var secret by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            label = { Text(stringResource(R.string.test_field_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.test_password_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    companion object {
        /** Read by the accessibility service, which runs in the same process. */
        @Volatile
        var isVisible: Boolean = false
            private set
    }
}
