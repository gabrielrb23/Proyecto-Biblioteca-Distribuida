package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.AuditLogger;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class ReturnActor {
  public static void main(String[] args) throws Exception {

    // Determinar si debe ejecutarse en modo sync o async
    boolean syncMode = false;
    for (String a : args) {
      if ("sync".equalsIgnoreCase(a) || "--sync".equalsIgnoreCase(a)) {
        syncMode = true;
        break;
      }
    }

    // Ejecutar modo seleccionado
    if (!syncMode) {
      runAsync();
    } else {
      runSync();
    }
  }

  private static void runAsync() throws Exception {
    // Endpoints PUB/SUB del GC
    String subConnect = System.getProperty(
        "actor.return.sub",
        AppConfig.get("actor.return.sub", "tcp://127.0.0.1:5556"));
    String[] subEndpoints = subConnect.split(",");

    // Endpoints del Gestor de Almacenamiento (GA)
    String gaEndpointsConf = System.getProperty(
        "ga.rep.endpoints",
        AppConfig.get("ga.rep.endpoints", "tcp://10.43.97.18:5560"));
    String[] gaEndpoints = gaEndpointsConf.split(",");

    int gaSendTimeout = 2000; // timeout envío GA
    int gaRecvTimeout = 2000; // timeout recepción GA
    int gaIndex = 0; // índice GA activo

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket sub = ctx.socket(SocketType.SUB)) {

      // Conectar a los endpoints de suscripción
      sub.connect(subEndpoints[0].trim());
      if (subEndpoints.length == 2) {
        sub.connect(subEndpoints[1].trim());
      }

      // Suscribirse al tópico DEVOLUCION
      sub.subscribe("DEVOLUCION".getBytes(ZMQ.CHARSET));
      System.out.println("[ReturnActor] se suscribio al topic DEVOLUCION");

      // Crear socket REQ hacia GA
      ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
      gaReq.setSendTimeOut(gaSendTimeout);
      gaReq.setReceiveTimeOut(gaRecvTimeout);
      gaReq.connect(gaEndpoints[gaIndex].trim());
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex].trim());
      System.out.println();

      while (true) {
        // Recibir mensaje de devolución desde el GC
        String topic = sub.recvStr();
        String payload = sub.recvStr();
        Message msg = Message.parse(payload);

        System.out.printf("[GC] -> [ReturnActor] -> [GA]: %s %s %s %s%n",
            msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

        // Construir comando para el GA
        Message cmd = new Message(
            "DEVOLUCION",
            msg.branchId(),
            msg.userId(),
            msg.bookCode());

        boolean sent = false;
        int attempts = 0;
        StorageResult res = null;

        // Intentar enviar a GA con failover
        while (!sent && attempts < gaEndpoints.length) {
          try {
            gaReq.send(cmd.serialize());
            String rawRes = gaReq.recvStr();
            if (rawRes == null) {
              throw new RuntimeException("Timeout al recibir respuesta de GA");
            }

            res = StorageResult.parse(rawRes);

            // Registrar auditoría
            if (res.ok()) {
              AuditLogger.log(
                  "ReturnActor",
                  "DEVOLUCION_OK",
                  String.format("branch=%s user=%s book=%s",
                      msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              AuditLogger.log(
                  "ReturnActor",
                  "DEVOLUCION_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s",
                      msg.branchId(), msg.userId(), msg.bookCode(), res.message()),
                  "FAIL");
            }

            // Log de consola
            System.out.printf("[GA] -> [ReturnActor]: %s (%s)%n",
                res.ok() ? "OK" : "ERROR", res.message());
            System.out.println();

            sent = true;

          } catch (Exception e) {
            // Error con el GA actual
            System.err.println(
                "[ReturnActor] Error con GA " + gaEndpoints[gaIndex].trim() + ": " + e.getMessage());

            attempts++;
            if (attempts >= gaEndpoints.length) {
              break;
            }

            // Pasar al siguiente GA disponible
            gaIndex = (gaIndex + 1) % gaEndpoints.length;

            // Recrear socket para conectar al nuevo GA
            gaReq.close();
            gaReq = ctx.socket(SocketType.REQ);
            gaReq.setSendTimeOut(gaSendTimeout);
            gaReq.setReceiveTimeOut(gaRecvTimeout);
            gaReq.connect(gaEndpoints[gaIndex].trim());
            System.out.printf("[ReturnActor] Reintentando con GA: %s%n",
                gaEndpoints[gaIndex].trim());
          }
        }

        // Falló con todos los GA
        if (!sent) {
          res = new StorageResult(false, "No se pudo conectar con ningún Gestor de Almacenamiento");
          System.err.println("[ReturnActor] " + res.message());
          AuditLogger.log(
              "ReturnActor",
              "DEVOLUCION_FAIL",
              String.format("branch=%s user=%s book=%s error=%s",
                  msg.branchId(), msg.userId(), msg.bookCode(), res.message()),
              "FAIL");
        }
      }
    }
  }

  private static void runSync() throws Exception {
    // Endpoint REQ/REP con GC
    String repConnect = System.getProperty(
        "actor.return.req",
        AppConfig.get("actor.return.req", "tcp://0.0.0.0:5558"));

    // Endpoints GA
    String gaEndpointsConf = System.getProperty(
        "ga.rep.endpoints",
        AppConfig.get("ga.rep.endpoints", "tcp://10.43.97.18:5560"));
    String[] gaEndpoints = gaEndpointsConf.split(",");

    int gaSendTimeout = 2000;
    int gaRecvTimeout = 2000;
    int gaIndex = 0;

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket gcRep = ctx.socket(SocketType.REP)) {

      // Escuchar solicitudes del GC
      gcRep.bind(repConnect);
      System.out.printf("[ReturnActor] se conecto a [GC]: %s%n", repConnect);

      // Crear socket hacia GA
      ZMQ.Socket gaReq = ctx.socket(SocketType.REQ);
      gaReq.setSendTimeOut(gaSendTimeout);
      gaReq.setReceiveTimeOut(gaRecvTimeout);
      gaReq.connect(gaEndpoints[gaIndex].trim());
      System.out.printf("[ReturnActor] se conecto a [GA]: %s%n", gaEndpoints[gaIndex].trim());
      System.out.println();

      while (true) {
        // Recibir comando desde el GC
        String rawCmd = gcRep.recvStr();
        Message cmd = Message.parse(rawCmd);

        System.out.printf("[GC] -> [ReturnActor] -> [GA]: %s %s %s %s%n",
            cmd.type(), cmd.branchId(), cmd.userId(), cmd.bookCode());

        StorageResult result = null;
        boolean sent = false;
        int attempts = 0;

        // Intentar contactar GA con failover
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

            // Registro de auditoría
            AuditLogger.log(
                "ReturnActor",
                result.ok() ? "DEVOLUCION_OK" : "DEVOLUCION_FAIL",
                String.format("branch=%s user=%s book=%s",
                    cmd.branchId(), cmd.userId(), cmd.bookCode()),
                result.ok() ? "OK" : "FAIL");

            sent = true;

          } catch (Exception e) {
            // Error con el GA actual
            System.err.printf("[ReturnActor] Error con GA %s: %s%n",
                gaEndpoints[gaIndex].trim(), e.getMessage());

            attempts++;
            if (attempts >= gaEndpoints.length) {
              break;
            }

            // Pasar al siguiente GA
            gaIndex = (gaIndex + 1) % gaEndpoints.length;

            // Re-crear socket para el nuevo GA
            gaReq.close();
            gaReq = ctx.socket(SocketType.REQ);
            gaReq.setSendTimeOut(gaSendTimeout);
            gaReq.setReceiveTimeOut(gaRecvTimeout);
            gaReq.connect(gaEndpoints[gaIndex].trim());
            System.out.printf("[ReturnActor] Reintentando con GA: %s%n",
                gaEndpoints[gaIndex].trim());
          }
        }

        // Si falló con todos los GA
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
