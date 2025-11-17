package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageCommand;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class RenewalActor {
	public static void main(String[] args) throws Exception {

		// Se llama al gestor de almacenamiento para procesar la renovacion
		String subConnect = AppConfig.get("actor.renewal.sub", "tcp://127.0.0.1:5556");
		String gaConnect = AppConfig.get("ga.rep", "tcp://127.0.0.1:5560");

		// Se conecta al gestor de almacenamiento
		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket sub = ctx.socket(SocketType.SUB);
				ZMQ.Socket gaReq = ctx.socket(SocketType.REQ)) {

			sub.connect(subConnect); // Se suscribe al topico de renovaciones
			sub.subscribe("RENOVACION".getBytes(ZMQ.CHARSET));
			System.out.println("[RenewalActor] se suscribio al topic RENOVACION");

			gaReq.connect(gaConnect);
			System.out.printf("[RenewalActor] se conecto a [GA]: %s%n", gaConnect);

			System.out.println();

			while (true) {
				// Se recibe la renovacion
				String payload = sub.recvStr();
				Message msg = Message.parse(payload);
				System.out.printf("[GC] -> [RenewalActor] -> [GA]: %s %s %s %s%n",
						msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

				// Construir comando de almacenamiento
				StorageCommand cmd = new StorageCommand(
						"RENOVACION",
						msg.branchId(),
						msg.userId(),
						msg.bookCode());
				gaReq.send(cmd.serialize());

				String rawRes = gaReq.recvStr();
				StorageResult res = StorageResult.parse(rawRes);
				System.out.printf("[GA] -> [RenewalActor]: %s (%s)%n",
						res.ok() ? "OK" : "ERROR", res.message());
				System.out.println();
			}
		}
	}
}
