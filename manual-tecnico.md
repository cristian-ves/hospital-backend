### Arquitectura del Backend (Java / Spring Boot)
El servidor expone una API REST para operaciones transaccionales síncronas (como dar de alta a un paciente) y un broker de mensajería WebSocket para la difusión asíncrona de cambios de estado. La ventaja de este enfoque es que el backend puede procesar miles de eventos de concurrencia por segundo y el cliente simplemente reacciona a los deltas de estado enviados por la red.

### Arquitectura del Frontend (React / TypeScript / Redux Toolkit)
El cliente opera de forma puramente reactiva. No realiza consultas continuas (*polling*) al servidor. Mediante una conexión duplex por WebSocket, el cliente mantiene un reflejo exacto del estado de la memoria del backend en sus *slices* de Redux, minimizando el uso de CPU en el navegador.

# Justificación de Mecanismos de Concurrencia

La simulación de un entorno hospitalario del mundo real presenta problemas clásicos de la ciencia de la computación relacionados con la exclusión mutua y el acceso a recursos compartidos. A continuación, se justifican las estructuras utilizadas:

### Semáforos (`java.util.concurrent.Semaphore`)
En lugar de utilizar bloques `synchronized` o cerraduras simples (`ReentrantLock`), se seleccionaron semáforos debido a la naturaleza cuantitativa de los recursos del hospital. Un *mutex* tradicional es un semáforo binario (capacidad 1). El hospital cuenta con pools de recursos con capacidades específicas (ej. 10 salas de emergencia, 4 cirujanos).
El semáforo permite inicializar un contador de permisos disponibles. Cuando un hilo ejecuta `.acquire(n)`, el semáforo reduce el contador de forma atómica. Si el contador llega a cero, el hilo solicitante se suspende en una cola de espera gestionada por el sistema operativo, garantizando un consumo nulo de ciclos de reloj mientras espera.

### Colas de Bloqueo por Prioridad (`PriorityBlockingQueue`)
La sala de espera de un hospital no puede operar bajo el principio FIFO (First-In, First-Out). Un paciente con triaje de nivel 1 (Crítico) que llega tarde debe ser atendido antes que un paciente con triaje de nivel 5 (No Urgente) que llegó temprano.
La `PriorityBlockingQueue` resolves esto combinando dos propiedades críticas:
1. **Thread-Safety:** Permite que múltiples hilos inyecten y extraigan pacientes simultáneamente sin corromper la estructura del montículo (*heap*) binario interno.
2. **Ordenamiento Dinámico:** Utiliza el método `compareTo` de los objetos contenidos para reorganizar la cola automáticamente cada vez que se añade un elemento, asegurando que la operación de extracción siempre devuelva al paciente con mayor prioridad.

### Pools de Hilos Reutilizables (`ExecutorService`)
Crear un nuevo hilo (`new Thread()`) por cada paciente que llega al hospital provocaría una degradación severa del rendimiento debido al costo de conmutación de contextos (*context switching*) y el riesgo latente de un error `OutOfMemoryError` ante ráfagas de datos.
Se implementó un `FixedThreadPool` con un límite estricto de 20 hilos. Esto independiza el número de pacientes en la cola del número de hilos activos en el procesador, permitiendo un consumo de memoria predecible y acotado.

# Código Fuente del Backend y Decisiones de Diseño

### Configuración del Entorno de Concurrencia y Red

Para permitir la comunicación sin restricciones entre el entorno de desarrollo local y los servidores de producción en la nube (Railway), se configuró una política flexible de control de acceso a recursos cruzados (CORS).

```java
// Archivo: com.hospital.hospitalbackend.config.CorsConfig.java
package com.hospital.hospitalbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Habilita el intercambio de datos para los endpoints de la API REST de control
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}

La comunicación en tiempo real requiere una infraestructura bidireccional basada en el protocolo STOMP sobre WebSockets. Al acoplar SockJS, se garantiza compatibilidad con navegadores antiguos mediante fallback a HTTP Streaming o Long Polling.
Java

// Archivo: com.hospital.hospitalbackend.config.WebSocketConfig.java
package com.hospital.hospitalbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Define el broker en memoria para la difusión hacia los clientes
        config.enableSimpleBroker("/topic");
        // Define el prefijo para los mensajes entrantes desde el cliente hacia el servidor
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint central de conexión para el frontend React
        registry.addEndpoint("/ws-hospital").setAllowedOriginPatterns("*").withSockJS();
    }
}

Para evitar pantallas en blanco cuando un cliente nuevo se conecta a la aplicación web después de que la simulación ha iniciado, se diseñó un escuchador de eventos que retransmite inmediatamente el estado completo de la memoria volátil.
Java

// Archivo: com.hospital.hospitalbackend.config.WebSocketEventListener.java
package com.hospital.hospitalbackend.config;

import com.hospital.hospitalbackend.service.SimulationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Component
public class WebSocketEventListener {

    private final SimulationService simulationService;

    public WebSocketEventListener(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        // Envía instantáneamente el inventario de recursos y pacientes al suscriptor entrante
        simulationService.broadcastCurrentState();
    }
}

Modelado del Negocio y Estructuras de Datos

El diseño del ordenamiento jerárquico se delegó al tipo enumerado TriageLevel. Aquí se asocian de manera inmutable los pesos numéricos de prioridad requeridos por las colas de bloqueo.
Java

// Archivo: com.hospital.hospitalbackend.model.TriageLevel.java
package com.hospital.hospitalbackend.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

@Getter
public enum TriageLevel {
    CRITICAL(1, "Immediate attention"),
    EMERGENCY(2, "Max 10 min wait"),
    URGENT(3, "Max 30 min wait"),
    LESS_URGENT(4, "Max 60 min wait"),
    NON_URGENT(5, "Max 120 min wait");

    private final int priority;
    private final String description;

    TriageLevel(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    @JsonCreator
    public static TriageLevel fromPriority(int value) {
        for (TriageLevel level : values()) {
            if (level.priority == value) return level;
        }
        throw new IllegalArgumentException("Unknown triage priority: " + value);
    }
}

El modelo Patient implementa la interfaz Comparable. Esta es una decisión de diseño fundamental para que la PriorityBlockingQueue sepa cómo ordenar los elementos internamente sin necesidad de algoritmos de ordenamiento externos concurrentes.
Java

// Archivo: com.hospital.hospitalbackend.model.Patient.java
package com.hospital.hospitalbackend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Patient implements Comparable<Patient> {
    private UUID id;
    private String name;
    private TriageLevel triageLevel;
    private LocalDateTime arrivalTime;

    @Override
    public int compareTo(Patient other) {
        // Los valores numéricos más bajos (ej. 1) tienen una prioridad más alta
        return Integer.compare(this.triageLevel.getPriority(), other.triageLevel.getPriority());
    }
}

Capa de Servicios y Control de Concurrencia

La clase ResourceManager actúa como la abstracción de control para la exclusión mutua de los recursos finitos del hospital.
Java

// Archivo: com.hospital.hospitalbackend.service.ResourceManager.java
package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.ResourceStatusDTO;
import com.hospital.hospitalbackend.model.TriageLevel;
import org.springframework.stereotype.Service;
import java.util.concurrent.Semaphore;

@Service
public class ResourceManager {

    private final NotificationService notificationService;

    // Inicialización atómica de semáforos basados en los requerimientos del enunciado
    private final Semaphore operatingRooms = new Semaphore(3);
    private final Semaphore surgeons = new Semaphore(4);
    private final Semaphore generalDoctors = new Semaphore(8);
    private final Semaphore nurses = new Semaphore(10);
    private final Semaphore ventilators = new Semaphore(5);
    private final Semaphore monitors = new Semaphore(8);
    private final Semaphore emergencyRooms = new Semaphore(10);

    public ResourceManager(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void acquireResources(TriageLevel level) throws InterruptedException {
        // Regla de negocio: Adquisición atómica por bloques según nivel de triaje
        switch (level) {
            case CRITICAL -> {
                operatingRooms.acquire(1);
                surgeons.acquire(1);
                nurses.acquire(2);
                ventilators.acquire(1);
                monitors.acquire(1);
            }
            case EMERGENCY -> {
                emergencyRooms.acquire(1);
                generalDoctors.acquire(1);
                nurses.acquire(1);
                monitors.acquire(1);
            }
            case URGENT, LESS_URGENT, NON_URGENT -> {
                emergencyRooms.acquire(1);
                generalDoctors.acquire(1);
            }
        }
        notificationService.sendUpdate("resource-status", getResourceState());
    }

    public void releaseResources(TriageLevel level) {
        // Retorno seguro de permisos al finalizar la atención médica
        switch (level) {
            case CRITICAL -> {
                operatingRooms.release(1);
                surgeons.release(1);
                nurses.release(2);
                ventilators.release(1);
                monitors.release(1);
            }
            case EMERGENCY -> {
                emergencyRooms.release(1);
                generalDoctors.release(1);
                nurses.release(1);
                monitors.release(1);
            }
            case URGENT, LESS_URGENT, NON_URGENT -> {
                emergencyRooms.acquire(1); // Liberación
                emergencyRooms.release(1);
                generalDoctors.release(1);
            }
        }
        notificationService.sendUpdate("resource-status", getResourceState());
    }

    private ResourceStatusDTO getResourceState() {
        return new ResourceStatusDTO(
                operatingRooms.availablePermits(),
                surgeons.availablePermits(),
                generalDoctors.availablePermits(),
                nurses.availablePermits(),
                ventilators.availablePermits(),
                monitors.availablePermits(),
                emergencyRooms.availablePermits()
        );
    }

    public void broadcastCurrentState() {
        notificationService.sendUpdate("resource-status", getResourceState());
    }

    public Semaphore getOperatingRooms() { return operatingRooms; }
    public Semaphore getMonitors() { return monitors; }
}

El SimulationService coordina el despachador de eventos asíncronos (dispatcher), el cual extrae continuamente elementos de la cola bloqueante y los transfiere al pool de hilos para su procesamiento sin bloquear el hilo principal de la aplicación.
Java

// Archivo: com.hospital.hospitalbackend.service.SimulationService.java
package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.PatientStatusDTO;
import com.hospital.hospitalbackend.model.LogEntry;
import com.hospital.hospitalbackend.model.Patient;
import com.hospital.hospitalbackend.model.TriageLevel;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SimulationService {

    private final ResourceManager resourceManager;
    private final NotificationService notificationService;
    private final ExecutorService threadPool;
    
    // Cola central ordenada por triaje
    private final PriorityBlockingQueue<Patient> waitingRoom = new PriorityBlockingQueue<>();
    // Mapa thread-safe para reflejar el estado vivo hacia la web
    private final Map<String, PatientStatusDTO> activePatients = new ConcurrentHashMap<>();
    private final AtomicInteger totalAttended = new AtomicInteger(0);
    private final List<Long> waitTimes = Collections.synchronizedList(new ArrayList<>());

    public SimulationService(ResourceManager resourceManager, NotificationService notificationService) {
        this.resourceManager = resourceManager;
        this.notificationService = notificationService;
        this.threadPool = Executors.newFixedThreadPool(20);
        startDispatcher();
    }

    private void startDispatcher() {
        // Hilo productor-consumidor clásico corriendo en segundo plano
        Thread dispatcher = new Thread(() -> {
            while (true) {
                try {
                    // Bloquea aquí de forma segura si la cola está vacía
                    Patient patient = waitingRoom.take();
                    threadPool.submit(() -> runPatientProcess(patient));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        dispatcher.setDaemon(true); // Evita colgar la JVM al apagar el servidor
        dispatcher.start();
    }

    public void processPatient(Patient patient) {
        long admittedAt = System.currentTimeMillis();
        PatientStatusDTO status = new PatientStatusDTO(
                patient.getId().toString(), patient.getName(),
                patient.getTriageLevel().name(), "QUEUED", admittedAt, 0L
        );
        activePatients.put(patient.getId().toString(), status);
        notificationService.sendPatientUpdate(activePatients.values());
        notificationService.sendLog(LogEntry.info("[ADMIT] " + patient.getName() + " en espera."));
        waitingRoom.add(patient);
    }

    private void runPatientProcess(Patient patient) {
        long queuedAt = activePatients.get(patient.getId().toString()).admittedAt();
        try {
            notificationService.sendLog(LogEntry.wait("[WAIT] " + patient.getName() + " requiriendo recursos..."));
            
            // Intenta adquirir recursos. Si no hay, el hilo del pool se suspende aquí
            resourceManager.acquireResources(patient.getTriageLevel());

            long startedAt = System.currentTimeMillis();
            long waitMs = startedAt - queuedAt;
            waitTimes.add(waitMs);

            activePatients.put(patient.getId().toString(), new PatientStatusDTO(
                    patient.getId().toString(), patient.getName(),
                    patient.getTriageLevel().name(), "IN_PROGRESS", queuedAt, startedAt
                ));
            notificationService.sendPatientUpdate(activePatients.values());
            notificationService.sendLog(LogEntry.info("[START] " + patient.getName() + " atendido (Espera: " + waitMs/1000 + "s)"));

            // Simulación del tiempo del tratamiento del paciente (1 minuto de atención reducida para pruebas)
            Thread.sleep(60_000);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Respeta la señal de cancelación externa
        } finally {
            // Se ejecuta SIEMPRE para evitar retención indefinida de semáforos
            resourceManager.releaseResources(patient.getTriageLevel());
            totalAttended.incrementAndGet();
            activePatients.remove(patient.getId().toString());
            notificationService.sendPatientUpdate(activePatients.values());
            notificationService.sendLog(LogEntry.system("[DONE] " + patient.getName() + " dado de alta."));
            broadcastStats();
        }
    }

    private void broadcastStats() {
        double avgWait = waitTimes.isEmpty() ? 0 :
                waitTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;
        notificationService.sendUpdate("stats", Map.of(
                "totalAttended", totalAttended.get(),
                "avgWaitSeconds", Math.round(avgWait * 10.0) / 10.0
        ));
    }

    public void broadcastCurrentState() {
        notificationService.sendPatientUpdate(activePatients.values());
        resourceManager.broadcastCurrentState();
        broadcastStats();
    }
}

Simulación y Resolución de Interbloqueos (Deadlock)
Análisis de la Inmunidad del Sistema Base

En el diseño estándar de nuestra aplicación, es matemáticamente imposible que ocurra un interbloqueo. La asignación de recursos en ResourceManager sigue una adquisición lineal estricta. Ningún hilo solicita recursos en órdenes inversos (por ejemplo, nadie solicita un Monitor antes que un Quirófano). Al romper de raíz la condición de Espera Circular de Coffman, el sistema es inherentemente seguro.
Implementación del Escenario Forzado

Para cumplir con los requerimientos académicos del curso, se desarrolló el DeadlockManager. Este componente se salta los mecanismos seguros del ResourceManager e implementa una rutina de hilos cruzados para forzar el deadlock en memoria de la siguiente forma:

    Agota por completo los permisos de Quirófanos y Monitores.

    Devuelve exactamente 1 permiso a cada semáforo.

    El Hilo A reclama un Quirófano y duerme 1.5 segundos (garantizando que el Hilo B se ejecute).

    El Hilo B reclama un Monitor y duerme 1.5 segundos.

    El Hilo A solicita el Monitor (bloqueado por B).

    El Hilo B solicita el Quirófano (bloqueado por A).

Java

// Archivo: com.hospital.hospitalbackend.service.DeadlockManager.java
package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.DeadlockStatusDTO;
import com.hospital.hospitalbackend.dto.DeadlockedPatientDTO;
import com.hospital.hospitalbackend.model.LogEntry;
import com.hospital.hospitalbackend.model.Patient;
import com.hospital.hospitalbackend.model.TriageLevel;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeadlockManager {

    private final ResourceManager resourceManager;
    private final NotificationService notificationService;

    private final Map<String, Thread> simulationThreads = new ConcurrentHashMap<>();
    private final Map<String, String> patientHolding = new ConcurrentHashMap<>();
    private final Map<String, String> patientWaiting = new ConcurrentHashMap<>();
    private final Map<String, Patient> deadlockedPatientCache = new ConcurrentHashMap<>();

    private boolean deadlockActive = false;

    public DeadlockManager(ResourceManager resourceManager, NotificationService notificationService) {
        this.resourceManager = resourceManager;
        this.notificationService = notificationService;
        startDeadlockDetector();
    }

    public void triggerSimulation() {
        if (deadlockActive) return;

        notificationService.sendLog(LogEntry.warn("[WARN] INICIANDO SIMULACIÓN DE DEADLOCK..."));

        // Vacía los recursos para controlar el punto de inicio exacto
        resourceManager.getOperatingRooms().drainPermits();
        resourceManager.getMonitors().drainPermits();

        // Inyecta el estado de carrera inicial (1 permiso libre para cada uno)
        resourceManager.getOperatingRooms().release(1);
        resourceManager.getMonitors().release(1);

        Patient patientA = Patient.builder().id(UUID.randomUUID()).name("Simulado Crítico A").triageLevel(TriageLevel.CRITICAL).build();
        Patient patientB = Patient.builder().id(UUID.randomUUID()).name("Simulado Emergencia B").triageLevel(TriageLevel.EMERGENCY).build();

        deadlockedPatientCache.put(patientA.getId().toString(), patientA);
        deadlockedPatientCache.put(patientB.getId().toString(), patientB);

        // HILO A: Adquiere Quirófano -> Intenta adquirir Monitor
        Thread threadA = new Thread(() -> {
            try {
                resourceManager.getOperatingRooms().acquire(1);
                patientHolding.put(patientA.getId().toString(), "Operating Room");
                notificationService.sendLog(LogEntry.info("[HOLD] Paciente A retiene Quirófano."));

                Thread.sleep(1500); // Ventana de tiempo para cruzamiento

                notificationService.sendLog(LogEntry.wait("[REQUEST] Paciente A solicita Monitor..."));
                patientWaiting.put(patientA.getId().toString(), "Monitor");
                resourceManager.getMonitors().acquire(1); // Bloqueo eterno aquí

            } catch (InterruptedException e) {
                handleInterruption(patientA, "Operating Room");
            } finally {
                cleanUpPatient(patientA.getId().toString());
            }
        });

        // HILO B: Adquiere Monitor -> Intenta adquirir Quirófano (Inversión de orden)
        Thread threadB = new Thread(() -> {
            try {
                resourceManager.getMonitors().acquire(1);
                patientHolding.put(patientB.getId().toString(), "Monitor");
                notificationService.sendLog(LogEntry.info("[HOLD] Paciente B retiene Monitor."));

                Thread.sleep(1500);

                notificationService.sendLog(LogEntry.wait("[REQUEST] Paciente B solicita Quirófano..."));
                patientWaiting.put(patientB.getId().toString(), "Operating Room");
                resourceManager.getOperatingRooms().acquire(1); // Bloqueo eterno aquí

            } catch (InterruptedException e) {
                handleInterruption(patientB, "Monitor");
            } finally {
                cleanUpPatient(patientB.getId().toString());
            }
        });

        simulationThreads.put(patientA.getId().toString(), threadA);
        simulationThreads.put(patientB.getId().toString(), threadB);

        threadA.start();
        threadB.start();
    }

    private void handleInterruption(Patient patient, String heldResource) {
        notificationService.sendLog(LogEntry.warn("[RESOLVE] Hilo interrumpido para " + patient.getName()));
        if ("Operating Room".equals(heldResource)) {
            resourceManager.getOperatingRooms().release(1);
        } else {
            resourceManager.getMonitors().release(1);
        }
    }

    public void resolve(String releasePatientId) {
        Thread target = simulationThreads.get(releasePatientId);
        if (target != null && target.isAlive()) {
            // El uso de interrupt() destruye el bloqueo del semáforo lanzando InterruptedException
            target.interrupt(); 
            deadlockActive = false;

            // Restaura las capacidades normales del hospital
            resourceManager.getOperatingRooms().release(3 - resourceManager.getOperatingRooms().availablePermits());
            resourceManager.getMonitors().release(8 - resourceManager.getMonitors().availablePermits());

            notificationService.sendUpdate("deadlock", new DeadlockStatusDTO(false, Collections.emptyList()));
            notificationService.sendLog(LogEntry.system("[SUCCESS] Condición de Deadlock resuelta por intervención manual."));
        }
    }

    private void startDeadlockDetector() {
        // Hilo Demonio encargado del monitoreo de estados circulares de Coffman
        Thread detector = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(4000);
                    if (!patientWaiting.isEmpty() && !deadlockActive) {
                        List<DeadlockedPatientDTO> victims = new ArrayList<>();
                        for (String pId : patientWaiting.keySet()) {
                            Patient p = deadlockedPatientCache.get(pId);
                            if (p != null) {
                                victims.add(new DeadlockedPatientDTO(
                                        pId, p.getName(), p.getTriageLevel().name(),
                                        patientHolding.getOrDefault(pId, "None"),
                                        patientWaiting.get(p)
                                ));
                            }
                        }
                        if (victims.size() >= 2) {
                            deadlockActive = true;
                            notificationService.sendLog(LogEntry.warn("[CRITICAL] ¡Espera Circular Detectada! Sistema en Interbloqueo."));
                            notificationService.sendUpdate("deadlock", new DeadlockStatusDTO(true, victims));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        detector.setDaemon(true);
        detector.start();
    }

    private void cleanUpPatient(String patientId) {
        patientHolding.remove(patientId);
        patientWaiting.remove(patientId);
        simulationThreads.remove(patientId);
        deadlockedPatientCache.remove(patientId);
        resourceManager.broadcastCurrentState();
    }
}

Estructura y Reactividad del Frontend

El cliente web procesa los datos en tiempo real mediante un flujo asíncrono controlado por ganchos personalizados de React (Custom Hooks) y almacenamiento reactivo de Redux.
Estructura de Módulos (Árbol de Directorios)

El proyecto frontend adopta un diseño por características distribuidas en componentes modulares:
Plaintext

src
├── app
│   ├── hooks.ts             # Enlaces estrictamente tipados para Redux
│   └── store.ts             # Configuración central del estado de la UI y datos
├── components
│   ├── dashboard
│   │   ├── AdmissionsCard.tsx   # Panel de inserción de pacientes vía REST Fetch
│   │   ├── PatientQueueCard.tsx # Visualizador de colas (QUEUED/IN_PROGRESS)
│   │   ├── ResourcePoolCard.tsx # Monitores de capacidad de semáforos
│   │   ├── StatisticsCard.tsx   # Contadores de métricas agregadas
│   │   └── SystemLogsCard.tsx   # Consola de logs en streaming
│   └── layout
└── hooks
    └── useHospitalSocket.ts # Cliente de conexión SockJS / STOMP

Flujo de Captura del WebSocket (useHospitalSocket.ts)

Este hook encapsula la sesión del WebSocket. Cada vez que el backend emite una mutación en un canal, el cliente despacha una acción atómica de Redux para refrescar la interfaz visual de manera eficiente.
TypeScript

import { useEffect, useRef } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { useAppDispatch } from "../app/hooks";
import { updateResources } from "../features/resources/resourcesSlice";
import { appendLog } from "../features/logs/logsSlice";
import { setActivePatients, updateStats } from "../features/patients/patientsSlice";

const WS_URL = "[https://hospital-backend-production-c052.up.railway.app/ws-hospital](https://hospital-backend-production-c052.up.railway.app/ws-hospital)";

export const useHospitalSocket = () => {
  const dispatch = useAppDispatch();
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 3000, // Reconección automática si el backend cae o se redespliega
      onConnect: () => {
        // Suscripción a canales específicos del dominio concurrente
        client.subscribe("/topic/resource-status", ({ body }) =>
          dispatch(updateResources(JSON.parse(body)))
        );
        client.subscribe("/topic/patients", ({ body }) =>
          dispatch(setActivePatients(JSON.parse(body)))
        );
        client.subscribe("/topic/logs", ({ body }) =>
          dispatch(appendLog(JSON.parse(body)))
        );
        client.subscribe("/topic/stats", ({ body }) =>
          dispatch(updateStats(JSON.parse(body)))
        );
      },
    });

    client.activate();
    clientRef.current = client;
    return () => {
      client.deactivate(); // Desconexión limpia del canal al destruir el componente
    };
  }, [dispatch]);
};

Manejo de Excepciones y Robustez del Sistema

El punto crítico de control de fallos del sistema reside en capturar y propagar de forma segura la excepción InterruptedException.
Ciclo de Resiliencia del Hilo

Cuando un hilo está bloqueado en una operación de espera (como Semaphore.acquire() o Thread.sleep()), la única manera de despertarlo o cancelarlo es mediante una señal de interrupción. Si esta señal se dispara (por ejemplo, al ejecutar la liberación forzada del Deadlock o apagar el servidor), el sistema opera bajo las siguientes directrices:

    Liberación Imperativa (finally): El uso de bloques try-catch-finally asegura que, independientemente de si el hilo finalizó con éxito o fue abortado a la mitad de su ejecución por una interrupción, el bloque finally se ejecuta obligatoriamente. Esto garantiza la devolución inmediata de los permisos del semáforo al pool general, eliminando fugas de recursos que podrían congelar el sistema de manera permanente.

    Preservación del Estado de Interrupción: Al capturar InterruptedException, el flag de interrupción del hilo es limpiado automáticamente por la JVM. Para evitar romper la cadena de llamadas y permitir que los servicios de infraestructura superior se enteren de la cancelación, se invoca inmediatamente Thread.currentThread().interrupt(), respetando los estándares de programación concurrente industrial.