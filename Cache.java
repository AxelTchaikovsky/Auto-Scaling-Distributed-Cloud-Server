import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;

public class Cache extends UnicastRemoteObject implements Cloud.DatabaseOps {
  private static ServerLib SL;
  private final Cloud.DatabaseOps DB = SL.getDB();
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
  protected Cache() throws RemoteException {
  }

  /**
   * Creates and exports a new UnicastRemoteObject object using the particular supplied port.
   *
   * <p>The object is exported with a server socket
   * created using the {@link RMISocketFactory} class.
   *
   * @param port the port number on which the remote object receives calls (if <code>port</code> is
   *             zero, an anonymous port is chosen)
   * @throws RemoteException if failed to export object
   * @since 1.2
   */
  protected Cache(int port) throws RemoteException {
    super(port);
  }

  /**
   * Creates and exports a new UnicastRemoteObject object using the particular supplied port and
   * socket factories.
   *
   * <p>Either socket factory may be {@code null}, in which case
   * the corresponding client or server socket creation method of {@link RMISocketFactory} is used
   * instead.
   *
   * @param port the port number on which the remote object receives calls (if <code>port</code> is
   *             zero, an anonymous port is chosen)
   * @param csf  the client-side socket factory for making calls to the remote object
   * @param ssf  the server-side socket factory for receiving remote calls
   * @throws RemoteException if failed to export object
   * @since 1.2
   */
  protected Cache(int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException {
    super(port, csf, ssf);
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
