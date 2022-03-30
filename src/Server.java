/* Server */

import java.net.MalformedURLException;
import java.rmi.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Server implements ServerInterface {
  private static final int FRONT = 0;
  private static final int MID = 1;
  private static int frontCount;
  private static int midCount;
  private static ServerLib SL;
  private static ServerInfo info;
  private static ServerInterface master;

  private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestQueue;
  private static ConcurrentHashMap<Integer, Integer> id2TierMap;

  public static void main(String args[]) throws Exception {
    if (args.length != 3) {
      throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
    }
    String ip = args[0];
    int port = Integer.parseInt(args[1]);
    int vmId = Integer.parseInt(args[2]);

    SL = new ServerLib(ip, port);

    if (registerMaster(ip, port)) {
      SL.register_frontend();
      SL.startVM();
      midCount++;
    } else {
      master = getInstance(ip, port, "Master");
      info.setMaster(false);
      info.setTier(id2TierMap.get(info.getId()));
      master.addVM(info.getId(), info.getTier());
      if (info.getTier() == FRONT) {
        registerFrontTier(ip, port, info.getId());
        SL.register_frontend();
      } else if (info.getTier() == MID) {
        registerMidTier(ip, port, info.getId());
      }
    }

//    System.err.println("[ Time: " + SL.getTime() + " ]");
//    int serverNum = 0;
//    if (SL.getTime() <= 6.0) {
//      serverNum = 2;
//    } else {
//      serverNum = 4;
//    }
//    if (vmId == 1) {
//      for (int i = 0; i < serverNum; i++) {
//        SL.startVM();
//      }
//    }
//    // register with load balancer so requests are sent to this server
//    SL.register_frontend();

    // main loop
    while (true) {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      SL.processRequest(r);
    }
  }

  private static ServerInterface getInstance(String ip, int port, String name) {
    try {
      String url = String.format("//%s:%d/%s", ip, port, name);
      return (ServerInterface) Naming.lookup(url);
    } catch (NotBoundException | RemoteException | MalformedURLException e) {
      e.printStackTrace(System.err);
      return null;
    }
  }

  /**
   * Try register a server as a master server.
   * @param ip server ip
   * @param port server port
   * @return true if master server have nor been registered, false otherwise.
   */
  private static boolean registerMaster(String ip, int port) {
    Server masterServer = new Server();
    frontCount++;
    id2TierMap = new ConcurrentHashMap<>();
    requestQueue = new LinkedBlockingQueue<>();
    String url = String.format("//%s:%d/%s", ip, port, "Master");
    try {
      Naming.bind(url, masterServer);
      return true;
    } catch (AlreadyBoundException
            | MalformedURLException
            | RemoteException e) {
      e.printStackTrace(System.err);
      return false;
    }
  }

  private static boolean registerFrontTier(String ip, int port, int id) {
    Server frontServer = new Server();
    String url = String.format("//%s:%d/%s", ip, port, "Front" + id);
    try {
      Naming.bind(url, frontServer);
      return true;
    } catch (AlreadyBoundException
            | MalformedURLException
            | RemoteException e) {
      e.printStackTrace(System.err);
      return false;
    }
  }

  private static boolean registerMidTier(String ip, int port, int id) {
    Server midServer = new Server();
    String url = String.format("//%s:%d/%s", ip, port, "Mid" + id);
    try {
      Naming.bind(url, midServer);
      return true;
    } catch (AlreadyBoundException
            | MalformedURLException
            | RemoteException e) {
      e.printStackTrace(System.err);
      return false;
    }
  }

  private static void shutDown(int vmId, String ip, int port) {
    ServerInterface server = null;
    int tierId = id2TierMap.get(vmId);
    if (tierId == 0) {
      server = getInstance(ip, port, "Front" + vmId);
    } else if (tierId == 1) {
      server = getInstance(ip,port, "Mid" + vmId);
    }
    SL.interruptGetNext();
    SL.shutDown();
    try {
      UnicastRemoteObject.unexportObject(server, true);
    } catch (NoSuchObjectException e) {
      e.printStackTrace(System.err);
    }
  }

  @Override
  public void addVM(int vmId, int tierId) {
    if (!id2TierMap.containsKey(vmId)) {
      id2TierMap.put(vmId, tierId);
      if (tierId == 0) {
        frontCount++;
      } else if (tierId == 1) {
        midCount++;
      }
    }
  }

  @Override
  public void scaleOut(int tierId) {
    int vmId = SL.startVM();
    addVM(vmId, tierId);
  }

  @Override
  public int getVMCount(int tierId) {
    if (tierId == 0) {
      return frontCount;
    } else if (tierId == 1) {
      return midCount;
    }
    return -1;
  }

  @Override
  public Cloud.FrontEndOps.Request pollRequest() {
    return requestQueue.poll();
  }

  @Override
  public void addRequest(Cloud.FrontEndOps.Request request) {
    requestQueue.add(request);
  }

  @Override
  public int getRequestLength() {
    return requestQueue.size();
  }
}


