package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DebtCalculator
import com.example.data.DebtRelation
import com.example.data.DebtSettlementEntity
import com.example.data.HangBillRepository
import com.example.data.OutingEntity
import com.example.data.ParticipantEntity
import com.example.data.UpcomingOutingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    object Dashboard : Screen
    object OutingsList : Screen
    data class OutingDetail(val outingId: Int) : Screen
    object AddOuting : Screen
    data class EditOuting(val outingId: Int) : Screen
    object DebtsList : Screen
    object StatsReports : Screen
}

class HangBillViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: HangBillRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = HangBillRepository(database.dao)
        prepopulateSampleDataIfEmpty()
    }

    // Navigation State
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Dashboard)
    val currentScreen: StateFlow<Screen> = _currentScreen

    private val navigationStack = mutableListOf<Screen>(Screen.Dashboard)

    fun navigateTo(screen: Screen) {
        if (screen == Screen.Dashboard) {
            navigationStack.clear()
            navigationStack.add(Screen.Dashboard)
        } else {
            // Avoid duplicate pushes
            if (navigationStack.lastOrNull() != screen) {
                navigationStack.add(screen)
            }
        }
        _currentScreen.value = screen
    }

    fun navigateBack(): Boolean {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.size - 1)
            val previous = navigationStack.last()
            _currentScreen.value = previous
            return true
        }
        return false
    }

    // Outings filters
    val searchQuery = MutableStateFlow("")
    val selectedCategory = MutableStateFlow("الكل")

    // Database Streams
    val outings: StateFlow<List<OutingEntity>> = repository.allOutings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allParticipants: StateFlow<List<ParticipantEntity>> = repository.allParticipants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSettlements: StateFlow<List<DebtSettlementEntity>> = repository.allSettlements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingOutings: StateFlow<List<UpcomingOutingEntity>> = repository.allUpcomingOutings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered Outings
    val filteredOutings: StateFlow<List<OutingEntity>> = combine(
        outings,
        searchQuery,
        selectedCategory
    ) { list, query, cat ->
        list.filter { outing ->
            val matchesQuery = query.isBlank() || outing.title.contains(query, ignoreCase = true) || outing.note.contains(query, ignoreCase = true)
            val matchesCat = cat == "الكل" || outing.type == cat
            matchesQuery && matchesCat
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All active global debts calculated across all outings
    val globalActiveDebts: StateFlow<List<DebtRelation>> = combine(
        outings,
        allParticipants,
        allSettlements
    ) { outingsList, participantsList, settlementsList ->
        val groupDebts = mutableListOf<DebtRelation>()
        outingsList.forEach { outing ->
            val partsObj = participantsList.filter { it.outingId == outing.id }
            val setsObj = settlementsList.filter { it.outingId == outing.id }
            val computedDebts = DebtCalculator.calculateDebts(
                outingId = outing.id,
                participants = partsObj,
                settlements = setsObj,
                outingTitle = outing.title,
                dateString = outing.date
            )
            groupDebts.addAll(computedDebts.filter { !it.isSettled })
        }
        groupDebts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Historically settled debts list (where isSettled is true)
    val globalSettledDebts: StateFlow<List<DebtRelation>> = combine(
        outings,
        allParticipants,
        allSettlements
    ) { outingsList, participantsList, settlementsList ->
        val GroupSettled = mutableListOf<DebtRelation>()
        outingsList.forEach { outing ->
            val partsObj = participantsList.filter { it.outingId == outing.id }
            val setsObj = settlementsList.filter { it.outingId == outing.id }
            val computedDebts = DebtCalculator.calculateDebts(
                outingId = outing.id,
                participants = partsObj,
                settlements = setsObj,
                outingTitle = outing.title,
                dateString = outing.date
            )
            GroupSettled.addAll(computedDebts.filter { it.isSettled })
        }
        GroupSettled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Create / Save Outing Action
    fun saveOuting(
        id: Int = 0,
        title: String,
        date: String,
        type: String,
        note: String,
        splitEqually: Boolean,
        participants: List<Pair<String, Pair<Double, Double>>> // Name -> (Spend, Paid)
    ) {
        viewModelScope.launch {
            val totalBill = participants.sumOf { it.second.second } // based on actual paid
            val singleShare = if (splitEqually && participants.isNotEmpty()) totalBill / participants.size else 0.0

            val outingEntity = OutingEntity(
                id = id,
                title = title.ifBlank { "طلعة جديدة" },
                date = date.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) },
                type = type.ifBlank { "مطعم" },
                note = note,
                splitEqually = splitEqually
            )

            val partEntities = participants.map { (name, spendPaid) ->
                ParticipantEntity(
                    id = 0,
                    outingId = id,
                    name = name.ifBlank { "صديق" },
                    spendAmount = if (splitEqually) singleShare else spendPaid.first,
                    paidAmount = spendPaid.second
                )
            }

            repository.insertOutingWithParticipants(outingEntity, partEntities)
            navigateTo(Screen.OutingsList)
        }
    }

    // Delete Outing
    fun deleteOuting(id: Int) {
        viewModelScope.launch {
            repository.deleteOutingAndDetails(id)
            navigateTo(Screen.OutingsList)
        }
    }

    fun saveUpcomingOuting(
        title: String,
        date: String,
        plans: String,
        approxCost: Double,
        participants: String
    ) {
        viewModelScope.launch {
            val upcoming = UpcomingOutingEntity(
                title = title.ifBlank { "طلعة قادمة" },
                date = date.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) },
                plans = plans,
                approxCost = approxCost,
                participants = participants
            )
            repository.insertUpcomingOuting(upcoming)
        }
    }

    fun deleteUpcomingOuting(id: Int) {
        viewModelScope.launch {
            repository.deleteUpcomingOuting(id)
        }
    }

    // Record Debt Settlement
    fun settleDebt(outingId: Int, fromUser: String, toUser: String, amount: Double) {
        viewModelScope.launch {
            val settlement = DebtSettlementEntity(
                outingId = outingId,
                fromName = fromUser,
                toName = toUser,
                amount = amount
            )
            repository.insertSettlement(settlement)
        }
    }

    // Helper functions for specific outing details stream
    fun getParticipantsForOuting(outingId: Int): Flow<List<ParticipantEntity>> {
        return repository.getParticipantsForOuting(outingId)
    }

    fun getSettlementsForOuting(outingId: Int): Flow<List<DebtSettlementEntity>> {
        return repository.getSettlementsForOuting(outingId)
    }

    // Prepoulate Database with Beautiful Arabic Dummy Data matching user's templates
    private fun prepopulateSampleDataIfEmpty() {
        val prefs = getApplication<Application>().getSharedPreferences("hangbill_prefs", android.content.Context.MODE_PRIVATE)
        val hasPrepopulated = prefs.getBoolean("has_prepopulated", false)
        if (!hasPrepopulated) {
            viewModelScope.launch {
                val list = repository.allOutings.first()
                if (list.isEmpty()) {
                    insertSampleOutings()
                }
                prefs.edit().putBoolean("has_prepopulated", true).apply()
            }
        }
    }

    private suspend fun insertSampleOutings() {
        // Group spending statistics
        // 1. عشاء مطعم نوبو
        val o1 = OutingEntity(
            id = 1,
            title = "عشاء مطعم نوبو",
            date = "2026-03-15",
            type = "مطعم",
            note = "عشاء السوشي الفاخر مع الشلة بمناسبة الاحتفال بترقية عبدالعزيز وشراء فهد لسيارته الجديدة",
            splitEqually = false
        )
        val p1 = listOf(
            ParticipantEntity(id = 1, outingId = 1, name = "عبدالعزيز", spendAmount = 800.0, paidAmount = 1200.0),
            ParticipantEntity(id = 2, outingId = 1, name = "سارة", spendAmount = 540.0, paidAmount = 100.0),
            ParticipantEntity(id = 3, outingId = 1, name = "فهد", spendAmount = 500.0, paidAmount = 540.0)
        )
        repository.insertOutingWithParticipants(o1, p1)

        // 2. غداء شلة الجمعة
        val o2 = OutingEntity(
            id = 2,
            title = "غداء شلة الجمعة",
            date = "2026-05-12",
            type = "مطعم",
            note = "الجمعة الأسبوعية في مطعم الكبسة والمأكولات الشعبية الشامية واليمنية دائمًا تجمعنا",
            splitEqually = true
        )
        val p2 = listOf(
            ParticipantEntity(id = 4, outingId = 2, name = "عبدالعزيز", spendAmount = 150.0, paidAmount = 150.0),
            ParticipantEntity(id = 5, outingId = 2, name = "سارة", spendAmount = 150.0, paidAmount = 300.0),
            ParticipantEntity(id = 6, outingId = 2, name = "فهد", spendAmount = 150.0, paidAmount = 0.0)
        )
        repository.insertOutingWithParticipants(o2, p2)

        // 3. قهوة المساء
        val o3 = OutingEntity(
            id = 3,
            title = "قهوة المساء",
            date = "2026-05-10",
            type = "كافيه",
            note = "شرب الماتشا واللاتيه والاستمتاع بحديث العمل ومراجعة الأفكار البرمجية الجديدة للمشروع",
            splitEqually = true
        )
        val p3 = listOf(
            ParticipantEntity(id = 7, outingId = 3, name = "سارة", spendAmount = 28.3, paidAmount = 85.0),
            ParticipantEntity(id = 8, outingId = 3, name = "فهد", spendAmount = 28.3, paidAmount = 0.0),
            ParticipantEntity(id = 9, outingId = 3, name = "عبدالعزيز", spendAmount = 28.3, paidAmount = 0.0)
        )
        repository.insertOutingWithParticipants(o3, p3)

        // 4. رحلة أبها
        val o4 = OutingEntity(
            id = 4,
            title = "رحلة أبها",
            date = "2026-05-05",
            type = "سفر",
            note = "رحلة استكشاف جبال عسير والأجواء الخلابة الرائعة. السكن والمواصلات والمصاريف شاملة الكشتة برعاية سارة وأحمد",
            splitEqually = false
        )
        val p4 = listOf(
            ParticipantEntity(id = 10, outingId = 4, name = "سارة", spendAmount = 800.0, paidAmount = 1500.0),
            ParticipantEntity(id = 11, outingId = 4, name = "عبدالعزيز", spendAmount = 800.0, paidAmount = 0.0),
            ParticipantEntity(id = 12, outingId = 4, name = "فهد", spendAmount = 800.0, paidAmount = 900.0)
        )
        repository.insertOutingWithParticipants(o4, p4)

        // 5. بوليفارد سيتي
        val o5 = OutingEntity(
            id = 5,
            title = "بوليفارد سيتي",
            date = "2026-05-01",
            type = "ترفيه",
            note = "دخول الألعاب والفعاليات والسينما في عطلة نهاية الأسبوع",
            splitEqually = true
        )
        val p5 = listOf(
            ParticipantEntity(id = 13, outingId = 5, name = "فهد", spendAmount = 400.0, paidAmount = 1200.0),
            ParticipantEntity(id = 14, outingId = 5, name = "سارة", spendAmount = 400.0, paidAmount = 0.0),
            ParticipantEntity(id = 15, outingId = 5, name = "عبدالعزيز", spendAmount = 400.0, paidAmount = 0.0)
        )
        repository.insertOutingWithParticipants(o5, p5)

        // 6. عشاء الخميس
        val o6 = OutingEntity(
            id = 6,
            title = "عشاء الخميس",
            date = "2026-05-14",
            type = "مطعم",
            note = "عشاء البرجر والبطاطس السريع اللذيذ في مطعم شيف برجر",
            splitEqually = false
        )
        val p6 = listOf(
            ParticipantEntity(id = 16, outingId = 6, name = "محمد العلي", spendAmount = 350.0, paidAmount = 280.0),
            ParticipantEntity(id = 17, outingId = 6, name = "خالد منصور", spendAmount = 200.0, paidAmount = 200.0),
            ParticipantEntity(id = 18, outingId = 6, name = "سارة فهد", spendAmount = 150.0, paidAmount = 60.0),
            ParticipantEntity(id = 19, outingId = 6, name = "أنت", spendAmount = 150.0, paidAmount = 310.0) // Creditor for 160.0
        )
        repository.insertOutingWithParticipants(o6, p6)
    }
}
