public class ServerInfo {
  private int tier;
  private boolean isMaster;
  private int id;

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
