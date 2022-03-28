/* Server */

import java.net.MalformedURLException;
import java.rmi.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Server implements ServerInterface {
  private int frontCount;
  private int midCount;
  private ServerLib serverLib;

  private LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestQueue;
  private ConcurrentHashMap<Integer, Integer> id2TierMap;

  public static void main(String args[]) throws Exception {
    if (args.length != 3) {
      throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
    }
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

  private ServerInterface getInstance(String ip, int port, String name) {
    try {
      String url = String.format("//%s:%d/%s", ip, port, name);
      return (ServerInterface) Naming.lookup(url);
    } catch (NotBoundException | RemoteException | MalformedURLException e) {
      e.printStackTrace(System.err);
      return null;
    }
  }

  private boolean registerMaster(String ip, int port) {
    Server masterServer = new Server();
    frontCount++;
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

  private boolean registerFrontTier(String ip, int port, int id) {
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

  private boolean registerMidTier(String ip, int port, int id) {
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

  private void shutDown(int vmId, String ip, int port) {
    ServerInterface server = null;
    int tierId = id2TierMap.get(vmId);
    if (tierId == 0) {
      server = getInstance(ip, port, "Front" + vmId);
    } else if (tierId == 1) {
      server = getInstance(ip,port, "Mid" + vmId);
    }
    serverLib.interruptGetNext();
    serverLib.shutDown();
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
    int vmId = serverLib.startVM();
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


