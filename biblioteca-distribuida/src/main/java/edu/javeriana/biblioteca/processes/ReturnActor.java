package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.AuditLogger;
import edu.javeriana.biblioteca.messaging.Message;
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
      System.out.println("[ReturnActor] se suscribio al topic DEVOLUCION");

      gaReq.connect(gaConnect);
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaConnect);

      System.out.println();

      while (true) {
        // Se recibe la devolucion
        String topic = sub.recvStr();
        String payload = sub.recvStr();
        Message msg = Message.parse(payload);
        System.out.printf("[GC] -> [ReturnActor] -> [GA]: %s %s %s %s%n",
            msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

        // Construir comando de almacenamiento
        Message cmd = new Message(
            "DEVOLUCION",
            msg.branchId(),
            msg.userId(),
            msg.bookCode());
        gaReq.send(cmd.serialize());

        String rawRes = gaReq.recvStr();
        StorageResult res = StorageResult.parse(rawRes);
        if (res.ok()) {
          AuditLogger.log(
              "ReturnActor",
              "DEVOLUCION_OK",
              String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
              "OK");
        } else {
          AuditLogger.log(
              "ReturnActor",
              "DEVOLUCION_FAIL",
              String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                  res.message()),
              "FAIL");
        }
        System.out.printf("[GA] -> [ReturnActor]: %s (%s)%n",
            res.ok() ? "OK" : "ERROR", res.message());
        System.out.println();
      }
    }
  }
}
