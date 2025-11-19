package edu.javeriana.biblioteca.replication;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.*;

public class FailoverMonitor {

	private final DataSourceRouter router; // Router que gestiona primaria/secundaria
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // hilo periódico
	private final long intervalMs; // intervalo entre chequeos
	private boolean once = false; // marca para evitar múltiples mensajes de "restauración"

	public FailoverMonitor(DataSourceRouter router, long intervalMs) {
		// Guarda la configuración necesaria
		this.router = router;
		this.intervalMs = intervalMs;
	}

	public void start() {
		// Ejecutar verificación periódica de la BD primaria
		scheduler.scheduleAtFixedRate(this::checkPrimary, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
	}

	public void stop() {
		// Detener el monitor de inmediato
		scheduler.shutdownNow();
	}

	private void checkPrimary() {
		try {
			// Intentar conexión simple a la BD primaria
			DataSource ds = router.primary();
			try (Connection c = ds.getConnection();
					PreparedStatement ps = c.prepareStatement("SELECT 1")) {
				ps.execute(); // si falla, cae al catch
			}

			// Si la primaria "volvió", solo reportamos una vez
			if (!router.isPrimaryUp() && !once) {
				once = true;
				System.out.println(
						"[FailoverMonitor] Primaria volvió, pero mantenemos escritura en secundaria (no auto-failback).");
			}

		} catch (Exception e) {
			// Si la primaria estaba marcada como disponible pero ahora falló → failover
			if (router.isPrimaryUp()) {
				System.err.println("[FailoverMonitor] Primaria no responde: " + e.getMessage());
				router.switchToSecondary(); // conmutar escritura a la secundaria
			}
		}
	}
}
