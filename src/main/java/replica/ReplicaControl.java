package replica;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Map;

/**
 * Methods replicas expose for replication/promotion.
 */
public interface ReplicaControl extends Remote {

    void pushFullState(Map<String,String> newState) throws RemoteException;

    // FrontEnd calls this on failover:
    // "You are now the primary."
    void promoteToPrimary() throws RemoteException;

    boolean ping() throws RemoteException;

}