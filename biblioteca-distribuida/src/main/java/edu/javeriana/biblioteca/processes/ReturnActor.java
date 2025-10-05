package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class ReturnActor {
  public static void main(String[] args) throws Exception {
    String connect = AppConfig.get("actor.return.sub", "tcp://127.0.0.1:5556");
    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket sub = ctx.socket(SocketType.SUB)) {
      sub.connect(connect);
      sub.subscribe("DEVOLUCION".getBytes(ZMQ.CHARSET));
      System.out.printf("[ReturnActor] SUB %s (topic DEVOLUCION)%n", connect);

      while (true) {
        String topic = sub.recvStr();
        String payload = sub.recvStr();
        Message msg = Message.parse(payload);
        // Aquí luego llamarás al GA/BD
        System.out.printf("[ReturnActor] Devolución: book=%s%n", msg.bookCode());
        Thread.sleep(50);
      }
    }
  }
}
