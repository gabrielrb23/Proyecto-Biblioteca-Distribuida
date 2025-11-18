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
		final int maxAttempts = 5; // número total de intentos
		final long waitMs = 2000; // espera entre intentos (2 segundos)

		int attempt = 1;
		while (true) {
			try {
				op.execute();
				return;
			} catch (Exception e) {
				if (!(e instanceof java.sql.SQLException)) {
					System.out.println("[StorageGateway] Error de negocio / no SQL: " + e.getMessage());
					throw e;
				}

				System.err.println("[StorageGateway] Error SQL en intento " + attempt + ": " + e.getMessage());

				if (attempt == 1 && router.isPrimaryUp()) {
					System.err.println("[StorageGateway] -> conmutando a secundaria");
					router.switchToSecondary();
				}

				if (attempt >= maxAttempts) {
					System.err.println("[StorageGateway] No se pudo completar la operación tras "
							+ maxAttempts + " intentos. Abortando.");
					throw new IllegalStateException(
							"No se pudo procesar la operación porque la base de datos no estuvo disponible",
							e);
				}

				try {
					Thread.sleep(waitMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Hilo interrumpido mientras se esperaba la BD", ie);
				}

				attempt++;
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

					if (updated > 0) {
						// Caso normal: pasamos de ACTIVE a RETURNED -> sumamos inventario
						psInv.setString(1, branchId);
						psInv.setString(2, bookCode);
						psInv.executeUpdate();
						c.commit();
						// Replicar solo cuando realmente se modificó inventario
						replicator.replicateIncrementAvailable(branchId, bookCode);
						return;
					}

					// Idempotencia: ¿ya estaba devuelto?
					try (PreparedStatement psCheck = c.prepareStatement(
							"SELECT status FROM loans " +
									"WHERE user_id=? AND book_code=? AND branch_id=? " +
									"ORDER BY start_date DESC LIMIT 1")) {
						psCheck.setString(1, userId);
						psCheck.setString(2, bookCode);
						psCheck.setString(3, branchId);
						ResultSet rs = psCheck.executeQuery();

						if (!rs.next()) {
							// Nunca hubo préstamo para este user/libro/sede
							throw new IllegalStateException("No existe préstamo para devolver");
						}

						String status = rs.getString("status");
						if ("RETURNED".equalsIgnoreCase(status)) {
							// Ya estaba devuelto -> tratamos como operación idempotente OK
							c.commit();
							return;
						} else {
							// Hay un préstamo pero no está ACTIVE ni RETURNED (caso raro)
							throw new IllegalStateException(
									"No se pudo devolver: estado actual del préstamo = " + status);
						}
					}
				} catch (Exception e) {
					c.rollback();
					throw e;
				}
			}
		});
	}

	public void applyRenewal(String branchId, String userId, String bookCode) throws Exception {
		runWithFailover(() -> {
			try (Connection c = getWriteConnection()) {
				c.setAutoCommit(false);
				try (PreparedStatement ps = c.prepareStatement(
						"UPDATE loans " +
								"SET renewals = renewals + 1, due_date = due_date + INTERVAL '7 day' " +
								"WHERE user_id=? AND book_code=? AND branch_id=? " +
								"AND status='ACTIVE' AND renewals < 2")) {

					ps.setString(1, userId);
					ps.setString(2, bookCode);
					ps.setString(3, branchId);
					int updated = ps.executeUpdate();

					if (updated > 0) {
						// Caso normal: se aplicó una renovación válida
						c.commit();
						// Replicación de la renovación
						replicator.replicateRenewLoan(branchId, userId, bookCode);
						return;
					}

					// No se actualizó nada: puede ser que:
					// - no haya préstamo
					// - no esté activo
					// - ya tenga 2 renovaciones (caso que queremos tratar como idempotente)
					try (PreparedStatement psCheck = c.prepareStatement(
							"SELECT renewals, status FROM loans " +
									"WHERE user_id=? AND book_code=? AND branch_id=? " +
									"ORDER BY start_date DESC LIMIT 1")) {
						psCheck.setString(1, userId);
						psCheck.setString(2, bookCode);
						psCheck.setString(3, branchId);
						ResultSet rs = psCheck.executeQuery();

						if (!rs.next()) {
							throw new IllegalStateException("No existe préstamo para renovar");
						}

						int renewals = rs.getInt("renewals");
						String status = rs.getString("status");

						if (!"ACTIVE".equalsIgnoreCase(status)) {
							throw new IllegalStateException(
									"No se puede renovar: el préstamo no está activo (estado=" + status + ")");
						}

						if (renewals >= 2) {
							// Ya tiene el máximo de renovaciones; tratamos como idempotente:
							// no avanzamos la fecha, pero tampoco disparamos error de negocio.
							c.commit();
							return;
						}

						// Si llegamos aquí, hay una combinación rara (ej. renewals<2 pero la cláusula
						// no aplicó)
						throw new IllegalStateException(
								"No se pudo renovar (estado inconsistente: renewals=" + renewals + ", status=" + status
										+ ")");
					}
				} catch (Exception e) {
					c.rollback();
					throw e;
				}
			}
		});
	}

	public void applyLoan(String branchId, String userId, String bookCode) throws Exception {
		runWithFailover(() -> {
			try (Connection c = getWriteConnection()) {
				c.setAutoCommit(false);
				try (
						PreparedStatement psCheckLoan = c.prepareStatement(
								"SELECT loan_id FROM loans " +
										"WHERE branch_id=? AND user_id=? AND book_code=? AND status='ACTIVE' " +
										"FOR UPDATE");
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

					// 1. Idempotencia: ¿ya hay préstamo activo igual?
					psCheckLoan.setString(1, branchId);
					psCheckLoan.setString(2, userId);
					psCheckLoan.setString(3, bookCode);
					ResultSet rsLoan = psCheckLoan.executeQuery();
					if (rsLoan.next()) {
						// Ya existe un préstamo activo para este usuario/libro/sede
						// -> consideramos la operación como idempotente y NO hacemos nada más.
						c.commit();
						return;
					}

					// 2. Flujo normal: revisar inventario
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

					// 3. Actualizar inventario
					psUpdateInv.setString(1, branchId);
					psUpdateInv.setString(2, bookCode);
					psUpdateInv.executeUpdate();

					// 4. Crear préstamo
					LocalDate start = LocalDate.now();
					LocalDate due = start.plusDays(7);

					psLoan.setString(1, userId);
					psLoan.setString(2, bookCode);
					psLoan.setString(3, branchId);
					psLoan.setDate(4, Date.valueOf(start));
					psLoan.setDate(5, Date.valueOf(due));
					psLoan.executeUpdate();

					c.commit();

					// 5. Replicar solo cuando realmente se creó un nuevo préstamo
					replicator.replicateNewLoan(branchId, userId, bookCode);
				} catch (Exception e) {
					c.rollback();
					throw e;
				}
			}
		});
	}
}
