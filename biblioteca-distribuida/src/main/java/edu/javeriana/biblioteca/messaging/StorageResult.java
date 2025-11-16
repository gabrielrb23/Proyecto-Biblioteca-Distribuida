package edu.javeriana.biblioteca.messaging;

public record StorageResult(
		boolean ok,
		String message) {
	public String serialize() {
		return (ok ? "OK" : "ERR") + "|" + (message == null ? "" : message);
	}

	public static StorageResult parse(String s) {
		String[] p = s.split("\\|", 2);
		boolean ok = p[0].equals("OK");
		String msg = p.length > 1 ? p[1] : "";
		return new StorageResult(ok, msg);
	}
}
