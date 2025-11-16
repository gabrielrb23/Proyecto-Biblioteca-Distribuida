package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.StorageCommand;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoanActor {
	public static void main(String[] args) throws Exception {

		// Se llama al gestor de almacenamiento para procesar el prestamo
		String repConnect = AppConfig.get("actor.loan.rep", "tcp://127.0.0.1:5557");
		String gaConnect = AppConfig.get("ga.rep", "tcp://127.0.0.1:5560");

		// Se conecta al gestor de almacenamiento
		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket gcRep = ctx.socket(SocketType.REP);
				ZMQ.Socket gaReq = ctx.socket(SocketType.REQ)) {

			// Escuchar solicitudes de préstamo desde GC
			gcRep.bind(repConnect);
			System.out.printf("[LoanActor] REP para GC en %s%n", repConnect);

			// Conectarse al Gestor de Almacenamiento (GA)
			gaReq.connect(gaConnect);
			System.out.printf("[LoanActor] REQ hacia GA en %s%n", gaConnect);

			while (true) {
				String rawCmd = gcRep.recvStr();
				StorageCommand cmd = StorageCommand.parse(rawCmd);
				System.out.printf("[LoanActor] Recibido desde GC: %s %s %s %s%n",
						cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

				StorageResult result;
				gaReq.send(cmd.serialize());

				String rawRes = gaReq.recvStr();
				result = StorageResult.parse(rawRes);
				System.out.printf("[LoanActor] Respuesta de GA: %s (%s)%n",
						result.ok() ? "OK" : "ERR", result.message());

				gcRep.send(result.serialize());
			}
		}
	}
}
