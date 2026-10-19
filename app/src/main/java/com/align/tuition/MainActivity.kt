package com.align.tuition

import java.time.format.DateTimeFormatter
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable

import android.os.Bundle
import android.app.DatePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.YearMonth
import java.time.LocalDate
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings

private val Green = Color(0xFF087F68)
private val Page = Color(0xFFF5F7F8)
private val Ink = Color(0xFF17232D)
private val Muted = Color(0xFF667781)


private fun currentFeePeriodKey(student: StudentEntity): String? =
    when (student.feeType) {
        "MONTHLY" -> YearMonth.now().toString()
        "YEARLY" -> "YEAR:" + LocalDate.now().year
        "DATE_RANGE" -> {
            val start = runCatching { LocalDate.parse(student.feeStartDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(student.feeEndDate) }.getOrNull()
            if (start != null && end != null && !end.isBefore(start)) {
                "RANGE:${student.feeStartDate}:${student.feeEndDate}"
            } else null
        }
        else -> YearMonth.now().toString()
    }

private fun feeTypeLabel(student: StudentEntity): String =
    when (student.feeType) {
        "YEARLY" -> "Yearly fee"
        "DATE_RANGE" -> "Period fee"
        else -> "Monthly fee"
    }

private fun paymentPeriodLabel(key: String): String =
    when {
        key.startsWith("YEAR:") -> key.removePrefix("YEAR:")
        key.startsWith("RANGE:") ->
            key.removePrefix("RANGE:").replace(":", " → ")
        else -> key
    }

private fun showFeeDatePicker(
    context: android.content.Context,
    currentValue: String,
    onSelected: (String) -> Unit
) {
    val initial = runCatching { LocalDate.parse(currentValue) }
        .getOrDefault(LocalDate.now())

    DatePickerDialog(
        context,
        { _, year, month, day ->
            onSelected(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth
    ).show()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        window.statusBarColor = android.graphics.Color.rgb(18, 61, 47)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    secondary = Color(0xFFB7E4D8),
                    background = Page,
                    surface = Color.White
                )
            ) {
                TuitionApp()
            }
        }
    }
}

@Composable
private fun TuitionApp() {
    val context = LocalContext.current
    val dao = remember { TuitionDatabase.get(context).tuitionDao() }
    val scope = rememberCoroutineScope()
    val students by dao.observeStudents().collectAsState(initial = emptyList())
    val payments by dao.observePayments().collectAsState(initial = emptyList())
    val feeRecords by dao.observeFeeRecords().collectAsState(initial = emptyList())
    val profile by dao.observeProfile().collectAsState(initial = null)
    val currentMonth = remember { YearMonth.now().toString() }
    val monthPayments = remember(payments, currentMonth) {
        payments.filter {
            YearMonth.from(
                java.time.Instant.ofEpochMilli(it.paidAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            ).toString() == currentMonth
        }
    }
    val paidByStudent = remember(payments, students) {
        students.associate { student ->
            val periodKey = currentFeePeriodKey(student)
            student.id to payments.filter {
                it.studentId == student.id &&
                    periodKey != null &&
                    it.monthKey == periodKey
            }.sumOf { it.amount }
        }
    }
    val collection = monthPayments.sumOf { it.amount }
    val pending = (feeRecords.sumOf { it.amount } - payments.sumOf { it.amount }).coerceAtLeast(0L)

    var tab by remember { mutableStateOf("Dashboard") }
    var showStudentEditor by remember { mutableStateOf(false) }
    var studentToEdit by remember { mutableStateOf<StudentEntity?>(null) }
    var selectedStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var recordFeeStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var studentToCollect by remember { mutableStateOf<StudentEntity?>(null) }
    var paymentToEdit by remember { mutableStateOf<FeePaymentEntity?>(null) }
    var paymentToDelete by remember { mutableStateOf<FeePaymentEntity?>(null) }
    var studentToDelete by remember { mutableStateOf<StudentEntity?>(null) }

BackHandler(
        enabled = recordFeeStudent != null || selectedStudent != null || tab == "Profile"
    ) {
        when {
            recordFeeStudent != null -> recordFeeStudent = null
            selectedStudent != null -> {
                selectedStudent = null
                tab = "Students"
            }
            tab == "Profile" -> tab = "Dashboard"
        }
    }


    Box(
        Modifier.fillMaxSize().background(
            if (recordFeeStudent != null || selectedStudent != null) {
                Color(0xFF123D2F)
            } else {
                Page
            }
        )
    ) {
        Scaffold(
            containerColor = if (recordFeeStudent != null || selectedStudent != null) {
                Color(0xFF123D2F)
            } else {
                Page
            },
            bottomBar = {
                if (selectedStudent == null && recordFeeStudent == null && tab != "Profile") {
                    NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = tab == "Dashboard",
                        onClick = { tab = "Dashboard" },
                        icon = { Icon(Icons.Default.Dashboard, null) },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Green,
                            indicatorColor = Green
                        )
                    )
                    NavigationBarItem(
                        selected = tab == "Students",
                        onClick = { tab = "Students" },
                        icon = { Icon(Icons.Default.People, null) },
                        label = { Text("Students") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Green,
                            indicatorColor = Green
                        )
                    )
}
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().then(
                    if (tab == "Profile") Modifier
                    else if (tab == "Students") {
                        Modifier.padding(bottom = padding.calculateBottomPadding())
                    } else Modifier.padding(padding)
                )
            ) {
                Column(
                    modifier = if (tab == "Profile") {
                        Modifier.fillMaxSize().background(Page)
                    } else if (recordFeeStudent != null) {
                        Modifier.fillMaxSize().background(Page)
                    } else if (selectedStudent != null || tab == "Students") {
                        Modifier.fillMaxSize().background(
                            if (selectedStudent == null && tab == "Students") {
                                Color(0xFF123D2F)
                            } else {
                                Page
                            }
                        )
                    } else {
                        Modifier.fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    }
                ) {
                    if (recordFeeStudent != null) {
                    val feeStudent = recordFeeStudent!!
                    RecordFeeScreen(
                        student = feeStudent,
                        history = feeRecords.filter { it.studentId == feeStudent.id },
                        onBack = { recordFeeStudent = null },
                        onSave = { updatedStudent, record ->
                            dao.updateStudent(updatedStudent)
                            val savedRecord = if (record.id == 0L) {
                                record.copy(id = dao.insertFeeRecord(record))
                            } else {
                                dao.updateFeeRecord(record)
                                record
                            }
                            selectedStudent = updatedStudent
                            savedRecord
                        },
                        onDelete = { record ->
                            dao.deleteFeeRecord(record)
                        }
                    )
                } else if (selectedStudent != null) {
                    val detailStudent = selectedStudent!!
                    val detailPayments = payments
                        .filter { it.studentId == detailStudent.id }
                        .sortedByDescending { it.paidAt }

                    StudentDetailsScreen(
                        modifier = Modifier.weight(1f),
                        student = detailStudent,
                        payments = detailPayments,
                        feeRecords = feeRecords.filter { it.studentId == detailStudent.id },
                        onEdit = {
                            studentToEdit = detailStudent
                            showStudentEditor = true
                        },
                        onRecordFee = { recordFeeStudent = detailStudent },
                        onCollect = { studentToCollect = detailStudent },
        onPaymentClick = { paymentToEdit = it },
                        onBack = {
                            selectedStudent = null
                            tab = "Students"
                        }
                    )
                } else when (tab) {
                    "Profile" -> ProfileScreen(
                        modifier = Modifier.weight(1f),
                        profile = profile,
                        onBack = { tab = "Dashboard" },
                        onSave = { name, mobile ->
                            scope.launch {
                                dao.saveProfile(
                                    ProfileEntity(name = name, mobile = mobile)
                                )
                            }
                        }
                    )
                    "Students" -> StudentsScreen(
                        modifier = Modifier.weight(1f),
                        students = students,
                        pendingByStudent = students.associate { student ->
                            student.id to (
                                feeRecords.filter { it.studentId == student.id }.sumOf { it.amount } -
                                payments.filter { it.studentId == student.id }.sumOf { it.amount }
                            ).coerceAtLeast(0L)
                        },
                        onAdd = {
                            studentToEdit = null
                            showStudentEditor = true
                        },
                        onEdit = {
                            studentToEdit = it
                            showStudentEditor = true
                        },
                        onStudentClick = { selectedStudent = it }
                    )
                    "Fees" -> FeesScreen(
                        modifier = Modifier.weight(1f),
                        payments = payments,
                        students = students,
                        monthKey = currentMonth,
                        monthCollection = collection,
                        pending = pending,
                        onCollect = { studentToCollect = it }
                    )
                    else -> DashboardScreen(
                        modifier = Modifier.weight(1f),
                        studentCount = students.size,
                        collection = collection,
                        pending = pending,
                        totalFees = students.sumOf { it.monthlyFee },
                        monthKey = currentMonth,
                        profileName = profile?.name.orEmpty(),
                        onProfileClick = { tab = "Profile" }
                    )
                }
            }
        }

        if (showStudentEditor) {
            StudentEditorDialog(
                student = studentToEdit,
                onDismiss = { showStudentEditor = false },
                onDelete = {
                    studentToEdit?.let { studentToDelete = it }
                    showStudentEditor = false
                },
                onSave = { item ->
                    scope.launch {
                        if (item.id == 0L) {
                            dao.insertStudent(item)
                        } else {
                            dao.updateStudent(item)
                        }
                    }
                    if (selectedStudent?.id == item.id) selectedStudent = item
                    showStudentEditor = false
                }
            )
        }

        studentToCollect?.let { student ->
            PaymentDialog(
                student = student,
                pendingAmount = (feeRecords.filter { it.studentId == student.id }.sumOf { it.amount } - payments.filter { it.studentId == student.id }.sumOf { it.amount }).coerceAtLeast(0L),
                onDismiss = { studentToCollect = null },
                onSave = { payment ->
                    scope.launch { dao.insertPayment(payment) }
                    studentToCollect = null
                    tab = "Fees"
                }
            )
        }

        paymentToEdit?.let { payment ->
            val paymentStudent = students.firstOrNull { it.id == payment.studentId }
            if (paymentStudent != null) {
                PaymentDialog(
                    student = paymentStudent,
                    pendingAmount = (feeRecords.filter { it.studentId == payment.studentId }.sumOf { it.amount } - payments.filter { it.studentId == payment.studentId }.sumOf { it.amount } + payment.amount).coerceAtLeast(0L),
                    existingPayment = payment,
                    onDismiss = { paymentToEdit = null },
                    onSave = { updated ->
                        scope.launch { dao.updatePayment(updated) }
                        paymentToEdit = null
                    },
                    onDelete = {
                        paymentToDelete = payment
                        paymentToEdit = null
                    }
                )
            }
        }

        paymentToDelete?.let { payment ->
            AlertDialog(
                onDismissRequest = { paymentToDelete = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White,
                title = { Text("Delete collected fee?") },
                text = {
                    Text("Delete this ${rupees(payment.amount)} payment? This cannot be undone.")
                },
                confirmButton = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { paymentToDelete = null },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFD5E4DB))
                        ) {
                            Text("Cancel", color = Ink)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch { dao.deletePayment(payment) }
                                paymentToDelete = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFB3261E)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFB3261E)
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                },
                dismissButton = {}
            )
        }

        studentToDelete?.let { student ->
            AlertDialog(
                onDismissRequest = { studentToDelete = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White,
                title = { Text("Delete student?") },
                text = {
                    Text("Delete ${student.name} and their fee history? This cannot be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dao.deleteStudent(student) }
                        studentToDelete = null
                    }) { Text("Delete", color = Color(0xFFB3261E)) }
                },
                dismissButton = {
                    TextButton(onClick = { studentToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
}

@Composable
private fun DashboardScreen(
    modifier: Modifier,
    studentCount: Int,
    collection: Long,
    pending: Long,
    totalFees: Long,
    monthKey: String,
    profileName: String,
    onProfileClick: () -> Unit
) {
    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "Align Tuition",
                    color = Ink,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    profileName.ifBlank { "Profile & Settings" },
                    color = Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onProfileClick),
                shape = RoundedCornerShape(50),
                color = Color(0xFFE5F4E9),
                border = BorderStroke(1.dp, Color(0xFFD8E9DE))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = profileName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                        color = Green,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(monthKey, color = Muted, fontSize = 13.sp)
        Text("Fee Overview", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardMetricCard(
                "Total Students", studentCount.toString(),
                Color(0xFF176B43), Color(0xFFE5F4E9), Modifier.weight(1f)
            )
            DashboardMetricCard(
                "Pending Fees", rupees(pending),
                Color(0xFF9A4A08), Color(0xFFFFF0DF), Modifier.weight(1f)
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardMetricCard(
                "Total Collection", rupees(collection),
                Color(0xFF245BB5), Color(0xFFE5EFFD), Modifier.weight(1f),
                "This month"
            )
            DashboardMetricCard(
                "Total Fees", rupees(totalFees),
                Color(0xFF7442AD), Color(0xFFF0E8FC), Modifier.weight(1f),
                "Monthly fee total"
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFD8E9DE))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Premium & App Details",
                    color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "Premium subscription is not activated in this app yet.",
                    color = Muted, fontSize = 13.sp
                )
                Divider(color = Color(0xFFE5EAE7))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Align Tuition", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("Tuition fee management", color = Muted, fontSize = 12.sp)
                    }
                    Text(
                        "Version 1.0.0",
                        color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardMetricCard(
    title: String,
    value: String,
    accent: Color,
    background: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                title, color = accent, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, minLines = 2
            )
            Text(
                value, color = Ink, fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, color = accent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Muted, fontSize = 13.sp)
            Text(value, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Green, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StudentsScreen(
    modifier: Modifier,
    students: List<StudentEntity>,
    pendingByStudent: Map<Long, Long>,
    onAdd: () -> Unit,
    onEdit: (StudentEntity) -> Unit,
    onStudentClick: (StudentEntity) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = students.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.phone.contains(search) ||
            it.className.contains(search, ignoreCase = true)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF123D2F))) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF123D2F))
                    .padding(start = 16.dp, end = 16.dp, top = 44.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp)),
                    singleLine = true,
                    placeholder = { Text("Search name, phone or class") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(16.dp)
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Page)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filtered.isEmpty()) {
                    EmptyState(
                        if (students.isEmpty()) "No students added" else "No matching students",
                        "Add a student with their own fee details."
                    )
                }
                filtered.forEach { student ->
                    StudentCard(
                        student = student,
                        pendingPayment = pendingByStudent[student.id] ?: 0L,
                        onClick = { onStudentClick(student) },
                        onEdit = { onEdit(student) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(56.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = Green,
            contentColor = Color.White
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add student",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun StudentCard(
    student: StudentEntity,
    pendingPayment: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onEdit
        ),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2EBE5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp)
                        .background(Color(0xFFE7F3EC), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        student.name.trim().firstOrNull()?.uppercase() ?: "?",
                        color = Color(0xFF176B48),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        student.name,
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        listOf(student.className, student.phone)
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                if (student.monthlyFee > 0L) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupees(student.monthlyFee), color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(feeTypeLabel(student), color = Muted, fontSize = 11.sp)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFEDF1EE), thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFFF1F8F4), RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Pending Payment", color = Color(0xFF52665A), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        rupees(pendingPayment),
                        color = if (pendingPayment > 0L) Color(0xFFB45309) else Green,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    if (pendingPayment > 0L) "DUE" else "CLEAR",
                    color = if (pendingPayment > 0L) Color(0xFF9A4A08) else Green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(
                        if (pendingPayment > 0L) Color(0xFFFFEBD1) else Color(0xFFDDF3E5),
                        RoundedCornerShape(8.dp)
                    ).padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StudentDetailsScreen(
    modifier: Modifier,
    student: StudentEntity,
    payments: List<FeePaymentEntity>,
    feeRecords: List<FeeRecordEntity>,
    onEdit: () -> Unit,
    onRecordFee: () -> Unit,
    onCollect: () -> Unit,
    onPaymentClick: (FeePaymentEntity) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 12.dp, top = 44.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                student.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

        val pendingPaymentTotal = (feeRecords.sumOf { it.amount } - payments.sumOf { it.amount }).coerceAtLeast(0L)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFD8E9DE))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Pending Payment",
                    color = Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    rupees(pendingPaymentTotal),
                    color = Green,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text("Payment history", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (payments.isEmpty()) {
            EmptyState("No payments yet", "Payments collected for this student will appear here.")
        } else {
            payments.forEach { payment ->
                Card(
                    onClick = { onPaymentClick(payment) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                paymentPeriodLabel(payment.monthKey),
                                color = Ink,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${payment.method} • ${dateText(payment.paidAt)}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            if (payment.note.isNotBlank()) {
                                Text(payment.note, color = Muted, fontSize = 12.sp)
                            }
                        }
                        Text(rupees(payment.amount), color = Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Page)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRecordFee,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE0AD00)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFFFFF8E1),
                    contentColor = Color(0xFF795900)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Filled.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        "Record Fee",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Button(
                onClick = onCollect,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        "Collect Fee",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }


}
}


@Composable
private fun FeesScreen(
    modifier: Modifier,
    payments: List<FeePaymentEntity>,
    students: List<StudentEntity>,
    monthKey: String,
    monthCollection: Long,
    pending: Long,
    onCollect: (StudentEntity) -> Unit
) {
    val names = students.associate { it.id to it.name }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("This month • $monthKey", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                StatCard("Collected", rupees(monthCollection), "This month")
            }
            Column(Modifier.weight(1f)) {
                StatCard("Pending", rupees(pending), "This month")
            }
        }

        Text("Record a payment", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (students.isEmpty()) {
            EmptyState("No students", "Add a student before recording a payment.")
        } else {
            students.forEach { student ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(student.name, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("Monthly fee: ${rupees(student.monthlyFee)}", color = Muted, fontSize = 12.sp)
                        }
                        Button(onClick = { onCollect(student) }) { Text("Collect") }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Payment history", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (payments.isEmpty()) {
            EmptyState("No payments yet", "Payments you record will appear here.")
        } else {
            payments.forEach { payment ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(names[payment.studentId] ?: "Student", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${paymentPeriodLabel(payment.monthKey)} • ${payment.method} • ${dateText(payment.paidAt)}",
                                color = Muted,
                                fontSize = 11.sp
                            )
                            if (payment.note.isNotBlank()) {
                                Text(payment.note, color = Muted, fontSize = 12.sp)
                            }
                        }
                        Text(rupees(payment.amount), color = Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
private fun RecordFeeScreen(
    student: StudentEntity,
    history: List<FeeRecordEntity>,
    onBack: () -> Unit,
    onSave: suspend (StudentEntity, FeeRecordEntity) -> FeeRecordEntity,
    onDelete: suspend (FeeRecordEntity) -> Unit
) {
    val context = LocalContext.current

    val feeScreenScrollState = rememberScrollState()
    val feeScreenScope = rememberCoroutineScope()
    var editingRecord by remember(student.id) { mutableStateOf<FeeRecordEntity?>(null) }
    var displayedHistory by remember(student.id) { mutableStateOf(history) }
var showDeleteConfirmation by remember(student.id) { mutableStateOf(false) }
    var entryKind by remember(student.id) {
        mutableStateOf(
            context.getSharedPreferences("tuition_preferences", 0)
                .getString("record_fee_entry_kind", "FEES") ?: "FEES"
        )
    }
    var titleText by remember(student.id) { mutableStateOf("") }
    var feeType by remember(student.id) {
        mutableStateOf(
            context.getSharedPreferences("tuition_preferences", 0)
                .getString("record_fee_type", "MONTHLY") ?: "MONTHLY"
        )
    }
    var feeText by remember(student.id) { mutableStateOf("") }
    var noteText by remember(student.id) { mutableStateOf("") }
    var startDate by remember(student.id) {
        mutableStateOf(LocalDate.now().withDayOfMonth(1).toString())
    }
    var endDate by remember(student.id) { mutableStateOf(LocalDate.now().toString()) }

    var selectedMonthKey by remember(student.id) {
        mutableStateOf(YearMonth.now().toString())
    }
    var selectedYear by remember(student.id) {
        mutableStateOf(LocalDate.now().year.toString())
    }
    var monthMenuExpanded by remember { mutableStateOf(false) }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 12.dp, top = 44.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (editingRecord == null) "Record Fee" else "Edit Fee Record",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(feeScreenScrollState)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
    

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFE0E8E3)),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = entryKind == "FEES",
                        onClick = {
                            entryKind = "FEES"
                            context.getSharedPreferences("tuition_preferences", 0)
                                .edit().putString("record_fee_entry_kind", "FEES").apply()
                            error = ""
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color(0xFF212121)
                        ),
                        label = { Text("Fees") }
                    )
                    FilterChip(
                        selected = entryKind == "OTHER",
                        onClick = {
                            entryKind = "OTHER"
                            context.getSharedPreferences("tuition_preferences", 0)
                                .edit().putString("record_fee_entry_kind", "OTHER").apply()
                            error = ""
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color(0xFF212121)
                        ),
                        label = { Text("Other Payment") }
                    )
                }

                if (entryKind == "FEES") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "MONTHLY" to "Monthly",
                        "YEARLY" to "Yearly",
                        "DATE_RANGE" to "Custom"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = feeType == value,
                            onClick = {
                                feeType = value
                                context.getSharedPreferences("tuition_preferences", 0)
                                    .edit().putString("record_fee_type", value).apply()
                                error = ""
                            },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, maxLines = 1, textAlign = TextAlign.Center)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF14532D),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                }

                if (entryKind == "FEES") {
                when (feeType) {
                    "MONTHLY" -> {
                        val monthLabel = runCatching {
                            YearMonth.parse(selectedMonthKey).format(
                                DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                            )
                        }.getOrDefault(selectedMonthKey)

                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = monthLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select month") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    disabledContainerColor = Color.White,
                                    focusedBorderColor = Green,
                                    unfocusedBorderColor = Color(0xFFD8DEE8)
                                ),
                                trailingIcon = {
                                    Text("▾", color = Green)
                                }
                            )
                            Box(Modifier.matchParentSize().clickable {
                                monthMenuExpanded = true
                            })
                            DropdownMenu(
                                expanded = monthMenuExpanded,
                                onDismissRequest = { monthMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 280.dp).background(Color.White)
                            ) {
                                (-12L..12L).map { YearMonth.now().plusMonths(it) }
                                    .forEach { month ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    month.format(
                                                        DateTimeFormatter.ofPattern(
                                                            "MMMM yyyy",
                                                            Locale.getDefault()
                                                        )
                                                    )
                                                )
                                            },
                                            onClick = {
                                                selectedMonthKey = month.toString()
                                                monthMenuExpanded = false
                                                error = ""
                                            }
                                        )
                                    }
                            }
                        }
                    }

                    "YEARLY" -> {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedYear,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select year") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    disabledContainerColor = Color.White,
                                    focusedBorderColor = Green,
                                    unfocusedBorderColor = Color(0xFFD8DEE8)
                                ),
                                trailingIcon = {
                                    Text("▾", color = Green)
                                }
                            )
                            Box(Modifier.matchParentSize().clickable {
                                yearMenuExpanded = true
                            })
                            DropdownMenu(
                                expanded = yearMenuExpanded,
                                onDismissRequest = { yearMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 280.dp).background(Color.White)
                            ) {
                                ((LocalDate.now().year - 10)..(LocalDate.now().year + 10))
                                    .forEach { year ->
                                        DropdownMenuItem(
                                            text = { Text(year.toString()) },
                                            onClick = {
                                                selectedYear = year.toString()
                                                yearMenuExpanded = false
                                                error = ""
                                            }
                                        )
                                    }
                            }
                        }
                    }

                    "DATE_RANGE" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = startDate,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("From date", maxLines = 1) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                Box(Modifier.matchParentSize().clickable {
                                    showFeeDatePicker(context, startDate) {
                                        startDate = it
                                        error = ""
                                    }
                                })
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = endDate,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("To date", maxLines = 1) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                Box(Modifier.matchParentSize().clickable {
                                    showFeeDatePicker(context, endDate) {
                                        endDate = it
                                        error = ""
                                    }
                                })
                            }
                        }
                    }
                }

                } else {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it; error = "" },
                        label = { Text("Title *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it; error = "" },
                    label = {
                        Text(
                            if (entryKind == "OTHER") "Amount (₹) *" else when (feeType) {
                                "YEARLY" -> "Yearly fee (₹) *"
                                "DATE_RANGE" -> "Total period fee (₹) *"
                                else -> "Monthly fee (₹) *"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB3261E), fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        val fee = feeText.toLongOrNull()
                        val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
                        val end = runCatching { LocalDate.parse(endDate) }.getOrNull()

                        when {
                            entryKind == "OTHER" && titleText.isBlank() ->
                                error = "Enter a title."
                            fee == null || fee <= 0L ->
                                error = "Enter a fee amount greater than zero."
                            entryKind == "FEES" && feeType == "DATE_RANGE" &&
                                (start == null || end == null || end.isBefore(start)) ->
                                error = "Select a valid From Date and To Date."
                            else -> {
                                val updatedStudent = student
                                val record = FeeRecordEntity(
                                    id = editingRecord?.id ?: 0L,
                                    studentId = student.id,
                                    feeType = if (entryKind == "OTHER") "OTHER" else feeType,
                                    amount = fee,
                                    startDate = when (feeType) { "DATE_RANGE" -> startDate; "MONTHLY" -> selectedMonthKey; else -> selectedYear },
                                    endDate = if (feeType == "DATE_RANGE") endDate else "",
                                    recordedAt = editingRecord?.recordedAt ?: System.currentTimeMillis(),
                                    note = noteText.trim(),
                                    title = if (entryKind == "OTHER") titleText.trim() else ""
                                )
                                feeScreenScope.launch {
                                    try {
                                        val savedRecord = onSave(updatedStudent, record)
                                        keyboardController?.hide()
                                        displayedHistory = if (record.id == 0L) {
                                            listOf(savedRecord) + displayedHistory
                                        } else {
                                            displayedHistory.map { if (it.id == savedRecord.id) savedRecord else it }
                                        }
                                        editingRecord = null
                                        feeText = ""
                                        noteText = ""
                                        titleText = ""
                                        selectedMonthKey = YearMonth.now().toString()
                                        selectedYear = LocalDate.now().year.toString()
                                        startDate = LocalDate.now().withDayOfMonth(1).toString()
                                        endDate = LocalDate.now().toString()
                                        error = ""
                                    } catch (e: Exception) {
                                        error = "Could not save fee record. Please try again."
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green)
                ) {
                    Text(if (editingRecord != null) "Save Changes" else if (entryKind == "OTHER") "Save Other Payment" else "Save Fee")
                }

                if (editingRecord != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFB3261E)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFB3261E)
                            )
                        ) {
                            Text("Delete", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                editingRecord = null
                                feeText = ""
                                noteText = ""
                                titleText = ""
                                selectedMonthKey = YearMonth.now().toString()
                                selectedYear = LocalDate.now().year.toString()
                                startDate = LocalDate.now().withDayOfMonth(1).toString()
                                endDate = LocalDate.now().toString()
                                error = ""
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFD8DEE8)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Muted
                            )
                        ) {
                            Text("Cancel Edit", maxLines = 1)
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        if (displayedHistory.isEmpty()) {
            EmptyState("No fee records yet", "Saved Monthly, Yearly and Custom fees will appear here.")
        } else {
            displayedHistory.forEach { record ->
                val typeLabel = if (record.feeType == "OTHER") {
                    record.title.ifBlank { "Other Payment" }
                } else when (record.feeType) {
                    "YEARLY" -> "Yearly"
                    "DATE_RANGE" -> "Custom"
                    else -> "Monthly"
                }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        feeScreenScope.launch {
                            feeScreenScrollState.animateScrollTo(0)
                        }
                        editingRecord = record
                        entryKind = if (record.feeType == "OTHER") "OTHER" else "FEES"
                        titleText = record.title
                        feeType = if (record.feeType == "OTHER") "MONTHLY" else record.feeType
                        feeText = record.amount.toString()
                        noteText = record.note
                        when (record.feeType) {
                            "MONTHLY" -> selectedMonthKey = record.startDate
                            "YEARLY" -> selectedYear = record.startDate
                            "DATE_RANGE" -> {
                                startDate = record.startDate
                                endDate = record.endDate
                            }
                        }
                        error = ""
                    },
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(typeLabel, color = Ink, fontWeight = FontWeight.SemiBold)
                            when (record.feeType) {
                                "OTHER" -> Unit
                                "MONTHLY" -> Text(record.startDate, color = Muted, fontSize = 12.sp)
                                "YEARLY" -> Text(record.startDate, color = Muted, fontSize = 12.sp)
                                "DATE_RANGE" -> Text(
                                    "${record.startDate} to ${record.endDate}",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                "Recorded ${dateText(record.recordedAt)}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            if (record.note.isNotBlank()) {
                                Text(
                                    "Note: ${record.note}",
                                    color = Ink,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Text(rupees(record.amount), color = Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            title = { Text("Delete fee record?") },
            text = {
                Text(
                    "This permanently deletes this fee-history entry."
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showDeleteConfirmation = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFD5E4DB))
                    ) {
                        Text("Cancel", color = Ink)
                    }

                    OutlinedButton(
                        onClick = {
                            val recordToDelete = editingRecord
                            if (recordToDelete != null) {
                                feeScreenScope.launch {
                                    try {
                                        onDelete(recordToDelete)
                                        displayedHistory = displayedHistory.filterNot { it.id == recordToDelete.id }
                                        editingRecord = null
                                        feeText = ""
                                        noteText = ""
                                        titleText = ""
                                        selectedMonthKey = YearMonth.now().toString()
                                        selectedYear = LocalDate.now().year.toString()
                                        startDate = LocalDate.now().withDayOfMonth(1).toString()
                                        endDate = LocalDate.now().toString()
                                        error = ""
                                        showDeleteConfirmation = false
                                    } catch (e: Exception) {
                                        error = "Could not delete fee record. Please try again."
                                        showDeleteConfirmation = false
                                    }
                                }
                            } else {
                                showDeleteConfirmation = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB3261E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB3261E)
                        )
                    ) {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {}
        )
    }

}


@Composable
private fun StudentEditorDialog(
    student: StudentEntity?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (StudentEntity) -> Unit
) {
    var name by remember(student?.id) { mutableStateOf(student?.name.orEmpty()) }
    var phone by remember(student?.id) { mutableStateOf(student?.phone.orEmpty()) }
    var className by remember(student?.id) { mutableStateOf(student?.className.orEmpty()) }
    var error by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Green,
        unfocusedBorderColor = Color(0xFFD8DEE8),
        focusedLabelColor = Green,
        unfocusedLabelColor = Muted,
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White
    )

    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        onDismissRequest = onDismiss,
        title = { Text(if (student == null) "Add Student" else "Edit Student") },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("Student name *") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = className,
                    onValueChange = { className = it },
                    label = { Text("Class / Batch") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (student != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB3261E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB3261E)
                        )
                    ) {
                        Text("Delete Student")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFD8DEE8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                    ) {
                        Text("Cancel")
                    }

                    OutlinedButton(
                        onClick = {
                            if (name.isBlank()) {
                                error = "Student name is required."
                            } else {
                                onSave(
                                    StudentEntity(
                                        id = student?.id ?: 0L,
                                        name = name.trim(),
                                        phone = phone.trim(),
                                        className = className.trim(),
                                        monthlyFee = student?.monthlyFee ?: 0L,
                                        feeType = student?.feeType ?: "MONTHLY",
                                        feeStartDate = student?.feeStartDate.orEmpty(),
                                        feeEndDate = student?.feeEndDate.orEmpty(),
                                        joinedAt = student?.joinedAt
                                            ?: System.currentTimeMillis()
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Green),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Green)
                    ) {
                        Text("Save")
                    }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun PaymentDialog(
    student: StudentEntity,
    pendingAmount: Long,
    existingPayment: FeePaymentEntity? = null,
    onDismiss: () -> Unit,
    onSave: (FeePaymentEntity) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var amountText by remember(student.id, existingPayment?.id) {
        mutableStateOf(existingPayment?.amount?.toString().orEmpty())
    }
    var method by remember(student.id, existingPayment?.id) {
        mutableStateOf(
            if (existingPayment?.method == "Cash") "Cash"
            else if (existingPayment != null) "Online"
            else "Cash"
        )
    }
    var note by remember(student.id, existingPayment?.id) {
        mutableStateOf(existingPayment?.note.orEmpty())
    }
    var error by remember(student.id, existingPayment?.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFF7FAF8),
        title = {
            Text(
                if (existingPayment == null) "Collect Fee" else "Edit Collected Fee",
                color = Color(0xFF123D2F),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.filter(Char::isDigit)
                        error = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount (₹)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )

                Text(
                    "PAYMENT METHOD",
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "Online").forEach { option ->
                        FilterChip(
                            selected = method == option,
                            onClick = { method = option },
                            label = { Text(option) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFDCEFE3),
                                selectedLabelColor = Color(0xFF123D2F)
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
                }

                if (existingPayment != null && onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB3261E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB3261E)
                        )
                    ) {
                        Text("Delete", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFD5E4DB))
                ) {
                    Text("Cancel", color = Ink)
                }

                Button(
                    onClick = {
                        val amount = amountText.toLongOrNull()
                        when {
                            amount == null || amount <= 0L ->
                                error = "Enter an amount greater than zero."
                            amount > pendingAmount ->
                                error = "Amount cannot exceed ${rupees(pendingAmount)}."
                            else -> onSave(
                                FeePaymentEntity(
                                    id = existingPayment?.id ?: 0L,
                                    studentId = student.id,
                                    monthKey = existingPayment?.monthKey
                                        ?: currentFeePeriodKey(student)
                                        ?: YearMonth.now().toString(),
                                    amount = amount,
                                    paidAt = existingPayment?.paidAt
                                        ?: System.currentTimeMillis(),
                                    method = method,
                                    note = note.trim()
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = pendingAmount > 0L,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF123D2F)
                    )
                ) {
                    Text(
                        if (existingPayment == null) "Collect" else "Save",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Ink, fontWeight = FontWeight.Bold)
        Text(message, color = Muted, fontSize = 13.sp)
    }
}

private fun rupees(amount: Long): String =
    "₹" + "%,d".format(Locale("en", "IN"), amount)

private fun dateText(timestamp: Long): String =
    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(timestamp))


@Composable
private fun ProfileScreen(
    modifier: Modifier,
    profile: ProfileEntity?,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(profile?.name) { mutableStateOf(profile?.name.orEmpty()) }
    var mobile by remember(profile?.mobile) { mutableStateOf(profile?.mobile.orEmpty()) }
    var savedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().background(Page)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Profile & Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Manage your account",
                    color = Color(0xFFD0E6DC),
                    fontSize = 12.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF176B4D))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(66.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                                color = Color(0xFF176B4D),
                                fontSize = 27.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            name.ifBlank { "Your Profile" },
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            mobile.ifBlank { "Add your mobile number" },
                            color = Color(0xFFD9EEE4),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2EBE5))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Personal Information",
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; savedMessage = false },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = mobile,
                        onValueChange = {
                            mobile = it.filter { c -> c.isDigit() || c == '+' || c == ' ' || c == '-' }
                                .take(16)
                            savedMessage = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Mobile Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            onSave(name.trim(), mobile.trim())
                            savedMessage = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Profile")
                    }
                    if (savedMessage) {
                        Text(
                            "Profile Saved",
                            color = Green,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Text(
                "APP SETTINGS",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            ProfileSettingCard(
                icon = Icons.Default.CloudUpload,
                title = "Backup & Restore",
                subtitle = "Protect your tuition data",
                status = "Coming soon"
            )
            ProfileSettingCard(
                icon = Icons.Default.Sync,
                title = "Sync Settings",
                subtitle = "Manage future data synchronization",
                status = "Coming soon"
            )
            ProfileSettingCard(
                icon = Icons.Default.Security,
                title = "Privacy & Security",
                subtitle = "Account and data protection",
                status = "Coming soon"
            )
            ProfileSettingCard(
                icon = Icons.Default.Settings,
                title = "App Information",
                subtitle = "Align Tuition",
                status = "Version 1.0.0"
            )
        }
    }
}

@Composable
private fun ProfileSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    status: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2EBE5))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFE5F4E9)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Green)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Text(status, color = Muted, fontSize = 10.sp)
        }
    }
}
