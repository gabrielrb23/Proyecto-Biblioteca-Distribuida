package edu.javeriana.biblioteca.replication;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.concurrent.*;

public class Replicator {
	private final DataSourceRouter router;
	private final ExecutorService exec = Executors.newFixedThreadPool(2);

	public Replicator(DataSourceRouter router) {
		this.router = router;
	}

	public void replicateIncrementAvailable(String branchId, String bookCode) {
		exec.submit(() -> {
			try (Connection c = router.secondary().getConnection();
					PreparedStatement ps = c.prepareStatement(
							"UPDATE branch_inventory " +
									"SET available_copies = available_copies + 1 " +
									"WHERE branch_id = ? AND book_code = ?")) {

				ps.setString(1, branchId);
				ps.setString(2, bookCode);
				int rows = ps.executeUpdate();
				System.out.printf("[Replicator] +available en secundaria %s/%s (filas=%d)%n",
						branchId, bookCode, rows);
			} catch (Exception e) {
				System.err.println("[Replicator] Error replicando inventario: " + e.getMessage());
			}
		});
	}

	public void replicateRenewLoan(String branchId, String userId, String bookCode) {
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
				int rows = ps.executeUpdate();
				System.out.printf("[Replicator] renovación replicada en secundaria %s/%s/%s (filas=%d)%n",
						branchId, userId, bookCode, rows);
			} catch (Exception e) {
				System.err.println("[Replicator] Error replicando renovación: " + e.getMessage());
			}
		});
	}

	public void replicateNewLoan(String branchId, String userId, String bookCode) {
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

				psInv.setString(1, branchId);
				psInv.setString(2, bookCode);
				int invRows = psInv.executeUpdate();

				LocalDate startDate = LocalDate.now();
				LocalDate dueDate = startDate.plusDays(14);

				psLoan.setString(1, userId);
				psLoan.setString(2, bookCode);
				psLoan.setString(3, branchId);
				psLoan.setDate(4, Date.valueOf(startDate));
				psLoan.setDate(5, Date.valueOf(dueDate));
				int loanRows = psLoan.executeUpdate();

				System.out.printf(
						"[Replicator] préstamo replicado en secundaria %s/%s/%s (inv=%d, loans=%d)%n",
						branchId, userId, bookCode, invRows, loanRows);
			} catch (Exception e) {
				System.err.println("[Replicator] Error replicando préstamo nuevo: " + e.getMessage());
			}
		});
	}

	public void shutdown() {
		exec.shutdown();
	}
}
