package com.example.data

import kotlin.math.abs
import kotlin.math.min

data class DebtRelation(
    val fromUser: String,
    val toUser: String,
    val amount: Double,
    val outingId: Int,
    val outingTitle: String = "",
    val isSettled: Boolean = false,
    val settledAmount: Double = 0.0,
    val dateString: String = ""
)

object DebtCalculator {
    fun calculateDebts(
        outingId: Int,
        participants: List<ParticipantEntity>,
        settlements: List<DebtSettlementEntity> = emptyList(),
        outingTitle: String = "",
        dateString: String = ""
    ): List<DebtRelation> {
        if (participants.isEmpty()) return emptyList()

        // calculate net balance = what they paid minus what they should spent
        val balances = participants.map { 
            it.name to (it.paidAmount - it.spendAmount)
        }.toMap().toMutableMap()

        // Separate into debtors and creditors
        val debtors = mutableListOf<Pair<String, Double>>()
        val creditors = mutableListOf<Pair<String, Double>>()

        for ((name, bal) in balances) {
            if (bal < -0.01) {
                debtors.add(name to bal)
            } else if (bal > 0.01) {
                creditors.add(name to bal)
            }
        }

        // Sort debtors ascending (most negative first, i.e. owes the most)
        debtors.sortBy { it.second }
        // Sort creditors descending (most positive first, i.e. owed the most)
        creditors.sortByDescending { it.second }

        val theoreticalDebts = mutableListOf<DebtRelation>()

        var dIdx = 0
        var cIdx = 0

        val debtorBalances = debtors.map { it.first to abs(it.second) }.toMutableList()
        val creditorBalances = creditors.map { it.first to it.second }.toMutableList()

        while (dIdx < debtorBalances.size && cIdx < creditorBalances.size) {
            val (debtorName, debtorOwes) = debtorBalances[dIdx]
            val (creditorName, creditorOwed) = creditorBalances[cIdx]

            if (debtorOwes < 0.01) {
                dIdx++
                continue
            }
            if (creditorOwed < 0.01) {
                cIdx++
                continue
            }

            val transfer = min(debtorOwes, creditorOwed)
            if (transfer > 0.01) {
                theoreticalDebts.add(
                    DebtRelation(
                        fromUser = debtorName,
                        toUser = creditorName,
                        amount = transfer,
                        outingId = outingId,
                        outingTitle = outingTitle,
                        dateString = dateString
                    )
                )
            }

            debtorBalances[dIdx] = debtorName to (debtorOwes - transfer)
            creditorBalances[cIdx] = creditorName to (creditorOwed - transfer)

            if (debtorBalances[dIdx].second < 0.01) {
                dIdx++
            }
            if (creditorBalances[cIdx].second < 0.01) {
                cIdx++
            }
        }

        // Now reconcile with actual settlements recorded
        // group settlements by fromName -> toName
        val matchKey = { s: DebtSettlementEntity -> "${s.fromName} -> ${s.toName}" }
        val settlementSum = settlements.groupBy(matchKey).mapValues { entry ->
            entry.value.sumOf { it.amount }
        }

        return theoreticalDebts.map { debt ->
            val key = "${debt.fromUser} -> ${debt.toUser}"
            val paidSoFar = settlementSum[key] ?: 0.0
            val isFullyPaid = paidSoFar >= (debt.amount - 0.05)
            debt.copy(
                isSettled = isFullyPaid,
                settledAmount = paidSoFar
            )
        }
    }
}
