package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.AuditLogger;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class RenewalActor {
	public static void main(String[] args) throws Exception {

		// Determinar si corre en modo sync o async
		boolean syncMode = false;
		for (String a : args) {
			if ("sync".equalsIgnoreCase(a) || "--sync".equalsIgnoreCase(a)) {
				syncMode = true;
				break;
			}
		}

		// Ejecutar el modo solicitado
		if (!syncMode) {
			runAsync();
		} else {
			runSync();
		}
	}

	private static void runAsync() throws Exception {
		// Endpoints PUB/SUB del GC
		String subConnect = AppConfig.get("actor.renew.sub", "tcp://127.0.0.1:5556");
		String[] subEndpoints = subConnect.split(",");

		// Endpoints del Gestor de Almacenamiento
		String gaEndpointsConf = AppConfig.get("ga.rep.endpoints", "tcp://10.43.97.18:5560");
		String[] gaEndpoints = gaEndpointsConf.split(",");

		int gaSendTimeout = 2000; // tiempo de envío al GA
		int gaRecvTimeout = 2000; // tiempo de respuesta del GA
		int gaIndex = 0; // GA activo

		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket sub = ctx.socket(SocketType.SUB)) {

			// Conectarse a los endpoints de suscripción
			sub.connect(subEndpoints[0].trim());
			if (subEndpoints.length == 2) {
				sub.connect(subEndpoints[1].trim());
			}

			// Suscribir al tópico RENOVACION
			sub.subscribe("RENOVACION".getBytes(ZMQ.CHARSET));
			System.out.println("[RenewalActor] se suscribio al topic RENOVACION");

			// Crear socket REQ para comunicación con GA
			ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
			gaReq.setSendTimeOut(gaSendTimeout);
			gaReq.setReceiveTimeOut(gaRecvTimeout);
			gaReq.connect(gaEndpoints[gaIndex].trim());
			System.out.printf("[RenewalActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex].trim());
			System.out.println();

			while (true) {
				// Recibir notificación del GC
				String topic = sub.recvStr();
				String payload = sub.recvStr();

				Message msg = Message.parse(payload);
				System.out.printf("[GC] -> [RenewalActor] -> [GA]: %s %s %s %s%n",
						msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

				// Crear mensaje de almacenamiento
				Message cmd = new Message("RENOVACION", msg.branchId(), msg.userId(), msg.bookCode());

				boolean sent = false;
				int attempts = 0;
				StorageResult res = null;

				// Intentos con failover entre nodos GA
				while (!sent && attempts < gaEndpoints.length) {
					try {
						gaReq.send(cmd.serialize());
						String rawRes = gaReq.recvStr();
						if (rawRes == null) {
							throw new RuntimeException("Timeout al recibir respuesta de GA");
						}

						res = StorageResult.parse(rawRes);

						// Log según resultado
						if (res.ok()) {
							AuditLogger.log("RenewalActor", "RENOVACION_OK",
									String.format("branch=%s user=%s book=%s",
											msg.branchId(), msg.userId(), msg.bookCode()),
									"OK");
						} else {
							AuditLogger.log("RenewalActor", "RENOVACION_FAIL",
									String.format("branch=%s user=%s book=%s error=%s",
											msg.branchId(), msg.userId(), msg.bookCode(), res.message()),
									"ERROR");
						}

						System.out.printf("[GA] -> [RenewalActor]: %s (%s)%n",
								res.ok() ? "OK" : "ERROR", res.message());
						System.out.println();

						sent = true;

					} catch (Exception e) {
						// Error -> cambiar a otro GA
						System.err.println("[RenewalActor] Error con GA "
								+ gaEndpoints[gaIndex].trim() + ": " + e.getMessage());

						gaIndex = gaIndex + 1; // avanzar al siguiente GA

						// Recrear socket hacia nuevo GA
						gaReq.close();
						gaReq = ctx.socket(SocketType.REQ);
						gaReq.setSendTimeOut(gaSendTimeout);
						gaReq.setReceiveTimeOut(gaRecvTimeout);
						gaReq.connect(gaEndpoints[gaIndex].trim());
						System.out.printf("[RenewalActor] Reintentando con GA: %s%n", gaEndpoints[gaIndex].trim());

						attempts++;
					}
				}

				// Si no fue posible contactar ningún GA
				if (!sent) {
					res = new StorageResult(false, "No se pudo conectar con ningún Gestor de Almacenamiento");
					System.err.println("[RenewalActor] " + res.message());

					AuditLogger.log("RenewalActor", "RENOVACION_FAIL",
							String.format("branch=%s user=%s book=%s error=%s",
									msg.branchId(), msg.userId(), msg.bookCode(), res.message()),
							"ERROR");
				}
			}
		}
	}

	private static void runSync() throws Exception {
		// Endpoint para REQ/REP con GC
		String repConnect = System.getProperty(
				"actor.renew.req",
				AppConfig.get("actor.renew.req", "tcp://0.0.0.0:5559"));

		// Endpoints de los GA
		String gaEndpointsConf = System.getProperty(
				"ga.rep.endpoints",
				AppConfig.get("ga.rep.endpoints", "tcp://127.0.0.1:5560"));
		String[] gaEndpoints = gaEndpointsConf.split(",");

		int gaSendTimeout = 2000;
		int gaRecvTimeout = 2000;
		int gaIndex = 0;

		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket gcRep = ctx.socket(SocketType.REP)) {

			// Recibir comandos del GC
			gcRep.bind(repConnect);
			System.out.printf("[RenewalActor] se conecto a [GC]: %s%n", repConnect);

			// Conectarse al primer GA
			ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
			gaReq.setSendTimeOut(gaSendTimeout);
			gaReq.setReceiveTimeOut(gaRecvTimeout);
			gaReq.connect(gaEndpoints[gaIndex].trim());
			System.out.printf("[RenewalActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex]);

			while (true) {
				// Recibir comando del GC
				String rawCmd = gcRep.recvStr();
				Message cmd = Message.parse(rawCmd);
				System.out.printf("[GC] -> [RenewalActor] -> [GA]: %s %s %s %s%n",
						cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

				StorageResult result = null;
				boolean sent = false;
				int attempts = 0;

				// Intentar contactar GA con failover
				while (!sent && attempts < gaEndpoints.length) {
					try {
						gaReq.send(cmd.serialize());
						String rawRes = gaReq.recvStr();
						if (rawRes == null) {
							throw new RuntimeException("Timeout esperando GA");
						}

						result = StorageResult.parse(rawRes);
						System.out.printf("[GA] -> [RenewalActor]: %s (%s)%n",
								result.ok() ? "OK" : "ERROR", result.message());

						sent = true;

					} catch (Exception e) {
						System.err.printf("[RenewalActor] Error con GA %s: %s%n",
								gaEndpoints[gaIndex], e.getMessage());

						attempts++;
						if (attempts >= gaEndpoints.length) {
							break;
						}

						// Cambiar GA
						gaIndex = (gaIndex + 1) % gaEndpoints.length;

						gaReq.close();
						gaReq = ctx.socket(SocketType.REQ);
						gaReq.setSendTimeOut(gaSendTimeout);
						gaReq.setReceiveTimeOut(gaRecvTimeout);
						gaReq.connect(gaEndpoints[gaIndex].trim());
						System.out.printf("[RenewalActor] Reintentando con GA %s%n",
								gaEndpoints[gaIndex]);
					}
				}

				// Fallo total
				if (!sent) {
					result = new StorageResult(false, "No se pudo contactar ningún GA");
					System.err.println("[RenewalActor] " + result.message());
				}

				// Responder al GC
				gcRep.send(result.serialize());
			}
		}
	}
}
