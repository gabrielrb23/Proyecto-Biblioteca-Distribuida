package edu.javeriana.biblioteca.messaging;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.CryptoUtil;

public record Message(String type, String branchId, String userId, String bookCode) {

  private static final String AES_KEY = AppConfig.getEnvOrProp(
      "ACTOR_ENCRYPTION_KEY",
      "actor.encryption.key",
      "R3p9qL0wN7sX2bV4cY8mK1tH6uP5zQ3");

  private static final String HMAC_KEY = AppConfig.getEnvOrProp(
      "ACTOR_SHARED_SECRET",
      "actor.shared.secret",
      "F8kP2xZ1qW7nT4mB9sD3vL6yH0cJ5rU");

  // Devolucion
  public static Message devolver(String branchId, String userId, String bookCode) {
    return new Message("DEVOLUCION", branchId, userId, bookCode);
  }

  // Renovacion
  public static Message renovar(String branchId, String userId, String bookCode) {
    return new Message("RENOVACION", branchId, userId, bookCode);
  }

  // Prestamo
  public static Message prestar(String branchId, String userId, String bookCode) {
    return new Message("PRESTAMO", branchId, userId, bookCode);
  }

  public String serialize() {
    // Lo que queremos proteger
    String payload = String.join("|", type, branchId, userId, bookCode);

    // Cifrar payload
    String encrypted = CryptoUtil.encryptAesGcmBase64(AES_KEY, payload);

    // Firmar
    String sig = CryptoUtil.hmacBase64(HMAC_KEY, encrypted);

    // Paquete final que viaja por ZeroMQ
    return encrypted + "|" + sig;
  }

  public static Message parse(String s) {
    String[] p = s.split("\\|", -1);
    if (p.length < 2)
      throw new IllegalArgumentException("Mensaje invalido: " + s);

    String encrypted = p[0];
    String signature = p[1];

    // Verificar HMAC
    if (!CryptoUtil.verifyHmacBase64(HMAC_KEY, encrypted, signature)) {
      throw new SecurityException("Firma HMAC invalida para mensaje seguro");
    }

    // Descifrar todos los campos
    String decrypted = CryptoUtil.decryptAesGcmBase64(AES_KEY, encrypted);
    String[] fields = decrypted.split("\\|", -1);
    if (fields.length < 4) {
      throw new IllegalArgumentException("Carga util invalida en mensaje seguro: " + decrypted);
    }

    String type = fields[0];
    String branchId = fields[1];
    String userId = fields[2];
    String bookCode = fields[3];

    return new Message(type, branchId, userId, bookCode);
  }
}
