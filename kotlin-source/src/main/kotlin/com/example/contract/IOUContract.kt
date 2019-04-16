package com.example.contract

import com.example.state.CashState
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
                val dividaSaida = tx.outputsOfType<IOUState>().single()
                "The lender and the borrower cannot be the same entity." using (dividaSaida.lender != dividaSaida.borrower)
                "All of the participants must be signers." using (command.signers.containsAll(dividaSaida.participants.map { it.owningKey }))

                // IOU-specific constraints.
                "The IOU's value must be non-negative." using (dividaSaida.value > 0)

                val dinheiroEntrada = tx.inputsOfType<CashState>().find { it.dono == dividaSaida.lender}
                "O dono original do dinheiro deve ser quem está emprestando." using (dinheiroEntrada != null)

                val dinheiroSaida = tx.outputsOfType<CashState>().find { it.dono == dividaSaida.borrower}
                "O dono do novo dinheiro deve ser quem está recebendo." using (dinheiroSaida != null)
                "O valor da dívida deve ser igual ao valor transferido." using ((dinheiroSaida?.value ?: 0) == dividaSaida.value)
            }
            is Commands.Pay -> requireThat {
                "Não deve possuir outputs." using (tx.outputs.isEmpty())
                "Só uma entrada deve ser consumida." using (tx.inputs.size == 1)
                val dividaEntrada = tx.inputsOfType<IOUState>().single()
                "Todos os participantes devem assinar." using (command.signers.containsAll(dividaEntrada.participants.map { it.owningKey }))

                val dinheiroEntrada = tx.inputsOfType<CashState>().find { it.dono == dividaEntrada.lender}
                "O dono original do dinheiro deve ser quem está emprestando." using (dinheiroEntrada != null)

                val dinheiroSaida = tx.outputsOfType<CashState>().find { it.dono == dividaEntrada.borrower}
                "O dono do novo dinheiro deve ser quem está recebendo." using (dinheiroSaida != null)
                "O valor da dívida deve ser igual ao valor transferido." using ((dinheiroSaida?.value ?: 0) == dividaEntrada.value)
            }
            is Commands.PartialPay -> requireThat {
                "Só uma saída deve ser gerada." using (tx.outputs.size == 1)
                "Só uma entrada deve ser consumida." using (tx.inputs.size == 1)

                val dividaEntrada = tx.inputsOfType<IOUState>().single()
                val dividaSaida = tx.outputsOfType<IOUState>().single()

                "O lender não pode mudar" using (dividaEntrada.lender == dividaSaida.lender)
                "O borrower não pode mudar" using (dividaEntrada.borrower == dividaSaida.borrower)
                "Os participantes não podem mudar" using (dividaEntrada.participants == dividaSaida.participants)

                "O valor da saida não pode ser negativo." using (dividaSaida.value > 0)
                "O valor da saida deve ser menor que o da entrada." using (dividaSaida.value < dividaEntrada.value)
                "Todos os participantes devem assinar." using (command.signers.containsAll(dividaEntrada.participants.map { it.owningKey }))

                val dinheiroEntrada = tx.inputsOfType<CashState>().find { it.dono == dividaEntrada.lender}
                "O dono original do dinheiro deve ser quem está emprestando." using (dinheiroEntrada != null)

                val dinheiroSaida = tx.outputsOfType<CashState>().find { it.dono == dividaEntrada.borrower}
                "O dono do novo dinheiro deve ser quem está recebendo." using (dinheiroSaida != null)
                "O valor subtraído da dívida deve ser igual ao valor transferido." using ((dinheiroSaida?.value ?: 0) == dividaEntrada.value - dividaSaida.value)
            }
            is Commands.Emitir -> requireThat {
                "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
                "Only one output state should be created." using (tx.outputs.size == 1)
                val out = tx.outputsOfType<CashState>().single()
                "O emissor deve ser o PartyC." using (out.dono.name.toString() == "O=PartyC,L=Paris,C=FR")
                "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))

                "The value must be non-negative." using (out.value > 0)
            }
            is Commands.TransferirParcial -> requireThat {
                "Duas saídas devem ser gerada." using (tx.outputs.size == 2)
                "Só uma entrada deve ser consumida." using (tx.inputs.size == 1)

                val entrada = tx.inputsOfType<CashState>().single()

                val alteracaoDinheiro = tx.outputsOfType<CashState>().find { it.dono == entrada.dono }
                val novoDinheiro = tx.outputsOfType<CashState>().find { it.dono != entrada.dono}

                "O dono original do dinheiro deve continuar possuindo algum dinheiro" using (alteracaoDinheiro != null)
                "Um novo dono deve possuir dinheiro" using (novoDinheiro != null)

                "O valor total após a transferencia deve ser igual ao valor original" using ((alteracaoDinheiro?.value ?: 0) + (novoDinheiro?.value ?: 0) == entrada.value)
                "O dono original do dinheiro deve continuar com um valor maior que 0" using ((alteracaoDinheiro?.value ?: 0) > 0)
                "O novo dono deve possuir um valor maior que 0" using ((novoDinheiro?.value ?: 0) > 0)
            }
            is Commands.Transferir -> requireThat {
                "Só uma saída deve ser gerada." using (tx.outputs.size == 1)
                "Só uma entrada deve ser consumida." using (tx.inputs.size == 1)

                val entrada = tx.inputsOfType<CashState>().single()

                val novoDinheiro = tx.outputsOfType<CashState>().find { it.dono != entrada.dono}

                "Um novo dono deve possuir dinheiro" using (novoDinheiro != null)

                "O valor total após a transferencia deve ser igual ao valor original" using ((novoDinheiro?.value ?: 0) == entrada.value)
                "O novo dono deve possuir um valor maior que 0" using ((novoDinheiro?.value ?: 0) > 0)
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
        class Emitir : Commands
        class Transferir : Commands
        class TransferirParcial : Commands
    }
}
