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
			System.err
					.println("Uso: mvn exec:java -Dexec.mainClass=\"edu.javeriana.biblioteca.processes.LoadManager\"");
			System.exit(1);
		}

		// Se leen las solicitudes
		String rep = AppConfig.get("gc.rep", "tcp://127.0.0.1:5555");
		Path path = Paths.get(args[0]);
		List<String> lines = Files.readAllLines(path);

		// Se conecta al gestor de almacenamiento
		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket req = ctx.socket(SocketType.REQ)) {
			req.connect(rep);
			System.out.printf("[PS] Conectado a [GC]: %s%n", rep);

			for (String line : lines) {

				// Ignorar lineas vacias y comentarios
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
					continue;

				String[] p = line.split(",", 2);
				String op = p[0].trim().toUpperCase();
				String bookId = p[1].trim();

				// Generar la solicitud
				Message msg = switch (op) {
					case "D" -> Message.devolver(bookId);
					case "R" -> Message.renovar(bookId);
					default -> null;
				};
				if (msg == null) {
					System.out.printf("[PS] Línea ignorada (operacion desconocida): %s%n", line);
					continue;
				}

				// Enviar la solicitud
				req.send(msg.serialize());
				String ack = req.recvStr();
				System.out.printf("[GC]->[PS]: %s%n", ack);
				Thread.sleep(20);
			}
		}
	}
}