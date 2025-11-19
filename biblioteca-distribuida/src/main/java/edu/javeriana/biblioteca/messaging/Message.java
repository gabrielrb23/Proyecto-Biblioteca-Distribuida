package edu.javeriana.biblioteca.messaging;

import edu.javeriana.biblioteca.common.AppConfig;

public record Message(String type, String branchId, String userId, String bookCode) {

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

  // Crea mensaje de devolucion
  public static Message devolver(String branchId, String userId, String bookCode) {
    return new Message("DEVOLUCION", branchId, userId, bookCode);
  }

  // Crea mensaje de renovacion
  public static Message renovar(String branchId, String userId, String bookCode) {
    return new Message("RENOVACION", branchId, userId, bookCode);
  }

  // Crea mensaje de prestamo
  public static Message prestar(String branchId, String userId, String bookCode) {
    return new Message("PRESTAMO", branchId, userId, bookCode);
  }

  public String serialize() {
    // Construir payload plano
    String payload = String.join("|", type, branchId, userId, bookCode);

    // Cifrar payload con AES-GCM
    String encrypted = CryptoUtil.encryptAesGcmBase64(AES_KEY, payload);

    // Firmar ciphertext con HMAC
    String sig = CryptoUtil.hmacBase64(HMAC_KEY, encrypted);

    // Regresar paquete cifrado + firma
    return encrypted + "|" + sig;
  }

  public static Message parse(String s) {
    // Separar ciphertext y firma
    String[] p = s.split("\\|", -1);
    if (p.length < 2)
      throw new IllegalArgumentException("Mensaje invalido: " + s);

    String encrypted = p[0];
    String signature = p[1];

    // Verificar integridad del mensaje
    if (!CryptoUtil.verifyHmacBase64(HMAC_KEY, encrypted, signature)) {
      throw new SecurityException("Firma HMAC invalida para mensaje seguro");
    }

    // Descifrar campos originales
    String decrypted = CryptoUtil.decryptAesGcmBase64(AES_KEY, encrypted);
    String[] fields = decrypted.split("\\|", -1);
    if (fields.length < 4) {
      throw new IllegalArgumentException("Carga util invalida en mensaje seguro: " + decrypted);
    }

    // Reconstruir el record
    String type = fields[0];
    String branchId = fields[1];
    String userId = fields[2];
    String bookCode = fields[3];

    return new Message(type, branchId, userId, bookCode);
  }
}
