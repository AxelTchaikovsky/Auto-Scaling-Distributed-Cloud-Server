import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ServerInterface extends Remote {
  int getVMCount(int tierId) throws RemoteException;

  Cloud.FrontEndOps.Request pollRequest() throws RemoteException;

  void addRequest(Cloud.FrontEndOps.Request request) throws RemoteException;

  int getRequestLength() throws RemoteException;

  int getTier(int id) throws RemoteException;

  void deleteVMFromMap(int vmId) throws RemoteException;

  void scaleOut(int i) throws RemoteException;
}
