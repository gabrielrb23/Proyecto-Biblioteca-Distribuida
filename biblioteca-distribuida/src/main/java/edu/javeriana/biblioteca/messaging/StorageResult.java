package edu.javeriana.biblioteca.messaging;

import edu.javeriana.biblioteca.common.AppConfig;

public record StorageResult(
		boolean ok,
		String message) {

	// Llave AES tomada desde env o propiedades
	private static final String AES_KEY = AppConfig.getEnvOrProp(
			"ACTOR_ENCRYPTION_KEY",
			"actor.encryption.key",
			"R3p9qL0wN7sX2bV4cY8mK1tH6uP5zQ3");

	// Llave HMAC para firmar mensajes
	private static final String HMAC_KEY = AppConfig.getEnvOrProp(
			"ACTOR_SHARED_SECRET",
			"actor.shared.secret",
			"F8kP2xZ1qW7nT4mB9sD3vL6yH0cJ5rU");

	public String serialize() {
		// Construye payload plano: "OK|mensaje" o "ERR|mensaje"
		String payload = (ok ? "OK" : "ERR") + "|" + (message == null ? "" : message);

		// Cifrar payload con AES-GCM
		String encrypted = CryptoUtil.encryptAesGcmBase64(AES_KEY, payload);

		// Firmar ciphertext con HMAC
		String sig = CryptoUtil.hmacBase64(HMAC_KEY, encrypted);

		// Regresar paquete cifrado + firma
		return encrypted + "|" + sig;
	}

	public static StorageResult parse(String s) {
		// Separar ciphertext y firma
		String[] p = s.split("\\|", -1);
		if (p.length < 2) {
			throw new IllegalArgumentException("StorageResult invalido: " + s);
		}

		String encrypted = p[0];
		String signature = p[1];

		// Verificar integridad del mensaje
		if (!CryptoUtil.verifyHmacBase64(HMAC_KEY, encrypted, signature)) {
			throw new SecurityException("Firma HMAC invalida para StorageResult");
		}

		// Descifrar payload original
		String decrypted = CryptoUtil.decryptAesGcmBase64(AES_KEY, encrypted);

		// Reconstruir campos "OK|mensaje" o "ERR|mensaje"
		String[] fields = decrypted.split("\\|", 2);
		if (fields.length < 1) {
			throw new IllegalArgumentException("Carga util invalida en StorageResult: " + decrypted);
		}

		boolean ok = "OK".equals(fields[0]);
		String msg = fields.length > 1 ? fields[1] : "";

		return new StorageResult(ok, msg);
	}
}
