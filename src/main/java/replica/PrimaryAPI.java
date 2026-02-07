package replica;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Map;

/**
 * Methods the FrontEnd calls on the current primary replica.
 */
public interface PrimaryAPI extends Remote {

    boolean handleClientPut(String key, String value) throws RemoteException;

    String handleClientGet(String key) throws RemoteException;
    
    // Return a snapshot of the current key–value store.
    // Used by the FrontEnd to initialise newly joined replicas.
    Map<String,String> getState() throws RemoteException;

}