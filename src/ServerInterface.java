import java.rmi.Remote;

public interface ServerInterface extends Remote {
  void addVM(int vmId, int tierId);
  void scaleOut(int tierId);
  int getVMCount(int tierId);
  Cloud.FrontEndOps.Request pollRequest();
  void addRequest(Cloud.FrontEndOps.Request request);
  int getRequestLength();

}
