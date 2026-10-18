package com.align.tuition

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val className: String = "",
    val monthlyFee: Long,
    @ColumnInfo(defaultValue = "'MONTHLY'") val feeType: String = "MONTHLY",
    @ColumnInfo(defaultValue = "''") val feeStartDate: String = "",
    @ColumnInfo(defaultValue = "''") val feeEndDate: String = "",
    val joinedAt: Long = System.currentTimeMillis()
)


@Entity(
    tableName = "fee_records",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("studentId"), Index("recordedAt")]
)
data class FeeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val feeType: String,
    val amount: Long,
    val startDate: String = "",
    val endDate: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val title: String = ""
)

@Entity(
    tableName = "fee_payments",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("studentId"), Index("monthKey")]
)
data class FeePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val monthKey: String,
    val amount: Long,
    val paidAt: Long = System.currentTimeMillis(),
    val method: String = "Cash",
    val note: String = ""
)

@androidx.room.Dao
interface TuitionDao {
    @Query("SELECT * FROM students ORDER BY name COLLATE NOCASE")
    fun observeStudents(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM fee_payments ORDER BY paidAt DESC")
    fun observePayments(): Flow<List<FeePaymentEntity>>

    @Query("SELECT * FROM fee_records ORDER BY recordedAt DESC, id DESC")
    fun observeFeeRecords(): Flow<List<FeeRecordEntity>>

    @Query("SELECT * FROM fee_records WHERE studentId = :studentId ORDER BY recordedAt DESC, id DESC")
    fun observeFeeRecordsForStudent(studentId: Long): Flow<List<FeeRecordEntity>>

    @Insert
    suspend fun insertFeeRecord(record: FeeRecordEntity): Long

    @Update
    suspend fun updateFeeRecord(record: FeeRecordEntity)

    @Delete
    suspend fun deleteFeeRecord(record: FeeRecordEntity)

    @Insert
    suspend fun insertStudent(student: StudentEntity): Long

    @Update
    suspend fun updateStudent(student: StudentEntity)

    @Delete
    suspend fun deleteStudent(student: StudentEntity)

    @Insert
    suspend fun insertPayment(payment: FeePaymentEntity): Long

    @Update
    suspend fun updatePayment(payment: FeePaymentEntity)

    @Delete
    suspend fun deletePayment(payment: FeePaymentEntity)
}


private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE students ADD COLUMN feeType TEXT NOT NULL DEFAULT 'MONTHLY'"
        )
        database.execSQL(
            "ALTER TABLE students ADD COLUMN feeStartDate TEXT NOT NULL DEFAULT ''"
        )
        database.execSQL(
            "ALTER TABLE students ADD COLUMN feeEndDate TEXT NOT NULL DEFAULT ''"
        )
    }
}


private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fee_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                studentId INTEGER NOT NULL,
                feeType TEXT NOT NULL,
                amount INTEGER NOT NULL,
                startDate TEXT NOT NULL,
                endDate TEXT NOT NULL,
                recordedAt INTEGER NOT NULL,
                FOREIGN KEY(studentId) REFERENCES students(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_fee_records_studentId ON fee_records(studentId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_fee_records_recordedAt ON fee_records(recordedAt)")
    }
}



private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE fee_records ADD COLUMN title TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE fee_records ADD COLUMN note TEXT NOT NULL DEFAULT ''"
        )
    }
}

@Database(
    entities = [StudentEntity::class, FeePaymentEntity::class, FeeRecordEntity::class],
    version = 5,
    exportSchema = false
)
abstract class TuitionDatabase : RoomDatabase() {
    abstract fun tuitionDao(): TuitionDao

    companion object {
        @Volatile private var INSTANCE: TuitionDatabase? = null

        fun get(context: Context): TuitionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TuitionDatabase::class.java,
                    "align_tuition.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
    }
}
