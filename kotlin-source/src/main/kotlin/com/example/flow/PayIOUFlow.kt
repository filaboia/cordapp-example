package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUContract
import com.example.state.CashState
import com.example.state.IOUState
import net.corda.core.contracts.*
import net.corda.core.flows.*
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
object PayIOUFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val cashStateRef: StateRef,
                    val iouStateRef: StateRef) : FlowLogic<SignedTransaction>() {
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
            val transactionState = serviceHub.loadState(iouStateRef)
            val iouState = transactionState.data as IOUState
            val txCommand = Command(IOUContract.Commands.Pay(), iouState.participants.map { it.owningKey })
            val iouValue = iouState.value

            val cashTransactionState = serviceHub.loadState(cashStateRef)
            val eu = serviceHub.myInfo.legalIdentities.first()
            val outro = iouState.lender
            val cashState = cashTransactionState.data as CashState

            requireThat {
                "Eu devo ser quem pegou emprestado" using (iouState.borrower == eu)
                "Eu devo ser o dono do dinheiro usado pra pagar a dívida" using (cashState.dono == eu)
            }

            val cashStateNovo = CashState(iouValue, outro, listOf(eu, outro))
            val diferencaTransferencia = cashState.value - iouValue

            val txBuilder : TransactionBuilder

            if (diferencaTransferencia > 0) {
                val cashStateAlteracao = CashState(diferencaTransferencia, eu, listOf(eu, outro))

                val txCommand = Command(IOUContract.Commands.TransferirParcial(), cashState.participants.map { it.owningKey })
                txBuilder =  TransactionBuilder(notary)
                        .addInputState(StateAndRef(cashTransactionState, cashStateRef))
                        .addOutputState(cashStateNovo, cashTransactionState.contract)
                        .addOutputState(cashStateAlteracao, cashTransactionState.contract)
                        .addCommand(txCommand)
            } else {
                val txCommand = Command(IOUContract.Commands.Transferir(), cashState.participants.map { it.owningKey })
                txBuilder = TransactionBuilder(notary)
                        .addInputState(StateAndRef(cashTransactionState, cashStateRef))
                        .addOutputState(cashStateNovo, cashTransactionState.contract)
                        .addCommand(txCommand)
            }

            txBuilder.addInputState(StateAndRef(transactionState, iouStateRef))
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
            val otherPartyFlow = initiateFlow(iouState.lender)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {

                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
