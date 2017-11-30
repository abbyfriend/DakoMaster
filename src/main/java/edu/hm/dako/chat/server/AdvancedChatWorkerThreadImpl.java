package edu.hm.dako.chat.server;

import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.hm.dako.chat.client.SharedClientData;
import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ClientListEntry;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import edu.hm.dako.chat.connection.ConnectionTimeoutException;
import edu.hm.dako.chat.connection.EndOfFileException;

/**
 * Worker-Thread zur serverseitigen Bedienung einer Session mit einem Client.
 * Jedem Chat-Client wird serverseitig ein Worker-Thread zugeordnet.
 * 
 * @author Peter Mandl
 *
 */
public class AdvancedChatWorkerThreadImpl extends AbstractWorkerThread {

	private static Log log = LogFactory.getLog(AdvancedChatWorkerThreadImpl.class);

	public AdvancedChatWorkerThreadImpl(Connection con, SharedChatClientList clients, SharedServerCounter counter,
			ChatServerGuiInterface serverGuiInterface) {

		super(con, clients, counter, serverGuiInterface);
	}

	@Override
	public void run() {
		log.debug("ChatWorker-Thread erzeugt, Threadname: " + Thread.currentThread().getName());
		System.out.println("CHatWorker-Thread erzeugt");
		while (!finished && !Thread.currentThread().isInterrupted()) {
			try {
				// Warte auf naechste Nachricht des Clients und fuehre
				// entsprechende Aktion aus
				handleIncomingMessage();
			} catch (Exception e) {
				log.error("Exception waehrend der Nachrichtenverarbeitung");
				ExceptionHandler.logException(e);
			}
		}
		log.debug(Thread.currentThread().getName() + " beendet sich");
		closeConnection();
	}

	/**
	 * Senden eines Login-List-Update-Event an alle angemeldeten Clients
	 * 
	 * @param pdu
	 *            Zu sendende PDU
	 */
	protected void sendLoginListUpdateEvent(ChatPDU pdu) {

		// Liste der eingeloggten bzw. sich einloggenden User ermitteln
		Vector<String> clientList = clients.getRegisteredClientNameList();

		log.debug("Aktuelle Clientliste, die an die Clients uebertragen wird: " + clientList);

		pdu.setClients(clientList);

		Vector<String> clientList2 = clients.getClientNameList();
		for (String s : new Vector<String>(clientList2)) {
			log.debug("Fuer " + s + " wird Login- oder Logout-Event-PDU an alle aktiven Clients gesendet");

			ClientListEntry client = clients.getClient(s);
			try {
				if (client != null) {

					client.getConnection().send(pdu);
					log.debug("Login- oder Logout-Event-PDU an " + client.getUserName() + " gesendet");
					clients.incrNumberOfSentChatEvents(client.getUserName());
					eventCounter.getAndIncrement();
				}
			} catch (Exception e) {
				log.error("Senden einer Login- oder Logout-Event-PDU an " + s + " nicht moeglich");
				ExceptionHandler.logException(e);
			}
		}
	}

	@Override
	protected void loginRequestAction(ChatPDU receivedPdu) {

		ChatPDU pdu;
		log.debug("Login-Request-PDU fuer " + receivedPdu.getUserName() + " empfangen");

		// Neuer Client moechte sich einloggen, Client in Client-Liste
		// eintragen
		if (!clients.existsClient(receivedPdu.getUserName())) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
			ClientListEntry client = new ClientListEntry(receivedPdu.getUserName(), connection);
			client.setLoginTime(System.nanoTime());
			clients.createClient(receivedPdu.getUserName(), client);
			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.REGISTERING);
			log.debug("User " + receivedPdu.getUserName() + " nun in Clientliste");

			userName = receivedPdu.getUserName();
			clientThreadName = receivedPdu.getClientThreadName();
			Thread.currentThread().setName(receivedPdu.getUserName());
			log.debug("Laenge der Clientliste: " + clients.size());
			serverGuiInterface.incrNumberOfLoggedInClients();

			// AL Waitlist erstellen mit allen Clients
			clients.createWaitList(userName);

			// Login-Event an alle Clients (auch an den gerade aktuell
			// anfragenden) senden

			Vector<String> clientList = clients.getClientNameList();
			pdu = ChatPDU.createLoginEventPdu(userName, clientList, receivedPdu);
			sendLoginListUpdateEvent(pdu);

			// Login Response senden
//			ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(userName, receivedPdu); // AG brauchen wir hier nicht
//			
//
//			try {
//				clients.getClient(userName).getConnection().send(responsePdu);
//				System.out.println("ResponsePdu gesendet an " +  userName);
//			} catch (Exception e) {
//				log.debug("Senden einer Login-Response-PDU an " + userName + " fehlgeschlagen");
//				log.debug("Exception Message: " + e.getMessage());
//			}
//
//			log.debug("Login-Response-PDU an Client " + userName + " gesendet");

			// Zustand des Clients aendern
			clients.changeClientStatus(userName, ClientConversationStatus.REGISTERED); //AG hier oder erst nach response und confirm?

		} else {
			// User bereits angemeldet, Fehlermeldung an Client senden,
			// Fehlercode an Client senden
			pdu = ChatPDU.createLoginErrorResponsePdu(receivedPdu, ChatPDU.LOGIN_ERROR);

			try {
				connection.send(pdu);
				log.debug("Login-Response-PDU an " + receivedPdu.getUserName() + " mit Fehlercode "
						+ ChatPDU.LOGIN_ERROR + " gesendet");
			} catch (Exception e) {
				log.debug("Senden einer Login-Response-PDU an " + receivedPdu.getUserName() + " nicth moeglich");
				ExceptionHandler.logExceptionAndTerminate(e);
			}
		}
	}

	@Override
	protected void logoutRequestAction(ChatPDU receivedPdu) {

		ChatPDU pdu;
		logoutCounter.getAndIncrement();
		log.debug("Logout-Request von " + receivedPdu.getUserName() + ", LogoutCount = " + logoutCounter.get());

		log.debug("Logout-Request-PDU von " + receivedPdu.getUserName() + " empfangen");

		if (!clients.existsClient(userName)) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
		} else {

			// Event an Client versenden
			Vector<String> clientList = clients.getClientNameList();
			pdu = ChatPDU.createLogoutEventPdu(userName, clientList, receivedPdu);

			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.UNREGISTERING);
			sendLoginListUpdateEvent(pdu);
			serverGuiInterface.decrNumberOfLoggedInClients();

			// Der Thread muss hier noch warten, bevor ein Logout-Response gesendet
			// wird, da sich sonst ein Client abmeldet, bevor er seinen letzten Event
			// empfangen hat. das funktioniert nicht bei einer grossen Anzahl an
			// Clients (kalkulierte Events stimmen dann nicht mit tatsaechlich
			// empfangenen Events ueberein.
			// In der Advanced-Variante wird noch ein Confirm gesendet, das ist
			// sicherer.

			try {
				Thread.sleep(1000);
			} catch (Exception e) {
				ExceptionHandler.logException(e);
			}

			clients.changeClientStatus(receivedPdu.getUserName(), ClientConversationStatus.UNREGISTERED);

			// Logout Response senden
			sendLogoutResponse(receivedPdu.getUserName());

			// Worker-Thread des Clients, der den Logout-Request gesendet
			// hat, auch gleich zum Beenden markieren
			clients.finish(receivedPdu.getUserName());
			log.debug("Laenge der Clientliste beim Vormerken zum Loeschen von " + receivedPdu.getUserName() + ": "
					+ clients.size());
		}
	}

	@Override
	protected void chatMessageRequestAction(ChatPDU receivedPdu) {
			
		ClientListEntry client = null; 
		clients.setRequestStartTime(receivedPdu.getUserName(), startTime);
		clients.incrNumberOfReceivedChatMessages(receivedPdu.getUserName());
		serverGuiInterface.incrNumberOfRequests();
		log.debug("Chat-Message-Request-PDU von " + receivedPdu.getUserName() + " mit Sequenznummer "
				+ receivedPdu.getSequenceNumber() + " empfangen");

		if (!clients.existsClient(receivedPdu.getUserName())) {
			log.debug("User nicht in Clientliste: " + receivedPdu.getUserName());
		} else {
			//AG: Erstellen einer Waitlist, an richtiger Stelle?
			clients.createWaitList(userName); 
			
			// Liste der betroffenen Clients ermitteln
			Vector<String> sendList = clients.getClientNameList();
						
			ChatPDU pdu = ChatPDU.createChatMessageEventPdu(userName, receivedPdu);
			
			// Event an Clients senden
			for (String s : new Vector<String>(sendList)) {
				client = clients.getClient(s);
				try {
					if ((client != null) && (client.getStatus() != ClientConversationStatus.UNREGISTERED)) {
						pdu.setUserName(client.getUserName());
						client.getConnection().send(pdu);
						log.debug("Chat-Event-PDU an " + client.getUserName() + " gesendet");
						clients.incrNumberOfSentChatEvents(client.getUserName());
						eventCounter.getAndIncrement();
						log.debug(userName + ": EventCounter erhoeht = " + eventCounter.get()
								+ ", Aktueller ConfirmCounter = " + confirmCounter.get()
								+ ", Anzahl gesendeter ChatMessages von dem Client = "
								+ receivedPdu.getSequenceNumber());
					}
				} catch (Exception e) {
					log.debug("Senden einer Chat-Event-PDU an " + client.getUserName() + " nicht moeglich");
					ExceptionHandler.logException(e);
				}
			}
//			AG: auskommentiert da hier nicht mehr response pdu senden
//				client = clients.getClient(receivedPdu.getUserName());
//			if (client != null ) { 
//				ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(receivedPdu.getUserName(), 0, 0, 0, 0,
//						client.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
//						(System.nanoTime() - client.getStartTime()));
//
//				if (responsePdu.getServerTime() / 1000000 > 100) {
//					log.debug(Thread.currentThread().getName()
//							+ ", Benoetigte Serverzeit vor dem Senden der Response-Nachricht > 100 ms: "
//							+ responsePdu.getServerTime() + " ns = " + responsePdu.getServerTime() / 1000000 + " ms");
//				}
//
//				try {
//					client.getConnection().send(responsePdu);
//					log.debug("Chat-Message-Response-PDU an " + receivedPdu.getUserName() + " gesendet");
//				} catch (Exception e) {
//					log.debug("Senden einer Chat-Message-Response-PDU an " + client.getUserName() + " nicht moeglich");
//					ExceptionHandler.logExceptionAndTerminate(e);
//				}
		//	}
			log.debug("Aktuelle Laenge der Clientliste: " + clients.size());
		}
	}

	/**
	 * Verbindung zu einem Client ordentlich abbauen
	 */
	private void closeConnection() {

		log.debug("Schliessen der Chat-Connection zum " + userName);

		// Bereinigen der Clientliste falls erforderlich

		if (clients.existsClient(userName)) {
			log.debug("Close Connection fuer " + userName
					+ ", Laenge der Clientliste vor dem bedingungslosen Loeschen: " + clients.size());

			clients.deleteClientWithoutCondition(userName);
			log.debug(
					"Laenge der Clientliste nach dem bedingungslosen Loeschen von " + userName + ": " + clients.size());
		}

		try {
			connection.close();
		} catch (Exception e) {
			log.debug("Exception bei close");
			// ExceptionHandler.logException(e);
		}
	}

	/**
	 * Antwort-PDU fuer den initiierenden Client aufbauen und senden
	 * 
	 * @param eventInitiatorClient
	 *            Name des Clients
	 */
	private void sendLogoutResponse(String eventInitiatorClient) {

		ClientListEntry client = clients.getClient(eventInitiatorClient);

		if (client != null) {
			ChatPDU responsePdu = ChatPDU.createLogoutResponsePdu(eventInitiatorClient, 0, 0, 0, 0,
					client.getNumberOfReceivedChatMessages(), clientThreadName);

			log.debug(eventInitiatorClient + ": SentEvents aus Clientliste: " + client.getNumberOfSentEvents()
					+ ": ReceivedConfirms aus Clientliste: " + client.getNumberOfReceivedEventConfirms());
			try {
				clients.getClient(eventInitiatorClient).getConnection().send(responsePdu);
			} catch (Exception e) {
				log.debug("Senden einer Logout-Response-PDU an " + eventInitiatorClient + " fehlgeschlagen");
				log.debug("Exception Message: " + e.getMessage());
			}

			log.debug("Logout-Response-PDU an Client " + eventInitiatorClient + " gesendet");
		}
	}

	/**
	 * Prueft, ob Clients aus der Clientliste geloescht werden koennen
	 * 
	 * @return boolean, true: Client geloescht, false: Client nicht geloescht
	 */
	private boolean checkIfClientIsDeletable() {

		ClientListEntry client;

		// Worker-Thread beenden, wenn sein Client schon abgemeldet ist
		if (userName != null) {
			client = clients.getClient(userName);
			if (client != null) {
				if (client.isFinished()) {
					// Loesche den Client aus der Clientliste
					// Ein Loeschen ist aber nur zulaessig, wenn der Client
					// nicht mehr in einer anderen Warteliste ist
					log.debug("Laenge der Clientliste vor dem Entfernen von " + userName + ": " + clients.size());
					if (clients.deleteClient(userName) == true) {
						// Jetzt kann auch Worker-Thread beendet werden

						log.debug("Laenge der Clientliste nach dem Entfernen von " + userName + ": " + clients.size());
						log.debug("Worker-Thread fuer " + userName + " zum Beenden vorgemerkt");
						return true;
					}
				}
			}
		}

		// Garbage Collection in der Clientliste durchfuehren
		Vector<String> deletedClients = clients.gcClientList();
		if (deletedClients.contains(userName)) {
			log.debug("Ueber Garbage Collector ermittelt: Laufender Worker-Thread fuer " + userName
					+ " kann beendet werden");
			finished = true;
			return true;
		}
		return false;
	}

	@Override
	protected void handleIncomingMessage() throws Exception {
		if (checkIfClientIsDeletable() == true) {
			return;
		}

		// Warten auf naechste Nachricht
		ChatPDU receivedPdu = null;

		// Nach einer Minute wird geprueft, ob Client noch eingeloggt ist
		final int RECEIVE_TIMEOUT = 1200000;

		try {
			receivedPdu = (ChatPDU) connection.receive(RECEIVE_TIMEOUT);
			// Nachricht empfangen
			// Zeitmessung fuer Serverbearbeitungszeit starten
			startTime = System.nanoTime();

		} catch (ConnectionTimeoutException e) {

			// Wartezeit beim Empfang abgelaufen, pruefen, ob der Client
			// ueberhaupt noch etwas sendet
			log.debug("Timeout beim Empfangen, " + RECEIVE_TIMEOUT + " ms ohne Nachricht vom Client");

			if (clients.getClient(userName) != null) {
				if (clients.getClient(userName).getStatus() == ClientConversationStatus.UNREGISTERING) {
					// Worker-Thread wartet auf eine Nachricht vom Client, aber es
					// kommt nichts mehr an
					log.error("Client ist im Zustand UNREGISTERING und bekommt aber keine Nachricht mehr");
					// Zur Sicherheit eine Logout-Response-PDU an Client senden und
					// dann Worker-Thread beenden
					finished = true;
				}
			}
			return;

		} catch (EndOfFileException e) {
			log.debug("End of File beim Empfang, vermutlich Verbindungsabbau des Partners fuer " + userName);
			finished = true;
			return;

		} catch (java.net.SocketException e) {
			log.error("Verbindungsabbruch beim Empfang der naechsten Nachricht vom Client " + getName());
			finished = true;
			return;

		} catch (Exception e) {
			log.error("Empfang einer Nachricht fehlgeschlagen, Workerthread fuer User: " + userName);
			ExceptionHandler.logException(e);
			finished = true;
			return;
		}

		// Empfangene Nachricht bearbeiten
		try {
			switch (receivedPdu.getPduType()) {

			case LOGIN_REQUEST:
				// Login-Request vom Client empfangen
				loginRequestAction(receivedPdu);
				break;

			case CHAT_MESSAGE_REQUEST:
				// Chat-Nachricht angekommen, an alle verteilen
				chatMessageRequestAction(receivedPdu);
				break;

			// case --> Chat Nachricht bei Client angekommen, hochz�hlen Anzahl Clients
			// (L�nge von Vektor sendList?)

			case CHAT_MESSAGE_RESPONSE_CONFIRM:
				// chat Nachricht beim Client angekommen
				chatMessageConfirmAction(receivedPdu);
				break;

			case LOGOUT_REQUEST:
				// Logout-Request vom Client empfangen
				logoutRequestAction(receivedPdu);
				break;

			 case LOGIN_CONFIRM:
				 System.out.println("Geht in SwitchCase loginconfirm");
			 // Login-Confirm von Client empfangen
			 loginConfirmAction(receivedPdu);
			 break;

			// case LOGOUT_CONFIRM:
			// // Login-Confirm von Client empfangen
			// logoutConfirmAction(receivedPdu);
			// break;

			default:
				log.debug("Falsche PDU empfangen von Client: " + receivedPdu.getUserName() + ", PduType: "
						+ receivedPdu.getPduType());
				break;
			}
 		} catch (Exception e) {
			log.error("Exception bei der Nachrichtenverarbeitung");
			ExceptionHandler.logExceptionAndTerminate(e);
		}
	}

	// Methode um Messages zu confirmen, sendet eine responsePDU an die CLients,
	// nachdem er sie aus der Liste gel�scht hat AL
	private void chatMessageConfirmAction(ChatPDU receivedPdu) {
	    //SharedChatClientList client = SharedChatClientList.getInstance(); //AG auskommentiert
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName());
		confirmCounter.getAndIncrement();
		log.debug("Chat Message Confirm PDU von " + receivedPdu.getEventUserName() + " f�r User "
				+ receivedPdu.getUserName() + " empfangen.");
		System.out.println("chat Message Confirm empfangen von" + userName);
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// l�sche clients aus Waitlist AL
			System.out.println("Event User Name: " + receivedPdu.getEventUserName());
			System.out.println("Gr��e vor L�schen" + clients.getWaitListSize(receivedPdu.getEventUserName()));
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName); //receivedPdu.getUserName()
			System.out.println("Gr��e nach L�schen" + clients.getWaitListSize(receivedPdu.getEventUserName()));
			// Wenn Waitlist aus 0, dann.... AL
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());
				// testen ob �berhaupt Clients vorhanden AL
				if (clientList != null) {
					// erstelle response PDU AL
					ChatPDU responsePdu = ChatPDU.createChatMessageResponsePdu(receivedPdu.getUserName(), 0, 0, 0, 0,
							clientList.getNumberOfReceivedChatMessages(), receivedPdu.getClientThreadName(),
							(System.nanoTime() - clientList.getStartTime()));

					try {
						// sende response PDU AL
						clientList.getConnection().send(responsePdu); //AG: respnse pdu doch nur an den Client, der die Nachricht geschickt hat, schicken???
                        System.out.println("Chat-Message-Response-PDU an " + responsePdu.getUserName() + " gesendet"); // AG: wird an richtigen gesendet?

					} catch (Exception e) {
						ExceptionHandler.logExceptionAndTerminate(e);
					}
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
	}

	// AG: Methode um Login zu best�tigen, sendet eine responsePDU an den Client, der sich einloggen will
	// nachdem er sie aus der Liste gel�scht hat AG
	private void loginConfirmAction(ChatPDU receivedPdu) {
		
		clients.incrNumberOfReceivedChatEventConfirms(receivedPdu.getEventUserName());
		confirmCounter.getAndIncrement();
		log.debug("Login Confirm PDU von " + receivedPdu.getEventUserName() + " f�r User "
				+ receivedPdu.getUserName() + " empfangen.");
		log.debug("so viele Confirms" + confirmCounter + "werden gesendet");

		try {
			// l�scht Client, der Nachricht best�tigt hat aus der Liste raus
			clients.deleteWaitListEntry(receivedPdu.getEventUserName(), userName);

			// Wenn Waitlist leer ist AG
			if (clients.getWaitListSize(receivedPdu.getEventUserName()) == 0) {
				// bekomme die Liste aller Clients AG
				ClientListEntry clientList = clients.getClient(receivedPdu.getEventUserName());
				// AG:testen ob �berhaupt Clients vorhanden sind und eine response Pdu gesendet werden muss -> wann ist das nicht der Fall?
				if (clientList != null) {
					// erstelle response PDU AL
					ChatPDU responsePdu = ChatPDU.createLoginResponsePdu(receivedPdu.getUserName(), receivedPdu);

					try {
						// sende response PDU AL
						clientList.getConnection().send(responsePdu);  //woher weis Server an welchen Kommunikationspartner?
						System.out.println("LoginResponse Pdu wurde gesendet an "+ responsePdu.getUserName()); //AG
						
					} catch (Exception e) {
						ExceptionHandler.logExceptionAndTerminate(e);
					}
				}
			}
		} catch (Exception e) {
			ExceptionHandler.logException(e);
		}
	}

}
