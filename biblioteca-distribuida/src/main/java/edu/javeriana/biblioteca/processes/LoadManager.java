package edu.javeriana.biblioteca.processes;

import edu.javeriana.biblioteca.common.AppConfig;
import edu.javeriana.biblioteca.messaging.Message;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

public class LoadManager {
  public static void main(String[] args) {
    String repBind = AppConfig.get("gc.rep", "tcp://127.0.0.1:5555");
    String pubBind = AppConfig.get("gc.pub", "tcp://127.0.0.1:5556");

    try (ZMQ.Context ctx = ZMQ.context(1);
        ZMQ.Socket rep = ctx.socket(SocketType.REP);
        ZMQ.Socket pub = ctx.socket(SocketType.PUB)) {

      rep.bind(repBind);
      pub.bind(pubBind);
      System.out.printf("[GC] REP %s, PUB %s%n", repBind, pubBind);

      while (true) {
        String req = rep.recvStr();
        Message msg = Message.parse(req);
        System.out.printf("[GC] Recibido PS: %s %s%n", msg.type(), msg.bookCode());

        rep.send("ACK");

        String topic = msg.type();
        pub.sendMore(topic);
        pub.send(msg.serialize());
        System.out.printf("[GC] Publicado %s -> %s%n", topic, msg.serialize());
      }
    }
  }
}
