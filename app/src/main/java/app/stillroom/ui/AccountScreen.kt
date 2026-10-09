package app.stillroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.stillroom.domain.Account
import app.stillroom.domain.AccountId
import app.stillroom.domain.ScanFormat
import app.stillroom.domain.ServerAddress
import app.stillroom.domain.parseGrocyApiKeyQr

@Composable
fun AccountScreen(state: AccountUiState, model: AccountViewModel) {
    var adding by rememberSaveable { mutableStateOf(state.accounts.accounts.isEmpty()) }
    var loggingOut by remember { mutableStateOf<Account?>(null) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    // Never place an API key in saved instance state or a navigation argument.
    var apiKey by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var insecure by rememberSaveable { mutableStateOf(false) }
    var verifierId by remember { mutableStateOf<AccountId?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val address = runCatching { ServerAddress.parse(baseUrl, insecure) }.getOrNull()
    val administrators = state.accounts.accounts.filter { it.permissions?.contains("ADMIN") == true && it.address.apiBase == address?.apiBase }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Saved accounts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (state.accounts.accounts.isEmpty()) Text("No saved accounts.")
        state.accounts.accounts.forEach { account ->
            KitchenCard(Modifier.fillMaxWidth().testTag("account-${account.id.value}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.username, style = MaterialTheme.typography.titleMedium)
                    Text(account.address.apiBase, style = MaterialTheme.typography.bodyMedium)
                    Text("Grocy ${account.version}")
                    if (state.accounts.active?.id == account.id) Text("Current account")
                    account.versionWarning?.let { Text(it, modifier = Modifier.testTag("version-warning-${account.id.value}")) }
                    if (account.permissions == null) Text("Permissions have not been verified.")
                    SecondaryButton(onClick = { model.activate(account.id) }, enabled = !state.busy,
                        modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = "Use account ${account.username}" }) {
                        Text(if (state.accounts.active?.id == account.id) "Verify again" else "Use account")
                    }
                    SecondaryButton(onClick = { loggingOut = account }, enabled = !state.busy,
                        modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = "Log out ${account.username}" }) { Text("Log out") }
                }
            }
        }
        QuietButton(onClick = { adding = !adding }) { Text(if (adding) "Close account form" else "Add account") }
        if (adding) {
        Text("Connect to Grocy", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        LabeledTextField(baseUrl, onValueChange = { baseUrl = it; verifierId = null }, label = "Server URL",
            placeholder = { Text("https://grocy.example") }, singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("server-url"))
        Text(
            "If the server uses a private CA, install that CA's root in Android's CA certificate settings. Stillroom then trusts every user-installed CA for its HTTPS connections, not one Grocy server. The server must send its intermediate certificates, and the URL hostname must match the certificate. A failed certificate check does not switch the connection to HTTP.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("private-ca-help"),
        )
        LabeledTextField(apiKey, onValueChange = { apiKey = it }, label = "API key",
            singleLine = true, enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().testTag("api-key"))
        SecondaryButton(onClick = { scanning = !scanning; scanMessage = null }, enabled = !state.busy,
            modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("scan-api-key")) {
            Text(if (scanning) "Stop scanning" else "Scan API key")
        }
        if (scanning) CameraScanner { codes ->
            val qr = codes.firstOrNull { it.format == ScanFormat.Qr } ?: return@CameraScanner
            runCatching { parseGrocyApiKeyQr(qr.raw) }.onSuccess { scanned ->
                if (scanned.serverUrl != null) {
                    baseUrl = scanned.serverUrl
                    insecure = scanned.insecureHttp
                    verifierId = null
                }
                apiKey = scanned.apiKey
                scanning = false
                scanMessage = if (scanned.serverUrl != null) "Server URL filled from the QR. Check it, then connect."
                    else "API key filled. Enter the server URL, then connect."
            }.onFailure { scanMessage = it.message }
        }
        scanMessage?.let { Text(it, modifier = Modifier.testTag("scan-message")) }
        Row(Modifier.fillMaxWidth().sizeIn(minHeight = 64.dp)
            .toggleable(insecure, enabled = !state.busy, role = Role.Switch, onValueChange = { insecure = it; verifierId = null })
            .semantics { contentDescription = "Allow insecure HTTP" }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Allow insecure HTTP", style = MaterialTheme.typography.bodyLarge)
                Text("Use only for your local test server. HTTP sends the key without encryption.", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(insecure, onCheckedChange = null, enabled = !state.busy)
        }
        if (administrators.isNotEmpty()) {
            Text("Verify a child's permissions", style = MaterialTheme.typography.titleMedium)
            Text("Grocy requires an administrator to read a child's granted permissions. Choose an administrator on this server to verify them.")
            administrators.forEach { admin ->
                Row(Modifier.fillMaxWidth().sizeIn(minHeight = 64.dp)
                    .toggleable(verifierId == admin.id, enabled = !state.busy, role = Role.Switch,
                        onValueChange = { verifierId = if (it) admin.id else null })
                    .semantics { contentDescription = "Verify permissions with ${admin.username}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Verify with ${admin.username}", Modifier.weight(1f).padding(end = 16.dp))
                    Switch(verifierId == admin.id, onCheckedChange = null, enabled = !state.busy)
                }
            }
        }
        PrimaryButton(onClick = {
            val submitted = apiKey
            apiKey = ""
            model.connect(baseUrl, submitted, insecure, verifierId)
        }, enabled = !state.busy && baseUrl.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("connect-account")) {
            Text("Connect")
        }
        if (state.busy) {
            LoadingState(message = "Connecting to Grocy…")
            SecondaryButton(onClick = model::cancel, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Cancel connection") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("connection-error").semantics { liveRegion = LiveRegionMode.Polite }) }

        }
    }
    loggingOut?.let { account ->
        AlertDialog(onDismissRequest = { loggingOut = null }, title = { Text("Log out ${account.username}?") },
            text = { Text("Remove this account and its saved data from this phone. Grocy records stay on the server.") },
            dismissButton = { QuietButton(onClick = { loggingOut = null }) { Text("Cancel") } },
            confirmButton = { PrimaryButton(onClick = { model.logout(account.id); loggingOut = null }, modifier = Modifier.testTag("confirm-logout")) { Text("Log out") } })
    }

}

@Composable
fun ConnectedPlaceholder(account: Account) {
    Column(Modifier.fillMaxWidth().testTag("connected-placeholder"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Connected as ${account.username}", style = MaterialTheme.typography.titleMedium)
        account.versionWarning?.let { Text(it, modifier = Modifier.testTag("version-warning")) }
        if (account.restricted) {
            Text("Open Household to view chores permitted for this account.")
            Text("Granted permissions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when {
                account.permissions == null -> Text("Grocy does not let this account read its permission list. An administrator can verify it in Accounts.")
                account.permissions.isEmpty() -> Text("The server granted no permissions.")
                else -> account.permissions.sorted().forEach { permission -> Text(permission, modifier = Modifier.testTag("permission-$permission")) }
            }
        } else Text("Open a section to view this account's Grocy data.")
    }
}
