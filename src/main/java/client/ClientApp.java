package client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import kv.KVServiceGrpc;
import kv.PutRequest;
import kv.PutReply;
import kv.GetRequest;
import kv.GetReply;

import java.util.Scanner;

/**
 * Interactive client.
 *
 * Commands:
 *   PUT <key> <value>
 *   GET <key>
 *   EXIT
 */
public class ClientApp {

    public static void main(String[] args) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50055)
                .usePlaintext() 
                .build();

        KVServiceGrpc.KVServiceBlockingStub stub =
                KVServiceGrpc.newBlockingStub(channel);

        System.out.println("Client ready.");
        System.out.println("Commands: PUT <key> <value> | GET <key> | EXIT");

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().trim();
            if (line.equalsIgnoreCase("EXIT")) break;
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toUpperCase();

            try {
                if (cmd.equals("PUT") && parts.length >= 3) {
                    String key = parts[1];
                    String value = parts[2];

                    PutRequest req = PutRequest.newBuilder()
                            .setKey(key)
                            .setValue(value)
                            .build();
                    PutReply rep = stub.put(req);
                    System.out.println("ok=" + rep.getOk());

                } else if (cmd.equals("GET") && parts.length >= 2) {
                    String key = parts[1];

                    GetRequest req = GetRequest.newBuilder()
                            .setKey(key)
                            .build();
                    GetReply rep = stub.get(req);

                    if (rep.getFound()) {
                        System.out.println(key + " = " + rep.getValue());
                    } else {
                        System.out.println(key + " not found");
                    }

                } else {
                    System.out.println("Unknown command / bad args.");
                }
            } catch (Exception e) {
                System.err.println("Request failed: " + e);
            }
        }

        channel.shutdownNow();
        System.out.println("Client exiting.");
    }
}