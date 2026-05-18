package com.example.kreedaprerana

import android.os.Bundle
import android.graphics.Color.rgb
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            KreedaPreranaApp()
        }
    }
}

@Entity(tableName = "athletes")
data class Athlete(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val coachName: String,
    val name: String,
    val age: String,
    val sport: String,
    val score: Double,
    val completed: Boolean = false
)

@Dao
interface AthleteDao {
    @Query("SELECT * FROM athletes WHERE coachName = :coachName")
    fun getAthletesByCoach(coachName: String): Flow<List<Athlete>>

    @Query("SELECT * FROM athletes")
    fun getAllAthletes(): Flow<List<Athlete>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(athletes: List<Athlete>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(athlete: Athlete)

    @Update
    suspend fun update(athlete: Athlete)

    @Query("DELETE FROM athletes")
    suspend fun clearAll()
}

@Database(entities = [Athlete::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun athleteDao(): AthleteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "athlete_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

fun parseAthletesCsv(content: String, coachName: String): List<Athlete> {
    val athletes = mutableListOf<Athlete>()
    val lines = content.split("\n").filter { it.isNotBlank() }

    // Skip header if present
    val startIndex = if (lines.isNotEmpty() && lines[0].lowercase().contains("name")) 1 else 0

    for (i in startIndex until lines.size) {
        val parts = lines[i].split(",").map { it.trim() }

        if (parts.size >= 3) {
            try {
                val athlete = Athlete(
                    coachName = coachName,
                    name = parts.getOrNull(0) ?: "",
                    age = parts.getOrNull(1) ?: "",
                    sport = parts.getOrNull(2) ?: "Athletics",
                    score = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
                    completed = parts.getOrNull(4)?.toBoolean() ?: false
                )
                if (athlete.name.isNotBlank()) {
                    athletes.add(athlete)
                }
            } catch (_: Exception) {
                // Skip malformed lines
                continue
            }
        }
    }
    return athletes
}

fun getAthleteBadge(athlete: Athlete): String {
    if (!athlete.completed) return "⌛ Pending"
    return when {
        athlete.sport == "Athletics" && athlete.score <= 11.5 -> "🥇 National Ready"
        athlete.sport == "Athletics" && athlete.score <= 12.5 -> "🥈 State Ready"
        athlete.sport == "Athletics" && athlete.score <= 13.5 -> "🥉 District Ready"
        else -> "🏅 Participated"
    }
}

@Composable
fun KreedaPreranaApp() {
    val context = LocalContext.current

    // --- Database Setup ---
    val database = remember { AppDatabase.getDatabase(context) }
    val athleteDao = remember { database.athleteDao() }
    
    // --- State variables (must be declared first!) ---
    var username by remember { mutableStateOf("") }
    
    // Filter athletes by current username (coach)
    val athletes by remember(username) { 
        athleteDao.getAthletesByCoach(username) 
    }.collectAsState(initial = emptyList())
    
    val coroutineScope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(false) }
    var athleteName by remember { mutableStateOf("") }
    var athleteAge by remember { mutableStateOf("") }
    var selectedSport by remember { mutableStateOf("Athletics") }
    var currentIndex by remember { mutableIntStateOf(0) }
    var showStudentDropdown by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(0L) }
    var startTime by remember { mutableLongStateOf(0L) }

    // --- CSV File Picker Launcher (can now use athletes) ---
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                val inputStream = context.contentResolver.openInputStream(selectedUri)
                inputStream?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream))
                    val csvContent = reader.readText()
                    val parsedAthletes = parseAthletesCsv(csvContent, username)
                    if (parsedAthletes.isNotEmpty()) {
                        coroutineScope.launch(Dispatchers.IO) {
                            athleteDao.insertAll(parsedAthletes)
                        }
                        Toast.makeText(
                            context,
                            "Imported ${parsedAthletes.size} students successfully!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "No valid student data found in CSV",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Error importing CSV: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /*
    LOGIN
     */

    /*
    STUDENT STORAGE
     */

    /*
    CURRENT STUDENT
     */

    /*
    TIMER
     */


    LaunchedEffect(running) {

        while (running) {

            time =
                SystemClock.elapsedRealtime() - startTime

            delay(10)
        }
    }

    val seconds =
        String.format(Locale.US, "%.2f", time / 1000f)

    /*
    LOGIN SCREEN
     */

    if (!isLoggedIn) {

        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            Image(
                painter = painterResource(
                    id = R.drawable.sports_bg
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),

                horizontalAlignment =
                    Alignment.CenterHorizontally,

                verticalArrangement =
                    Arrangement.Center
            ) {

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor =
                            Color(0xCC0D47A1)
                    ),

                    shape = RoundedCornerShape(28.dp)
                ) {

                    Column(
                        modifier = Modifier.padding(24.dp),

                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {

                        Text(
                            text = "🏆 Kreeda-Prerana",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )

                        Text(
                            text =
                                "Grassroots Sports Talent Scout",

                            color = Color.White
                        )

                        Spacer(
                            modifier = Modifier.height(25.dp)
                        )

                        OutlinedTextField(
                            value = username,

                            onValueChange = {
                                username = it
                            },

                            label = {
                                Text("Enter Username")
                            },

                            textStyle = TextStyle(
                                color = Color.White
                            ),

                            colors =
                                OutlinedTextFieldDefaults.colors(

                                    focusedTextColor =
                                        Color.White,

                                    unfocusedTextColor =
                                        Color.White,

                                    focusedLabelColor =
                                        Color.White,

                                    unfocusedLabelColor =
                                        Color.LightGray,

                                    cursorColor =
                                        Color.White
                                )
                        )

                        Spacer(
                            modifier = Modifier.height(20.dp)
                        )

                        Button(
                            onClick = {

                                if (username.isNotEmpty()) {

                                    isLoggedIn = true

                                    Toast.makeText(
                                        context,
                                        "Welcome Coach $username",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                } else {

                                    Toast.makeText(
                                        context,
                                        "Enter Username",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },

                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        Color(0xFFFF9800)
                                )
                        ) {

                            Text(
                                text = "ENTER SCOUT PORTAL",
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        TextButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    athleteDao.clearAll()
                                }
                                Toast.makeText(context, "Database Refreshed (All Data Cleared)", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(
                                text = "REFRESH DATABASE (CLEAR ALL)",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

    } else {

        LazyColumn(

            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFE3F2FD))
                .padding(16.dp)

        ) {

            item {

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🏆 Kreeda-Prerana",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )

                        Text(
                            text = "Welcome Coach $username",
                            color = Color.DarkGray
                        )
                    }

                    IconButton(onClick = { isLoggedIn = false }) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_lock_power_off),
                            contentDescription = "Logout",
                            tint = Color.Red
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                /*
                ATHLETE PROFILE
                 */

                Card(

                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),

                    shape = RoundedCornerShape(24.dp)

                ) {

                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {

                        Text(
                            text = "👟 Athlete Batch Entry",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        Image(
                            painter = painterResource(
                                id = R.drawable.school_sports
                            ),

                            contentDescription = null,

                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(
                                    RoundedCornerShape(20.dp)
                                ),

                            contentScale =
                                ContentScale.Crop
                        )

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        OutlinedTextField(
                            value = athleteName,

                            onValueChange = {
                                athleteName = it
                            },

                            label = {
                                Text("Athlete Name")
                            },

                            modifier =
                                Modifier.fillMaxWidth()
                        )

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )

                        OutlinedTextField(
                            value = athleteAge,

                            onValueChange = {
                                athleteAge = it
                            },

                            label = {
                                Text("Age")
                            },

                            modifier =
                                Modifier.fillMaxWidth()
                        )

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        Text(
                            text = "Primary Sport",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )

                        Row {

                            Button(
                                onClick = {
                                    selectedSport =
                                        "Athletics"
                                },

                                colors =
                                    ButtonDefaults.buttonColors(

                                        containerColor =

                                            if (
                                                selectedSport ==
                                                "Athletics"
                                            )

                                                Color.Blue

                                            else

                                                Color.Gray
                                    )
                            ) {

                                Text("Athletics")
                            }

                            Spacer(
                                modifier = Modifier.width(10.dp)
                            )

                            Button(
                                onClick = {
                                    selectedSport =
                                        "Kabaddi"
                                },

                                colors =
                                    ButtonDefaults.buttonColors(

                                        containerColor =

                                            if (
                                                selectedSport ==
                                                "Kabaddi"
                                            )

                                                Color.Blue

                                            else

                                                Color.Gray
                                    )
                            ) {

                                Text("Kabaddi")
                            }
                        }

                        Spacer(
                            modifier = Modifier.height(20.dp)
                        )

                        Button(

                            onClick = {
                                val trimmedName = athleteName.trim()
                                val trimmedAge = athleteAge.trim()

                                if (
                                    trimmedName.isNotBlank() &&
                                    trimmedAge.isNotBlank()
                                ) {

                                    coroutineScope.launch(Dispatchers.IO) {
                                        athleteDao.insert(
                                            Athlete(
                                                coachName = username,
                                                name = trimmedName,
                                                age = trimmedAge,
                                                sport = selectedSport,
                                                score = 0.0,
                                                completed = false
                                            )
                                        )
                                    }

                                    athleteName = ""
                                    athleteAge = ""

                                    Toast.makeText(
                                        context,
                                        "Student $trimmedName Added Successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Please enter both Name and Age",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },

                            modifier =
                                Modifier.fillMaxWidth(),

                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        Color(0xFF0D47A1)
                                )
                        ) {

                            Text(
                                text = "ADD STUDENT",
                                color = Color.White
                            )
                        }

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )

                        Button(

                            onClick = {
                                csvPickerLauncher.launch("text/*")
                            },

                            modifier =
                                Modifier.fillMaxWidth(),

                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        Color(0xFFFF9800)
                                )
                        ) {

                            Text(
                                text = "📄 BATCH IMPORT FROM CSV",
                                color = Color.White
                            )
                        }

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        Text(
                            text =
                                "Students Added : ${athletes.size}",

                            fontWeight = FontWeight.Bold,

                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                /*
                TRIAL LOGGER
                 */

                Card(

                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFBBDEFB)
                    ),

                    shape = RoundedCornerShape(24.dp)

                ) {

                    Column(

                        modifier = Modifier.padding(18.dp),

                        horizontalAlignment =
                            Alignment.CenterHorizontally

                    ) {

                        Text(
                            text = "⏱ Trial Logger",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        Image(
                            painter = painterResource(
                                id = R.drawable.running_track
                            ),

                            contentDescription = null,

                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(
                                    RoundedCornerShape(20.dp)
                                ),

                            contentScale =
                                ContentScale.Crop
                        )

                        Spacer(
                            modifier = Modifier.height(20.dp)
                        )

                        if (
                            athletes.isNotEmpty() &&
                            currentIndex < athletes.size
                        ) {

                            Box {
                                OutlinedButton(
                                    onClick = { showStudentDropdown = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "Current Student: ${athletes[currentIndex].name.ifBlank { "Unnamed" }} (Age: ${athletes[currentIndex].age.ifBlank { "N/A" }})",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }

                                DropdownMenu(
                                    expanded = showStudentDropdown,
                                    onDismissRequest = { showStudentDropdown = false },
                                    modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 400.dp)
                                ) {
                                    athletes.forEachIndexed { index, athlete ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${index + 1}. ${athlete.name.ifBlank { "Unnamed" }} (Age: ${athlete.age.ifBlank { "N/A" }}) ${if (athlete.completed) "✅" else "⌛"}"
                                                )
                                            },
                                            onClick = {
                                                currentIndex = index
                                                showStudentDropdown = false
                                                time = 0L
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(
                                modifier = Modifier.height(10.dp)
                            )
                        }

                        Text(
                            text = "$seconds sec",
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Red
                        )

                        Spacer(
                            modifier = Modifier.height(20.dp)
                        )

                        Row {

                            Button(

                                onClick = {

                                    startTime =
                                        SystemClock.elapsedRealtime()

                                    running = true
                                },

                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            Color.Green
                                    )
                            ) {

                                Text("START")
                            }

                            Spacer(
                                modifier = Modifier.width(12.dp)
                            )

                            Button(

                                onClick = {

                                    running = false

                                    if (
                                        athletes.isNotEmpty() &&
                                        currentIndex < athletes.size
                                    ) {

                                        val updatedAthlete =
                                            athletes[currentIndex]
                                                .copy(

                                                    score =
                                                        seconds.toDouble(),

                                                    completed = true
                                                )

                                        coroutineScope.launch(Dispatchers.IO) {
                                            athleteDao.update(updatedAthlete)
                                        }

                                        if (
                                            currentIndex <
                                            athletes.size - 1
                                        ) {

                                            currentIndex++
                                        }

                                        time = 0L
                                    }
                                },

                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            Color.Red
                                    )
                            ) {

                                Text("STOP")
                            }
                        }

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        Text(
                            text =
                                "Timer Accurate To Two Decimal Places",

                            color = Color.Black
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                /*
                TALENT CURVE
                 */

                /*
REAL TIME TALENT CURVE GRAPH
 */

                Card(

                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),

                    shape = RoundedCornerShape(24.dp)

                ) {

                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {

                        Text(
                            text = "📈 Real-Time Talent Curve",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )

                        Spacer(
                            modifier = Modifier.height(20.dp)
                        )

                        AndroidView(

                            modifier = Modifier
                                .fillMaxWidth()
                                .height(350.dp),

                            factory = { context ->

                                LineChart(context).apply {

                                    layoutParams =
                                        android.view.ViewGroup.LayoutParams(
                                            MATCH_PARENT,
                                            MATCH_PARENT
                                        )

                                    description.isEnabled = false

                                    setTouchEnabled(true)

                                    setPinchZoom(true)

                                    setScaleEnabled(true)

                                    animateX(1500)

                                    xAxis.position =
                                        XAxis.XAxisPosition.BOTTOM

                                    axisRight.isEnabled = false

                                    legend.isEnabled = true
                                }
                            },

                            update = { chart ->

                                val entries =
                                    ArrayList<Entry>()

                                athletes
                                    .filter { it.completed }
                                    .sortedBy { it.score }
                                    .forEachIndexed { index, athlete ->

                                        entries.add(

                                            Entry(
                                                index.toFloat(),
                                                athlete.score.toFloat(),
                                                athlete.name
                                            )
                                        )
                                    }

                                val dataSet = LineDataSet(
                                    entries,
                                    "Sprint Timing Performance"
                                )

                                dataSet.valueFormatter = object : ValueFormatter() {
                                    override fun getPointLabel(entry: Entry?): String {
                                        return entry?.data?.toString() ?: ""
                                    }
                                }

                                dataSet.color =
                                    rgb(13, 71, 161)

                                dataSet.valueTextColor =
                                    rgb(0, 0, 0)

                                dataSet.lineWidth = 3f

                                dataSet.circleRadius = 6f

                                dataSet.setCircleColor(
                                    rgb(255, 87, 34)
                                )

                                dataSet.valueTextSize = 12f

                                val lineData =
                                    LineData(dataSet)

                                chart.data = lineData

                                chart.invalidate()
                            }
                        )

                        Spacer(
                            modifier = Modifier.height(20.dp)
                        )

                        Text(
                            text =
                                "Lower Timing Indicates Better Athletic Performance",

                            color = Color.DarkGray,

                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                /*
                LEADERBOARD BY SPORT
                 */

                val sportsInDb = athletes.map { it.sport }.distinct().sorted()

                sportsInDb.forEach { sport ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Text(
                                text = "📊 $sport Leaderboard",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0D47A1)
                            )

                            Spacer(
                                modifier = Modifier.height(15.dp)
                            )

                            athletes
                                .filter { it.sport == sport }
                                .sortedWith(compareBy<Athlete> { !it.completed }.thenBy { it.score })
                                .forEachIndexed { index, athlete ->

                                    val originalIndex = athletes.indexOf(athlete)

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        onClick = {
                                            if (originalIndex != -1) {
                                                currentIndex = originalIndex
                                                time = 0L
                                                Toast.makeText(context, "Selected ${athlete.name} for trial", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (originalIndex == currentIndex) Color(0xFFFFF59D) else Color(0xFFE3F2FD)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${index + 1}. ${athlete.name.ifBlank { "Unknown Student" }}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 18.sp
                                                )
                                                
                                                if (athlete.completed) {
                                                    Text(
                                                        text = getAthleteBadge(athlete),
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF0D47A1)
                                                    )
                                                }
                                            }

                                            Text(
                                                text = "Age: ${athlete.age.ifBlank { "N/A" }}",
                                                fontWeight = FontWeight.Medium,
                                                color = Color.DarkGray
                                            )

                                            Text(
                                                text = "Sport: ${athlete.sport}"
                                            )

                                            if (athlete.completed) {
                                                Text(
                                                    text = "Sprint Time: ${athlete.score} sec",
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1B5E20)
                                                )
                                            }

                                            Text(
                                                text = if (athlete.completed) "✅ Trial Completed" else "⌛ Pending",
                                                color = if (athlete.completed) Color(0xFF2E7D32) else Color.Red,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                /*
                IMPACT GOALS
                 */

                Card(

                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFD1C4E9)
                    ),

                    shape = RoundedCornerShape(24.dp)

                ) {

                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {

                        Text(
                            text = "🌍 Impact Goals",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4527A0)
                        )

                        Spacer(
                            modifier = Modifier.height(12.dp)
                        )

                        Text(
                            text =
                                "• Identifies Rural Sports Talent Early",

                            color = Color.Black
                        )

                        Text(
                            text =
                                "• Supports Khelo India Vision",

                            color = Color.Black
                        )

                        Text(
                            text =
                                "• Encourages Fitness & Competition",

                            color = Color.Black
                        )

                        Text(
                            text =
                                "• Builds Equal Sports Opportunities",

                            color = Color.Black
                        )

                        Text(
                            text =
                                "• Batch Entry For 30+ Students",

                            color = Color.Black
                        )

                        Text(
                            text =
                                "• AI-Based Talent Curve Monitoring",

                            color = Color.Black
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(40.dp)
                )
            }
        }
    }
}