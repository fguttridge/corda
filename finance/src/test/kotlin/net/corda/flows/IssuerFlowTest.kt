package net.corda.flows

import com.google.common.util.concurrent.ListenableFuture
import net.corda.contracts.testing.calculateRandomlySizedAmounts
import net.corda.flows.IssuerFlow.IssuanceRequester
import net.corda.core.contracts.Amount
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.PartyAndReference
import net.corda.core.contracts.currency
import net.corda.core.flows.FlowStateMachine
import net.corda.core.map
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.core.utilities.DUMMY_NOTARY_KEY
import net.corda.testing.*
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IssuerFlowTest {
    lateinit var net: MockNetwork
    lateinit var notaryNode: MockNetwork.MockNode
    lateinit var bankOfCordaNode: MockNetwork.MockNode
    lateinit var bankClientNode: MockNetwork.MockNode

    @Before
    fun setUp() {

    }

    @Test
    fun `test issuer flow`() {
        net = MockNetwork(false, true)
        ledger {
            notaryNode = net.createNotaryNode(null, DUMMY_NOTARY.name, DUMMY_NOTARY_KEY)
            bankOfCordaNode = net.createPartyNode(notaryNode.info.address, BOC.name, BOC_KEY)
            bankClientNode = net.createPartyNode(notaryNode.info.address, MEGA_CORP.name, MEGA_CORP_KEY)

            // using default IssueTo Party Reference
            val issueToPartyAndRef = MEGA_CORP.ref(OpaqueBytes.Companion.of(123))
            val (issuer, issuerResult) = runIssuerAndIssueRequester(bankClientNode, bankOfCordaNode, 1000000.DOLLARS, issueToPartyAndRef)
            assertEquals(issuerResult.get(), issuer.get().resultFuture.get())

            // try to issue an amount of a restricted currency
            assertFailsWith<Exception> {
                runIssuerAndIssueRequester(bankClientNode, bankOfCordaNode, Amount(100000L, currency("BRL")), issueToPartyAndRef).issueRequestResult.get()
            }

            bankOfCordaNode.stop()
            bankClientNode.stop()

            bankOfCordaNode.manuallyCloseDB()
            bankClientNode.manuallyCloseDB()
        }
    }

    @Test
    fun `test issue flow to self`() {
        net = MockNetwork(false, true)
        ledger {
            notaryNode = net.createNotaryNode(null, DUMMY_NOTARY.name, DUMMY_NOTARY_KEY)
            bankOfCordaNode = net.createPartyNode(notaryNode.info.address, BOC.name, BOC_KEY)

            // using default IssueTo Party Reference
            val issueToPartyAndRef = BOC.ref(OpaqueBytes.Companion.of(123))
            val (issuer, issuerResult) = runIssuerAndIssueRequester(bankOfCordaNode, bankOfCordaNode, 1000000.DOLLARS, issueToPartyAndRef)

            bankOfCordaNode.stop()
            bankOfCordaNode.manuallyCloseDB()
        }
    }

    @Test
    fun `test concurrent issuer flow`() {

        net = MockNetwork(false, true)
        ledger {
            notaryNode = net.createNotaryNode(null, DUMMY_NOTARY.name, DUMMY_NOTARY_KEY)
            bankOfCordaNode = net.createPartyNode(notaryNode.info.address, BOC.name, BOC_KEY)
            bankClientNode = net.createPartyNode(notaryNode.info.address, MEGA_CORP.name, MEGA_CORP_KEY)

            // using default IssueTo Party Reference
            val issueToPartyAndRef = MEGA_CORP.ref(OpaqueBytes.of(123))

            // this test exercises the Cashflow issue and move subflows to ensure consistent spending of issued states
            val amounts = calculateRandomlySizedAmounts(10000.DOLLARS, 10, 10, Random())
            val handles = amounts.map { amount ->
                runIssuerAndIssueRequester(bankClientNode, bankOfCordaNode, amount.toInt().DOLLARS, issueToPartyAndRef)
            }
            handles.forEach {
                require (it.issueRequestResult.get() is SignedTransaction)
            }

            bankOfCordaNode.stop()
            bankClientNode.stop()
        }
    }

    private fun runIssuerAndIssueRequester(requesterNode: MockNode, issuerNode: MockNode, amount: Amount<Currency>, issueToPartyAndRef: PartyAndReference) : RunResult {
        val issuerFuture = issuerNode.initiateSingleShotFlow(IssuerFlow.IssuanceRequester::class) {
            otherParty -> IssuerFlow.Issuer(issueToPartyAndRef.party)
        }.map { it.fsm }

        val issueRequest = IssuanceRequester(amount, issueToPartyAndRef.party, issueToPartyAndRef.reference, issuerNode.info.legalIdentity)
        val issueRequestResultFuture = requesterNode.smm.add(issueRequest).resultFuture

        return IssuerFlowTest.RunResult(issuerFuture, issueRequestResultFuture)
    }

    private data class RunResult(
            val issuer: ListenableFuture<FlowStateMachine<*>>,
            val issueRequestResult: ListenableFuture<SignedTransaction>
    )
}