/* Server */

import java.io.IOException;
import java.net.MalformedURLException;
import java.rmi.AlreadyBoundException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server extends UnicastRemoteObject implements ServerInterface {
  private static final int FRONT = 0;
  private static final int MID = 1;
  private static final int allowedMasterProcess = 15;
  private static final double frontFactor = 5;
  private static final double midFactor = 3;
  private static final long allowedIdleCycle = 2000;
  private static final ServerInfo info = new ServerInfo();
  private static final int fastRequestInterval = 400;
  private static int frontCount = 0;
  private static int midCount = 0;
  private static long lastProcessTime = 0;
  private static int masterProcessCount = 0;
  private static ServerLib SL;
  private static int fastRequestCount = 0;
  private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestQueue;
  private static ConcurrentHashMap<Integer, Integer> id2TierMap;
  private static long masterStartTime;
  private static int shortQueueCount;
  private static long lastMeasureTime;
  private static String ip;
  private static int port;
  private static int vmId;

  /**
   * Creates and exports a new UnicastRemoteObject object using an anonymous port.
   *
   * <p>The object is exported with a server socket
   * created using the {@link RMISocketFactory} class.
   *
   * @throws RemoteException if failed to export object
   * @since JDK1.1
   */
  protected Server() throws RemoteException {
    super();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
    }
    ip = args[0];
    port = Integer.parseInt(args[1]);
    vmId = Integer.parseInt(args[2]);

    info.setId(vmId);

    SL = new ServerLib(ip, port);
    ServerInterface master = null;

    if (registerMaster(ip, port)) {
      masterHandler();
    } else {
      // set current process as non-master
      info.setMaster(false);
      // get master object by RMI
      master = getInstance("Master");
      System.err.println("before getTier: " + info.getId());
//      while (master.getTier(info.getId()) == -1) {
//        TimeUnit.SECONDS.sleep(1);
//      }
      info.setTier(master.getTier(info.getId()));

      if (info.getTier() == FRONT) {
        System.err.println("[ Is frontend Server... ]");
        registerServer(ip, port, info.getId());
        SL.register_frontend();
      } else if (info.getTier() == MID) {
        System.err.println("[ Is mid-tier Server... ]");
        registerServer(ip, port, info.getId());
      } else {
        assert false;
      }
    }

    Cloud.DatabaseOps cache = null;
    try {
      cache = (Cloud.DatabaseOps) Naming.lookup(String.format("//%s:%d/%s", ip, port, "Cache"));
    } catch (NotBoundException e) {
      e.printStackTrace(System.err);
    }

    // main loop
    try {
      while (true) {
        if (info.isMaster()) {
          masterRoutine();
        } else {
          if (info.getTier() == FRONT) {
            frontRoutine(master);
          } else if (info.getTier() == MID) {
            midRoutine(master, cache);
          }
        }
      }
    } catch (Exception e) {
      // Catches the EOF exception introduced by the infinite loop.
    }
  }

  /**
   * Handle master server life cycle.
   */
  private static void masterHandler() {
    /*
     Start cache
     Set current process as master
     Add (vmId, tier) pair to master hashmap
     Register self as frontend
     ++ master's frontend counter
     Add 1 new mid-tier server
    */
    startCache(ip, port);
    info.setMaster(true);
    addVM2Map(vmId, 0);
    SL.register_frontend();
    masterStartTime = new Date().getTime();
    frontCount++;
    masterScaleOut(1);

    try {
      while (true) {
        masterRoutine();
      }
    } catch (Exception e) {
      // Do nothing
    }
  }

  private static void masterRoutine() {
    if (SL.getStatusVM(2) == Cloud.CloudOps.VMStatus.Booting) {
      handleBooting();
      return;
    } else {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      masterAddRequest(r);
    }
    // TODO: Conditional Scale out
    if (SL.getQueueLength() > frontFactor * frontCount) {
      masterScaleOut(0);
    }
  }

  private static void frontRoutine(ServerInterface master) throws RemoteException {
    if (frontScaleIn(ip, port, master)) return;
    Cloud.FrontEndOps.Request r = SL.getNextRequest();
    master.addRequest(r);
  }

  private static boolean frontScaleIn(String ip, int port, ServerInterface master) throws RemoteException {
    int frontMasterCnt = master.getVMCount(0);
    int masterRequestLen = master.getRequestLength();
    if (masterRequestLen < 2) {
      shortQueueCount++;
      if (shortQueueCount > 40 && frontMasterCnt > 1) {
        shutDown(info.getId());
        return true;
      }
    } else {
      shortQueueCount = 0;
    }
    return false;
  }

  private static boolean midScaleIn(String ip, int port, ServerInterface master) throws RemoteException {
    int midMasterCnt = master.getVMCount(1);
    int masterRequestLen = master.getRequestLength();
    if (masterRequestLen < 1) {
      shortQueueCount++;
      if (shortQueueCount > 60 && midMasterCnt > 1) {
        shutDown(info.getId());
        return true;
      }
    } else {
      shortQueueCount = 0;
    }
    return false;
  }


  private static void handleBooting() {
    Date now = new Date();
    long timeDiff = now.getTime() - masterStartTime;
//    System.err.println("[ Started for:" + timeDiff + "; queue len: " + SL.getQueueLength() + " ]");
    if (timeDiff > 1000) {
      int queueLen = SL.getQueueLength();
//      System.err.println("[ Started for:" + timeDiff + "; queue len: " + queueLen + " ]");
      if (queueLen != 0) {
        if (timeDiff / queueLen < 185) {
          System.err.println("[ Rate over 1000/150, start 3 mid 1 front ]");
          masterScaleOut(0);
          masterScaleOut(1);
          masterScaleOut(1);
          masterScaleOut(1);
        }
      }
      masterStartTime = Long.MAX_VALUE;
    }
    if (masterProcessCount < allowedMasterProcess) {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      SL.processRequest(r);
      masterProcessCount++;
      long timeNow = now.getTime();
      if (lastProcessTime != 0 && timeNow - lastProcessTime < 800) {
        SL.dropHead();
      }
      lastProcessTime = timeNow;
    } else {
      SL.dropHead();
    }
  }

  private static void midRoutine(ServerInterface master, Cloud.DatabaseOps cache) throws IOException {
    int midMasterCnt = master.getVMCount(1);
    int masterRequestLen = master.getRequestLength();
    Date now = new Date();
    if (lastProcessTime != 0 && now.getTime() - lastProcessTime > allowedIdleCycle && midMasterCnt > 1) {
      shutDown(info.getId());
      return;
    }
//    if (midScaleIn(ip, port, master)) return;
    Cloud.FrontEndOps.Request r = master.pollRequest();
    if (r != null) {
      if (dropFastRequest(now, r)) return;
      if (masterRequestLen > midMasterCnt * midFactor) {
        master.scaleOut(1);
        SL.drop(r);
        SL.dropTail();
      } else {
        SL.processRequest(r, cache);
        lastProcessTime = now.getTime();
      }
    } else {
      if (lastProcessTime == 0) {
        lastProcessTime = now.getTime();
      }
    }
  }

  /**
   * Drop one request if there is 10 request coming in a row with interval less than 500.
   *
   * @param now The current time
   * @param r   the request from the top of master queue
   * @return true if condition are met and request is dropped, false otherwise.
   */
  private static boolean dropFastRequest(Date now, Cloud.FrontEndOps.Request r) {
    if (now.getTime() - lastProcessTime < fastRequestInterval) {
      fastRequestCount++;
    } else {
      fastRequestCount = 0;
    }
    if (fastRequestCount > 20) {
      SL.drop(r);
      fastRequestCount = 0;
      return true;
    }
    return false;
  }

  private static ServerInterface getInstance(String name) {
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
   *
   * @param ip   server ip
   * @param port server port
   * @return true if master server have nor been registered, false otherwise.
   */
  private static boolean registerMaster(String ip, int port) {
    try {
      Server master = new Server();
      id2TierMap = new ConcurrentHashMap<>();
      requestQueue = new LinkedBlockingQueue<>();
      String url = String.format("//%s:%d/%s", ip, port, "Master");
      Naming.bind(url, master);
    } catch (AlreadyBoundException e) {
      return false;
    } catch (RemoteException | MalformedURLException e) {
      System.err.println("[ Failed creating unicast object ]");
      e.printStackTrace();
      System.exit(-1);
    }
    System.err.println("[ Successfully registered master ]");
    return true;
  }

  /**
   * Bind the current server to a remote object.
   *
   * @param ip   server ip address
   * @param port server port
   * @param id   virtual machine id
   * @throws RemoteException when RMI fails
   */
  private static void registerServer(String ip, int port, int id) throws RemoteException {
    Server server = new Server();
    String url = String.format("//%s:%d/%s", ip, port, id);
    try {
      Naming.bind(url, server);
    } catch (AlreadyBoundException | MalformedURLException e) {
      // Do nothing here, AlreadyBoundException is expected
    } catch (RemoteException e) {
      e.printStackTrace(System.err);
    }
  }

  /**
   * Shut down itself by ID.
   *
   * @param vmId virtual machine's id
   */
  private static void shutDown(int vmId) {
    System.out.println("[ Shutting down " + vmId + " ]");
    SL.shutDown();
    try {
      ServerInterface master = getInstance("Master");
      assert master != null;
      master.deleteVMFromMap(vmId);
    } catch (RemoteException e) {
      // TODO: Shut down exception connection refused (283) and unmarshall (283)
      e.printStackTrace(System.err);
    }
    System.exit(0);
  }

  /**
   * When current server is the master server, add (vmId, tierId) pair to hashmap.
   *
   * @param vmId   virtual machine's id
   * @param tierId 0 for front-tier, 1 for mid-tier
   */
  public synchronized static void addVM2Map(int vmId, int tierId) {
    if (!id2TierMap.containsKey(vmId)) {
      id2TierMap.put(vmId, tierId);
    }
  }

  private synchronized static void masterAddRequest(Cloud.FrontEndOps.Request request) {
    requestQueue.add(request);
  }

  private static void scaleOutCore(int tierId) {
    int vmId = SL.startVM();
    addVM2Map(vmId, tierId);
    if (tierId == 0) {
      frontCount++;
    } else {
      midCount++;
    }
  }

  public static void masterScaleOut(int tierId) {
    scaleOutCore(tierId);
  }

  private static void startCache(String ip, int port) {
    try {
       Cache cache = new Cache(ip, port);
      String url = String.format("//%s:%d/%s", ip, port, "Cache");
      Naming.bind(url, cache);
    } catch (RemoteException | MalformedURLException | AlreadyBoundException e) {
      e.printStackTrace();
    }
  }

  /**
   * Master server operation: delete virtual machine from hashmap, decrement counter.
   *
   * @param vmId ID of virtual machine.
   */
  @Override
  public synchronized void deleteVMFromMap(int vmId) {
    if (id2TierMap.containsKey(vmId)) {
      int tierId = id2TierMap.get(vmId);
      id2TierMap.remove(vmId);
      if (tierId == 0) {
        frontCount--;
      } else if (tierId == 1) {
        midCount--;
      }
    }
  }

  @Override
  public int getTier(int id) throws RemoteException {
    if (!id2TierMap.containsKey(id)) {
      return -1;
    }

    return id2TierMap.get(id);
  }

  public void scaleOut(int tierId) throws RemoteException {
    scaleOutCore(tierId);
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

  @Override
  public ServerLib getSL() {
    return SL;
  }
}


