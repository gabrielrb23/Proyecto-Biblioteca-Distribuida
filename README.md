# 📚 Proyecto Biblioteca Distribuida
Sistema de Biblioteca con Arquitectura Distribuida, Concurrencia, Tolerancia a Fallos y Replicación  
**Java + ZeroMQ + PostgreSQL**

## 🚀 Descripción General

Este proyecto implementa un **sistema de biblioteca distribuido**, tolerante a fallos y completamente desacoplado.  
Está diseñado con **actores**, **procesos independientes**, **replicación de BD**, **failover automático**, y **ZeroMQ** como middleware de mensajería.

El sistema se compone de:

- **GA – Gestor de Almacenamiento (StorageManager)**  
- **GC – Gestor de Carga (LoadManager)**  
- **Actores** (LoanActor, ReturnActor, RenewalActor)  
- **PS – Proceso Solicitante (SolicitingProcess)**  
- **BD Primaria y Secundaria** con replicación manual

✔ Cada proceso corre en su propia JVM  
✔ Comunicación con sockets ZeroMQ (REQ/REP – PUB/SUB)  
✔ Soporte para operación local y distribuida  
✔ Failover automático GA → GA secundario  
✔ Failover GC → GC secundario  
✔ Actores con idempotencia y reintentos  
✔ PS con tolerancia a fallos y rotación de GC  
✔ Transacciones ACID + `FOR UPDATE` para consistencia

---

# 🧩 Arquitectura General

```
          ┌──────────────────┐
          │  SolicitingProcess│
          └───────┬──────────┘
                  │ REQ/REP (failover)
                  ▼
        ┌──────────────────────┐
        │   Gestores de Carga  │
        │  GC Primario / Sec.  │
        └──────┬───────────────┘
               │ PUB/SUB
               ▼
 ┌─────────────┬─────────────────────────┬─────────────────────────┐
 │         LoanActor                   ReturnActor               RenewalActor
 │             │ REQ/REP con failover hacia GA                     │       
 └─────────────┴──────────────┬─────────┴──────────────┬──────────┘
                              │
                              ▼
                  ┌──────────────────────┐
                  │ Gestor Almacenamiento│
                  │ GA Primario / Sec.   │
                  └───────────┬──────────┘
                              │ SQL
                              ▼
             BD Primaria ←── Replicación ─→ BD Secundaria
```

---

# 🛠️ Comandos de Ejecución (modo local)

## 🟩 1. GA – Gestores de Almacenamiento (Primario y Secundario)

```
# GA Primario
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.StorageManager" -Dga.rep=tcp://0.0.0.0:5560

# GA Secundario
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.StorageManager" -Dga.rep=tcp://0.0.0.0:5564
```

---

## 🟦 2. GC – Gestores de Carga

### Modo Asíncrono

```
# GC Primario
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" -Dgc.rep=tcp://0.0.0.0:5555 -Dgc.pub=tcp://0.0.0.0:5556

# GC Secundario
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" -Dgc.rep=tcp://0.0.0.0:5551 -Dgc.pub=tcp://0.0.0.0:5552
```

### Modo Sincrónico

```
# GC Primario Sync
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" -Dgc.rep=tcp://0.0.0.0:5555 -Dgc.pub=tcp://0.0.0.0:5556 -Dexec.args="sync"

# GC Secundario Sync
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" -Dgc.rep=tcp://0.0.0.0:5551 -Dgc.pub=tcp://0.0.0.0:5552 -Dexec.args="sync"
```

---

## 🟨 3. Actores

### Modo Async

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoanActor"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.RenewalActor"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.ReturnActor"
```

### Modo Sync

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoanActor" -Dexec.args="--sync"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.RenewalActor" -Dexec.args="--sync"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.ReturnActor" -Dexec.args="--sync"
```

---

## 🟧 4. PS – Proceso Solicitante

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.SolicitingProcess" -Dexec.args="src/main/resources/test-files/S01.txt"
```

---

# 🔧 Configuración (`app.properties`)

```
ga.rep.endpoints=tcp://localhost:5560,tcp://localhost:5564
ps.gc.endpoints=tcp://localhost:5555,tcp://localhost:5551
actor.loan.sub=tcp://localhost:5556
actor.return.sub=tcp://localhost:5556
actor.renew.sub=tcp://localhost:5556
actor.loan.req=tcp://localhost:5561
actor.return.req=tcp://localhost:5562
actor.renew.req=tcp://localhost:5563
db.primary.url=jdbc:postgresql://localhost:5432/BDPrimaria
db.secondary.url=jdbc:postgresql://localhost:5432/BDSecundaria
```

---

# 🧪 Archivos de prueba

Se encuentran en:

```
src/main/resources/test-files/
```

Ejemplo:

```
PRESTAMO,S1,U1,BK-0001
DEVOLUCION,S1,U2,BK-0031
RENOVACION,S1,U5,BK-0011
```

---

# 📝 Notas Finales

- Cada proceso debe ejecutarse en una consola independiente.  
- Totalmente compatible con despliegue distribuido (múltiples máquinas).  
- Idempotencia, failover y replicación implementados.  
- ZeroMQ garantiza bajo acoplamiento y alta disponibilidad.
