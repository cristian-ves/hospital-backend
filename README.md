# Hospital Emergency System — Backend

A real-time concurrent simulation engine for hospital emergency department resource management, built with Spring Boot and Java's concurrency utilities. The system models priority-based patient triage, limited medical resource allocation, and deadlock detection in a multi-threaded environment.

**Live demo:** [click here](https://hospital-emergency-room.netlify.app)

---

## Overview

Emergency departments operate under a hard constraint: medical resources (operating rooms, surgeons, nurses, ventilators) are finite, but patient arrivals are not. This project simulates that environment as a concurrent system, where each patient is represented by an independent thread competing for shared resources under strict priority rules.

The simulation demonstrates classical concurrent programming problems — mutual exclusion, resource starvation, and deadlock — using production-grade Java concurrency primitives rather than simplified locks.

## Architecture

The backend exposes two communication channels:

-   **REST API** — synchronous, transactional operations such as patient admission
-   **WebSocket (STOMP over SockJS)** — asynchronous, real-time broadcast of state changes (resource availability, patient queue, system logs, statistics)

This separation allows the server to process thousands of concurrency events per second while clients simply react to state deltas pushed over the socket connection, rather than polling.

```
Client (React)  <──REST──>  Spring Boot API  (patient admission)
Client (React)  <──WS────>  STOMP Broker     (live state broadcast)
```

## Concurrency Design

### Semaphores over simple locks

Hospital resources aren't binary — there are 10 emergency rooms, 4 surgeons, 8 ventilators, each with its own capacity. `java.util.concurrent.Semaphore` was chosen over `synchronized` blocks or `ReentrantLock` because it natively supports counting permits. A thread calling `.acquire()` atomically decrements the available count; if none remain, the thread suspends without consuming CPU cycles until a permit is released.

### Priority-ordered waiting room

A hospital waiting room cannot operate FIFO — a Level 1 (Critical) patient arriving after a Level 5 (Non-Urgent) patient must still be treated first. `PriorityBlockingQueue` solves this with two guarantees: thread-safe concurrent insertion/extraction without corrupting the internal heap, and automatic reordering via each `Patient` object's `Comparable` implementation.

### Bounded thread pool

Spawning a new `Thread` per patient arrival would degrade performance under load and risk `OutOfMemoryError` during traffic spikes. A fixed `ExecutorService` with a capped pool size decouples the number of incoming patients from the number of live OS threads, keeping memory usage predictable.

### Deadlock detection and resolution

A background daemon thread monitors for circular wait conditions (Coffman's conditions) among patients holding partial resource sets. When detected, the system broadcasts the conflicting patients and resources to the frontend in real time, allowing manual resolution via thread interruption — which safely releases held semaphore permits through `try/finally` blocks, preventing permanent resource leaks.

## Resource Model

| Resource               | Quantity |
| ---------------------- | -------- |
| Emergency rooms        | 10       |
| Operating rooms        | 3        |
| General doctors        | 8        |
| Surgeons               | 4        |
| Nurses                 | 10       |
| Mechanical ventilators | 5        |
| Monitors               | 8        |

## Triage Levels

| Level | Classification | Max wait  | Resources assigned                                         |
| ----- | -------------- | --------- | ---------------------------------------------------------- |
| 1     | Critical       | Immediate | Operating room + Surgeon + 2 Nurses + Ventilator + Monitor |
| 2     | Emergency      | 10 min    | Emergency room + Doctor + Nurse + Monitor                  |
| 3     | Urgent         | 30 min    | Emergency room + Doctor                                    |
| 4     | Less Urgent    | 60 min    | Emergency room + Doctor                                    |
| 5     | Non-Urgent     | 120 min   | Emergency room + Doctor                                    |

When resources are unavailable, patients enter a priority-ordered waiting queue that is automatically reprocessed as resources free up.

## Tech Stack

-   **Java 17** — core language
-   **Spring Boot** — application framework, REST controllers
-   **Spring WebSocket (STOMP/SockJS)** — real-time bidirectional messaging
-   **java.util.concurrent** — `Semaphore`, `PriorityBlockingQueue`, `ExecutorService`
-   **Lombok** — boilerplate reduction on models

## Running Locally

```bash
# clone the repository
git clone https://github.com/cristian-ves/hospital-backend.git
cd hospital-backend

# run with Maven
./mvnw spring-boot:run
```

The server starts on `http://localhost:8080`. The WebSocket endpoint is available at `/ws-hospital`.

## API Endpoints

| Method | Endpoint         | Description                             |
| ------ | ---------------- | --------------------------------------- |
| `POST` | `/api/patients`  | Admit a new patient into the simulation |
| `GET`  | `/api/resources` | Current resource availability snapshot  |

## WebSocket Topics

| Topic                    | Payload                                          |
| ------------------------ | ------------------------------------------------ |
| `/topic/resource-status` | Live resource pool availability                  |
| `/topic/patients`        | Active patient queue and progress                |
| `/topic/logs`            | Real-time system event log                       |
| `/topic/stats`           | Aggregated statistics (avg wait time, occupancy) |
| `/topic/deadlock`        | Deadlock state and affected patients             |

## Related

This is the backend half of the Hospital Emergency System. The frontend (React, TypeScript, Redux Toolkit) is available at:
**[hospital-frontend](https://github.com/cristian-ves/hospital-frontend)** — update with real link

## Author

**Cristian Vásquez** — [LinkedIn](https://linkedin.com/in/cristian-vasquez-web-developer) · [GitHub](https://github.com/cristian-ves)
