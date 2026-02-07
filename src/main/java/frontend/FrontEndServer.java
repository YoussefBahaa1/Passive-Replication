package frontend;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import replica.PrimaryAPI;
import replica.ReplicaControl;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage:
 *   mvn -Dexec.mainClass=frontend.FrontEndServer \
 *       -Dexec.args="1 2 3 4" exec:java
 *
 * First arg is the initial primary ID (e.g., 1).
 * Remaining args are backup IDs in failover order (e.g., 2 3 4).
 */
public class FrontEndServer {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: FrontEndServer <primaryId> <backupId1> [backupId2 ...]");
            System.exit(1);
        }

        String primaryId = args[0];
        List<String> backupIds = new ArrayList<>();
        for (int i = 1; i < args.length; i++) 
            backupIds.add(args[i]);

        Registry reg = LocateRegistry.getRegistry();

        String primaryName = "replica" + primaryId;
        System.out.println("[FrontEnd] Initial primary = " + primaryName);

        PrimaryAPI primaryStub = (PrimaryAPI) reg.lookup(primaryName);

        List<ReplicaControl> backupStubs = new ArrayList<>();
        for (String bid : backupIds) {
            String name = "replica" + bid;
            ReplicaControl stub = (ReplicaControl) reg.lookup(name);
            backupStubs.add(stub);
            System.out.println("[FrontEnd] Added backup = " + name);
        }

        KVServiceImpl svc = new KVServiceImpl(primaryStub, backupStubs);

        Server grpcServer = ServerBuilder
                .forPort(50055)
                .addService(svc)
                .build()
                .start();

        System.out.println("[FrontEnd] gRPC listening on :50055");
        System.out.println("[FrontEnd] Press Ctrl+C to stop.");
        grpcServer.awaitTermination();
    }
}