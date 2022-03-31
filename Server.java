/* Server */

import java.io.IOException;
import java.net.MalformedURLException;
import java.rmi.*;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server extends UnicastRemoteObject implements ServerInterface {
  private static final int FRONT = 0;
  private static final int MID = 1;
  private static int frontCount = 0;
  private static int midCount = 0;
  private static long lastProcessTime = 0;
  private static final long allowedIdleCycle = 7000;
  private static final double frontFactor = 1.3;
  private static int masterProcessCount = 0;
  private static final int allowedMasterProcess = 11;
  private static ServerLib SL;
  private static final ServerInfo info = new ServerInfo();
  private static final double midFactor = 1.2;
  private static int fastRequestCount = 0;

  private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestQueue;
  private static ConcurrentHashMap<Integer, Integer> id2TierMap;

  /**
   * Creates and exports a new UnicastRemoteObject object using an
   * anonymous port.
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

    String ip = args[0];
    int port = Integer.parseInt(args[1]);
    int vmId = Integer.parseInt(args[2]);

    info.setId(vmId);

    SL = new ServerLib(ip, port);
    ServerInterface master = null;

    if (registerMaster(ip, port)) {
      // Set current process as master
      info.setMaster(true);
      // Add (vmId, tier) pair to master hashmap
      addVM2Map(vmId, 0);
      // Register this process as frontend
      SL.register_frontend();
      // ++ master's frontend counter
      frontCount++;

      // Add 1 new frontend server
//      masterScaleOut(0);
      // Add 1 new mid tier server
      masterScaleOut(1);

    } else {
      // set current process as non-master
      info.setMaster(false);
      // get master object by RMI
      master = getInstance(ip, port, "Master");
      if (master == null) {
        System.err.println("kkkkkkkkkkkf");
      }

      System.err.println("before getTier: " + info.getId());
      while (master.getTier(info.getId()) == -1) {
        TimeUnit.SECONDS.sleep(1);
      }
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

    // main loop
    try {
      while (true) {
        if (info.isMaster()) {
          masterRoutine();
        } else {
          if (info.getTier() == FRONT) {
            Cloud.FrontEndOps.Request r = SL.getNextRequest();
            master.addRequest(r);
          } else if (info.getTier() == MID) {
            midRoutine(ip, port, master);
          }
        }
      }
    } catch (Exception e) {
    }
  }

  private static void masterRoutine() {
    Date now = new Date();
    if (SL.getStatusVM(2) == Cloud.CloudOps.VMStatus.Booting) {
      if (masterProcessCount < allowedMasterProcess) {
        Cloud.FrontEndOps.Request r = SL.getNextRequest();
        SL.processRequest(r);
        masterProcessCount++;
        long timeNow = now.getTime();
        if (lastProcessTime != 0 && timeNow - lastProcessTime < 1000) {
          SL.dropHead();
        }
        lastProcessTime = timeNow;
      } else {
        SL.dropHead();
      }
      return;
    } else {
      Cloud.FrontEndOps.Request r = SL.getNextRequest();
      masterAddRequest(r);
    }
    // TODO: Conditional Scale out
    if (SL.getQueueLength() > frontFactor * frontCount) {
      masterScaleOut(0);
    }
//    if (masterGetRequestLength() > midCount) {
//      masterScaleOut(1);
//    }
  }

  private static void midRoutine(String ip, int port, ServerInterface master) throws IOException {
    int midMasterCnt = master.getVMCount(1);
    int masterRequestLen = master.getRequestLength();
    Date now = new Date();
    if (lastProcessTime != 0 && now.getTime() - lastProcessTime > allowedIdleCycle && midMasterCnt > 1) {
      shutDown(info.getId(), ip, port);
      return;
    }
//    System.err.println("[ mid tier server: " + midMasterCnt + " | master request len: " + masterRequestLen + " ]");
    Cloud.FrontEndOps.Request r = master.pollRequest();
    if (r != null) {
      if (now.getTime() - lastProcessTime < 500) {
        fastRequestCount++;
      } else {
        fastRequestCount = 0;
      }
      if (fastRequestCount > 5) {
        SL.drop(r);
        fastRequestCount = 0;
        return;
      }
      if (masterRequestLen > midMasterCnt * midFactor) {
        SL.drop(r);
        SL.dropTail();
        // TODO: Master do something
        master.scaleOut(1);
        master.scaleOut(1);
      } else if (masterRequestLen > midMasterCnt && masterRequestLen < midMasterCnt * midFactor) {
        if (now.getTime() - lastProcessTime < 500) {
          SL.drop(r);
        }
        master.scaleOut(1);
      }else {
//        System.err.println("[ Processing request " + r + " ]");
        SL.processRequest(r);
        lastProcessTime = now.getTime();
      }
    } else {
      if (lastProcessTime == 0) {
        lastProcessTime = now.getTime();
      }
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
   * @param ip server ip address
   * @param port server port
   * @param id virtual machine id
   * @throws RemoteException when RMI fails
   */
  private static void registerServer(String ip, int port, int id) throws RemoteException {
    Server server = new Server();
    String url = String.format("//%s:%d/%s", ip, port, id);
    try {
      Naming.bind(url, server);
    } catch (AlreadyBoundException | MalformedURLException e) {
    } catch (RemoteException e) {
      e.printStackTrace(System.err);
    }
  }

  /**
   * Unbind the current server from RMI.
   * @param ip server ip address
   * @param port server port
   * @param id virtual machine id
   */
  private static void unRegisterServer(String ip, int port, int id) {
    ServerInterface server = getInstance(ip, port, Integer.toString(id));
    try {
      UnicastRemoteObject.unexportObject(server, true);
    } catch (RemoteException e) {
      e.printStackTrace(System.err);
    }
  }

  /**
   * Shut down itself by ID.
   * @param vmId virtual machine's id
   * @param ip ip address
   * @param port server port
   */
  private static void shutDown(int vmId, String ip, int port) {
    System.out.println("[ Shutting down " + vmId + " ]");
    SL.shutDown();
//    unRegisterServer(ip, port, vmId);
    try {
      ServerInterface master = getInstance(ip, port, "Master");
      assert master != null;
      master.deleteVMFromMap(vmId);
    } catch (RemoteException e) {
      e.printStackTrace(System.err);
    }
    System.exit(0);
  }

  /**
   * When current server is the master server, add (vmId, tierId) pair to
   * hashmap.
   * @param vmId virtual machine's id
   * @param tierId 0 for front-tier, 1 for mid-tier
   */
  public synchronized static void addVM2Map(int vmId, int tierId) {
    if (!id2TierMap.containsKey(vmId)) {
      id2TierMap.put(vmId, tierId);
      if (tierId == 0) {
        frontCount++;
      } else if (tierId == 1) {
        midCount++;
      }
    }
//
//    System.err.print("addVM2Map: ");
//    for (int id : id2TierMap.keySet()) {
//      System.err.print(id + " ");
//    }
//    System.err.println();

  }

  /**
   * Master server operation: delete virtual machine from hashmap, decrement
   * counter.
   * @param vmId ID of virtual machine.
   */
  @Override
  public synchronized void deleteVMFromMap(int vmId) {
    if (id2TierMap.containsKey(vmId)) {
      int tierId = id2TierMap.get(vmId);
      id2TierMap.remove(vmId);
      if (tierId == 0) {
//        System.err.println("[ -- front ]");
        frontCount--;
      } else if (tierId == 1) {
//        System.err.println("[ -- mid ]");
        midCount--;
      }
    }
  }

  @Override
  public int getTier(int id) throws RemoteException {
//    System.err.print("getTier: ");
//    for (int x : id2TierMap.keySet()) {
//      System.err.print(x + " ");
//    }
//    System.err.println();

    if (!id2TierMap.containsKey(id)) {
      return -1;
//      System.err.println("No key");
    }

    return id2TierMap.get(id);
  }

  private synchronized static void masterAddRequest(Cloud.FrontEndOps.Request request) {
    requestQueue.add(request);
  }

  public void scaleOut(int tierId) throws RemoteException {
    scaleOutCore(tierId);
  }

  private static void scaleOutCore(int tierId) {
    int vmId = SL.startVM();
//    System.err.println("masterScaleOut: adding... " + vmId);
    addVM2Map(vmId, tierId);
//    System.err.println("masterScaleOut: Done~ " + vmId);

    if (tierId == 0) {
      frontCount++;
    } else {
      midCount++;
    }
  }

  public static void masterScaleOut(int tierId) {
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
//    System.err.println("[ Request length: " + requestQueue.size() + " ]");
  }

  @Override
  public int getRequestLength() {
    return requestQueue.size();
  }

  @Override
  public ServerLib getSL() {
    return SL;
  }

  private static int masterGetRequestLength() {
    return requestQueue.size();
  }
}


