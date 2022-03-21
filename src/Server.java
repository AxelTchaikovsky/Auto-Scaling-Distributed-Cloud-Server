/* Server */

public class Server {
  public static void main(String args[]) throws Exception {
    if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
    ServerLib SL = new ServerLib(args[0], Integer.parseInt(args[1]));
    int vmId = Integer.parseInt(args[2]);

    System.err.println("[ Time: " + SL.getTime() + " ]");
    int serverNum = 0;
    if (SL.getTime() <= 6.0) {
      serverNum = 2;
    } else {
      serverNum = 4;
    }
    if (vmId == 1) {
      for (int i = 0; i < serverNum; i++) {
        SL.startVM();
      }
    }
    // register with load balancer so requests are sent to this server
    SL.register_frontend();

    // main loop
    while (true) {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      SL.processRequest(r);
    }
  }
}


