package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.StorageCommand;
import edu.javeriana.biblioteca.messaging.StorageResult;
import edu.javeriana.biblioteca.persistence.StorageGateway;
import edu.javeriana.biblioteca.replication.DataSourceRouter;
import edu.javeriana.biblioteca.replication.FailoverMonitor;
import edu.javeriana.biblioteca.replication.Replicator;

import org.zeromq.SocketType;
import org.zeromq.ZMQ;

public class StorageManager {

	public static void main(String[] args) {
		// Config BD
		String dbUrl = AppConfig.get("db.primary.url", "jdbc:postgresql://localhost:5432/BDPrimaria");
		String dbUser = AppConfig.get("db.primary.user", "postgres");
		String dbPass = AppConfig.get("db.primary.pass", "123");

		String dbSUrl = AppConfig.get("db.secondary.url", "jdbc:postgresql://localhost:5432/BDSecundaria");
		String dbSUser = AppConfig.get("db.secondary.user", "postgres");
		String dbSPass = AppConfig.get("db.secondary.pass", "123");

		// Endpoint ZeroMQ para GA
		String bind = AppConfig.get("ga.rep", "tcp://0.0.0.0:5560");

		DataSourceRouter router = new DataSourceRouter(dbUrl, dbUser, dbPass, dbSUrl, dbSUser, dbSPass);
		Replicator replicator = new Replicator(router);

		StorageGateway gateway = new StorageGateway(router, replicator);

		long interval = Long.parseLong(AppConfig.get("db.health.interval", "1500"));
		FailoverMonitor monitor = new FailoverMonitor(router, interval);
		monitor.start();

		try (ZMQ.Context ctx = ZMQ.context(1);
				ZMQ.Socket rep = ctx.socket(SocketType.REP)) {

			rep.bind(bind);
			System.out.printf("[GA] StorageManager REP en %s%n", bind);

			while (true) {
				String raw = rep.recvStr();
				StorageCommand cmd = StorageCommand.parse(raw);
				System.out.printf("[GA] Recibido comando: %s %s %s %s%n",
						cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

				StorageResult result;
				try {
					switch (cmd.type()) {
						case "DEVOLUCION" -> {
							gateway.applyReturn(cmd.branchId(), cmd.userId(), cmd.bookCode());
							result = new StorageResult(true, "Devolución aplicada");
						}
						case "RENOVACION" -> {
							gateway.applyRenewal(cmd.branchId(), cmd.userId(), cmd.bookCode());
							result = new StorageResult(true, "Renovación aplicada");
						}
						case "PRESTAMO" -> {
							gateway.applyLoan(cmd.branchId(), cmd.userId(), cmd.bookCode());
							result = new StorageResult(true, "Préstamo aplicado");
						}
						default -> result = new StorageResult(false, "Tipo de comando desconocido: " + cmd.type());
					}
				} catch (Exception e) {
					e.printStackTrace();
					result = new StorageResult(false, e.getMessage());
				}

				rep.send(result.serialize());
			}
		}
	}
}
