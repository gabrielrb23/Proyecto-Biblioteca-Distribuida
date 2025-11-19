package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.AuditLogger;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoadManager {
  public static void main(String[] args) {

    // Determinar si el modo debe ser síncrono o asíncrono
    boolean syncMode = false;
    for (String a : args) {
      if ("sync".equalsIgnoreCase(a) || "--sync".equalsIgnoreCase(a)) {
        syncMode = true;
        break;
      }
    }

    // Ejecutar el modo correspondiente
    if (syncMode) {
      runSync();
    } else {
      runAsync();
    }
  }

  private static void runAsync() {
    // Obtener endpoints para modo asíncrono
    String repConnectPS = System.getProperty("gc.rep", AppConfig.get("gc.rep", "tcp://127.0.0.1:5555"));
    String pubConnect = System.getProperty("gc.pub", AppConfig.get("gc.pub", "tcp://127.0.0.1:5556"));
    String reqConnectAc = System.getProperty("actor.loan.req", AppConfig.get("actor.loan.req", "tcp://127.0.0.1:5557"));

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket rep = ctx.socket(SocketType.REP); // recibe solicitudes PS
        ZMQ.Socket pub = ctx.socket(SocketType.PUB); // publica a actores asíncronos
        ZMQ.Socket req = ctx.socket(SocketType.REQ)) { // canal sincrónico al LoanActor

      rep.bind(repConnectPS);
      pub.bind(pubConnect);
      req.connect(reqConnectAc);

      System.out.printf("[GC] se conecto a [PS] y [LoanActor]: %s y %s%n", repConnectPS, pubConnect);
      System.out.println();

      // Bucle principal del GC modo async
      while (true) {
        String reqString = rep.recvStr(); // recibir petición del PS
        Message msg = Message.parse(reqString);

        System.out.printf("[PS] -> [GC]: %s %s %s %s%n",
            msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

        switch (msg.type()) {
          case "DEVOLUCION":
            // Responder rápido al PS y publicar para ReturnActor
            rep.send("Se ha recibido su solicitud de DEVOLUCION para el libro " + msg.bookCode());
            pub.sendMore("DEVOLUCION");
            pub.send(msg.serialize());
            break;

          case "RENOVACION":
            // Responder rápido al PS y publicar para RenewalActor
            rep.send("Se ha recibido su solicitud de RENOVACION para el libro " + msg.bookCode());
            pub.sendMore("RENOVACION");
            pub.send(msg.serialize());
            break;

          case "PRESTAMO":
            // Canal síncrono: bloquear hasta obtener respuesta del LoanActor
            req.send(msg.serialize());
            String loanResStr = req.recvStr();
            StorageResult loanRes = StorageResult.parse(loanResStr);

            if (loanRes.ok()) {
              rep.send("Préstamo concedido para el libro " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log("GC", "PRESTAMO_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar el préstamo de " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log("GC", "PRESTAMO_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      loanRes.message()),
                  "FAIL");
            }
            break;

          default:
            // Tipo desconocido
            rep.send("Tipo de operación no soportado por GC: " + msg.type());
        }
      }
    }
  }

  private static void runSync() {
    // Endpoints para modo totalmente síncrono
    String repConnectPS = System.getProperty("gc.rep", AppConfig.get("gc.rep", "tcp://127.0.0.1:5555"));
    String loanReqConn = System.getProperty("actor.loan.req", AppConfig.get("actor.loan.req", "tcp://127.0.0.1:5557"));
    String returnReqConn = System.getProperty("actor.return.req",
        AppConfig.get("actor.return.req", "tcp://127.0.0.1:5558"));
    String renewalReqConn = System.getProperty("actor.renew.req",
        AppConfig.get("actor.renew.req", "tcp://127.0.0.1:5559"));

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket rep = ctx.socket(SocketType.REP); // PS -> GC
        ZMQ.Socket loanReq = ctx.socket(SocketType.REQ); // GC -> LoanActor
        ZMQ.Socket returnReq = ctx.socket(SocketType.REQ); // GC -> ReturnActor
        ZMQ.Socket renewalReq = ctx.socket(SocketType.REQ)) {// GC -> RenewalActor

      // Inicializar conexiones
      rep.bind(repConnectPS);
      loanReq.connect(loanReqConn);
      returnReq.connect(returnReqConn);
      renewalReq.connect(renewalReqConn);

      System.out.printf("[GC] se conecto a [PS]: %s%n", repConnectPS);
      System.out.printf("[GC] se conecto a [LoanActor]: %s%n", loanReqConn);
      System.out.printf("[GC] se conecto a [ReturnActor]: %s%n", returnReqConn);
      System.out.printf("[GC] se conecto a [RenewalActor]: %s%n", renewalReqConn);

      // Bucle principal modo sync
      while (true) {
        String reqString = rep.recvStr();
        Message msg = Message.parse(reqString);

        System.out.printf("[PS] -> [GC]: %s %s %s %s%n",
            msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

        switch (msg.type()) {

          case "PRESTAMO":
            // Enviar al LoanActor
            loanReq.send(msg.serialize());
            String loanResStr = loanReq.recvStr();
            StorageResult loanRes = StorageResult.parse(loanResStr);

            System.out.printf("[GC] -> [LoanActor]: %s%n", loanResStr);

            if (loanRes.ok()) {
              rep.send("Préstamo concedido para el libro " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log("GC", "PRESTAMO_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar el préstamo de " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log("GC", "PRESTAMO_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      loanRes.message()),
                  "FAIL");
            }
            break;

          case "DEVOLUCION": {
            // Envío síncrono al ReturnActor
            returnReq.send(msg.serialize());
            String rawRes = returnReq.recvStr();
            StorageResult resMsg = StorageResult.parse(rawRes);

            System.out.printf("[GC] -> [ReturnActor]: %s%n", rawRes);

            if (resMsg.ok()) {
              rep.send("Devolucion concedido para el libro " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log("GC", "DEVOLUCION_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar la devolución de " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log("GC", "DEVOLUCION_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      resMsg.message()),
                  "FAIL");
            }
            break;
          }

          case "RENOVACION": {
            // Envío síncrono al RenewalActor
            renewalReq.send(msg.serialize());
            String rawRes = renewalReq.recvStr();
            StorageResult resMsg = StorageResult.parse(rawRes);

            System.out.printf("[GC] -> [RenewalActor]: %s%n", rawRes);

            if (resMsg.ok()) {
              rep.send("Renovacion concedido para el libro " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log("GC", "RENOVACION_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar la devolución de " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log("GC", "RENOVACION_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      resMsg.message()),
                  "FAIL");
            }
            break;
          }

          default:
            rep.send("Tipo de operación no soportado por GC: " + msg.type());
        }
      }
    }
  }
}
