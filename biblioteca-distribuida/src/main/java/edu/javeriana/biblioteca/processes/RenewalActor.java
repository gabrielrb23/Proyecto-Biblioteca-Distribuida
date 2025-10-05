package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class RenewalActor {
	public static void main(String[] args) throws Exception {
		String connect = AppConfig.get("actor.renewal.sub", "tcp://127.0.0.1:5556");
		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket sub = ctx.socket(SocketType.SUB)) {
			sub.connect(connect);
			sub.subscribe("RENOVACION".getBytes(ZMQ.CHARSET));
			System.out.printf("[RenewalActor] SUB %s (topic RENOVACION)%n", connect);

			while (true) {
				String topic = sub.recvStr();
				String payload = sub.recvStr();
				Message msg = Message.parse(payload);
				// Aquí validas <= 2 renovaciones y actualizas BD en la entrega final
				System.out.printf("[RenewalActor] Renovación: book=%s%n", msg.bookCode());
				Thread.sleep(50);
			}
		}
	}
}
