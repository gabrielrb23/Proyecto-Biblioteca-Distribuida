package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoanActor {
	public static void main(String[] args) throws Exception {

		// Se llama al gestor de almacenamiento para procesar el prestamo
		String repConnect = System.getProperty(
				"actor.loan.req",
				AppConfig.get("actor.loan.req", "tcp://0.0.0.0:5557"));
		String gaEndpointsConf = System.getProperty(
				"ga.rep.endpoints",
				AppConfig.get("ga.rep.endpoints", "tcp://127.0.0.1:5560"));
		String[] gaEndpoints = gaEndpointsConf.split(",");

		int gaSendTimeout = 2000;
		int gaRecvTimeout = 2000;
		int gaIndex = 0;

		// Se conecta al gestor de almacenamiento
		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket gcRep = ctx.socket(SocketType.REP)) {

			// Escuchar solicitudes de préstamo desde GC
			gcRep.bind(repConnect);
			System.out.printf("[LoanActor] se conecto a [GC]: %s%n", repConnect);

			// Conectarse al Gestor de Almacenamiento (GA)
			ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
			gaReq.setSendTimeOut(gaSendTimeout);
			gaReq.setReceiveTimeOut(gaRecvTimeout);
			gaReq.connect(gaEndpoints[gaIndex].trim());
			System.out.printf("[LoanActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex].trim());
			System.out.println();

			while (true) {
				String rawCmd = gcRep.recvStr();
				Message cmd = Message.parse(rawCmd);
				System.out.printf("[GC] -> [LoanActor] -> [GA]: %s %s %s %s%n",
						cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

				StorageResult result = null;
				boolean sent = false;
				int attempts = 0;

				while (!sent && attempts < gaEndpoints.length) {
					try {
						gaReq.send(cmd.serialize());
						String rawRes = gaReq.recvStr();
						if (rawRes == null) {
							throw new RuntimeException("Timeout al recibir respuesta de GA");
						}

						result = StorageResult.parse(rawRes);
						System.out.printf("[GA] -> [LoanActor]: %s (%s)%n",
								result.ok() ? "OK" : "ERROR", result.message());
						System.out.println();
						sent = true;
					} catch (Exception e) {
						System.err.println(
								"[LoanActor] Error con GA " + gaEndpoints[gaIndex].trim() + ": " + e.getMessage());

						attempts++;
						if (attempts >= gaEndpoints.length) {
							break;
						}

						// Cambiar al siguiente GA
						gaIndex = (gaIndex + 1) % gaEndpoints.length;

						// Recrear socket REQ hacia el nuevo GA
						gaReq.close();
						gaReq = ctx.socket(SocketType.REQ);
						gaReq.setSendTimeOut(gaSendTimeout);
						gaReq.setReceiveTimeOut(gaRecvTimeout);
						gaReq.connect(gaEndpoints[gaIndex].trim());
						System.out.printf("[LoanActor] Reintentando con GA: %s%n", gaEndpoints[gaIndex].trim());
					}
				}
				if (!sent) {
					result = new StorageResult(false, "No se pudo conectar con ningún Gestor de Almacenamiento");
					System.err.println("[LoanActor] " + result.message());
				}

				gcRep.send(result.serialize());
			}
		}
	}
}
