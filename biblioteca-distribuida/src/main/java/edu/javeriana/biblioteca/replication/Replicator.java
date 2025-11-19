package edu.javeriana.biblioteca.replication;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.concurrent.*;

public class Replicator {

	private final DataSourceRouter router; // Router para elegir primaria/secundaria
	private final ExecutorService exec = Executors.newFixedThreadPool(2); // Hilos para replicación async

	public Replicator(DataSourceRouter router) {
		// Guarda referencia al router para saber dónde replicar
		this.router = router;
	}

	public void replicateIncrementAvailable(String branchId, String bookCode) {
		// Sólo replicar si la primaria está activa
		if (!router.isPrimaryUp()) {
			return;
		}

		// Ejecutar replicación en segundo plano
		exec.submit(() -> {
			try (Connection c = router.secondary().getConnection();
					PreparedStatement ps = c.prepareStatement(
							"UPDATE branch_inventory " +
									"SET available_copies = available_copies + 1 " +
									"WHERE branch_id = ? AND book_code = ?")) {

				ps.setString(1, branchId);
				ps.setString(2, bookCode);
				ps.executeUpdate();

			} catch (Exception e) {
				System.err.println("[Replicator] Error replicando inventario: " + e.getMessage());
			}
		});
	}

	public void replicateRenewLoan(String branchId, String userId, String bookCode) {
		// No replicar si ya estamos usando la secundaria para escritura
		if (!router.isPrimaryUp()) {
			return;
		}

		exec.submit(() -> {
			try (Connection c = router.secondary().getConnection();
					PreparedStatement ps = c.prepareStatement(
							"UPDATE loans " +
									"SET renewals = renewals + 1, " +
									"    due_date = due_date + INTERVAL '7 day' " +
									"WHERE user_id = ? AND book_code = ? AND branch_id = ? " +
									"  AND status = 'ACTIVE' AND renewals < 2")) {

				ps.setString(1, userId);
				ps.setString(2, bookCode);
				ps.setString(3, branchId);
				ps.executeUpdate();

			} catch (Exception e) {
				System.err.println("[Replicator] Error replicando renovación: " + e.getMessage());
			}
		});
	}

	public void replicateNewLoan(String branchId, String userId, String bookCode) {
		// Evita replicar si estamos en secundaria
		if (!router.isPrimaryUp()) {
			return;
		}

		exec.submit(() -> {
			try (Connection c = router.secondary().getConnection();
					PreparedStatement psInv = c.prepareStatement(
							"UPDATE branch_inventory " +
									"SET available_copies = available_copies - 1 " +
									"WHERE branch_id = ? AND book_code = ?");
					PreparedStatement psLoan = c.prepareStatement(
							"INSERT INTO loans (user_id, book_code, branch_id, start_date, due_date, renewals, status) "
									+
									"VALUES (?,?,?,?,?,0,'ACTIVE') " +
									"ON CONFLICT (loan_id) DO NOTHING")) {

				// Replicar ajuste de inventario
				psInv.setString(1, branchId);
				psInv.setString(2, bookCode);
				psInv.executeUpdate();

				// Replicar creación del préstamo
				LocalDate startDate = LocalDate.now();
				LocalDate dueDate = startDate.plusDays(7);

				psLoan.setString(1, userId);
				psLoan.setString(2, bookCode);
				psLoan.setString(3, branchId);
				psLoan.setDate(4, Date.valueOf(startDate));
				psLoan.setDate(5, Date.valueOf(dueDate));
				psLoan.executeUpdate();

			} catch (Exception e) {
				System.err.println("[Replicator] Error replicando préstamo nuevo: " + e.getMessage());
			}
		});
	}

	public void shutdown() {
		// Apagar los hilos del replicador
		exec.shutdown();
	}
}
