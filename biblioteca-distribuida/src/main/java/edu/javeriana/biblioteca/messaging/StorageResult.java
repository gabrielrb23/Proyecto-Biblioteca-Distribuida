package edu.javeriana.biblioteca.messaging;

public record StorageResult(
		boolean ok,
		String message) {

	public String serialize() {
		// Convierte el resultado en formato "OK|mensaje" o "ERR|mensaje"
		return (ok ? "OK" : "ERR") + "|" + (message == null ? "" : message);
	}

	public static StorageResult parse(String s) {
		// Reconstruye StorageResult desde el formato serializado
		String[] p = s.split("\\|", 2);
		boolean ok = p[0].equals("OK");
		String msg = p.length > 1 ? p[1] : "";
		return new StorageResult(ok, msg);
	}
}
