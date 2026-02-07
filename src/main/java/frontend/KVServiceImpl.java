package frontend;

import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import kv.KVServiceGrpc;
import kv.PutRequest;
import kv.PutReply;
import kv.GetRequest;
import kv.GetReply;
import replica.PrimaryAPI;
import replica.ReplicaControl;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class KVServiceImpl extends KVServiceGrpc.KVServiceImplBase {

    private volatile PrimaryAPI primaryStub;
    private final List<ReplicaControl> remainingBackups; // in promotion order

    // Pattern to recognise "replica<id>" bindings in the RMI registry.
    private static final Pattern REPLICA_NAME = Pattern.compile("^replica\\d+$");

    // Names discovered in rmiregistry and found dead to avoid spamming logs
    private final List<String> ignoredReplicaNames = new ArrayList<>();

    public KVServiceImpl(PrimaryAPI initialPrimary, List<ReplicaControl> backupsInOrder) {
        this.primaryStub = initialPrimary;
        this.remainingBackups = new ArrayList<>(backupsInOrder);

        // Start a background discovery loop to see newly joined replicas.
        Thread discoveryThread = new Thread(this::discoveryLoop, "frontend-discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    /**
     * Helper to promote backup if primary died.
     */
    private synchronized void failoverToBackup(ReplicaControl backupStub) throws RemoteException {

        if (backupStub == null) {
            throw new RemoteException("No backup available for failover.");
        }
    
        System.out.println("[FrontEnd] Initiating failover to backup...");

        // 1) Call promoteToPrimary() on the backupStub.
        backupStub.promoteToPrimary();

        // 2) Update this.primaryStub so that future requests go to the new primary.
        this.primaryStub = (PrimaryAPI) backupStub;

        // 3) Remove this backup from remainingBackups (it is no longer a backup).
        remainingBackups.remove(backupStub);

        // 4) Log what happened.
        System.out.println("[FrontEnd] Failover complete. New primary selected.");
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutReply> responseObserver) {
        String key = request.getKey();
        String value = request.getValue();

        boolean ok = false;
        RemoteException lastRemoteEx = null;

        // 1) call handleClientPut on the primary replica (primaryStub)
        // 2) update the boolean ok (if needed)
        // 3) Implement a failure detector –
        //      a) Use try, catch (using appropriate exception) to detect failure
        //      b) If primary has failed, then failover to backup, using failoverToBackup()

        // Try with current primary, if it fails, try to failover through backups.
        while (true) {
            PrimaryAPI currentPrimary = primaryStub;
            if (currentPrimary == null) {
                break; // No primary.
            }

            try {
                ok = currentPrimary.handleClientPut(key, value);
                // Success on some primary.
                break;
            } catch (RemoteException e) {
                System.err.println("[FrontEnd] RemoteException on put: " + e);
                lastRemoteEx = e;
                
                // Try to fail over to the next available backup.
                ReplicaControl backupToPromote = null;
                synchronized (this) {
                    if (!remainingBackups.isEmpty()) {
                        backupToPromote = remainingBackups.get(0);
                    }
                }

                if (backupToPromote == null) {
                    // No backups left to promote.
                    System.err.println("[FrontEnd] No backups available for failover on put.");
                    primaryStub = null;
                    break;
                }

                try {
                    failoverToBackup(backupToPromote);
                    // Loop again with new primary.
                } catch (RemoteException promoteEx) {
                    System.err.println("[FrontEnd] Failed to promote backup during put: " + promoteEx);
                    lastRemoteEx = promoteEx;
                    // Remove this backup from the list and try the next one in the next iteration.
                    synchronized (this) {
                        remainingBackups.remove(backupToPromote);
                    }
                }
            }
        }

        if (primaryStub == null && !ok) {
            // No primary available. Report a gRPC error.
            responseObserver.onError(
                    Status.UNAVAILABLE
                            .withDescription("No replicas available to handle PUT request.")
                            .withCause(lastRemoteEx)
                            .asRuntimeException()
            );
            return;
        }

        // Build a reply and send it back to the client.
        PutReply reply = PutReply.newBuilder()
                .setOk(ok)
                .build();

        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetReply> responseObserver) {
        String key = request.getKey();
        String value = null;
        boolean found = false;
        RemoteException lastRemoteEx = null;

        // 1) Call handleClientGet on the primary replica (primaryStub)
        // 2) update value and found variables (if needed)
        // 3) Implement a failure detector –
        //      a) Use try, catch (using appropriate exception) to detect failure
        //      b) If primary has failed, then failover to backup, using failoverToBackup()

        // Try with current primary, if it fails, try to failover through backups.
        while (true) {
            PrimaryAPI currentPrimary = primaryStub;
            if (currentPrimary == null) {
                break; // No primary. Error handled below.
            }

            try {
                value = currentPrimary.handleClientGet(key);
                found = (value != null);
                // Success on some primary.
                break;
            } catch (RemoteException e) {
                System.err.println("[FrontEnd] RemoteException on put: " + e);
                lastRemoteEx = e;
                
                // Try to fail over to the next available backup.
                ReplicaControl backupToPromote = null;
                synchronized (this) {
                    if (!remainingBackups.isEmpty()) {
                        backupToPromote = remainingBackups.get(0);
                    }
                }

                if (backupToPromote == null) {
                    // No backups left to promote.
                    System.err.println("[FrontEnd] No backups available for failover on put.");
                    primaryStub = null;
                    break;
                }

                try {
                    failoverToBackup(backupToPromote);
                    // Loop again with new primary.
                } catch (RemoteException promoteEx) {
                    System.err.println("[FrontEnd] Failed to promote backup during put: " + promoteEx);
                    lastRemoteEx = promoteEx;
                    // Remove this backup from the list and try the next one in the next iteration.
                    synchronized (this) {
                        remainingBackups.remove(backupToPromote);
                    }
                }
            }
        }

        if (primaryStub == null && !found) {
            // No primary available. Report a gRPC error.
            responseObserver.onError(
                    Status.UNAVAILABLE
                            .withDescription("No replicas available to handle GET request.")
                            .withCause(lastRemoteEx)
                            .asRuntimeException()
            );
            return;
        }

        // Build a reply and send it back to the client.
        GetReply reply = GetReply.newBuilder()
                .setFound(found)
                .setValue(found ? value : "")
                .build();

        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    /**
     * Background loop: periodically scan the RMI registry for newly joined replicas
     * and add them as backups after syncing full state from the current primary.
     */
    private void discoveryLoop() {
        while (true) {
            try {
                Thread.sleep(5000); // every 5 seconds
                discoverNewReplicas();
            } catch (InterruptedException e) {
                // Thread interrupted. Exit loop.
                return;
            } catch (Exception e) {
                System.err.println("[FrontEnd] Discovery loop error: " + e);
            }
        }
    }

    private void discoverNewReplicas() {
        PrimaryAPI currentPrimary = primaryStub;
        if (currentPrimary == null) {
            // No primary available. Nothing to sync.
            return;
        }

        try {
            Registry reg = LocateRegistry.getRegistry();
            String[] names = reg.list();

            for (String name : names) {
                if (!REPLICA_NAME.matcher(name).matches()) {
                    continue;
                }

                // Skip names of dead replicas.
                synchronized (this) {
                    if (ignoredReplicaNames.contains(name)) {
                        continue;
                    }
                }

                try {
                    ReplicaControl stub = (ReplicaControl) reg.lookup(name);

                    // Skip the current primary.
                    if (primaryStub != null && stub.equals(primaryStub)) {
                        continue;
                    }

                    // Skip replicas we already know as backups.
                    boolean alreadyKnown = false;
                    synchronized (this) {
                        for (ReplicaControl rc : remainingBackups) {
                            if (rc.equals(stub)) {
                                alreadyKnown = true;
                                break;
                            }
                        }
                    }
                    if (alreadyKnown) {
                        continue;
                    }

                    // Check that the replica is alive.
                    if (!stub.ping()) {
                        // Mark this name as dead to avoid repeated log spam.
                        synchronized (this) {
                            ignoredReplicaNames.add(name);
                        }
                        continue;
                    }

                    // Fetch current state from the primary and push it to the new backup.
                    Map<String,String> state = currentPrimary.getState();
                    stub.pushFullState(state);

                    // Add this replica to the backup list so it can be promoted on future failures.
                    synchronized (this) {
                        remainingBackups.add(stub);
                    }

                    System.out.println("[FrontEnd] Discovered and synced new backup replica: " + name);
                } catch (Exception e) {
                    // Mark this name as dead to avoid repeated log spam.
                    synchronized (this) {
                        if (!ignoredReplicaNames.contains(name)) {
                            ignoredReplicaNames.add(name);
                        }
                    }
                    System.err.println("[FrontEnd] Error handling discovered replica " + name + ": " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("[FrontEnd] Error during replica discovery: " + e);
        }
    }
}