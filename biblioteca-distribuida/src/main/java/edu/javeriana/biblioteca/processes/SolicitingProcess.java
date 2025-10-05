package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

import java.nio.file.*;
import java.util.List;

public class SolicitingProcess {
	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Uso: java ... SolicitingProcess <archivo_workload.csv>");
			System.exit(1);
		}
		String rep = AppConfig.get("gc.rep", "tcp://127.0.0.1:5555");
		Path path = Paths.get(args[0]);
		List<String> lines = Files.readAllLines(path);

		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket req = ctx.socket(SocketType.REQ)) {
			req.connect(rep);
			System.out.printf("[PS] Conectado a GC REP: %s%n", rep);

			for (String line : lines) {
				String[] p = line.split(",");
				if (p.length < 3)
					continue;
				String op = p[0].trim().toUpperCase();
				String book = p[1].trim();
				Message msg = switch (op) {
					case "DEVOLVER" -> Message.devolver(book);
					case "RENOVAR" -> Message.renovar(book);
					default -> null;
				};
				if (msg == null)
					continue;

				req.send(msg.serialize());
				String ack = req.recvStr();
				System.out.printf("[PS] %s %s %s -> %s%n", op, book, ack);
				Thread.sleep(20);
			}
		}
	}
}