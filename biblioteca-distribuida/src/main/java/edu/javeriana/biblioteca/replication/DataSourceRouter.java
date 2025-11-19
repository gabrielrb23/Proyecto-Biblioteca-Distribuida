package edu.javeriana.biblioteca.replication;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

public class DataSourceRouter {

	private final DataSource primary; // DataSource principal
	private final DataSource secondary; // DataSource secundario (backup)
	private volatile boolean primaryAvailable = true; // indica si la primaria está activa

	public DataSourceRouter(String pUrl, String pUser, String pPass,
			String sUrl, String sUser, String sPass) {

		// Crear DataSources simples para primaria y secundaria
		this.primary = new SimpleDataSource(pUrl, pUser, pPass);
		this.secondary = new SimpleDataSource(sUrl, sUser, sPass);
	}

	public DataSource currentWrite() {
		// Devuelve la BD para operaciones de escritura (primaria si está arriba)
		return primaryAvailable ? primary : secondary;
	}

	public DataSource currentRead() {
		// Lecturas también siguen primaria → secundaria solo si hay failover
		return primaryAvailable ? primary : secondary;
	}

	public DataSource primary() {
		// Acceso directo a la primaria
		return primary;
	}

	public DataSource secondary() {
		// Acceso directo a la secundaria
		return secondary;
	}

	public void switchToSecondary() {
		// Cambiar a la BD secundaria
		if (primaryAvailable) {
			primaryAvailable = false;
			System.out.println("[Router] Conmutado a SECUNDARIA");
		}
	}

	public void switchToPrimary() {
		// Volver a la BD primaria si se recupera
		if (!primaryAvailable) {
			primaryAvailable = true;
			System.out.println("[Router] Conmutado a PRIMARIA");
		}
	}

	public boolean isPrimaryUp() {
		// Saber si la primaria está activa
		return primaryAvailable;
	}

	// Implementación mínima de DataSource
	private static class SimpleDataSource implements DataSource {

		private final String url;
		private final String user;
		private final String pass;

		SimpleDataSource(String url, String user, String pass) {
			this.url = url;
			this.user = user;
			this.pass = pass;
		}

		@Override
		public Connection getConnection() throws SQLException {
			// Crear conexión con credenciales por defecto
			return DriverManager.getConnection(url, user, pass);
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			// Crear conexión con credenciales explícitas
			return DriverManager.getConnection(url, username, password);
		}

		// Métodos no utilizados en el proyecto
		@Override
		public <T> T unwrap(Class<T> iface) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) {
			return false;
		}

		@Override
		public PrintWriter getLogWriter() {
			return null;
		}

		@Override
		public void setLogWriter(PrintWriter out) {
		}

		@Override
		public void setLoginTimeout(int seconds) {
		}

		@Override
		public int getLoginTimeout() {
			return 0;
		}

		@Override
		public Logger getParentLogger() {
			return Logger.getGlobal();
		}
	}
}
