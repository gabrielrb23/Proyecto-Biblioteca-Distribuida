package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import edu.javeriana.biblioteca.messaging.StorageResult;

import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoadManager {
  public static void main(String[] args) {
    String repBindGc = AppConfig.get("gc.rep", "tcp://127.0.0.1:5555");
    String pubBind = AppConfig.get("gc.pub", "tcp://127.0.0.1:5556");
    String repBindAc = AppConfig.get("actor.loan.rep", "tcp://127.0.0.1:5557");

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket rep = ctx.socket(SocketType.REP);
        ZMQ.Socket pub = ctx.socket(SocketType.PUB);
        ZMQ.Socket req = ctx.socket(SocketType.REQ)) {

      rep.bind(repBindGc);
      pub.bind(pubBind);
      req.connect(repBindAc);
      System.out.printf("[GC] Conectado a [PS]: %s y [GA]: %s%n", repBindGc, pubBind);

      while (true) {
        String reqString = rep.recvStr();
        Message msg = Message.parse(reqString);
        String type = msg.type();
        System.out.printf("[PS]->[GC]: %s %s%n", msg.type(), msg.bookCode());

        switch (type) {
          case "DEVOLUCION":
          case "RENOVACION":
            rep.send("Se ha recibido su solicitud de " + type + " para el libro " + msg.bookCode());

            pub.sendMore(type);
            pub.send(msg.serialize());
            System.out.printf("[GC] PUB topic=%s payload=%s%n", type, msg.serialize());
            break;

          case "PRESTAMO":
            System.out.println("[GC] Enviando solicitud de PRESTAMO al LoanActor...");
            req.send(msg.serialize());

            String loanResStr = req.recvStr();
            StorageResult loanRes = StorageResult.parse(loanResStr);

            if (loanRes.ok()) {
              rep.send("Préstamo concedido para el libro " + msg.bookCode() + ": " + loanRes.message());
            } else {
              rep.send("No se pudo realizar el préstamo de " + msg.bookCode() + ": " + loanRes.message());
            }

            System.out.printf("[GC] PRESTAMO resultado: %s (%s)%n", loanRes.ok() ? "OK" : "ERR", loanRes.message());
            break;

          default:
            rep.send("Tipo de operación no soportado por GC: " + msg.type());
            System.out.printf("[GC] Tipo desconocido: %s%n", msg.type());
        }
      }
    }
  }
}
