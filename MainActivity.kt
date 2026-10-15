package com.example.lect10permissionsecurity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/* ======================================================================
   Lecture 10 demo app: RouteMapper, Privacy, Permissions and Security
   One app, three tabs, one tab per live demo:
     1 Permissions : ask in context, allow / deny / deny again / Settings
     2 Storage     : token in plain text vs encrypted with a Keystore key
     3 My data     : export my data, delete my data, retention cleanup
   ====================================================================== */

private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
private const val TAG_ROUTE = "RouteService"   // Logcat filter for Demo 1
private const val TAG_PRIVACY = "Privacy"      // Logcat filter for Demo 3
private const val DAY_MS = 24L * 60L * 60L * 1000L

private fun hasPermission(ctx: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

/** Either precise or approximate location is enough for our feature. */
private fun hasLocation(ctx: Context): Boolean =
    hasPermission(ctx, FINE) || hasPermission(ctx, COARSE)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Milliseconds to an ISO-8601 UTC text like 2026-10-13T08:15:30Z. Works on every API level. */
private fun isoTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))

/* ---------------------------------------------------------------- Room */

@Entity(tableName = "route_points")
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val timestamp: Long
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customer: String,
    val address: String,
    val text: String,
    val createdAt: Long
)

@Dao
interface RouteDao {
    @Insert
    suspend fun insert(point: RoutePointEntity)

    @Insert
    suspend fun insertAll(points: List<RoutePointEntity>)

    @Query("SELECT * FROM route_points ORDER BY timestamp ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<RoutePointEntity>>

    @Query("SELECT * FROM route_points ORDER BY timestamp ASC")
    suspend fun getAll(): List<RoutePointEntity>

    @Query("DELETE FROM route_points WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity)

    @Insert
    suspend fun insertAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes ORDER BY createdAt ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt ASC")
    suspend fun getAll(): List<NoteEntity>

    @Query("DELETE FROM notes WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Database(entities = [RoutePointEntity::class, NoteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // File name "route.db": the backup rules exclude this exact name.
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "route.db"
            ).build().also { instance = it }
        }
    }
}

/* ------------------------------------------------- WorkManager cleanup */

/** Demo 3: the retention rule. Deletes route points and notes older than N days. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val days = inputData.getInt(KEY_DAYS, 30)
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val db = AppDatabase.get(applicationContext)
        val points = db.routeDao().deleteOlderThan(cutoff)
        val notes = db.noteDao().deleteOlderThan(cutoff)
        Log.d(TAG_PRIVACY, "cleanup: removed $points points and $notes notes older than $days days")
        return Result.success(
            Data.Builder().putInt(OUT_POINTS, points).putInt(OUT_NOTES, notes).build()
        )
    }

    companion object {
        const val KEY_DAYS = "days"
        const val OUT_POINTS = "points"
        const val OUT_NOTES = "notes"
        const val UNIQUE_NOW = "cleanup-now"
        const val UNIQUE_DAILY = "cleanup-daily"

        fun runNow(context: Context, days: Int) {
            val request = OneTimeWorkRequest.Builder(CleanupWorker::class.java)
                .setInputData(Data.Builder().putInt(KEY_DAYS, days).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun scheduleDaily(context: Context, days: Int) {
            val request = PeriodicWorkRequest.Builder(CleanupWorker::class.java, 1, TimeUnit.DAYS)
                .setInputData(Data.Builder().putInt(KEY_DAYS, days).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelDaily(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_DAILY)
        }
    }
}

/* -------------------------------------------- Demo 2: token storage */

// DataStore must be declared at the top level of a file.
private val Context.secureStore: DataStore<Preferences> by preferencesDataStore(name = "secure")
private val TOKEN_ENC = stringPreferencesKey("token_enc")
private const val AAD = "token"   // ties the ciphertext to its purpose

/* -------------------------------------------------------------- ViewModel */

class DemoViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    private val db = AppDatabase.get(app)
    private val fused = LocationServices.getFusedLocationProviderClient(app)
    private var cachedAead: Aead? = null

    // ---- shared data (Room is the source of truth) ----
    val points: StateFlow<List<RoutePointEntity>> = db.routeDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notes: StateFlow<List<NoteEntity>> = db.noteDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Demo 1: permissions and tracking ----
    private val _tracking = MutableStateFlow(false)
    val tracking: StateFlow<Boolean> = _tracking.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _requestLog = MutableStateFlow<List<String>>(emptyList())
    val requestLog: StateFlow<List<String>> = _requestLog.asStateFlow()

    private val _refresh = MutableStateFlow(0)
    val refresh: StateFlow<Int> = _refresh.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            viewModelScope.launch(Dispatchers.IO) {
                result.locations.forEach { loc ->
                    // Fine for a lecture (so you can follow the route), remove in a release build.
                    Log.d(TAG_ROUTE, "[tracking] lat=${loc.latitude} lng=${loc.longitude} accuracy=${loc.accuracy} m")
                    db.routeDao().insert(
                        RoutePointEntity(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            accuracyM = loc.accuracy,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    fun setMessage(text: String?) {
        _message.value = text
    }

    fun logRequest(line: String) {
        Log.d(TAG_ROUTE, "[permission] $line")
        _requestLog.value = listOf(line) + _requestLog.value
    }

    /** Called from Activity.onResume: the user may have changed the grant in Settings. */
    fun onResumed() {
        _refresh.value = _refresh.value + 1
        if (_tracking.value && !hasLocation(ctx)) {
            stopTracking()
            _message.value = "Location permission was removed, so tracking stopped."
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (_tracking.value) return
        // Check on every use: the grant can vanish at any moment.
        if (!hasLocation(ctx)) {
            _message.value = "Location permission denied. Route recording needs location."
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L).build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            _tracking.value = true
            _message.value = null
            Log.d(TAG_ROUTE, "[tracking] started")
        } catch (e: SecurityException) {
            _message.value = "Location permission denied. Route recording needs location."
        }
    }

    fun stopTracking() {
        fused.removeLocationUpdates(callback)
        _tracking.value = false
        Log.d(TAG_ROUTE, "[tracking] stopped")
    }

    fun addNote(customer: String, address: String, text: String) {
        if (customer.isBlank() && address.isBlank() && text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.noteDao().insert(
                NoteEntity(
                    customer = customer.trim(),
                    address = address.trim(),
                    text = text.trim(),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    override fun onCleared() {
        fused.removeLocationUpdates(callback)
        super.onCleared()
    }

    // ---- Demo 2: plain vs encrypted token ----
    private val _plainFile = MutableStateFlow("(file does not exist yet)")
    val plainFile: StateFlow<String> = _plainFile.asStateFlow()

    private val _encValue = MutableStateFlow("(nothing stored yet)")
    val encValue: StateFlow<String> = _encValue.asStateFlow()

    private val _shownToken = MutableStateFlow("(tap Show saved token)")
    val shownToken: StateFlow<String> = _shownToken.asStateFlow()

    private val _storageMsg = MutableStateFlow("Type a fake token, then save it both ways.")
    val storageMsg: StateFlow<String> = _storageMsg.asStateFlow()

    init {
        refreshFiles()
    }

    private fun aead(): Aead {
        cachedAead?.let { return it }
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "keyset", "tink_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://master_key")   // the master key lives in the Keystore
            .build()
            .keysetHandle
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java).also { cachedAead = it }
    }

    private fun refreshFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val plain = File(app.applicationInfo.dataDir, "shared_prefs/plain.xml")
            _plainFile.value = if (plain.exists()) plain.readText().trim() else "(file does not exist)"
            _encValue.value = app.secureStore.data.first()[TOKEN_ENC] ?: "(nothing stored)"
        }
    }

    fun savePlain(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            // commit() so the file is on disk right away, for the demo
            app.getSharedPreferences("plain", Context.MODE_PRIVATE).edit().putString("token", token).commit()
            _storageMsg.value = "Saved in plain text: shared_prefs/plain.xml"
            refreshFiles()
        }
    }

    fun saveEncrypted(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val cipher = aead().encrypt(token.toByteArray(), AAD.toByteArray())
                val b64 = Base64.encodeToString(cipher, Base64.NO_WRAP)
                app.secureStore.edit { it[TOKEN_ENC] = b64 }
                _storageMsg.value = "Saved encrypted (AES-256-GCM, key in the Keystore): files/datastore/secure.preferences_pb"
            } catch (e: Exception) {
                _storageMsg.value = "Encryption failed: ${e.message}"
            }
            refreshFiles()
        }
    }

    fun showEncrypted() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val b64 = app.secureStore.data.first()[TOKEN_ENC]
            if (b64 == null) {
                _shownToken.value = "(nothing stored)"
                return@launch
            }
            try {
                val plain = aead().decrypt(Base64.decode(b64, Base64.NO_WRAP), AAD.toByteArray())
                _shownToken.value = String(plain)
                _storageMsg.value = "Decrypted inside the app. The app can read it, a file copy cannot."
            } catch (e: Exception) {
                _shownToken.value = "(decryption failed)"
                _storageMsg.value = "Decryption failed: the data was changed or the key is gone."
            }
        }
    }

    /** Bonus: flip one character of the stored value. GCM notices that the data was changed. */
    fun tamper() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val b64 = app.secureStore.data.first()[TOKEN_ENC]
            if (b64 == null || b64.length < 20) {
                _storageMsg.value = "Save an encrypted token first."
                return@launch
            }
            val chars = b64.toCharArray()
            chars[10] = if (chars[10] == 'A') 'B' else 'A'
            app.secureStore.edit { it[TOKEN_ENC] = String(chars) }
            _storageMsg.value = "One character of the stored value was changed. Now tap Show saved token."
            refreshFiles()
        }
    }

    fun clearToken() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            app.deleteSharedPreferences("plain")
            app.secureStore.edit { it.clear() }
            _shownToken.value = "(tap Show saved token)"
            _storageMsg.value = "Both stored tokens removed."
            refreshFiles()
        }
    }

    // ---- Demo 3: export, delete, retention ----
    val cleanupStatus: StateFlow<String> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(CleanupWorker.UNIQUE_NOW)
        .map { list ->
            val info = list.firstOrNull()
            when {
                info == null -> "No cleanup has run yet"
                info.state == WorkInfo.State.SUCCEEDED ->
                    "Cleanup SUCCEEDED: removed ${info.outputData.getInt(CleanupWorker.OUT_POINTS, 0)} " +
                        "route points and ${info.outputData.getInt(CleanupWorker.OUT_NOTES, 0)} notes"
                else -> "Cleanup ${info.state}"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "No cleanup has run yet")

    val dailyCleanupOn: StateFlow<Boolean> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(CleanupWorker.UNIQUE_DAILY)
        .map { list ->
            list.any {
                it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _dataMsg = MutableStateFlow("Record a route and add notes, or tap Add sample data.")
    val dataMsg: StateFlow<String> = _dataMsg.asStateFlow()

    private val _exportPreview = MutableStateFlow("")
    val exportPreview: StateFlow<String> = _exportPreview.asStateFlow()

    fun addSampleData() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val old = now - 40 * DAY_MS
            fun route(start: Long) = (0 until 10).map { i ->
                RoutePointEntity(
                    lat = 63.4305 + i * 0.0004,
                    lng = 10.3951 + i * 0.0006,
                    accuracyM = 8f,
                    timestamp = start + i * 5_000L
                )
            }
            db.routeDao().insertAll(route(old) + route(now - 60_000L))
            db.noteDao().insertAll(
                listOf(
                    NoteEntity(customer = "Kari Nordmann (fake)", address = "Storgata 5", text = "Leave at the door", createdAt = old),
                    NoteEntity(customer = "Ola Nordmann (fake)", address = "Kirkegata 12", text = "Ring twice", createdAt = old + 1_000L),
                    NoteEntity(customer = "Per Hansen (fake)", address = "Torget 1", text = "Back entrance", createdAt = now - 30_000L),
                    NoteEntity(customer = "Lise Berg (fake)", address = "Elvegata 8", text = "Fragile parcel", createdAt = now - 20_000L)
                )
            )
            _dataMsg.value = "Added 20 route points and 4 notes: half of them 40 days old."
        }
    }

    /** Everything the app stores about people, as readable JSON. The token is not included. */
    suspend fun buildExport(): File = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val root = JSONObject()
        root.put("exportedAt", isoTime(System.currentTimeMillis()))
        root.put("about", "Everything RouteMapper stores about you. The upload token is not included.")
        val pts = JSONArray()
        db.routeDao().getAll().forEach { p ->
            pts.put(
                JSONObject()
                    .put("time", isoTime(p.timestamp))
                    .put("lat", p.lat)
                    .put("lng", p.lng)
                    .put("accuracyMeters", p.accuracyM.toDouble())
            )
        }
        root.put("routePoints", pts)
        val ns = JSONArray()
        db.noteDao().getAll().forEach { n ->
            ns.put(
                JSONObject()
                    .put("created", isoTime(n.createdAt))
                    .put("customer", n.customer)
                    .put("address", n.address)
                    .put("note", n.text)
            )
        }
        root.put("notes", ns)
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "my-data.json")
        val text = root.toString(2)
        file.writeText(text)
        _exportPreview.value = text
        _dataMsg.value = "Exported ${pts.length()} route points and ${ns.length()} notes."
        file
    }

    /** Delete my data: database, both tokens, the keyset, pending jobs and the export file. */
    fun deleteMyData() {
        if (_tracking.value) stopTracking()
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            WorkManager.getInstance(app).cancelAllWork()
            db.clearAllTables()
            app.deleteSharedPreferences("plain")
            app.secureStore.edit { it.clear() }
            app.deleteSharedPreferences("tink_prefs")
            cachedAead = null
            File(app.cacheDir, "exports").deleteRecursively()
            _shownToken.value = "(tap Show saved token)"
            _storageMsg.value = "Type a fake token, then save it both ways."
            _exportPreview.value = ""
            _dataMsg.value = "All data deleted: database, tokens, keyset, pending jobs."
            refreshFiles()
        }
    }

    fun runCleanupNow(days: Int) {
        CleanupWorker.runNow(ctx, days)
        _dataMsg.value = "Cleanup job queued with a $days-day retention."
    }

    fun setDailyCleanup(enabled: Boolean, days: Int) {
        if (enabled) CleanupWorker.scheduleDaily(ctx, days)
        else CleanupWorker.cancelDaily(ctx)
    }
}

/* ------------------------------------------------------------------- UI */

class MainActivity : ComponentActivity() {
    private val vm: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
    }
}

@Composable
fun AppRoot(vm: DemoViewModel) {
    var onHome by rememberSaveable { mutableStateOf(true) }
    if (onHome) {
        HomeScreen(onStart = { onHome = false })
    } else {
        BackHandler { onHome = true }
        DemoScreen(vm)
    }
}

@Composable
fun HomeScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("RouteMapper", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Lecture 10: Privacy, Permissions and Security", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Three tabs, three live demos: permissions, secure storage, my data.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onStart) { Text("Start demo") }
    }
}

@Composable
fun DemoScreen(vm: DemoViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf("1 Permissions", "2 Storage", "3 My data")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> PermissionsTab(vm)
            1 -> StorageTab(vm)
            else -> MyDataTab(vm)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/* ---------------------------------------------- Tab 1: Demo 1, permissions */

@Composable
fun PermissionsTab(vm: DemoViewModel) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val refresh by vm.refresh.collectAsState()
    val tracking by vm.tracking.collectAsState()
    val message by vm.message.collectAsState()
    val log by vm.requestLog.collectAsState()
    val points by vm.points.collectAsState()
    val notes by vm.notes.collectAsState()

    var asked by remember { mutableStateOf(0) }
    var askedAt by remember { mutableStateOf(0L) }

    // Read the grant again when the screen resumes (back from Settings) or a request finishes.
    val fineGranted = remember(refresh, log.size) { hasPermission(ctx, FINE) }
    val coarseGranted = remember(refresh, log.size) { hasPermission(ctx, COARSE) }
    val rationale = remember(refresh, log.size) {
        activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, FINE) } ?: false
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val tookMs = SystemClock.elapsedRealtime() - askedAt
        val fine = result[FINE] == true
        val coarse = result[COARSE] == true
        val outcome = when {
            fine -> "Precise granted"
            coarse -> "Approximate granted"
            else -> "Denied"
        }
        // Demo timing check: a person needs seconds to answer a dialog, the system answers at once.
        val how = if (tookMs < 400) "no dialog: Android answered at once" else "answered in the dialog"
        vm.logRequest("Request #$asked: $outcome ($tookMs ms, $how)")
        if (fine || coarse) {
            vm.startTracking()
        } else {
            vm.setMessage("Location permission denied. Route recording needs location. Notes and the rest of the app still work.")
        }
    }

    var customer by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var noteText by rememberSaveable { mutableStateOf("") }

    // Photo picker: the system shows the photos, we receive only the one the user picks.
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> photoUri = uri?.toString() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Demo 1: The permission journey", style = MaterialTheme.typography.titleLarge)
        Text(
            "No dialog at launch. We ask only when the user taps Start tracking.",
            style = MaterialTheme.typography.bodyMedium
        )

        SectionCard("What the app sees right now") {
            StatusRow("Precise location (FINE)", if (fineGranted) "GRANTED" else "not granted")
            StatusRow("Approximate location (COARSE)", if (coarseGranted) "GRANTED" else "not granted")
            StatusRow("shouldShowRequestPermissionRationale", rationale.toString())
            StatusRow("Times we asked this session", asked.toString())
            StatusRow("Tracking", if (tracking) "RECORDING" else "off")
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(it, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (hasLocation(ctx)) {
                        vm.startTracking()                       // 1. check: already allowed
                    } else {
                        asked += 1
                        askedAt = SystemClock.elapsedRealtime()
                        launcher.launch(arrayOf(FINE, COARSE))   // 3. request both together
                    }
                },
                enabled = !tracking
            ) { Text("Start tracking") }
            OutlinedButton(onClick = { vm.stopTracking() }, enabled = tracking) { Text("Stop tracking") }
        }

        SectionCard("Photo without a permission") {
            Text("The system photo screen opens. The app receives only the photo you pick.")
            OutlinedButton(onClick = {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Attach photo") }
            Mono(photoUri ?: "(no photo chosen)")
        }

        SectionCard("Route recorded") {
            val last = points.lastOrNull()
            StatusRow("Route points saved", points.size.toString())
            if (last != null) {
                Mono("last fix: ${"%.5f".format(last.lat)}, ${"%.5f".format(last.lng)}")
                Mono("accuracy: about ${last.accuracyM.toInt()} m")
            } else if (tracking) {
                Text("Waiting for the first fix. On the emulator: Extended controls (three dots) → Location → set a location or play a route.")
            } else {
                Text("Nothing recorded yet.")
            }
        }

        SectionCard("Request log (newest first)") {
            if (log.isEmpty()) Mono("(no request yet)") else log.forEach { Mono(it) }
        }

        HorizontalDivider()
        Text("Delivery notes work with or without location", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = customer, onValueChange = { customer = it },
            label = { Text("Customer") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = address, onValueChange = { address = it },
            label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = noteText, onValueChange = { noteText = it },
            label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.addNote(customer, address, noteText)
            customer = ""; address = ""; noteText = ""
        }) { Text("Add note") }
        notes.takeLast(3).reversed().forEach {
            Text("• ${it.customer}, ${it.address}: ${it.text}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Notes saved: ${notes.size}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------- Tab 2: Demo 2, plain vs encrypted */

@Composable
fun StorageTab(vm: DemoViewModel) {
    val ctx = LocalContext.current
    val plainFile by vm.plainFile.collectAsState()
    val encValue by vm.encValue.collectAsState()
    val shown by vm.shownToken.collectAsState()
    val msg by vm.storageMsg.collectAsState()
    var token by rememberSaveable { mutableStateOf("demo-token-1234") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Demo 2: Plain vs encrypted", style = MaterialTheme.typography.titleLarge)
        Text("Same fake token, saved two ways. What would a thief with a copy of the files see?")

        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("Fake API token") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.savePlain(token) }) { Text("Save plain") }
            Button(onClick = { vm.saveEncrypted(token) }) { Text("Save encrypted") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.showEncrypted() }) { Text("Show saved token") }
            OutlinedButton(onClick = { vm.tamper() }) { Text("Tamper") }
            OutlinedButton(onClick = { vm.clearToken() }) { Text("Clear") }
        }
        Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)

        SectionCard("What a file copy shows: shared_prefs/plain.xml") { Mono(plainFile) }
        SectionCard("What a file copy shows: files/datastore/secure.preferences_pb (Base64)") { Mono(encValue) }
        SectionCard("What the app shows after decrypting") { Mono(shown) }

        SectionCard("Read the files from a terminal (debug builds only)") {
            Mono("adb shell run-as ${ctx.packageName} cat shared_prefs/plain.xml")
            Mono("adb shell run-as ${ctx.packageName} cat files/datastore/secure.preferences_pb")
        }
        Text(
            "run-as works only on debug builds. A thief needs a rooted phone, a backup or a forensic tool instead. " +
                "The point is the same: whoever gets the file gets the token, unless the file is encrypted with a key that stays in the Keystore.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------------------------------------- Tab 3: Demo 3, my data */

@Composable
fun MyDataTab(vm: DemoViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val points by vm.points.collectAsState()
    val notes by vm.notes.collectAsState()
    val cleanupStatus by vm.cleanupStatus.collectAsState()
    val dailyOn by vm.dailyCleanupOn.collectAsState()
    val dataMsg by vm.dataMsg.collectAsState()
    val preview by vm.exportPreview.collectAsState()

    var retention by rememberSaveable { mutableStateOf("30") }
    var confirmDelete by remember { mutableStateOf(false) }
    val days = retention.toIntOrNull()?.coerceAtLeast(0) ?: 30

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Demo 3: Export and delete", style = MaterialTheme.typography.titleLarge)
        Text("People own their data. The app can show it, hand it over, and erase it.")

        SectionCard("What the app stores about people") {
            StatusRow("Route points", points.size.toString())
            StatusRow("Delivery notes", notes.size.toString())
            StatusRow("Oldest route point", points.firstOrNull()?.let { "${(System.currentTimeMillis() - it.timestamp) / DAY_MS} days ago" } ?: "none")
        }

        Button(onClick = { vm.addSampleData() }) { Text("Add sample data (fake, half is 40 days old)") }

        Text("Right of access: export", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            scope.launch {
                val file = vm.buildExport()
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Export my data"))
            }
        }) { Text("Export my data") }
        if (preview.isNotEmpty()) {
            SectionCard("The exported file: my-data.json") {
                Mono(
                    preview.take(1500) +
                        if (preview.length > 1500) "\n... (${preview.length} characters in total)" else ""
                )
            }
        }

        Text("Right to erasure: delete", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { confirmDelete = true },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) { Text("Delete my data") }

        Text("Storage limitation: retention", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = retention,
            onValueChange = { retention = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text("Keep data for this many days (0 = delete everything)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runCleanupNow(days) }) { Text("Run cleanup now") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = dailyOn, onCheckedChange = { vm.setDailyCleanup(it, days) })
            Text("Auto-cleanup every day (WorkManager periodic job)")
        }
        Text(cleanupStatus, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(dataMsg, style = MaterialTheme.typography.bodyMedium)

        Text(
            "Check it: App Inspection → Database Inspector shows the tables; Background Task Inspector shows the cleanup job.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all my data?") },
            text = { Text("This removes the route, the notes, both stored tokens and any pending jobs. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteMyData()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
