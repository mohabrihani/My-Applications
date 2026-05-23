package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outings")
data class OutingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val date: String,
    val type: String, // مطعم, كافيه, سفر, ترفيه, غيره
    val note: String = "",
    val splitEqually: Boolean = false
)

@Entity(tableName = "participants")
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val outingId: Int,
    val name: String,
    val spendAmount: Double, // صرفه (عليه)
    val paidAmount: Double   // اللي جابه (دفع)
)

@Entity(tableName = "debt_settlements")
data class DebtSettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val outingId: Int,
    val fromName: String,
    val toName: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "upcoming_outings")
data class UpcomingOutingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val date: String,
    val plans: String = "",
    val approxCost: Double = 0.0,
    val participants: String = "" // Comma-separated or simple text list of people
)

@Dao
interface HangBillDao {
    // Upcoming Outings
    @Query("SELECT * FROM upcoming_outings ORDER BY date ASC, id DESC")
    fun getAllUpcomingOutings(): Flow<List<UpcomingOutingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUpcomingOuting(upcoming: UpcomingOutingEntity)

    @Query("DELETE FROM upcoming_outings WHERE id = :id")
    suspend fun deleteUpcomingOutingById(id: Int)
    // Outings
    @Query("SELECT * FROM outings ORDER BY date DESC, id DESC")
    fun getAllOutings(): Flow<List<OutingEntity>>

    @Query("SELECT * FROM outings WHERE id = :id")
    suspend fun getOutingById(id: Int): OutingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOuting(outing: OutingEntity): Long

    @Query("DELETE FROM outings WHERE id = :id")
    suspend fun deleteOutingById(id: Int)

    // Participants
    @Query("SELECT * FROM participants WHERE outingId = :outingId")
    fun getParticipantsForOuting(outingId: Int): Flow<List<ParticipantEntity>>

    @Query("SELECT * FROM participants WHERE outingId = :outingId")
    suspend fun getParticipantsForOutingSync(outingId: Int): List<ParticipantEntity>

    @Query("SELECT * FROM participants")
    fun getAllParticipants(): Flow<List<ParticipantEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipant(participant: ParticipantEntity)

    @Query("DELETE FROM participants WHERE outingId = :outingId")
    suspend fun deleteParticipantsForOuting(outingId: Int)

    // Debt Settlements
    @Query("SELECT * FROM debt_settlements WHERE outingId = :outingId")
    fun getSettlementsForOuting(outingId: Int): Flow<List<DebtSettlementEntity>>

    @Query("SELECT * FROM debt_settlements")
    fun getAllSettlements(): Flow<List<DebtSettlementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: DebtSettlementEntity)

    @Query("DELETE FROM debt_settlements WHERE outingId = :outingId")
    suspend fun deleteSettlementsForOuting(outingId: Int)

    @Query("DELETE FROM debt_settlements WHERE id = :id")
    suspend fun deleteSettlementById(id: Int)
}

@Database(
    entities = [OutingEntity::class, ParticipantEntity::class, DebtSettlementEntity::class, UpcomingOutingEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract val dao: HangBillDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hangbill_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
