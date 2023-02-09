import io.ktor.http.cio.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

object SessionManager {

    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val clients = mutableMapOf<UUID, DefaultWebSocketServerSession>()

    private var sessionState: WebRTCSessionState = WebRTCSessionState.Impossible

    fun onSessionStarted(sessionId: UUID, session: DefaultWebSocketServerSession) {
        sessionManagerScope.launch {
            mutex.withLock {
                if (clients.size >= 2) {
                    sessionManagerScope.launch(NonCancellable) {
                        session.send(Frame.Close()) // only two peers are supported
                    }
                    return@launch
                }
                clients[sessionId] = session
                session.send("Added as a client: $sessionId")
                if (clients.size >= 2) {
                    sessionState = WebRTCSessionState.Ready
                }
                notifyAboutStateUpdate()
            }
        }
    }

    fun onMessage(sessionId: UUID, message: String) {
        when {
            message.startsWith(MessageType.STATE.toString(), true) -> handleState(sessionId)
            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer(sessionId, message)
            message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer(sessionId, message)
            message.startsWith(MessageType.ICE.toString(), true) -> handleIce(sessionId, message)
        }
    }

    private fun handleState(sessionId: UUID) {
        sessionManagerScope.launch {
            clients[sessionId]?.send("${MessageType.STATE} $sessionState")
        }
    }

    private fun handleOffer(sessionId: UUID, message: String) {
        if (sessionState != WebRTCSessionState.Ready) {
            // Disabled to enable renegotiation
//            error("Session should be in Ready state to handle offer (Current state = $sessionState)")
        }
        sessionState = WebRTCSessionState.Creating
        println("handling offer from $sessionId")
        notifyAboutStateUpdate()
        val clientToSendOfferTo = clientToSendMessageTo(sessionId)
        clientToSendOfferTo.send(message)
    }

    private fun handleAnswer(sessionId: UUID, message: String) {
        if (sessionState != WebRTCSessionState.Creating) {
            error("Session should be in Creating state to handle answer")
        }
        println("handling answer from $sessionId")
        val clientToSendAnswerTo = clientToSendMessageTo(sessionId)
        clientToSendAnswerTo.send(message)
        sessionState = WebRTCSessionState.Active
        notifyAboutStateUpdate()
    }

    private fun handleIce(sessionId: UUID, message: String) {
        println("handling ice from $sessionId")
        val clientToSendIceTo = clientToSendMessageTo(sessionId)
        clientToSendIceTo.send(message)
    }

    private fun clientToSendMessageTo(sessionId: UUID) =
        clients.filterKeys { it != sessionId }.values.first()

    fun onSessionClose(sessionId: UUID) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(sessionId)
                sessionState = WebRTCSessionState.Impossible
                notifyAboutStateUpdate()
            }
        }
    }

    enum class WebRTCSessionState {
        Active, // Offer and Answer messages has been sent
        Creating, // Creating session, offer has been sent
        Ready, // Both clients available and ready to initiate session
        Impossible // We have less than two clients
    }

    enum class MessageType {
        STATE,
        OFFER,
        ANSWER,
        ICE
    }

    private fun notifyAboutStateUpdate() {
        clients.forEach { (_, client) ->
            client.send("${MessageType.STATE} $sessionState")
        }
    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}
