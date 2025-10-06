package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class ReturnActor {
  public static void main(String[] args) throws Exception {

    // Se llama al gestor de almacenamiento para procesar la devolucion
    String connect = AppConfig.get("actor.return.sub", "tcp://127.0.0.1:5556");

    // Se conecta al gestor de almacenamiento
    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket sub = ctx.socket(SocketType.SUB)) {
      sub.connect(connect);

      // Se suscribe al topico de devoluciones
      sub.subscribe("DEVOLUCION".getBytes(ZMQ.CHARSET));
      System.out.printf("[ReturnActor] se suscribio a [GA]: %s (topic DEVOLUCION)%n", connect);

      while (true) {
        // Se recibe la devolucion
        String topic = sub.recvStr();
        String payload = sub.recvStr();
        Message msg = Message.parse(payload);
        // Aquí se llamara al gestor de almacenamiento
        System.out.printf("[GC]->[ReturnActor] Devolución: %s%n", msg.bookCode());
        Thread.sleep(50);
      }
    }
  }
}
