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
			System.err.println("Uso: java ... SolicitingProcess <archivo_workload.txt>");
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

				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
					continue;

				String[] p = line.split(",", 2);
				String op = p[0].trim().toUpperCase();
				String bookId = p[1].trim();
				Message msg = switch (op) {
					case "D" -> Message.devolver(bookId);
					case "R" -> Message.renovar(bookId);
					default -> null;
				};
				if (msg == null) {
					System.out.printf("[PS] Línea ignorada (op desconocida): %s%n", line);
					continue;
				}

				req.send(msg.serialize());
				String ack = req.recvStr();
				System.out.printf("[PS] %s %s -> %s%n", op, bookId, ack);
				Thread.sleep(20);
			}
		}
	}
}