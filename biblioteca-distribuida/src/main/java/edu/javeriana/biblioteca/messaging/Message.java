package edu.javeriana.biblioteca.messaging;

public record Message(String type, String bookCode, String payload) {

  // Devolucion
  public static Message devolver(String bookCode) {
    return new Message("DEVOLUCION", bookCode, "");
  }

  // Renovacion
  public static Message renovar(String bookCode) {
    return new Message("RENOVACION", bookCode, "");
  }

  // Serializar el mensaje
  public String serialize() {
    return String.join("|", type, bookCode, payload == null ? "" : payload);
  }

  // Deserializar el mensaje
  public static Message parse(String s) {
    String[] p = s.split("\\|", -1);
    if (p.length < 3)
      throw new IllegalArgumentException("bad message: " + s);
    return new Message(p[0], p[1], p[2]);
  }
}
