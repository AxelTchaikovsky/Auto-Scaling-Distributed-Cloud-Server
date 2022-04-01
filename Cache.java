import java.rmi.RemoteException;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;

public class Cache extends UnicastRemoteObject implements Cloud.DatabaseOps {
  private static ServerLib SL;
  private static Cloud.DatabaseOps DB;
  private ConcurrentHashMap<String, String> cacheMap = new ConcurrentHashMap<>();

  /**
   * Creates and exports a new UnicastRemoteObject object using an anonymous port.
   *
   * <p>The object is exported with a server socket
   * created using the {@link RMISocketFactory} class.
   *
   * @throws RemoteException if failed to export object
   * @since JDK1.1
   */
  protected Cache(String ip, int port) throws RemoteException {
    SL = new ServerLib(ip, port);
    DB = SL.getDB();
  }

  @Override
  public String get(String s) throws RemoteException {
    if (!cacheMap.containsKey(s)) {
      String val = DB.get(s);
      cacheMap.put(s, val);
      return val;
    }
    return cacheMap.get(s);
  }

  @Override
  public boolean set(String s, String s1, String s2) throws RemoteException {
    return DB.set(s, s1, s2);
  }

  @Override
  public boolean transaction(String s, float v, int i) throws RemoteException {
    return DB.transaction(s, v, i);
  }
}
