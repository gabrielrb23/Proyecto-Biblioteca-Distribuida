package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.AuditLogger;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class ReturnActor {
  public static void main(String[] args) throws Exception {

    boolean syncMode = false;
    for (String a : args) {
      if ("sync".equalsIgnoreCase(a) || "--sync".equalsIgnoreCase(a)) {
        syncMode = true;
        break;
      }
    }

    if (!syncMode) {
      runAsync();
    } else {
      runSync();
    }

  }

  private static void runAsync() throws Exception {
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

  private static void runSync() throws Exception {
    String repConnect = AppConfig.get("actor.return.rep", "tcp://127.0.0.1:5558");
    String gaConnect = AppConfig.get("ga.rep", "tcp://127.0.0.1:5560");

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket gcRep = ctx.socket(SocketType.REP);
        ZMQ.Socket gaReq = ctx.socket(SocketType.REQ)) {

      gcRep.bind(repConnect);
      System.out.printf("[ReturnActor] se conecto a [GC]: %s%n", repConnect);

      gaReq.connect(gaConnect);
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaConnect);

      System.out.println();

      while (true) {
        String rawCmd = gcRep.recvStr();
        Message cmd = Message.parse(rawCmd);
        System.out.printf("[GC] -> [ReturnActor] -> [GA]: %s %s %s %s%n",
            cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

        StorageResult result;
        gaReq.send(cmd.serialize());

        String rawRes = gaReq.recvStr();
        result = StorageResult.parse(rawRes);
        System.out.printf("[GA] -> [ReturnActor]: %s (%s)%n",
            result.ok() ? "OK" : "ERROR", result.message());
        System.out.println();
        gcRep.send(result.serialize());
      }
    }
  }
}
