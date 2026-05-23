package com.example.data

import kotlinx.coroutines.flow.Flow

class HangBillRepository(private val dao: HangBillDao) {

    val allOutings: Flow<List<OutingEntity>> = dao.getAllOutings()
    val allParticipants: Flow<List<ParticipantEntity>> = dao.getAllParticipants()
    val allSettlements: Flow<List<DebtSettlementEntity>> = dao.getAllSettlements()
    val allUpcomingOutings: Flow<List<UpcomingOutingEntity>> = dao.getAllUpcomingOutings()

    suspend fun getOutingById(id: Int): OutingEntity? {
        return dao.getOutingById(id)
    }

    suspend fun insertUpcomingOuting(upcoming: UpcomingOutingEntity) {
        dao.insertUpcomingOuting(upcoming)
    }

    suspend fun deleteUpcomingOuting(id: Int) {
        dao.deleteUpcomingOutingById(id)
    }

    fun getParticipantsForOuting(outingId: Int): Flow<List<ParticipantEntity>> {
        return dao.getParticipantsForOuting(outingId)
    }

    suspend fun getParticipantsForOutingSync(outingId: Int): List<ParticipantEntity> {
        return dao.getParticipantsForOutingSync(outingId)
    }

    fun getSettlementsForOuting(outingId: Int): Flow<List<DebtSettlementEntity>> {
        return dao.getSettlementsForOuting(outingId)
    }

    suspend fun insertOutingWithParticipants(
        outing: OutingEntity,
        participants: List<ParticipantEntity>
    ): Long {
        val outingId = dao.insertOuting(outing).toInt()
        // Delete old participants if editing (since we rewrite them upon save/edit)
        dao.deleteParticipantsForOuting(outingId)
        participants.forEach {
            dao.insertParticipant(it.copy(outingId = outingId))
        }
        return outingId.toLong()
    }

    suspend fun deleteOutingAndDetails(outingId: Int) {
        dao.deleteOutingById(outingId)
        dao.deleteParticipantsForOuting(outingId)
        dao.deleteSettlementsForOuting(outingId)
    }

    suspend fun insertParticipant(participant: ParticipantEntity) {
        dao.insertParticipant(participant)
    }

    suspend fun insertSettlement(settlement: DebtSettlementEntity) {
        dao.insertSettlement(settlement)
    }

    suspend fun deleteSettlementById(id: Int) {
        dao.deleteSettlementById(id)
    }
}
