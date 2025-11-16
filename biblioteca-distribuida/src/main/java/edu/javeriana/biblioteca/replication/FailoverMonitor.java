package edu.javeriana.biblioteca.replication;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.*;

public class FailoverMonitor {

	private final DataSourceRouter router;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private final long intervalMs;

	public FailoverMonitor(DataSourceRouter router, long intervalMs) {
		this.router = router;
		this.intervalMs = intervalMs;
	}

	public void start() {
		scheduler.scheduleAtFixedRate(this::checkPrimary, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
	}

	public void stop() {
		scheduler.shutdownNow();
	}

	private void checkPrimary() {
		try {
			DataSource ds = router.primary();
			try (Connection c = ds.getConnection();
					PreparedStatement ps = c.prepareStatement("SELECT 1")) {
				ps.execute();
			}
			if (!router.isPrimaryUp()) {
				router.switchToPrimary();
			}
		} catch (Exception e) {
			if (router.isPrimaryUp()) {
				System.err.println("[FailoverMonitor] Primaria no responde: " + e.getMessage());
				router.switchToSecondary();
			}
		}
	}
}
