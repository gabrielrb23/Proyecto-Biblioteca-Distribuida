package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.common.AuditLogger;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoadManager {
  public static void main(String[] args) {

    boolean syncMode = false;
    for (String a : args) {
      if ("sync".equalsIgnoreCase(a) || "--sync".equalsIgnoreCase(a)) {
        syncMode = true;
        break;
      }
    }

    if (syncMode) {
      runSync();
    } else {
      runAsync();
    }
  }

  private static void runAsync() {
    String repConnectPS = AppConfig.get("gc.rep", "tcp://127.0.0.1:5555");
    String pubConnect = AppConfig.get("gc.pub", "tcp://127.0.0.1:5556");
    String reqConnectAc = AppConfig.get("actor.loan.req", "tcp://127.0.0.1:5557");

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket rep = ctx.socket(SocketType.REP);
        ZMQ.Socket pub = ctx.socket(SocketType.PUB);
        ZMQ.Socket req = ctx.socket(SocketType.REQ)) {

      rep.bind(repConnectPS);
      pub.bind(pubConnect);
      req.connect(reqConnectAc);

      System.out.printf("[GC] se conecto a [PS] y [LoanActor]: %s y %s%n", repConnectPS, pubConnect);

      while (true) {
        String reqString = rep.recvStr();
        Message msg = Message.parse(reqString);

        System.out.printf("[PS] -> [GC]: %s %s %s %s%n", msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

        switch (msg.type()) {
          case "DEVOLUCION":
            rep.send("Se ha recibido su solicitud de DEVOLUCION para el libro " + msg.bookCode());
            pub.sendMore("DEVOLUCION");
            pub.send(msg.serialize());
            break;

          case "RENOVACION":
            rep.send("Se ha recibido su solicitud de RENOVACION para el libro " + msg.bookCode());
            pub.sendMore("RENOVACION");
            pub.send(msg.serialize());
            break;

          case "PRESTAMO":
            req.send(msg.serialize());
            String loanResStr = req.recvStr();
            StorageResult loanRes = StorageResult.parse(loanResStr);
            if (loanRes.ok()) {
              rep.send("Préstamo concedido para el libro " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log(
                  "GC",
                  "PRESTAMO_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar el préstamo de " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log(
                  "GC",
                  "PRESTAMO_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      loanRes.message()),
                  "FAIL");
            }
            break;

          default:
            rep.send("Tipo de operación no soportado por GC: " + msg.type());
        }
      }
    }
  }

  private static void runSync() {
    String repConnectPS = AppConfig.get("gc.rep", "tcp://127.0.0.1:5555");
    String loanReqConn = AppConfig.get("actor.loan.req", "tcp://127.0.0.1:5557");
    String returnReqConn = AppConfig.get("actor.return.req", "tcp://127.0.0.1:5558");
    String renewalReqConn = AppConfig.get("actor.renewal.req", "tcp://127.0.0.1:5559");

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket rep = ctx.socket(SocketType.REP);
        ZMQ.Socket loanReq = ctx.socket(SocketType.REQ);
        ZMQ.Socket returnReq = ctx.socket(SocketType.REQ);
        ZMQ.Socket renewalReq = ctx.socket(SocketType.REQ)) {

      rep.bind(repConnectPS);
      loanReq.connect(loanReqConn);
      returnReq.connect(returnReqConn);
      renewalReq.connect(renewalReqConn);

      System.out.printf("[GC] se conecto a [PS]: %s%n", repConnectPS);
      System.out.printf("[GC] se conecto a [LoanActor]: %s%n", loanReqConn);
      System.out.printf("[GC] se conecto a [ReturnActor]: %s%n", returnReqConn);
      System.out.printf("[GC] se conecto a [RenewalActor]: %s%n", renewalReqConn);

      while (true) {
        String reqString = rep.recvStr();
        Message msg = Message.parse(reqString);

        System.out.printf("[PS] -> [GC]: %s %s %s %s%n", msg.type(), msg.branchId(), msg.userId(), msg.bookCode());

        switch (msg.type()) {
          case "PRESTAMO":
            loanReq.send(msg.serialize());

            String loanResStr = loanReq.recvStr();
            StorageResult loanRes = StorageResult.parse(loanResStr);

            System.out.printf("[GC] -> [LoanActor]: %s%n", loanResStr);

            if (loanRes.ok()) {
              rep.send("Préstamo concedido para el libro " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log(
                  "GC",
                  "PRESTAMO_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar el préstamo de " + msg.bookCode() + ": " + loanRes.message());
              AuditLogger.log(
                  "GC",
                  "PRESTAMO_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      loanRes.message()),
                  "FAIL");
            }
            break;

          case "DEVOLUCION": {
            returnReq.send(msg.serialize());
            String rawRes = returnReq.recvStr();
            StorageResult resMsg = StorageResult.parse(rawRes);

            System.out.printf("[GC] -> [ReturnActor]: %s%n", rawRes);

            if (resMsg.ok()) {
              rep.send("Devolucion concedido para el libro " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log(
                  "GC",
                  "DEVOLUCION_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar la devolución de " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log(
                  "GC",
                  "DEVOLUCION_FAIL",
                  String.format("branch=%s user=%s book=%s error=%s", msg.branchId(), msg.userId(), msg.bookCode(),
                      resMsg.message()),
                  "FAIL");
            }
            break;
          }

          case "RENOVACION": {
            renewalReq.send(msg.serialize());
            String rawRes = renewalReq.recvStr();
            StorageResult resMsg = StorageResult.parse(rawRes);

            System.out.printf("[GC] -> [RenewalActor]: %s%n", rawRes);

            if (resMsg.ok()) {
              rep.send("Renovacion concedido para el libro " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log(
                  "GC",
                  "RENOVACION_OK",
                  String.format("branch=%s user=%s book=%s", msg.branchId(), msg.userId(), msg.bookCode()),
                  "OK");
            } else {
              rep.send("No se pudo realizar la devolución de " + msg.bookCode() + ": " + resMsg.message());
              AuditLogger.log(
                  "GC",
                  "RENOVACION_FAIL",
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