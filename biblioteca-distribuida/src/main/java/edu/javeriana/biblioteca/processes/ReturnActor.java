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
    String subConnect = System.getProperty(
        "actor.return.sub",
        AppConfig.get("actor.return.sub", "tcp://127.0.0.1:5556"));
    String[] subEndpoints = subConnect.split(",");

    String gaEndpointsConf = System.getProperty(
        "ga.rep.endpoints",
        AppConfig.get("ga.rep.endpoints", "tcp://10.43.97.18:5560"));
    String[] gaEndpoints = gaEndpointsConf.split(",");

    int gaSendTimeout = 2000;
    int gaRecvTimeout = 2000;
    int gaIndex = 0;

    // Se conecta al gestor de almacenamiento
    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket sub = ctx.socket(SocketType.SUB)) {

      sub.connect(subEndpoints[0].trim()); // Se suscribe al topico de devoluciones
      if (subEndpoints.length == 2) {
        sub.connect(subEndpoints[1].trim());
      }

      sub.subscribe("DEVOLUCION".getBytes(ZMQ.CHARSET));
      System.out.println("[ReturnActor] se suscribio al topic DEVOLUCION");

      ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
      gaReq.setSendTimeOut(gaSendTimeout);
      gaReq.setReceiveTimeOut(gaRecvTimeout);
      gaReq.connect(gaEndpoints[gaIndex].trim());
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex].trim());
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

        boolean sent = false;
        int attempts = 0;
        StorageResult res = null;

        while (!sent && attempts < gaEndpoints.length) {
          try {
            gaReq.send(cmd.serialize());
            String rawRes = gaReq.recvStr();
            if (rawRes == null) {
              throw new RuntimeException("Timeout al recibir respuesta de GA");
            }

            res = StorageResult.parse(rawRes);

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
            sent = true;
          } catch (Exception e) {
            System.err.println(
                "[ReturnActor] Error con GA " + gaEndpoints[gaIndex].trim() + ": " + e.getMessage());

            attempts++;
            if (attempts >= gaEndpoints.length) {
              break;
            }

            gaIndex = (gaIndex + 1) % gaEndpoints.length;

            // Recrear socket REQ hacia el nuevo GA
            gaReq.close();
            gaReq = ctx.socket(SocketType.REQ);
            gaReq.setSendTimeOut(gaSendTimeout);
            gaReq.setReceiveTimeOut(gaRecvTimeout);
            gaReq.connect(gaEndpoints[gaIndex].trim());
            System.out.printf("[ReturnActor] Reintentando con GA: %s%n", gaEndpoints[gaIndex].trim());

          }
        }
        if (!sent) {
          res = new StorageResult(false, "No se pudo conectar con ningún Gestor de Almacenamiento");
          System.err.println("[ReturnActor] " + res.message());
          AuditLogger.log(
              "ReturnActor",
              "DEVOLUCION_FAIL",
              String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                  res.message()),
              "FAIL");
        }
      }
    }
  }

  private static void runSync() throws Exception {
    String repConnect = System.getProperty(
        "actor.return.req",
        AppConfig.get("actor.return.req", "tcp://0.0.0.0:5558"));
    String gaEndpointsConf = System.getProperty(
        "ga.rep.endpoints",
        AppConfig.get("ga.rep.endpoints", "tcp://10.43.97.18:5560"));
    String[] gaEndpoints = gaEndpointsConf.split(",");

    int gaSendTimeout = 2000;
    int gaRecvTimeout = 2000;
    int gaIndex = 0;

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket gcRep = ctx.socket(SocketType.REP)) {

      gcRep.bind(repConnect);
      System.out.printf("[ReturnActor] se conecto a [GC]: %s%n", repConnect);

      ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
      gaReq.setSendTimeOut(gaSendTimeout);
      gaReq.setReceiveTimeOut(gaRecvTimeout);
      gaReq.connect(gaEndpoints[gaIndex].trim());
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex].trim());
      System.out.println();

      while (true) {
        String rawCmd = gcRep.recvStr();
        Message cmd = Message.parse(rawCmd);
        System.out.printf("[GC] -> [ReturnActor] -> [GA]: %s %s %s %s%n",
            cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

        StorageResult result = null;
        boolean sent = false;
        int attempts = 0;

        while (!sent && attempts < gaEndpoints.length) {
          try {
            gaReq.send(cmd.serialize());
            String rawRes = gaReq.recvStr();
            if (rawRes == null) {
              throw new RuntimeException("Timeout al recibir respuesta de GA");
            }

            result = StorageResult.parse(rawRes);
            System.out.printf("[GA] -> [ReturnActor]: %s (%s)%n",
                result.ok() ? "OK" : "ERROR", result.message());
            System.out.println();
            AuditLogger.log(
                "ReturnActor",
                result.ok() ? "DEVOLUCION_OK" : "DEVOLUCION_FAIL",
                String.format("branch=%s user=%s book=%s", cmd.branchId(), cmd.userId(), cmd.bookCode()),
                result.ok() ? "OK" : "FAIL");

            sent = true;
          } catch (Exception e) {
            System.err.printf("[ReturnActor] Error con GA %s: %s%n",
                gaEndpoints[gaIndex].trim(), e.getMessage());

            attempts++;
            if (attempts >= gaEndpoints.length) {
              break;
            }

            // Pasar al siguiente GA (cíclico)
            gaIndex = (gaIndex + 1) % gaEndpoints.length;

            // Re-crear socket REQ para el nuevo GA
            gaReq.close();
            gaReq = ctx.socket(SocketType.REQ);
            gaReq.setSendTimeOut(gaSendTimeout);
            gaReq.setReceiveTimeOut(gaRecvTimeout);
            gaReq.connect(gaEndpoints[gaIndex].trim());
            System.out.printf("[ReturnActor] Reintentando con GA: %s%n",
                gaEndpoints[gaIndex].trim());
          }
        }

        if (!sent) {
          result = new StorageResult(false, "No se pudo conectar con ningún Gestor de Almacenamiento");
          System.err.println("[ReturnActor] " + result.message());
        }

        // Responder al GC
        gcRep.send(result.serialize());
      }
    }
  }
}