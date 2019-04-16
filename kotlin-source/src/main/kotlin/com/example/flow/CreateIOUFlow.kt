package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUContract
import com.example.contract.IOUContract.Companion.IOU_CONTRACT_ID
import com.example.flow.CreateIOUFlow.Acceptor
import com.example.flow.CreateIOUFlow.Initiator
import com.example.state.CashState
import com.example.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the IOU encapsulated
 * within an [IOUState].
 *
 * In our simple example, the [Acceptor] always accepts a valid IOU.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object CreateIOUFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val cashStateRef: StateRef,
                    val iouValue: Int,
                    val otherParty: Party) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new IOU.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val iouState = IOUState(iouValue, serviceHub.myInfo.legalIdentities.first(), otherParty)
            val txCommand = Command(IOUContract.Commands.Create(), iouState.participants.map { it.owningKey })

            val txBuilder = criaTransacao()
                    .addOutputState(iouState, IOU_CONTRACT_ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartyFlow = initiateFlow(otherParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }

        @Suspendable
        fun criaTransacao(): TransactionBuilder {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            val transactionState = serviceHub.loadState(cashStateRef)
            val eu = serviceHub.myInfo.legalIdentities.first()
            val cashState = transactionState.data as CashState

            requireThat {
                "Eu devo ser o dono do dinheiro a ser transferido" using (cashState.dono == eu)
                "Eu não posso transferir pra mim" using (cashState.dono != otherParty)
            }

            val cashStateNovo = CashState(iouValue, otherParty, listOf(cashState.dono, otherParty))
            val diferencaTransferencia = cashState.value - iouValue

            if (diferencaTransferencia > 0) {
                val cashStateAlteracao = CashState(diferencaTransferencia, cashState.dono, listOf(cashState.dono, otherParty))

                val txCommand = Command(IOUContract.Commands.TransferirParcial(), cashState.participants.map { it.owningKey })
                return TransactionBuilder(notary)
                        .addInputState(StateAndRef(transactionState, cashStateRef))
                        .addOutputState(cashStateNovo, transactionState.contract)
                        .addOutputState(cashStateAlteracao, transactionState.contract)
                        .addCommand(txCommand)
            } else {
                val txCommand = Command(IOUContract.Commands.Transferir(), cashState.participants.map { it.owningKey })
                return TransactionBuilder(notary)
                        .addInputState(StateAndRef(transactionState, cashStateRef))
                        .addOutputState(cashStateNovo, transactionState.contract)
                        .addCommand(txCommand)
            }
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction." using (output is IOUState)
                    val iou = output as IOUState
                    "I won't accept IOUs with a value over 100." using (iou.value <= 100)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
