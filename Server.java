import java.io.IOException;
import java.net.MalformedURLException;
import java.rmi.*;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Server extends UnicastRemoteObject implements ServerInterface {
  private static final int FRONT = 0;
  private static final int MID = 1;
  private static int allowedMasterProcess = 15;
  private static final double frontFactor = 2.9;
  private static final int addFrontInterval = 4000;
  private static final double midFactor = 3.3;
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
  private static long lastAddFrontTime;
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

    if (vmId == 1) {
      registerMaster(ip, port);
      masterHandler();
    } else {
      slaveHandler();
    }
  }

  /**
   * Handle slave server life cycle.
   */
  private static void slaveHandler() throws RemoteException, MalformedURLException {
    ServerInterface master = getInstance("Master");
    switch (master.getTier(vmId)) {
      case FRONT:
        frontHandler(master);
        break;
      case MID:
        midHandler(master);
        break;
      default:
        assert false;
        break;
    }
  }

  /**
   * Handle master server life cycle.
   */
  private static void masterHandler() {
    /*
     1. Start cache
     2. Set current process as master
     3. Add (vmId, tier) pair to master hashmap
     4. Register self as frontend
     6. ++ master's frontend counter
     7. Add 1 new front-end server and 1 new mid-tier server
    */
    startCache(ip, port);
    info.setMaster(true);
    addVM2Map(vmId, 0);
    SL.register_frontend();
    Date now = new Date();
    masterStartTime = now.getTime();
    frontCount++;
    masterScaleOut(1);
    masterScaleOut(0);

    try {
      while (true) {
        masterRoutine();
      }
    } catch (Exception e) {
      // Do nothing
    }
  }

  /**
   * Manage front-tier server life cycle.
   *
   * @param master the master node.
   * @throws RemoteException when RMI call fails.
   */
  private static void frontHandler(ServerInterface master) throws RemoteException {
    System.err.println("[ Is frontend Server... ]");
    registerServer(ip, port, info.getId());
    SL.register_frontend();
    try {
      while (true) {
        frontRoutine(master);
      }
    } catch (Exception e) {
      // Do nothing
    }
  }

  /**
   * Manage mid-tier server life cycle.
   *
   * @param master the master node.
   * @throws RemoteException when RMI call fails.
   */
  private static void midHandler(ServerInterface master) throws RemoteException,
          MalformedURLException {
    System.err.println("[ Is mid-tier Server... ]");
    registerServer(ip, port, info.getId());
    Cloud.DatabaseOps cache = getCache();
    try {
      while (true) {
        midRoutine(master, cache);
      }
    } catch (Exception e) {
      // Do nothing
    }
  }

  private static void midRoutine(ServerInterface master, Cloud.DatabaseOps cache) throws IOException {
    Date now = new Date();
    int midMasterCnt = master.getVMCount(1);
    int masterRequestLen = master.getRequestLength();
    if (lastProcessTime != 0 && now.getTime() - lastProcessTime > allowedIdleCycle && midMasterCnt > 1) {
      shutDown(info.getId());
      return;
    }
    Cloud.FrontEndOps.Request r = master.pollRequest();
    if (r != null) {
      if (dropFastRequest(r)) {
        return;
      }
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
   * Master server main loop's core implementation.
   */
  private static void masterRoutine() {
    if (SL.getStatusVM(2) == Cloud.CloudOps.VMStatus.Booting) {
      handleBooting();
      return;
    } else {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      masterAddRequest(r);
    }
    // TODO: Conditional Scale out
    Date now = new Date();
    if (lastAddFrontTime == 0) lastAddFrontTime = now.getTime();
    int queueLen = SL.getQueueLength();
    if (now.getTime() - lastAddFrontTime > addFrontInterval
            && queueLen > frontFactor * frontCount) {
      masterScaleOut(0);
      lastAddFrontTime = now.getTime();
      System.err.println("[ last add front time: " + lastAddFrontTime + "]");
      System.err.println("[ queue len: " + queueLen + ", front count: " + frontCount + "]");
    }
  }

  /**
   * Frontend server main loop's core implementation.
   *
   * @param master master server
   * @throws RemoteException when RMI call fails
   */
  private static void frontRoutine(ServerInterface master) throws RemoteException {
    if (frontScaleIn(master)) return;
    Cloud.FrontEndOps.Request r = SL.getNextRequest();
    master.addRequest(r);
  }

  /**
   * Frontend server scale in strategy, if queue stays under 2 in length for 100 cycles, shutdown
   * current front server.
   *
   * @param master master server
   * @return true if current server is closed
   * @throws RemoteException when RMI call fails
   */
  private static boolean frontScaleIn(ServerInterface master) throws RemoteException {
    int frontMasterCnt = master.getVMCount(0);
    int masterRequestLen = master.getRequestLength();
    if (masterRequestLen < 2) {
      shortQueueCount++;
      if (shortQueueCount > 55 && frontMasterCnt > 1) {
        shutDown(info.getId());
        return true;
      }
    } else {
      shortQueueCount = 0;
    }
    return false;
  }

  /**
   * Master's procedure when the first mid-tier sever is still booting. Process at most 15 requests
   * and when process interval is smaller than 800 ms, drop the first request in the serverLib
   * queue. When rate is too high during booting, start 3 middle-tier and 1 front-tier in a row.
   */
  private static void handleBooting() {
    Date now = new Date();
    long timeDiff = now.getTime() - masterStartTime;
    if (timeDiff > 1000) {
      int queueLen = SL.getQueueLength();
      if (queueLen != 0) {
        if (timeDiff / queueLen < 185) {
          System.err.println("[ Rate over 1000/150, start 3 mid 1 front ]");
          masterScaleOut(0);
          masterScaleOut(1);
          masterScaleOut(1);
          masterScaleOut(1);
          masterScaleOut(1);
          allowedMasterProcess = 25;
        }
      }
      masterStartTime = Long.MAX_VALUE;
    }
    if (masterProcessCount < allowedMasterProcess) {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      SL.processRequest(r);
      masterProcessCount++;
      if (lastProcessTime != 0 && now.getTime() - lastProcessTime < 800) {
        SL.dropHead();
      }
      lastProcessTime = now.getTime();
    } else {
      SL.dropHead();
    }
  }

  /**
   * Drop one request if there is 10 request coming in a row with interval less than 500.
   *
   * @param r the request from the top of master queue
   * @return true if condition are met and request is dropped, false otherwise.
   */
  private static boolean dropFastRequest(Cloud.FrontEndOps.Request r) {
    Date now = new Date();
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

  /**
   * Get VM instance by its registered name.
   *
   * @param name "Master" for master server, other server are denoted by vmId.
   * @return the {@link ServerInterface} object
   */
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
   */
  private static void registerMaster(String ip, int port) {
    try {
      Server master = new Server();
      id2TierMap = new ConcurrentHashMap<>();
      requestQueue = new LinkedBlockingQueue<>();
      String url = String.format("//%s:%d/%s", ip, port, "Master");
      Naming.bind(url, master);
    } catch (AlreadyBoundException e) {
      return;
    } catch (RemoteException | MalformedURLException e) {
      System.err.println("[ Failed creating unicast object ]");
      e.printStackTrace();
      System.exit(-1);
    }
    System.err.println("[ Successfully registered master ]");
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
    System.out.println("[ Shutting down " + vmId + " at " + new Date().getTime() + " ]");
    SL.shutDown();
    try {
      ServerInterface master = getInstance("Master");
      assert master != null;
      master.deleteVMFromMap(vmId);
    } catch (RemoteException e) {
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

  /**
   * Register cache to the RMI ip and port.
   *
   * @param ip   ip address
   * @param port RMI port
   */
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
   * Get the remote {@link Cache} object from a remote object registry.
   *
   * @return the {@link Cloud.DatabaseOps} cache instance
   */
  private static Cloud.DatabaseOps getCache() throws MalformedURLException, RemoteException {
    Cloud.DatabaseOps cache = null;
    try {
      cache = (Cloud.DatabaseOps) Naming.lookup(String.format("//%s:%d/%s", ip, port, "Cache"));
    } catch (NotBoundException e) {
      e.printStackTrace(System.err);
    }
    return cache;
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

  /**
   * Master remote operation: get tier information by vmID.
   *
   * @param id virtual machine's id
   * @return 0 for frontend server, 1 for mid-tier server
   */
  @Override
  public int getTier(int id) throws RemoteException {
    if (!id2TierMap.containsKey(id)) {
      return -1;
    }
    return id2TierMap.get(id);
  }

  /**
   * Master remote operation: scale out one server by the given tier.
   *
   * @param tierId 0 for frontend server, 1 for mid-tier server
   */
  public void scaleOut(int tierId) throws RemoteException {
    scaleOutCore(tierId);
  }

  /**
   * Get the count of VM of the given tier.
   *
   * @param tierId 0 for frontend server, 1 for mid-tier server
   * @return the count of VM of the given tier, -1 if tierId is invalid
   */
  @Override
  public int getVMCount(int tierId) {
    if (tierId == 0) {
      return frontCount;
    } else if (tierId == 1) {
      return midCount;
    }
    return -1;
  }

  /**
   * Master remote operation: get one request from the request queue maintain by the master server.
   *
   * @return the {@link Cloud.FrontEndOps.Request} object
   */
  @Override
  public Cloud.FrontEndOps.Request pollRequest() {
    return requestQueue.poll();
  }

  /**
   * Master remote operation: Add one request to the request queue maintain by the master server.
   *
   * @param request the {@link Cloud.FrontEndOps.Request} object
   */
  @Override
  public void addRequest(Cloud.FrontEndOps.Request request) {
    requestQueue.add(request);
  }

  /**
   * Master remote operation: Get the length of the request queue maintain by the master server.
   *
   * @return queue length
   */
  @Override
  public int getRequestLength() {
    return requestQueue.size();
  }

}


