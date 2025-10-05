package edu.javeriana.biblioteca.messaging;

public record Message(String type, String bookCode, String payload) {

  public static Message devolver(String bookCode) {
    return new Message("DEVOLUCION", bookCode, "");
  }

  public static Message renovar(String bookCode) {
    return new Message("RENOVACION", bookCode, "");
  }

  public String serialize() {
    return String.join("|", type, bookCode, payload == null ? "" : payload);
  }

  public static Message parse(String s) {
    String[] p = s.split("\\|", -1);
    if (p.length < 3)
      throw new IllegalArgumentException("bad message: " + s);
    return new Message(p[0], p[1], p[2]);
  }
}
