package edu.javeriana.biblioteca.replication;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

public class DataSourceRouter {

	private final DataSource primary;
	private final DataSource secondary;
	private volatile boolean primaryAvailable = true;

	public DataSourceRouter(String pUrl, String pUser, String pPass,
			String sUrl, String sUser, String sPass) {
		this.primary = new SimpleDataSource(pUrl, pUser, pPass);
		this.secondary = new SimpleDataSource(sUrl, sUser, sPass);
	}

	public DataSource currentWrite() {
		return primaryAvailable ? primary : secondary;
	}

	public DataSource currentRead() {
		return primaryAvailable ? primary : secondary;
	}

	public DataSource primary() {
		return primary;
	}

	public DataSource secondary() {
		return secondary;
	}

	public void switchToSecondary() {
		if (primaryAvailable) {
			primaryAvailable = false;
			System.out.println("[Router] Conmutado a SECUNDARIA");
		}
	}

	public void switchToPrimary() {
		if (!primaryAvailable) {
			primaryAvailable = true;
			System.out.println("[Router] Conmutado a PRIMARIA");
		}
	}

	public boolean isPrimaryUp() {
		return primaryAvailable;
	}

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
			return DriverManager.getConnection(url, user, pass);
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return DriverManager.getConnection(url, username, password);
		}

		// Métodos no usados
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
