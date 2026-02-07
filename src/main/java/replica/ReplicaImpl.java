package replica;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.regex.Pattern;

public class ReplicaImpl extends UnicastRemoteObject
        implements PrimaryAPI, ReplicaControl {

    // in-memory key-value store
    private final Map<String,String> store = new HashMap<>();

    // am I currently the primary?
    private boolean isPrimary;

    // my ID for naming (e.g., "1" for name "replica1")
    private final String myId;

    // stubs to the other replicas that act as backups
    private final List<ReplicaControl> backups;

    // pattern to recognise replica bindings in the registry
    private static final Pattern REPLICA_NAME = Pattern.compile("^replica\\d+$");

    // Names discovered in rmiregistry and found dead to avoid spamming logs
    private final List<String> ignoredReplicaNames = new ArrayList<>();

    protected ReplicaImpl(String myId,
                          boolean startAsPrimary,
                          List<ReplicaControl> backupsList)
            throws RemoteException {

        super();
        this.myId = Objects.requireNonNull(myId, "myId");
        this.isPrimary = startAsPrimary;
        this.backups = new ArrayList<>(backupsList);
    }


    /*========== PrimaryAPI ==========*/

    @Override
    public synchronized boolean handleClientPut(String key, String value) throws RemoteException {

        // 1) Check if I am currently the primary. If not, print an error message and return false.
        if (!isPrimary) {
        System.err.println("[Replica " + myId + "] handleClientPut called but I am NOT PRIMARY. Ignoring.");
        return false;
        }

        // 2) Update local state (store) by adding the key, value pair.
        store.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        
        // 3) Push full state to backups.
        // Take a snapshot to avoid concurrent modification issues and to send a consistent view.
        Map<String,String> snapshot = new HashMap<>(store);

        // Discover current backups dynamically
        List<ReplicaControl> currentBackups = discoverBackups();
        setBackups(currentBackups);

        Iterator<ReplicaControl> it = backups.iterator();
        while (it.hasNext()) {
            ReplicaControl backup = it.next();
            try {
                backup.pushFullState(snapshot);
            } catch (RemoteException e) {
                System.err.println("[Replica " + myId + "] WARNING: failed to push state to a backup: " + e);
                // Skip dead backups.
                it.remove();
            }
        }

        // 4) Return true (success), if all goes well.
        return true;
    }

    @Override
    public synchronized String handleClientGet(String key) throws RemoteException {
        // 1) Retrieve the value corresponding to the key.
        // 2) Return null if the key does not exist; otherwise, return value.

        return store.get(key);
    }

    @Override
    public synchronized Map<String,String> getState() throws RemoteException {
        // Return a copy of the current store
        return new HashMap<>(store);
    }


    /*========== ReplicaControl ==========*/

    @Override
    public synchronized void pushFullState(Map<String,String> newState) throws RemoteException {
        // Replace the store with the newState
        store.clear();
        store.putAll(newState);

        System.out.println("[Replica " + myId + "] pushFullState applied. Store now: " + store);
    }

    @Override
    public synchronized void promoteToPrimary() throws RemoteException {

        // 1) Set this replica to act as primary.
        if (isPrimary) {
            // If already primary, log and return.
            System.out.println("[Replica " + myId + "] promoteToPrimary called, but I am already PRIMARY.");
            return;
        }

        isPrimary = true;

        // 2) Discover current backups in the RMI registry.
        List<ReplicaControl> discoveredBackups = discoverBackups();

        // 3) Call setBackups(...) with the discovered list.
        setBackups(discoveredBackups);

        // 4) Log useful information.
        System.out.println("[Replica " + myId + "] PROMOTED TO PRIMARY.");
        System.out.println("[Replica " + myId + "] Current backups count = " + discoveredBackups.size());
    }

    @Override
    public boolean ping() throws RemoteException {
        return true;
    }

    // ========== Helpers ==========

    public synchronized void setBackups(List<ReplicaControl> newBackups) {
        this.backups.clear();
        this.backups.addAll(newBackups);
    }

    private List<ReplicaControl> discoverBackups() {

        List<ReplicaControl> result = new ArrayList<>();

        try {
            Registry reg = LocateRegistry.getRegistry(); // default host=localhost, port=1099
            String[] names = reg.list();

            String myName = "replica" + myId;

            for (String name : names) {
                // 1) Query the RMI registry for all bindings whose names match "replica<id>".
                if (!REPLICA_NAME.matcher(name).matches()) {
                    continue;
                }

                // 2) Skip your own name ("replica" + myId).
                if (name.equals(myName)) {
                    continue;
                }

                // Skip names of dead replicas.
                synchronized (this) {
                    if (ignoredReplicaNames.contains(name)) {
                        continue;
                    }
                }

                try {
                    // 3) For each, look it up, cast to ReplicaControl, and only include if ping() returns true.
                    ReplicaControl stub = (ReplicaControl) reg.lookup(name);

                    if (stub.ping()) {
                        result.add(stub);
                    } else {
                        // Mark this name as dead to avoid repeated log spam.
                        synchronized (this) {
                            ignoredReplicaNames.add(name);
                        }
                    }
                } catch (Exception e) {
                    // Mark this name as dead to avoid repeated log spam.
                    synchronized (this) {
                        if (!ignoredReplicaNames.contains(name)) {
                            ignoredReplicaNames.add(name);
                        }
                    }
                    System.err.println("[Replica " + myId + "] WARNING: could not add backup " + name + ": " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("[Replica " + myId + "] ERROR during discoverBackups: " + e);
        }
        
        // 4) Return the list.
        return result;
        }
}