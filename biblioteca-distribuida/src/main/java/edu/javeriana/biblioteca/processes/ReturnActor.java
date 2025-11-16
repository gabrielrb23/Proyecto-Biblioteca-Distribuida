package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageCommand;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class ReturnActor {
  public static void main(String[] args) throws Exception {

    // Se llama al gestor de almacenamiento para procesar la devolucion
    String subConnect = AppConfig.get("actor.return.sub", "tcp://127.0.0.1:5556");
    String gaConnect = AppConfig.get("ga.rep", "tcp://127.0.0.1:5560");

    // Se conecta al gestor de almacenamiento
    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket sub = ctx.socket(SocketType.SUB);
        ZMQ.Socket gaReq = ctx.socket(SocketType.REQ)) {

      sub.connect(subConnect); // Se suscribe al topico de devoluciones
      sub.subscribe("DEVOLUCION".getBytes(ZMQ.CHARSET));
      System.out.printf("[ReturnActor] se suscribio a [GA]: %s (topic DEVOLUCION)%n", subConnect);

      gaReq.connect(gaConnect);
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaConnect);

      while (true) {
        // Se recibe la devolucion
        String topic = sub.recvStr();
        String payload = sub.recvStr();
        Message msg = Message.parse(payload);

        // Construir comando de almacenamiento
        StorageCommand cmd = new StorageCommand(
            "DEVOLUCION",
            msg.branchId(),
            msg.userId(),
            msg.bookCode());
        gaReq.send(cmd.serialize());

        String rawRes = gaReq.recvStr();
        StorageResult res = StorageResult.parse(rawRes);
        System.out.printf("[ReturnActor] BD -> %s (%s)%n",
            res.ok() ? "OK" : "ERROR", res.message());
      }
    }
  }
}
