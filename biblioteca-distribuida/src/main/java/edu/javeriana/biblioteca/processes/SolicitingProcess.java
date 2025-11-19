package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

import java.nio.file.*;
import java.util.List;

public class SolicitingProcess {
	public static void main(String[] args) throws Exception {
		// Verificar argumento del CSV
		if (args.length < 1) {
			System.err.println(
					"Uso: mvn exec:java " +
							"-Dexec.mainClass=\"edu.javeriana.biblioteca.processes.SolicitingProcess\" " +
							"-Dexec.args=\"ruta/archivo.csv\"");
			System.exit(1);
		}

		// Endpoints del GC (con failover)
		String endpointsConfig = System.getProperty(
				"ps.gc.endpoints",
				AppConfig.get("ps.gc.endpoints", "tcp://127.0.0.1:5555"));
		String[] endpoints = endpointsConfig.split(",");

		int sndTimeout = 2000; // timeout envío
		int rcvTimeout = 2000; // timeout recepción
		long retryBackoffMs = 2000; // espera antes de reintentar GC
		int gcIndex = 0; // índice actual del GC

		// Leer solicitudes desde archivo CSV
		Path path = Paths.get(args[0]);
		List<String> lines = Files.readAllLines(path);

		try (ZMQ.Context ctx = ZMQ.context(1)) {

			// Crear socket hacia GC inicial
			ZMQ.Socket req = ctx.socket(SocketType.REQ);
			req.setSendTimeOut(sndTimeout);
			req.setReceiveTimeOut(rcvTimeout);
			req.setLinger(0);
			req.connect(endpoints[gcIndex].trim());

			System.out.printf("[PS] se conectó a [GC]: %s%n", endpoints[gcIndex].trim());
			System.out.println();

			for (String line : lines) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue; // ignorar líneas vacías o comentarios
				}

				// Formato esperado: OPERACION,branchId,userId,bookCode
				String[] p = line.split(",", 4);
				if (p.length < 4) {
					System.out.printf("[PS] Línea ignorada (formato inválido): %s%n", line);
					continue;
				}

				String op = p[0].trim().toUpperCase();
				String branchId = p[1].trim();
				String userId = p[2].trim();
				String bookCode = p[3].trim();

				// Crear mensaje según operación
				Message msg = switch (op) {
					case "DEVOLUCION" -> Message.devolver(branchId, userId, bookCode);
					case "RENOVACION" -> Message.renovar(branchId, userId, bookCode);
					case "PRESTAMO" -> Message.prestar(branchId, userId, bookCode);
					default -> null;
				};

				if (msg == null) {
					System.out.printf("[PS] Línea ignorada (operación desconocida): %s%n", line);
					continue;
				}

				boolean sent = false;

				// Envío con failover entre múltiples GC
				while (!sent) {
					int attempts = 0;

					while (!sent && attempts < endpoints.length) {
						try {
							req.send(msg.serialize());
							String ack = req.recvStr();

							if (ack == null) {
								throw new Exception("No se recibió respuesta");
							}

							System.out.printf("[GC] -> [PS]: %s%n", ack);
							sent = true;

						} catch (Exception e) {
							// Pasar al siguiente GC
							attempts++;
							if (attempts >= endpoints.length) {
								break;
							}

							gcIndex = (gcIndex + 1) % endpoints.length;

							// Re-crear socket hacia nuevo GC
							req.close();
							req = ctx.socket(SocketType.REQ);
							req.setSendTimeOut(sndTimeout);
							req.setReceiveTimeOut(rcvTimeout);
							req.setLinger(0);
							req.connect(endpoints[gcIndex].trim());
						}
					}

					if (!sent) {
						// Espera antes de reintentar con la lista completa
						try {
							Thread.sleep(retryBackoffMs);
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							return;
						}
					}
				}

				// Pequeño delay entre solicitudes
				try {
					Thread.sleep(500);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}
}
