package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;
import edu.javeriana.biblioteca.persistence.StorageGateway;
import edu.javeriana.biblioteca.replication.DataSourceRouter;
import edu.javeriana.biblioteca.replication.FailoverMonitor;
import edu.javeriana.biblioteca.replication.Replicator;

import org.zeromq.SocketType;
import org.zeromq.ZMQ;

public class StorageManager {

	public static void main(String[] args) {
		// Configuración de conexiones a BD primaria y secundaria
		String dbUrl = AppConfig.get("db.primary.url", "jdbc:postgresql://localhost:5432/BDPrimaria");
		String dbUser = AppConfig.get("db.primary.user", "postgres");
		String dbPass = AppConfig.get("db.primary.pass", "123");

		String dbSUrl = AppConfig.get("db.secondary.url", "jdbc:postgresql://localhost:5432/BDSecundaria");
		String dbSUser = AppConfig.get("db.secondary.user", "postgres");
		String dbSPass = AppConfig.get("db.secondary.pass", "123");

		// Endpoint donde GA escucha comandos
		String bind = System.getProperty("ga.rep", AppConfig.get("ga.rep", "tcp://0.0.0.0:5560"));

		// Router para escoger entre primaria/secundaria
		DataSourceRouter router = new DataSourceRouter(dbUrl, dbUser, dbPass, dbSUrl, dbSUser, dbSPass);

		// Replicador encargado de sincronizar cambios entre nodos
		Replicator replicator = new Replicator(router);

		// Gateway que ejecuta la lógica de almacenamiento
		StorageGateway gateway = new StorageGateway(router, replicator);

		// Monitor de salud para detectar caídas de la BD primaria
		long interval = Long.parseLong(AppConfig.get("db.health.interval", "1500"));
		FailoverMonitor monitor = new FailoverMonitor(router, interval);
		monitor.start(); // inicia el hilo de chequeo

		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket rep = ctx.socket(SocketType.REP)) {

			// GA recibe comandos desde los actores
			rep.bind(bind);
			System.out.println("[GA] Esperando comandos en " + bind);
			System.out.println();

			while (true) {
				// Recibir comando del actor
				String raw = rep.recvStr();
				Message cmd = Message.parse(raw);
				StorageResult result;

				try {
					// Ejecutar operación solicitada
					switch (cmd.type()) {
						case "DEVOLUCION" -> {
							System.out.printf("[ReturnActor] -> [GA]: %s %s %s%n",
									cmd.branchId(), cmd.userId(), cmd.bookCode());
							gateway.applyReturn(cmd.branchId(), cmd.userId(), cmd.bookCode());
							result = new StorageResult(true, "Devolución aplicada");
						}
						case "RENOVACION" -> {
							System.out.printf("[RenewalActor] -> [GA]: %s %s %s%n",
									cmd.branchId(), cmd.userId(), cmd.bookCode());
							gateway.applyRenewal(cmd.branchId(), cmd.userId(), cmd.bookCode());
							result = new StorageResult(true, "Renovación aplicada");
						}
						case "PRESTAMO" -> {
							System.out.printf("[LoanActor] -> [GA]: %s %s %s%n",
									cmd.branchId(), cmd.userId(), cmd.bookCode());
							gateway.applyLoan(cmd.branchId(), cmd.userId(), cmd.bookCode());
							result = new StorageResult(true, "Préstamo aplicado");
						}
						default -> {
							// Comando desconocido
							result = new StorageResult(false, "Tipo de comando desconocido: " + cmd.type());
						}
					}
				} catch (Exception e) {
					// Manejo de errores de negocio vs errores internos
					if (e instanceof IllegalStateException) {
						System.out.println("[GA] Error de negocio: " + e.getMessage());
						result = new StorageResult(false, e.getMessage());
					} else {
						e.printStackTrace();
						result = new StorageResult(false, "Error interno en GA: " + e.getMessage());
					}
				}

				// Enviar respuesta al actor solicitante
				rep.send(result.serialize());
			}
		}
	}
}
