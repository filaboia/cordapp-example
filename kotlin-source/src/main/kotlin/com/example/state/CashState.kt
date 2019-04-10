package com.example.state

import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.QueryableState

class CashState(val value: Int,
                val dono: Party,
                override val participants: List<AbstractParty>,
                override val linearId: UniqueIdentifier = UniqueIdentifier()):
        LinearState {

}