package org.grise.homeguard.matrix

import android.util.Log
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.inMemory
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.image
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.store.isEncrypted
import de.connect2x.trixnity.client.store.repository.inMemory
import de.connect2x.trixnity.client.store.roomId
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.clientserverapi.client.classicLoginWithPassword
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.clientserverapi.model.user.Filters
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.utils.toByteArrayFlow
import io.ktor.http.ContentType
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.grise.homeguard.util.Constants
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object MatrixManager {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(Constants.TAG, "MatrixManager Scope Error", e)
        }
    )
    
    private var client: MatrixClient? = null
    private var sessionJob: Job? = null
    
    private var batteryLevelProvider: (() -> Int)? = null
    private var photoProvider: (suspend () -> Unit)? = null
    private var alarmProvider: (() -> Unit)? = null
    private var intervalSaveCallback: ((Int) -> Unit)? = null
    private var motionSettingCallback: ((Boolean) -> Unit)? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _remainingTime = MutableStateFlow(0L)
    val remainingTime: StateFlow<Long> = _remainingTime.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

    private val _currentInterval = MutableStateFlow(0)
    val currentInterval: StateFlow<Int> = _currentInterval.asStateFlow()

    @Synchronized
    fun login(
        host: String,
        user: String,
        pass: String,
        rid: String,
        interval: Int,
        onPhoto: suspend () -> Unit,
        battery: () -> Int,
        onAlarm: () -> Unit = {},
        onIntervalChange: (Int) -> Unit = {},
        onMotionChange: (Boolean) -> Unit = {}
    ) {
        stop()
        
        _currentInterval.value = interval.coerceAtLeast(1)
        intervalSaveCallback = onIntervalChange
        motionSettingCallback = onMotionChange
        batteryLevelProvider = battery
        photoProvider = onPhoto
        alarmProvider = onAlarm
        _elapsedTime.value = 0
        
        sessionJob = scope.launch {
            launch {
                while (isActive) {
                    delay(Constants.MONITORING_CHECK_INTERVAL)
                    _elapsedTime.value += 1
                }
            }
            try {
                addLog("Connecting...")
                val auth = MatrixClientAuthProviderData.classicLoginWithPassword(
                    Url(host),
                    IdentifierType.User(user),
                    pass
                ).getOrThrow()
                
                if (!isActive) return@launch

                client = MatrixClient.create(
                    RepositoriesModule.inMemory(),
                    MediaStoreModule.inMemory(),
                    CryptoDriverModule.vodozemac(),
                    auth,
                    coroutineContext
                ) {
                    syncFilter = Filters(
                        room = Filters.RoomFilter(
                            rooms = setOf(rid.trim()),
                            timeline = Filters.RoomFilter.RoomEventFilter(
                                limit = 20,
                                lazyLoadMembers = true
                            ),
                            state = Filters.RoomFilter.RoomEventFilter(
                                lazyLoadMembers = true
                            )
                        )
                    )
                }.getOrThrow()
                
                addLog("Connected as ${client?.userId?.full}")
                addLog("Sync starting...")

                setupListeners(client!!, rid)
                startLoop(onPhoto)
                autoJoinRoom(client!!, rid)

                var syncStarted = false
                launch {
                    client?.syncState?.collect { state ->
                        Log.d(Constants.TAG, "Sync state changed: $state")
                        if (state == SyncState.RUNNING && !syncStarted) {
                            syncStarted = true
                            addLog("Sync active")
                            listRooms(client!!)
                        }
                        _connectionState.value = state in listOf(
                            SyncState.STARTED,
                            SyncState.INITIAL_SYNC,
                            SyncState.RUNNING
                        )
                    }
                }
                
                addLog("Sync loop starting...")
                while (isActive) {
                    try {
                        client?.startSync()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(Constants.TAG, "Sync loop error, restarting in 5s", e)
                        addLog("Sync error: ${e.message}")
                        delay(5.seconds)
                    }
                    if (isActive) delay(1.seconds)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    addLog("Error: ${e.message}")
                    Log.e(Constants.TAG, "Login/Sync error", e)
                }
            }
        }
    }

    private fun CoroutineScope.autoJoinRoom(c: MatrixClient, rid: String) {
        val target = try { RoomId(rid.trim()) } catch (_: Exception) { null } ?: return
        launch {
            try {
                c.room.getById(target).first() ?: run {
                    addLog("Joining room...")
                    c.api.room.joinRoom(target).getOrThrow()
                    addLog("Room joined")
                    delay(500.milliseconds)
                }
            } catch (e: Exception) {
                addLog("Join failed: ${e.message}")
                Log.e(Constants.TAG, "Room join error", e)
            }
        }
    }

    private suspend fun listRooms(c: MatrixClient) {
        val rooms = withTimeoutOrNull(5000.milliseconds) { 
            c.room.getAll().first { it.isNotEmpty() } 
        } ?: c.room.getAll().first()
        
        addLog("${rooms.size} rooms found")
        rooms.forEach { (id, flow) ->
            val r = flow.first()
            val name = r?.name?.explicitName ?: r?.name?.heroes?.joinToString { it.localpart } ?: "Unnamed"
            addLog("- $name")
            Log.d(Constants.TAG, "Room found: $name ID: ${id.full}")
        }
    }

    private fun CoroutineScope.setupListeners(c: MatrixClient, rid: String) {
        val target = try { RoomId(rid.trim()) } catch (_: Exception) { null } ?: return
        
        Log.d(Constants.TAG, "Setting up command listener for room: ${target.full}")
        addLog("Listening to ${target.full}")
        
        launch {
            try {
                c.room.getTimelineEventsFromNowOn(decryptionTimeout = 20.minutes).collect { event ->
                    val contentResult = event.content
                    if ((event.roomId == target) && (event.sender != c.userId)) {
                        contentResult?.getOrNull()?.let { content ->
                            if (content is RoomMessageEventContent.TextBased) {
                                val body = content.body.trim().lowercase()
                                Log.d(Constants.TAG, "Msg received in target room: $body")
                                when {
                                    body == "!batterie" || body == "!battery" -> {
                                        addLog("Recv !batterie")
                                        replyBattery(c, target)
                                    }
                                    body == "!picture" || body == "!photo" || body == "!image" -> {
                                        addLog("Recv !picture")
                                        photoProvider?.invoke()
                                    }
                                    body == "!alarm" -> {
                                        addLog("Recv !alarm")
                                        alarmProvider?.invoke()
                                    }
                                    body.startsWith("!freq") -> {
                                        addLog("Recv !freq")
                                        handleFreqCommand(c, target, body)
                                    }
                                    body.startsWith("!motions") -> {
                                        addLog("Recv !motions")
                                        handleMotionCommand(c, target, body)
                                    }
                                    body.startsWith("!help") -> {
                                        addLog("Recv !help")
                                        handleHelpCommand(c, target)
                                    }
                                }
                            }
                        } ?: run {
                            if (contentResult?.isFailure == true) {
                                Log.e(Constants.TAG, "Failed to decrypt event in target room", contentResult.exceptionOrNull())
                            } else if (event.isEncrypted) {
                                Log.w(Constants.TAG, "Encrypted event Rx but content is null")
                            }
                        }
                    } else if (event.roomId != target) {
                        contentResult?.getOrNull()?.let { content ->
                            if (content is RoomMessageEventContent.TextBased && content.body.startsWith("!")) {
                                Log.d(Constants.TAG, "Command '${content.body}' seen in wrong room: ${event.roomId.full}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(Constants.TAG, "Listener collection failed", e)
                    addLog("Listener error: ${e.message}")
                }
            }
        }
    }

    private fun CoroutineScope.replyBattery(c: MatrixClient, rid: RoomId) {
        val level = batteryLevelProvider?.invoke() ?: -1
        launch { c.room.sendMessage(rid) { text("Battery: $level%") } }
    }

    private fun CoroutineScope.handleHelpCommand(c: MatrixClient, rid: RoomId) {
        launch { c.room.sendMessage(rid) { text("Help:\n* !help : displays this help message\n* !batterie : displays the current battery level\n* !picture : takes a picture\n* !alarm : triggers the alarm\n* !freq <minutes> : sets the frequency of the monitoring (do not pass a number to get the current value)\n* !motions <true/false>: enables/disables the motions tracking")} }
    }
    private fun CoroutineScope.handleFreqCommand(c: MatrixClient, rid: RoomId, body: String) {
        val parts = body.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val newInterval = parts[1].toIntOrNull()
            if (newInterval != null && newInterval > 0) {
                _currentInterval.value = newInterval
                _remainingTime.value = newInterval * 60L
                intervalSaveCallback?.invoke(newInterval)
                launch {
                    c.room.sendMessage(rid) {
                        text("Fréquence mise à jour: $newInterval minute(s)")
                    }
                }
                addLog("Frequency updated to $newInterval min")
            } else {
                launch {
                    c.room.sendMessage(rid) {
                        text("Usage: !freq <minutes>")
                    }
                }
                addLog("Invalid frequency format")
            }
        } else {
            val current = _currentInterval.value
            launch {
                c.room.sendMessage(rid) {
                    text("Fréquence actuelle: $current minute(s). Usage: !freq <minutes>")
                }
            }
            addLog("Current frequency: $current min")
        }
    }

    private fun CoroutineScope.startLoop(onPhoto: suspend () -> Unit) {
        launch {
            var sec = _currentInterval.value * 60L
            _remainingTime.value = sec
            while (isActive) {
                delay(1.seconds)
                if (_remainingTime.value > 0) {
                    _remainingTime.value -= 1
                } else {
                    addLog("Taking photo...")
                    onPhoto()
                    sec = _currentInterval.value * 60L
                    _remainingTime.value = sec
                }
            }
        }
    }

    suspend fun sendImage(file: File, rid: String, includeBattery: Boolean = true) {
        val c = client ?: return
        val target = try { RoomId(rid.trim()) } catch (_: Exception) { null } ?: return
        val battery = batteryLevelProvider?.invoke() ?: -1
        
        try {
            if (includeBattery) {
                c.room.sendMessage(target) {
                    text("Photo - Battery: $battery%")
                }
            }
            c.room.sendMessage(target) {
                image(
                    "Photo",
                    file.readBytes().toByteArrayFlow(),
                    type = ContentType.Image.JPEG
                )
            }
            addLog("Photo sent")
        } catch (e: Exception) {
            addLog("Failed to send image: ${e.message}")
        }
    }

    private fun CoroutineScope.handleMotionCommand(c: MatrixClient, rid: RoomId, body: String) {
        val parts = body.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val enabled = parts[1].toBoolean()
            motionSettingCallback?.invoke(enabled)
            launch {
                c.room.sendMessage(rid) {
                    text("Film the movements: $enabled")
                }
            }
            addLog("Motion detection: $enabled")
        }
    }

    @Synchronized
    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        batteryLevelProvider = null
        photoProvider = null
        
        val c = client
        client = null
        
        scope.launch {
            try {
                c?.stopSync()
                c?.close()
            } catch (_: Exception) {}
            
            _connectionState.value = false
            _remainingTime.value = 0
            _elapsedTime.value = 0
            _logs.value = emptyList()
        }
    }

    fun addLog(msg: String) {
        Log.d(Constants.TAG, "AppLog: $msg")
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(msg)
        if (currentLogs.size > Constants.MAX_LOGS_COUNT) {
            currentLogs.removeAt(0)
        }
        _logs.value = currentLogs
    }
}
