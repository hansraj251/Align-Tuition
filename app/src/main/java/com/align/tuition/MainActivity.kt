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
    val pending = students.sumOf { student ->
        if (currentFeePeriodKey(student) == null) 0L
        else FeeMath.pendingFee(
            student.monthlyFee,
            paidByStudent[student.id] ?: 0L
        )
    }

    var tab by remember { mutableStateOf("Dashboard") }
    var showStudentEditor by remember { mutableStateOf(false) }
    var studentToEdit by remember { mutableStateOf<StudentEntity?>(null) }
    var selectedStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var recordFeeStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var studentToCollect by remember { mutableStateOf<StudentEntity?>(null) }
    var studentToDelete by remember { mutableStateOf<StudentEntity?>(null) }

    BackHandler(enabled = selectedStudent != null) {
        selectedStudent = null
        tab = "Students"
    }

    Box(Modifier.fillMaxSize().background(Page)) {
        Scaffold(
            containerColor = if (recordFeeStudent != null) Color(0xFF123D2F) else Page,
            bottomBar = {
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
                    NavigationBarItem(
                        selected = tab == "Fees",
                        onClick = { tab = "Fees" },
                        icon = { Icon(Icons.Default.ReceiptLong, null) },
                        label = { Text("Fees") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Green,
                            indicatorColor = Green
                        )
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
            ) {
                Column(
                    modifier = if (recordFeeStudent != null) {
                        Modifier.fillMaxSize().background(Page)
                    } else {
                        Modifier.fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    }
                ) {
                    if (recordFeeStudent != null) {
                    val feeStudent = recordFeeStudent!!
                    RecordFeeScreen(
                        student = feeStudent,
                        history = feeRecords.filter { it.studentId == feeStudent.id },
                        onBack = { recordFeeStudent = null },
                        onSave = { updatedStudent, record ->
                            scope.launch {
                                dao.updateStudent(updatedStudent)
                                if (record.id == 0L) {
                                    dao.insertFeeRecord(record)
                                } else {
                                    dao.updateFeeRecord(record)
                                }
                            }
                            selectedStudent = updatedStudent
                        },
                        onDelete = { record ->
                            scope.launch {
                                dao.deleteFeeRecord(record)
                            }
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
                        onEdit = {
                            studentToEdit = detailStudent
                            showStudentEditor = true
                        },
                        onRecordFee = { recordFeeStudent = detailStudent },
                        onCollect = { studentToCollect = detailStudent },
                        onBack = {
                            selectedStudent = null
                            tab = "Students"
                        }
                    )
                } else when (tab) {
                    "Students" -> StudentsScreen(
                        modifier = Modifier.weight(1f),
                        students = students,
                        paidByStudent = paidByStudent,
                        onAdd = {
                            studentToEdit = null
                            showStudentEditor = true
                        },
                        onEdit = {
                            studentToEdit = it
                            showStudentEditor = true
                        },
                        onCollect = { studentToCollect = it },
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
                        monthKey = currentMonth,

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
                onDismiss = { studentToCollect = null },
                onSave = { payment ->
                    scope.launch { dao.insertPayment(payment) }
                    studentToCollect = null
                    tab = "Fees"
                }
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
    monthKey: String
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(monthKey, color = Muted, fontSize = 13.sp)
        Text("Fee overview", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Total Students", color = Muted, fontSize = 13.sp)
                    Text("$studentCount", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("This Month Collection", color = Muted, fontSize = 13.sp)
                    Text("₹$collection", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Pending Fees", color = Muted, fontSize = 13.sp)
                    Text("₹$pending", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                }
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
    paidByStudent: Map<Long, Long>,
    onAdd: () -> Unit,
    onEdit: (StudentEntity) -> Unit,
    onCollect: (StudentEntity) -> Unit,
    onStudentClick: (StudentEntity) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = students.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.phone.contains(search) ||
            it.className.contains(search, ignoreCase = true)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search name, phone or class") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Spacer(Modifier.height(10.dp))

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        paidThisMonth = paidByStudent[student.id] ?: 0L,
                        onClick = { onStudentClick(student) },
                        onEdit = { onEdit(student) },
                        onCollect = { onCollect(student) }
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
    paidThisMonth: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCollect: () -> Unit
) {
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onEdit
        ),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(student.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(student.className, student.phone)
                            .filter { it.isNotBlank() }.joinToString(" • ")
                            .ifBlank { "No class or phone added" },
                        color = Muted, fontSize = 12.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rupees(student.monthlyFee), color = Green, fontWeight = FontWeight.Bold)
                    Text(feeTypeLabel(student), color = Muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val due = FeeMath.pendingFee(student.monthlyFee, paidThisMonth)
            Text(
                if (due == 0L) "This month: Paid" else "This month due: ${rupees(due)}",
                color = if (due == 0L) Green else Color(0xFFB45309),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCollect) { Text("Collect fee") }
            }
        }
    }
}



@Composable
private fun StudentDetailsScreen(
    modifier: Modifier,
    student: StudentEntity,
    payments: List<FeePaymentEntity>,
    onEdit: () -> Unit,
    onRecordFee: () -> Unit,
    onCollect: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            Text(" Students")
        }

        Card(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFFE0E8E3)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(62.dp)
                        .background(Color(0xFFE5F3EC), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        student.name.trim().firstOrNull()?.uppercase() ?: "S",
                        color = Color(0xFF14532D),
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        student.name,
                        color = Ink,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        student.phone.ifBlank { "Phone number not added" },
                        color = Muted,
                        fontSize = 13.sp
                    )
                    if (student.className.isNotBlank()) {
                        Text(
                            "Class / Batch: ${student.className}",
                            color = Green,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text("Tap to edit profile", color = Muted, fontSize = 12.sp)
                }
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit student",
                    tint = Muted
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F7F2)),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFD8E9DE))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "FEE OVERVIEW",
                    color = Green,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fee plan", color = Muted, fontSize = 12.sp)
                        Text(
                            feeTypeLabel(student),
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Fee amount", color = Muted, fontSize = 12.sp)
                        Text(
                            rupees(student.monthlyFee),
                            color = Green,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (student.feeType == "DATE_RANGE") {
                    HorizontalDivider(color = Color(0xFFD8E9DE))
                    Text(
                        "${student.feeStartDate}  –  ${student.feeEndDate}",
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
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

        Text("Payment history", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (payments.isEmpty()) {
            EmptyState("No payments yet", "Payments collected for this student will appear here.")
        } else {
            payments.forEach { payment ->
                Card(
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
    onSave: (StudentEntity, FeeRecordEntity) -> Unit,
    onDelete: suspend (FeeRecordEntity) -> Unit
) {
    val feeScreenScrollState = rememberScrollState()
    val feeScreenScope = rememberCoroutineScope()
    var editingRecord by remember(student.id) { mutableStateOf<FeeRecordEntity?>(null) }
    var showDeleteConfirmation by remember(student.id) { mutableStateOf(false) }
    var entryKind by remember(student.id) { mutableStateOf("FEES") }
    var titleText by remember(student.id) { mutableStateOf("") }
    var feeType by remember(student.id) { mutableStateOf("MONTHLY") }
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
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                border = BorderStroke(1.dp, Color(0xFFD5E4DB)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Box(
                        modifier = Modifier.size(52.dp)
                            .background(Color(0xFFE5F3EC), RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            student.name.trim().firstOrNull()?.uppercase() ?: "S",
                            color = Color(0xFF14532D),
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "RECORDING FOR",
                            color = Green,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            student.name,
                            color = Ink,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (student.className.isNotBlank()) {
                            Text(student.className, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }

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
                        onClick = { entryKind = "FEES"; error = "" },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color(0xFF212121)
                        ),
                        label = { Text("Fees") }
                    )
                    FilterChip(
                        selected = entryKind == "OTHER",
                        onClick = { entryKind = "OTHER"; error = "" },
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
                            onClick = { feeType = value; error = "" },
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
                                val updatedStudent = if (entryKind == "FEES") {
                                    student.copy(
                                        monthlyFee = fee,
                                        feeType = feeType,
                                        feeStartDate = if (feeType == "DATE_RANGE") startDate else "",
                                        feeEndDate = if (feeType == "DATE_RANGE") endDate else ""
                                    )
                                } else student
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
                                onSave(updatedStudent, record)
                                editingRecord = null
                                feeType = "MONTHLY"
                                feeText = ""
                                noteText = ""
                                titleText = ""
                                entryKind = "FEES"
                                selectedMonthKey = YearMonth.now().toString()
                                selectedYear = LocalDate.now().year.toString()
                                startDate = LocalDate.now().withDayOfMonth(1).toString()
                                endDate = LocalDate.now().toString()
                                error = ""
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
                                feeType = "MONTHLY"
                                feeText = ""
                                noteText = ""
                                titleText = ""
                                entryKind = "FEES"
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

        if (history.isEmpty()) {
            EmptyState("No fee records yet", "Saved Monthly, Yearly and Custom fees will appear here.")
        } else {
            history.forEach { record ->
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
            title = { Text("Delete fee record?") },
            text = {
                Text("This permanently deletes this fee-history entry. The student profile will remain.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val recordToDelete = editingRecord
                    if (recordToDelete != null) {
                        feeScreenScope.launch {
                            try {
                                onDelete(recordToDelete)
                                editingRecord = null
                                feeType = "MONTHLY"
                                feeText = ""
                                noteText = ""
                                titleText = ""
                                entryKind = "FEES"
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
                }) {
                    Text("Delete", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
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
    onDismiss: () -> Unit,
    onSave: (FeePaymentEntity) -> Unit
) {
    var amountText by remember(student.id) { mutableStateOf(student.monthlyFee.toString()) }
    var method by remember(student.id) { mutableStateOf("Cash") }
    var note by remember(student.id) { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collect fee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(student.name, color = Green, fontWeight = FontWeight.Bold)
                Text("Monthly fee: ${rupees(student.monthlyFee)}", color = Muted)
                OutlinedTextField(
                    amountText,
                    { amountText = it; error = "" },
                    label = { Text("Amount received (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text("Payment method", color = Ink, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "UPI", "Bank").forEach {
                        FilterChip(
                            selected = method == it,
                            onClick = { method = it },
                            label = { Text(it) }
                        )
                    }
                }
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true
                )
                if (error.isNotBlank()) Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
                Text("Partial payments are supported.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toLongOrNull()
                if (amount == null || amount <= 0L) {
                    error = "Enter an amount greater than zero."
                } else {
                    onSave(
                        FeePaymentEntity(
                            studentId = student.id,
                            monthKey = currentFeePeriodKey(student) ?: YearMonth.now().toString(),
                            amount = amount,
                            method = method,
                            note = note.trim()
                        )
                    )
                }
            }) { Text("Record payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
