package replica;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class ReplicaMain {

    public static void main(String[] rawArgs) throws Exception {
        if (rawArgs.length < 2) {
            System.err.println("Usage: ReplicaMain <id> <primary|backup> [backupIds...]");
            System.exit(1);
        }

        String myId = rawArgs[0];
        String role = rawArgs[1].toLowerCase();
        boolean startAsPrimary = role.equals("primary");

        Registry reg = LocateRegistry.getRegistry(); // default host=localhost, port=1099

        // If starting as primary, we may optionally pre-load backups; otherwise empty.
        List<ReplicaControl> backups = new ArrayList<>();
        if (startAsPrimary) {
            for (int i = 2; i < rawArgs.length; i++) {
                String backupId = rawArgs[i];
                String backupName = "replica" + backupId;
                try {
                    ReplicaControl stub = (ReplicaControl) reg.lookup(backupName);
                    // liveness probe
                    if (stub.ping()) {
                        backups.add(stub);
                        System.out.println("[ReplicaMain] Added backup stub: " + backupName);
                    }
                } catch (Exception e) {
                    System.err.println("[ReplicaMain] WARNING: couldn't add backup " + backupName + ": " + e);
                }
            }
        }

        // Create my replica object (pass myId so it can exclude itself during discovery)
        ReplicaImpl me = new ReplicaImpl(myId, startAsPrimary, backups);

        // Bind under replica<ID>
        String myName = "replica" + myId;
        reg.rebind(myName, me);

        System.out.println("[ReplicaMain] Started " + myName +
                " role=" + (startAsPrimary ? "PRIMARY" : "BACKUP"));
        System.out.println("[ReplicaMain] Connected to external rmiregistry");
        System.out.println("[ReplicaMain] Press ENTER to exit this replica...");

        // Keep process alive without sleeps
        new Scanner(System.in).nextLine();

        System.out.println("[ReplicaMain] Shutting down " + myName);
        try { reg.unbind(myName); } catch (Exception ignored) {}
        System.exit(0);
    }
}