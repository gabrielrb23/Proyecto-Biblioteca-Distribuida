package edu.javeriana.biblioteca.messaging;

public record Message(String type, String branchId, String userId, String bookCode) {

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

  // Serializar el mensaje
  public String serialize() {
    return String.join("|", type, branchId, userId, bookCode);
  }

  // Deserializar el mensaje
  public static Message parse(String s) {
    String[] p = s.split("\\|", -1);
    if (p.length < 4)
      throw new IllegalArgumentException("bad message: " + s);
    return new Message(p[0], p[1], p[2], p[3]);
  }
}
