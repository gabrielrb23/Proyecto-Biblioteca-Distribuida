package edu.javeriana.biblioteca.persistence;

import edu.javeriana.biblioteca.replication.DataSourceRouter;
import edu.javeriana.biblioteca.replication.Replicator;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;

public class StorageGateway {

	private final DataSourceRouter router;
	private final Replicator replicator;

	public StorageGateway(DataSourceRouter router, Replicator replicator) {
		this.router = router;
		this.replicator = replicator;
	}

	/** Operación genérica con reintento en secundaria si la primaria falla */
	@FunctionalInterface
	private interface StorageOperation {
		void execute() throws Exception;
	}

	private void runWithFailover(StorageOperation op) throws Exception {
		try {
			op.execute();
		} catch (Exception e) {
			if (e instanceof java.sql.SQLException) {
				System.err.println("[StorageGateway] Error SQL en primaria: " + e.getMessage()
						+ " -> conmutando a secundaria y reintentando");
				router.switchToSecondary();
				op.execute();
			} else {
				System.out.println("[StorageGateway] Error en operación: " + e.getMessage());
				throw e;
			}
		}
	}

	private Connection getWriteConnection() throws SQLException {
		DataSource ds = router.currentWrite();
		return ds.getConnection();
	}

	public void applyReturn(String branchId, String userId, String bookCode) throws Exception {
		runWithFailover(() -> {
			try (Connection c = getWriteConnection()) {
				c.setAutoCommit(false);
				try (
						PreparedStatement psLoan = c.prepareStatement(
								"UPDATE loans SET status='RETURNED' " +
										"WHERE user_id=? AND book_code=? AND branch_id=? AND status='ACTIVE'");
						PreparedStatement psInv = c.prepareStatement(
								"UPDATE branch_inventory " +
										"SET available_copies = available_copies + 1 " +
										"WHERE branch_id=? AND book_code=?")) {
					psLoan.setString(1, userId);
					psLoan.setString(2, bookCode);
					psLoan.setString(3, branchId);
					int updated = psLoan.executeUpdate();

					if (updated == 0) {
						throw new IllegalStateException(
								"No se encontró un préstamo ACTIVO para ese usuario/libro/sede");
					}

					if (updated > 0) {
						psInv.setString(1, branchId);
						psInv.setString(2, bookCode);
						psInv.executeUpdate();
					}

					c.commit();
				} catch (Exception e) {
					c.rollback();
					throw e;
				}
			}

			// Replicación asíncrona en secundaria
			replicator.replicateIncrementAvailable(branchId, bookCode);
		});
	}

	public void applyRenewal(String branchId, String userId, String bookCode) throws Exception {
		runWithFailover(() -> {
			try (Connection c = getWriteConnection()) {
				c.setAutoCommit(false);

				try (
						PreparedStatement psSelect = c.prepareStatement(
								"SELECT status, renewals " +
										"FROM loans " +
										"WHERE user_id = ? AND book_code = ? AND branch_id = ? " +
										"ORDER BY start_date DESC " +
										"LIMIT 1 FOR UPDATE");
						PreparedStatement psUpdate = c.prepareStatement(
								"UPDATE loans " +
										"SET renewals = renewals + 1, " +
										"    due_date = due_date + INTERVAL '7 day' " +
										"WHERE user_id = ? AND book_code = ? AND branch_id = ? " +
										"  AND status = 'ACTIVE' AND renewals < 2")) {

					psSelect.setString(1, userId);
					psSelect.setString(2, bookCode);
					psSelect.setString(3, branchId);
					ResultSet rs = psSelect.executeQuery();

					if (!rs.next()) {
						throw new IllegalStateException(
								"No existe ningún préstamo registrado para ese usuario/libro en la sede");
					}

					String status = rs.getString("status");
					int renewals = rs.getInt("renewals");

					if (!"ACTIVE".equalsIgnoreCase(status)) {
						throw new IllegalStateException(
								"No hay préstamo ACTIVO para ese usuario/libro en la sede");
					}

					if (renewals >= 2) {
						throw new IllegalStateException(
								"El préstamo ya alcanzó el máximo de 2 renovaciones");
					}

					psUpdate.setString(1, userId);
					psUpdate.setString(2, bookCode);
					psUpdate.setString(3, branchId);
					psUpdate.executeUpdate();

					c.commit();
				} catch (Exception e) {
					c.rollback();
					throw e;
				}
			}

			// Replicación asíncrona de la renovación (solo si no hubo errores)
			replicator.replicateRenewLoan(branchId, userId, bookCode);
		});
	}

	public void applyLoan(String branchId, String userId, String bookCode) throws Exception {
		runWithFailover(() -> {
			try (Connection c = getWriteConnection()) {
				c.setAutoCommit(false);
				try (
						PreparedStatement psInv = c.prepareStatement(
								"SELECT available_copies FROM branch_inventory " +
										"WHERE branch_id=? AND book_code=? FOR UPDATE");
						PreparedStatement psUpdateInv = c.prepareStatement(
								"UPDATE branch_inventory SET available_copies = available_copies - 1 " +
										"WHERE branch_id=? AND book_code=?");
						PreparedStatement psLoan = c.prepareStatement(
								"INSERT INTO loans (user_id, book_code, branch_id, start_date, due_date, renewals, status) "
										+
										"VALUES (?,?,?,?,?,0,'ACTIVE')")) {
					psInv.setString(1, branchId);
					psInv.setString(2, bookCode);
					ResultSet rs = psInv.executeQuery();
					if (!rs.next()) {
						throw new IllegalStateException("Libro no existe en inventario de la sede");
					}
					int available = rs.getInt(1);
					if (available <= 0) {
						throw new IllegalStateException("No hay ejemplares disponibles en la sede");
					}

					// Actualizar inventario
					psUpdateInv.setString(1, branchId);
					psUpdateInv.setString(2, bookCode);
					psUpdateInv.executeUpdate();

					// Crear préstamo
					LocalDate start = LocalDate.now();
					LocalDate due = start.plusDays(7);

					psLoan.setString(1, userId);
					psLoan.setString(2, bookCode);
					psLoan.setString(3, branchId);
					psLoan.setDate(4, Date.valueOf(start));
					psLoan.setDate(5, Date.valueOf(due));
					psLoan.executeUpdate();

					c.commit();
				} catch (Exception e) {
					c.rollback();
					throw e;
				}
			}
			replicator.replicateNewLoan(branchId, userId, bookCode);
		});
	}
}
