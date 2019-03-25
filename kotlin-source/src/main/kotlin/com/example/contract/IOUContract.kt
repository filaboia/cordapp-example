package com.example.contract

import com.example.state.IOUState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [IOUState], which in turn encapsulates an [IOU].
 *
 * For a new [IOU] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [IOU].
 * - An Create() command with the public keys of both the lender and the borrower.
 *
 * All contracts must sub-class the [Contract] interface.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "com.example.contract.IOUContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        when (command.value) {
            is Commands.Create -> requireThat {
                // Generic constraints around the IOU transaction.
                "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
                "Only one output state should be created." using (tx.outputs.size == 1)
                val out = tx.outputsOfType<IOUState>().single()
                "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)
                "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))

                // IOU-specific constraints.
                "The IOU's value must be non-negative." using (out.value > 0)
            }
            is Commands.Pay -> requireThat {
                "Não deve possuir outputs." using (tx.outputs.isEmpty())
                "Só uma entrada deve ser consumida." using (tx.inputs.size == 1)
                val entrada = tx.inputsOfType<IOUState>().single()
                "Todos os participantes devem assinar." using (command.signers.containsAll(entrada.participants.map { it.owningKey }))
            }
            is Commands.PartialPay -> requireThat {
                "Só uma saída deve ser gerada." using (tx.outputs.size == 1)
                "Só uma entrada deve ser consumida." using (tx.inputs.size == 1)

                val entrada = tx.inputsOfType<IOUState>().single()
                val saida = tx.outputsOfType<IOUState>().single()

                "O lender não pode mudar" using (entrada.lender == saida.lender)
                "O borrower não pode mudar" using (entrada.borrower == saida.borrower)
                "Os participantes não podem mudar" using (entrada.participants == saida.participants)

                "O valor da saida não pode ser negativo." using (saida.value > 0)
                "O valor da saida deve ser menor que o da entrada." using (saida.value < entrada.value)
                "Todos os participantes devem assinar." using (command.signers.containsAll(entrada.participants.map { it.owningKey }))
            }
        }

    }

    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        class Create : Commands
        class Pay : Commands
        class PartialPay : Commands
    }
}
