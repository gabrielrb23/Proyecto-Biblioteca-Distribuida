package edu.javeriana.biblioteca.messaging;

public record StorageCommand(
		String type,
		String branchId,
		String userId,
		String bookCode) {
	public String serialize() {
		return String.join("|", type, branchId, userId, bookCode);
	}

	public static StorageCommand parse(String s) {
		String[] p = s.split("\\|", -1);
		if (p.length < 4)
			throw new IllegalArgumentException("bad StorageCommand: " + s);
		return new StorageCommand(p[0], p[1], p[2], p[3]);
	}
}
