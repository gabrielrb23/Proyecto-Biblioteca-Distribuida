package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoadManager {
  public static void main(String[] args) {
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
      System.out.printf("[GC] se conecto a [PS] y [LoanActor]: %s%n", repConnectPS, pubConnect);

      while (true) {
        String reqString = rep.recvStr();
        Message msg = Message.parse(reqString);
        String type = msg.type();
        System.out.printf("[PS]->[GC]: %s %s %s %s%n", msg.type(), msg.bookCode(), msg.branchId(), msg.userId());

        switch (type) {
          case "DEVOLUCION":
          case "RENOVACION":
            rep.send("Se ha recibido su solicitud de " + type + " para el libro " + msg.bookCode());
            pub.send(msg.serialize());
            break;

          case "PRESTAMO":
            req.send(msg.serialize());

            String loanResStr = req.recvStr();
            StorageResult loanRes = StorageResult.parse(loanResStr);

            if (loanRes.ok()) {
              rep.send("Préstamo concedido para el libro " + msg.bookCode() + ": " + loanRes.message());
            } else {
              rep.send("No se pudo realizar el préstamo de " + msg.bookCode() + ": " + loanRes.message());
            }

            System.out.printf("[GC] -> [PS]: %s (%s)%n", loanRes.ok() ? "OK" : "ERR", loanRes.message());
            break;

          default:
            rep.send("Tipo de operación no soportado por GC: " + msg.type());
            System.out.printf("[GC] Tipo desconocido: %s%n", msg.type());
        }
      }
    }
  }
}
