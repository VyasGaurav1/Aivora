package com.chronoit.aivora.ui

import com.chronoit.aivora.actions.describe
import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.chronoit.aivora.ProductIdentity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chronoit.aivora.data.AssistantSettings
import com.chronoit.aivora.voice.EnrollmentKind
import com.chronoit.aivora.voice.VoiceLaunchTickets
import com.chronoit.aivora.brain.FileOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

open class MainActivity : ComponentActivity() {
    protected open val voicePanelOnly = false
    private val assistantModel: AssistantViewModel by viewModels()
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(assistantModel::addDocumentGrant) }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(assistantModel::addDocumentGrant) }
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { assistantModel.refresh() }
    private val callPlacementPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { assistantModel.refresh() }
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { assistantModel.refresh() }
    private val phoneStatePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { assistantModel.refresh() }
    private val setupPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { assistantModel.refresh() }
    private val reminderPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { assistantModel.refreshReminders() }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val paymentQrScanner = registerForActivityResult(ScanContract()) { result ->
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            assistantModel.foreground(true)
            assistantModel.stagePaymentQr(result.contents)
        }
    }
    private fun launchPaymentQrScanner() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            assistantModel.paymentQrUnavailable()
            return
        }
        try {
            paymentQrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan a merchant UPI QR code").setBeepEnabled(false))
        } catch (_: android.content.ActivityNotFoundException) {
            assistantModel.paymentQrUnavailable()
        }
    }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val launchTicket = MutableStateFlow<String?>(null)
    private var recordAfterPermission: (() -> Unit)? = null
    private var actionAuthenticationToken: String? = null
    private var actionAuthenticationSignal: CancellationSignal? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val begin = recordAfterPermission
        recordAfterPermission = null
        assistantModel.refresh()
        if (granted) begin?.invoke()
        else assistantModel.microphonePermissionDenied()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptVoiceLaunch(intent)
        setContent {
            val model: AssistantViewModel = viewModel()
            val ui by model.ui.collectAsStateWithLifecycle()
            val settings by model.settings.collectAsStateWithLifecycle()
            val lifecycle = LocalLifecycleOwner.current
            val ticket by launchTicket.collectAsStateWithLifecycle()
            LaunchedEffect(ui.capabilities, settings.onboardingComplete) {
                if (!voicePanelOnly &&
                    getSharedPreferences("starter_ui", MODE_PRIVATE).getBoolean("wake_enabled", true)) {
                    lifecycle.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                    val roles = getSystemService(RoleManager::class.java)
                    if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                        com.chronoit.aivora.platform.BackgroundWakeService.start(this@MainActivity)
                    }
                }
            }
            LaunchedEffect(Unit) {
                if (!voicePanelOnly) {
                    val setup = getSharedPreferences("starter_ui", MODE_PRIVATE)
                    if (!setup.getBoolean("permissions_requested", false)) {
                        setup.edit().putBoolean("permissions_requested", true).apply()
                        requestStarterPermissions()
                    }
                }
            }
            LaunchedEffect(model, lifecycle) {
                model.paymentQrRequests.collect {
                    lifecycle.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                    launchPaymentQrScanner()
                }
            }
            LaunchedEffect(ui.authentication?.token, settings.handsFreeControls) {
                if (actionAuthenticationToken != null && actionAuthenticationToken != ui.authentication?.token) {
                    val signal = actionAuthenticationSignal
                    finishActionAuthentication(actionAuthenticationToken!!, false)
                    signal?.cancel()
                }
                if (ui.authentication == null) return@LaunchedEffect
                lifecycle.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                model.resumed(true)
                model.consumeVoiceAuthentication()?.let(::authenticateAction)
            }
            LaunchedEffect(ticket) {
                val token = ticket ?: return@LaunchedEffect
                lifecycle.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                val starterCommand = VoiceLaunchTickets.store.consumeStarterMode(token)
                if (starterCommand != null) {
                    model.foreground(true)
                    model.resumed(true)
                    android.util.Log.i("AivoraWake", "Voice launch ticket accepted by resumed panel")
                    val review = model.ui.value
                    val begin = {
                        if (starterCommand) {
                            val confirmation = review.confirmation
                            if (confirmation != null) model.replyToActionFromShortcut(confirmation.token)
                            else if (review.networkPromptToken == null && review.contactChoiceToken == null) model.startVoiceConversation()
                            else model.record(reviewedToken = review.networkPromptToken,
                                contactToken = review.contactChoiceToken, assistantShortcut = true)
                        } else {
                            model.startVoiceConversation()
                        }
                        Unit
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
                    else {
                        recordAfterPermission = {
                            lifecycleScope.launch {
                                this@MainActivity.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                                begin()
                            }
                        }
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                launchTicket.value = null
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) model.foreground(true)
                    if (event == Lifecycle.Event.ON_RESUME) model.refresh()
                    if (event == Lifecycle.Event.ON_RESUME) model.resumed(true)
                    if (event == Lifecycle.Event.ON_PAUSE) model.resumed(false)
                    if (event == Lifecycle.Event.ON_STOP) model.foreground(false)
                }
                lifecycle.lifecycle.addObserver(observer)
                onDispose { lifecycle.lifecycle.removeObserver(observer) }
            }
            MaterialTheme {
                if (voicePanelOnly) {
                    StarterVoicePanel(model, ui, onClose = { model.cancel(); finish() })
                } else {
                    StarterScreen(model, ui, settings,
                        requestPermissions = ::requestStarterPermissions,
                        openAppPermissions = {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", packageName, null)))
                        },
                        requestAssistantRole = ::chooseStarterAssistant,
                        enroll = ::enrollStarterVoice,
                        startWake = {
                            getSharedPreferences("starter_ui", MODE_PRIVATE).edit().putBoolean("wake_enabled", true).apply()
                            lifecycleScope.launch {
                                val app = application as com.chronoit.aivora.AivoraApplication
                                app.settings.rename(com.chronoit.aivora.ProductIdentity.DEFAULT_ASSISTANT_NAME)
                                if (com.chronoit.aivora.platform.BackgroundWakeService.start(this@MainActivity)) {
                                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                                }
                            }
                        },
                        stopWake = {
                            getSharedPreferences("starter_ui", MODE_PRIVATE).edit().putBoolean("wake_enabled", false).apply()
                            com.chronoit.aivora.platform.BackgroundWakeService.stop(this)
                        },
                        record = {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.startVoiceConversation()
                            else { recordAfterPermission = { model.startVoiceConversation() }; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                        })
                }
            }
        }
    }

    private fun requestStarterPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) setupPermissions.launch(needed.toTypedArray())
    }

    private fun chooseStarterAssistant() {
        for (action in listOf(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
            android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, android.provider.Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Try the next system settings entry.
            }
        }
    }

    private fun enrollStarterVoice(kind: EnrollmentKind) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestStarterPermissions()
            return
        }
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            assistantModel.voice.credentialUnavailable()
            return
        }
        authenticateOwner("Confirm your device credential to set up wake listening.",
            success = { assistantModel.voice.beginEnrollment(kind) }, failure = {})
    }

    private fun legacyContent(model: AssistantViewModel, ui: AssistantUiState, settings: AssistantSettings) {
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AssistantScreen(model, ui, settings,
                        pickFile = { documentPicker.launch(arrayOf("*/*")) },
                        pickFolder = { folderPicker.launch(null) },
                        requestContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                        requestCallPlacement = { callPlacementPermission.launch(Manifest.permission.CALL_PHONE) },
                        requestSms = { smsPermission.launch(Manifest.permission.SEND_SMS) },
                        requestPhoneState = { phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE) },
                        requestSetupPermissions = {
                            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
                            if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) permissions.add(Manifest.permission.CAMERA)
                            if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && (Build.VERSION.SDK_INT < 33 || packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA) || packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))) permissions.add(Manifest.permission.READ_PHONE_STATE)
                            if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && (Build.VERSION.SDK_INT < 33 || packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))) permissions.add(Manifest.permission.CALL_PHONE)
                            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                            setupPermissions.launch(permissions.toTypedArray())
                        },
                        requestReminderNotifications = {
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                reminderPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                        },
                        authenticateAction = ::authenticateAction,
                        requestCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
                        scanPaymentQr = ::launchPaymentQrScanner,
                        requestSetupMicrophone = { recordAfterPermission = null; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                        requestMicrophone = {
                            val reviewedToken = ui.networkPromptToken
                            val choiceToken = ui.contactChoiceToken
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                model.record(reviewedToken, choiceToken)
                            } else {
                                recordAfterPermission = {
                                    lifecycleScope.launch {
                                        this@MainActivity.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                                        model.record(reviewedToken, choiceToken)
                                    }
                                }
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        requestAssistantRole = {
                            val roles = getSystemService(RoleManager::class.java)
                            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                                assistantRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        })
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptVoiceLaunch(intent) }
    private fun acceptVoiceLaunch(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "aivora" && data.host == "voice" && data.pathSegments.size == 1) launchTicket.value = data.lastPathSegment
    }

    private fun authenticateAction(token: String) {
        if (assistantModel.ui.value.authentication?.token != token || actionAuthenticationToken != null) return
        actionAuthenticationToken = token
        assistantModel.voice.interrupt()
        try {
            authenticateOwner("Confirm your device credential for this action.",
                success = { finishActionAuthentication(token, true) }, failure = { finishActionAuthentication(token, false) })
        } catch (_: Exception) {
            finishActionAuthentication(token, false)
        }
    }
    private fun finishActionAuthentication(token: String, succeeded: Boolean) {
        if (actionAuthenticationToken != token) return
        actionAuthenticationSignal = null
        if (!succeeded || assistantModel.ui.value.authentication?.token != token) {
            actionAuthenticationToken = null
            assistantModel.authenticationFailed(token)
            return
        }
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            if (actionAuthenticationToken != token) return@launch
            actionAuthenticationToken = null
            assistantModel.foreground(true)
            assistantModel.authenticated(token)
        }
    }
    override fun onDestroy() {
        val token = actionAuthenticationToken
        actionAuthenticationToken = null
        actionAuthenticationSignal?.cancel()
        actionAuthenticationSignal = null
        if (token != null) assistantModel.authenticationFailed(token)
        super.onDestroy()
    }

    private fun authenticateOwner(reason: String, success: () -> Unit, failure: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) { failure(); return }
        run {
            val signal = CancellationSignal()
            actionAuthenticationSignal = signal
            BiometricPrompt.Builder(this).setTitle("AIVORA authentication").setDescription(reason)
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                .authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { success() }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { failure() }
                })
        }
    }
}

@Composable private fun AssistantScreen(model: AssistantViewModel, ui: AssistantUiState, settings: AssistantSettings, pickFile: () -> Unit, pickFolder: () -> Unit, requestContacts: () -> Unit, requestPhoneState: () -> Unit, requestCallPlacement: () -> Unit, requestSms: () -> Unit, requestSetupPermissions: () -> Unit, requestSetupMicrophone: () -> Unit, requestReminderNotifications: () -> Unit, authenticateAction: (String) -> Unit, requestCamera: () -> Unit, scanPaymentQr: () -> Unit, requestMicrophone: () -> Unit, requestAssistantRole: () -> Unit) {
    if (!settings.onboardingComplete) {
        SetupScreen(model, ui, settings, requestSetupPermissions, requestSetupMicrophone, requestAssistantRole)
        return
    }
    val pages = listOf("Home", "Voice", "Files", "Reminders", "Memory", "Capabilities", "Settings", "Developer")
    val voice by model.voice.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Home") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ProductIdentity.PRODUCT_NAME, style = MaterialTheme.typography.headlineLarge)
        Text("${settings.identity.assistantDisplayName} · ${if (voice.speaking) "speaking" else ui.state.name.lowercase().replace('_', ' ')}")
        ScrollableTabRow(selectedTabIndex = pages.indexOf(page)) {
            pages.forEach { name -> Tab(selected = page == name, onClick = { page = name; model.refresh() }, text = { Text(name) }) }
        }
        when (page) {
            "Home" -> Home(model, ui, Modifier.weight(1f), requestMicrophone, scanPaymentQr)
            "Files" -> FilesScreen(model, ui, pickFile, pickFolder, Modifier.weight(1f))
            "Reminders" -> RemindersScreen(model, ui, requestReminderNotifications, Modifier.weight(1f))
            "Voice" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(voice.message)
                Text("Tap the microphone and say a command. AIVORA processes it offline and asks for review when needed.")
                Button(onClick = { page = "Home"; requestMicrophone() }, enabled = !voice.capturing) { Text("Speak a command") }
                Row {
                    Switch(settings.spokenReplies, model::spokenReplies)
                    Text("Speak replies", Modifier.padding(12.dp))
                }
                Text("Owner voice enrollment and wake-name setup are planned for a later build.")
                Button(onClick = model::readLastReply) { Text("Read last reply aloud") }
                OutlinedButton(onClick = model::cancel) { Text("Stop") }
                var removeProfile by remember { mutableStateOf<EnrollmentKind?>(null) }
                if (voice.ownerEnrolled) TextButton(onClick = { removeProfile = EnrollmentKind.OWNER }) { Text("Remove owner voice profile") }
                if (voice.activeWakeName != null) TextButton(onClick = { removeProfile = EnrollmentKind.WAKE }) { Text("Remove wake name profile") }
                removeProfile?.let { kind -> AlertDialog(onDismissRequest = { removeProfile = null },
                    title = { Text("Remove voice profile?") }, text = { Text("This deletes the selected encrypted features. Enroll again to use this capability.") },
                    confirmButton = { TextButton(onClick = { model.voice.forgetProfile(kind); removeProfile = null }) { Text("Remove") } },
                    dismissButton = { TextButton(onClick = { removeProfile = null }) { Text("Keep") } }) }
            }
            "Memory" -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(if (settings.privateMode) "Private mode: new memories are disabled." else "Memories are encrypted locally.") }
                if (ui.memories.isEmpty()) item { Text("No saved memories.") }
                items(ui.memories, key = { it.id }) { memory -> Card { Column(Modifier.padding(12.dp)) {
                    Text(memory.content)
                    Text(memory.type.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                    var confirmDelete by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirmDelete = true }) { Text("Forget") }
                    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Forget this memory?") },
                        text = { Text("This permanently deletes the selected memory from AIVORA.") },
                        confirmButton = { TextButton(onClick = { model.forget(memory.id); confirmDelete = false }) { Text("Forget") } },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } })
                } } }
            }
            "Capabilities" -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Enable access only when a command needs it.") }
                item { Button(onClick = requestCamera) { Text("Allow camera for QR scans and flashlight") } }
                item { Button(onClick = requestContacts) { Text("Allow contact-name resolution") } }
                item { Button(onClick = requestCallPlacement) { Text("Allow phone-call placement") } }
                item { Button(onClick = requestSms) { Text("Allow SMS sending") } }
                item { Button(onClick = requestPhoneState) { Text("Allow SIM/calling verification") } }
                item { Button(onClick = requestAssistantRole) { Text("Choose AIVORA as assistant") } }
                item { TextButton(onClick = { model.submit("notification access settings") }) { Text("Notification access settings") } }
                  item { TextButton(onClick = { model.submit("app control settings") }) { Text("AIVORA app-control accessibility settings") } }
                item { TextButton(onClick = { model.submit("open settings") }) { Text("Open Android settings") } }
                items(ui.capabilities) { capability -> Card { Column(Modifier.padding(12.dp)) {
                    Text(capability.name, style = MaterialTheme.typography.titleMedium)
                    Text(capability.status.name.lowercase().replace('_', ' ')); Text(capability.detail)
                } } }
            }
            "Settings" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InternetSettings(model, settings)
                var searchKey by remember { mutableStateOf("") }
                Text("Spoken web answers", style = MaterialTheme.typography.titleMedium)
                Text("Search questions are sent to Brave. Answers are spoken. Say Show suggestions to also see them here in AIVORA.")
                OutlinedTextField(searchKey, { searchKey = it.take(256) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("Brave Answers API key") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.saveWebSearchKey(searchKey); searchKey = "" }, enabled = searchKey.isNotBlank()) { Text("Save search key") }
                    TextButton(onClick = { model.saveWebSearchKey(""); searchKey = "" }) { Text("Remove key") }
                }
                Row { Switch(settings.localReasoning, model::localReasoning); Text("Offline question answers") }
                Text("The local model can make factual mistakes. Generated answers are labeled unverified.")
                Text("Short questions can use a bundled local model when memory permits. Its answers may be inaccurate and do not perform phone actions. Commands and saved personal facts use the existing deterministic paths.")
                Text("Direct cellular calling", style = MaterialTheme.typography.titleMedium)
                Row { Switch(settings.directCalling, model::directCalling); Text("Place confirmed phone calls") }
                Row { Switch(settings.directSms, model::directSms); Text("Send confirmed short SMS") }
                Text("AIVORA keeps an encrypted SMS request/status journal, separate from learned memory. Say SMS status to check its last request. An interrupted or uncertain request is never retried automatically.")
                Text("Direct SMS requires SMS sending and SIM verification permissions, one ready default SMS SIM, device authentication and exact confirmation. Carrier charges may apply. Android submission is distinct from recipient delivery. Longer messages remain drafts.")
                Text("Optional. Requires call placement and SIM verification permissions, device authentication and exact confirmation. One unchanged ready default voice SIM/account is supported, without roaming or another active call. Use full international numbers. Otherwise calls open the dialer. Android may require further interaction; connection is not verified.")
                var name by remember(settings.identity.assistantDisplayName) { mutableStateOf(settings.identity.assistantDisplayName) }
                OutlinedTextField(name, { name = it }, label = { Text("What would you like to call me?") }, singleLine = true)
                Button(onClick = { model.rename(name) }, enabled = name.isNotBlank() && name.length <= 40 && name.none(Char::isISOControl)) { Text("Save assistant name") }
                Row { Switch(settings.privateMode, model::privateMode); Text("Private mode", Modifier.padding(12.dp)) }
                Row { Switch(settings.spokenReplies, model::spokenReplies); Text("Speak replies", Modifier.padding(12.dp)) }
                Row { Switch(settings.handsFreeControls, model::handsFreeControls); Text("Hands-free controls", Modifier.padding(12.dp)) }
                Text("Allow the enrolled owner voice to run low-risk actions such as volume, flashlight, media and app opening. Other actions still require confirmation or device authentication. Voice matching cannot guarantee liveness.")
                var speed by remember(settings.speechSpeed) { mutableStateOf(settings.speechSpeed) }
                Text("Speech speed: ${"%.2f".format(speed)}")
                Slider(speed, { speed = it }, valueRange = 0.5f..2f, onValueChangeFinished = { model.speechOptions(speed, settings.speechVoice) })
                Row { (0..3).forEach { id -> TextButton(onClick = { model.speechOptions(speed, id) }) { Text("${if (settings.speechVoice == id) "✓ " else ""}Voice ${id + 1}") } } }
                Text("Reminders, alarms and speech work offline. Online questions require a connection.")
            }
            "Developer" -> {
                val audit by model.audit.collectAsStateWithLifecycle()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Local action audit. Sensitive parameters and command text are not retained.") }
                    items(audit, key = { it.id }) { Text("${it.actionType}: ${it.result} · confirmed: ${it.confirmed}") }
                }
            }
        }
    }
    ui.confirmation?.let { confirmation -> AlertDialog(onDismissRequest = model::cancel, title = { Text("Confirm action") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text(confirmation.plan.actions.joinToString("\n") { it.describe() })
            if (settings.handsFreeControls && voice.ownerEnrolled) {
                TextButton(onClick = model::speakConfirmation) { Text("Review and confirm by voice") }
            } else {
                TextButton(onClick = model::readConfirmation, enabled = !voice.speaking) { Text("Read action aloud") }
            }
            if (voice.capturing) TextButton(onClick = model::finishRecording) { Text("Finish reply") }
        } },
        confirmButton = { TextButton(onClick = model::confirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel") } }) }
    ui.authentication?.let { request -> AlertDialog(onDismissRequest = model::cancel, title = { Text("Device authentication required") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(request.plan.actions.joinToString("\n") { it.describe() }) } },
        confirmButton = { TextButton(onClick = { authenticateAction(request.token) }) { Text("Authenticate") } },
        dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel") } }) }
    ClarificationDialogs(model, ui, requestMicrophone)
    ui.webSuggestions?.let { suggestions ->
        AlertDialog(onDismissRequest = model::dismissWebSuggestions,
            title = { Text("Web suggestions") },
            text = { Text(suggestions, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = model::dismissWebSuggestions) { Text("Close") } })
    }
}

@Composable private fun Home(model: AssistantViewModel, ui: AssistantUiState, modifier: Modifier, requestMicrophone: () -> Unit, scanPaymentQr: () -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ui.voiceDraft, ui.voiceDraftRevision) { if (ui.voiceDraft.isNotBlank()) command = ui.voiceDraft }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = true) {
            items(ui.conversation.asReversed()) { line -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { Text(if (line.owner) "You" else "Assistant", style = MaterialTheme.typography.labelSmall); Text(line.text) }
            } }
        }
        Text("Try: volume 40%, flashlight on, alarm 7 am, remember that Rahul is my friend.", style = MaterialTheme.typography.bodySmall)
        val voice by model.voice.state.collectAsStateWithLifecycle()
        Text(voice.message, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = scanPaymentQr, enabled = ui.state != AssistantState.LISTENING) { Text("Scan payment QR") }
        OutlinedTextField(command, { command = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Command") })
        if (ui.state == AssistantState.LISTENING) {
            Text("Say your command and pause. You can also tap Finish recording.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.submit(command); command = "" }, enabled = command.isNotBlank()) { Text("Send") }
            OutlinedButton(onClick = model::cancel) { Text("Stop") }
            OutlinedButton(onClick = { if (ui.state == AssistantState.LISTENING) model.finishRecording() else requestMicrophone() }) {
                Text(if (ui.state == AssistantState.LISTENING) "Finish recording" else "Voice input")
            }
        }
    }
}


