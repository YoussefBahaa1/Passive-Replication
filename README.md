# Passive Replication Key-Value Store (Java, gRPC + Java RMI)

A distributed key-value store prototype implementing passive replication with:
- `gRPC` for client-to-frontend requests
- `Java RMI` for frontend-to-replica control and state transfer
- Primary-backup failover and backup discovery

## Project Structure

- `src/main/java/client/ClientApp.java`: interactive CLI client (`PUT`, `GET`, `EXIT`)
- `src/main/java/frontend/FrontEndServer.java`: gRPC frontend on port `50055`
- `src/main/java/frontend/KVServiceImpl.java`: request handling, failover, replica discovery
- `src/main/java/replica/ReplicaMain.java`: replica bootstrap and RMI binding
- `src/main/java/replica/ReplicaImpl.java`: replica state machine + replication logic
- `src/main/proto/kv.proto`: gRPC service and message definitions

## Prerequisites

- Java `17`
- Maven `3.8+`

Check versions:

```bash
java -version
mvn -version
```

## Build

From project root:

```bash
mvn clean compile
```

## Run Locally

Use separate terminals for each process.

### 1) Start RMI registry

```bash
rmiregistry 1099
```

### 2) Start backup replicas

```bash
mvn -Dexec.mainClass=replica.ReplicaMain -Dexec.args="2 backup" exec:java
mvn -Dexec.mainClass=replica.ReplicaMain -Dexec.args="3 backup" exec:java
```

### 3) Start primary replica

```bash
mvn -Dexec.mainClass=replica.ReplicaMain -Dexec.args="1 primary 2 3" exec:java
```

### 4) Start frontend

```bash
mvn -Dexec.mainClass=frontend.FrontEndServer -Dexec.args="1 2 3" exec:java
```

### 5) Start client

```bash
mvn -Dexec.mainClass=client.ClientApp exec:java
```

Client commands:

```text
PUT <key> <value>
GET <key>
EXIT
```

## Failover Demo

1. Run all processes above.
2. In the primary replica terminal, press `ENTER` to stop it.
3. Send another client request.
4. Frontend promotes the next backup to primary and continues serving requests.