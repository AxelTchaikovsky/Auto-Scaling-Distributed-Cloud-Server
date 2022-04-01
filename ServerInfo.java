public class ServerInfo {
  private int tier = 1;
  private boolean isMaster = false;
  private int id = 0;

  public boolean isMaster() {
    return isMaster;
  }

  public void setMaster(boolean master) {
    isMaster = master;
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public int getTier() {
    return tier;
  }

  public void setTier(int tier) {
    this.tier = tier;
  }
}
